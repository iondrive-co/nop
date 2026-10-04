package iondrive.nop.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.ui.JediTermWidget
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.unix.Pty
import com.pty4j.unix.UnixPtyProcess
import iondrive.nop.Log
import iondrive.nop.launchers.Launcher
import java.awt.Color
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * A single PTY-backed terminal session: owns a pty4j [PtyProcess] and the JediTerm Swing widget
 * that renders it. Because the child runs under a real pseudo-terminal it sees a TTY (`isatty`
 * true), which is what makes password prompts, full-screen TUIs (vim/htop), colour and
 * `SIGWINCH`-on-resize work — none of which a pipe-based runner can do.
 *
 * Build one with [forLauncher], [shell] or [agent]. The widget + process are created lazily on the AWT
 * event dispatch thread via [getOrCreateWidget] (called by the terminal host's `SwingPanel`); the
 * host also drives [applyColors] when the theme changes. A launcher session can be re-run in place
 * with [restart]; [dispose] tears everything down when the tab closes.
 */
class TerminalSession private constructor(
    val title: String,
    private val command: List<String>,
    private val workingDir: File,
    /** Launcher sessions show a header with status + Stop/Re-run; a plain shell shows none. */
    val isLauncher: Boolean,
    /**
     * Extra environment for the child, merged over the inherited one. Empty for a shell or a
     * launcher run, which want exactly the environment nop was started with; an agent session uses
     * it to point the vendor CLI at one account's credential directory, which is the whole of how
     * several accounts stay apart.
     */
    private val env: Map<String, String> = emptyMap(),
    /**
     * Sees the child's output on its way to the screen, unchanged. Only an agent session sets one —
     * it is how nop notices a quota wall the vendor announced in its own UI, which nothing else
     * tells it about. See [PtyTtyConnector].
     */
    private val outputTap: ((String) -> Unit)? = null,
    /**
     * The launcher this run came from, for a session built by [forLauncher] and null for anything
     * else. Kept so the Run tabs can be written down and put back at the next start — a tab that
     * came back with no way to say what it would run would be a label and nothing else.
     */
    val launcher: Launcher? = null,
    deferred: Boolean = false,
    /**
     * The program the previous nop was running in this terminal, handed over across the restart —
     * see [handOff]. Null for a terminal that starts its own.
     */
    private val adopted: Adopted? = null,
) {
    /** Whether the child process is currently alive. Compose-observable so the header updates. */
    var running: Boolean by mutableStateOf(false)
        internal set

    /**
     * True for a run tab restored from the last time nop was open: the tab is back, the command has
     * not been run again, and [start] is what runs it.
     *
     * Restored rather than re-run deliberately. A launcher is an arbitrary shell command — a
     * deploy, a database migration, a release — and starting nop is not consent to run it. The tab
     * is the useful half of remembering it; pressing Run is the user's half.
     */
    var deferred: Boolean by mutableStateOf(deferred)
        internal set

    /**
     * What the last child exited with, or null while one is still running (or before any has run).
     * Compose-observable, because the agent panel's post-exit choices are drawn from it — "the TUI
     * quit" and "the TUI died" are different situations and only the code tells them apart.
     */
    var exitCode: Int? by mutableStateOf(null)
        private set

    /**
     * Called once on the watcher thread each time a child exits, with its exit code. Set by an
     * agent session so it can drain its tailer and close its log at the moment the run ends,
     * rather than noticing later from a poll.
     */
    @Volatile
    var onExit: ((Int) -> Unit)? = null

    /**
     * Called when the user types input (e.g. Enter) or sends text to the session. Used by agent sessions
     * to detect when a prompt is submitted in a resumed session.
     */
    @Volatile
    var onUserInput: (() -> Unit)? = null

    /**
     * Whether the program's prompt may be holding text the user has not sent: something was typed,
     * pasted, dropped or recalled since the last Enter. A message from another agent is submitted
     * with Enter, which would send such a draft along with it as though it were one instruction, so
     * the inbox warns while this holds.
     *
     * It errs towards yes. A prompt cleared with Esc still reads as holding a draft until
     * the next Enter, because nop cannot see the prompt, and a warning nobody needed costs less than
     * a half-written instruction going out under another agent's message. Compose state, so the
     * warning comes and goes as the user types.
     */
    var draftPending: Boolean by mutableStateOf(false)
        internal set

    /** When a prompt was last submitted (by the user or via [submit]) in this terminal. */
    var lastSubmitAt: Long = 0L
        internal set

    /**
     * Whether the program in the terminal has asked for bracketed paste (`ESC [ ? 2004 h`) and not
     * turned it off since. Read off the output, because JediTerm keeps its own copy private.
     */
    @Volatile
    private var bracketedPaste = false

    private var widget: JediTermWidget? = null
    private var settings: NopTerminalSettings? = null
    private var process: PtyProcess? = null
    private var connector: PtyTtyConnector? = null

    /**
     * What the program has drawn lately, kept so a restart can hand it to the next nop (see
     * [handOff]). Only for the terminals a restart hands over — agents and shells; a launcher run is
     * put back waiting to be run again, as it always was.
     */
    private val recorder: PtyRecorder? = if (isLauncher) null else PtyRecorder().also { r -> adopted?.let { r.seed(it.replay) } }

    /** Whether [adopted] has been attached yet. It is attached once; a later run spawns as usual. */
    private var adoptionUsed = false

    /**
     * Set once [handOff] has given the program to the next nop: nothing here may signal it, close
     * its PTY or read from it again.
     */
    @Volatile
    var handedOver: Boolean = false
        private set

    /** True once the widget has been created. */
    val isStarted: Boolean get() = widget != null || testStarted
    internal var testStarted: Boolean = false

    /** True for a terminal whose program the last nop handed over, rather than one this nop started. */
    val isAdopted: Boolean get() = adopted != null

    /** Current terminal theme colors, or null if not yet created. */
    val themeColors: Triple<Color, Color, Color>?
        get() = settings?.let { Triple(it.bg, it.fg, it.link) }

    @Volatile
    private var restarting = false

    /**
     * Builds the widget + starts the PTY on first call, returning the same widget thereafter (so
     * the host can re-attach it to its card without respawning). Must run on the AWT EDT.
     */
    fun getOrCreateWidget(
        bg: Color = DEFAULT_BG,
        fg: Color = DEFAULT_FG,
        link: Color = DEFAULT_LINK,
    ): JediTermWidget {
        widget?.let { return it }
        val s = NopTerminalSettings(bg, fg, link)
        // An adopted program drew its screen at the size the last nop gave it, and the replay is
        // that drawing: a terminal of any other size would wrap it into nonsense before the panel
        // has even been laid out.
        val w = NopTerminalWidget(adopted?.columns ?: INITIAL_COLUMNS, adopted?.rows ?: INITIAL_ROWS, s)
        // Underlines the http(s) URLs the run prints and makes them open in the browser on click.
        // Must be installed before the process starts writing, or early output misses out: JediTerm
        // only runs the filters over a line as it is written.
        w.addHyperlinkFilter(UrlHyperlinkFilter())
        // Dropped files are typed in as paths — see TerminalFileDrop. On the terminal panel rather
        // than on the widget: the panel is what fills the widget, so it is what the pointer is over.
        if (!GraphicsEnvironment.isHeadless()) {
            w.terminalPanel.dropTarget = DropTarget(
                w.terminalPanel,
                DnDConstants.ACTION_COPY,
                TerminalFileDrop { paths ->
                    // A trailing space so a second drop doesn't run into the first, and focus so the
                    // next keystroke — usually Enter — goes to the terminal rather than to whatever the
                    // drag started from.
                    sendText(paths.joinToString(" ") { quoteForPrompt(it) } + " ")
                    w.requestFocusInWindow()
                },
            )
        }
        // Shift+Enter writes a line feed rather than submitting the line — see [ShiftEnterNewline].
        // Added before the widget is started, which is what puts it in front of JediTerm's own key
        // handler in the panel's listener list: the panel adds that one when the session connects,
        // and only a listener that runs first can take the event off it.
        w.terminalPanel.addCustomKeyListener(ShiftEnterNewline { sendText(it) })
        w.terminalPanel.addCustomKeyListener(AltArrowKeys { sendText(it) })
        w.terminalPanel.addCustomKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    onUserInput?.invoke()
                } else if (e.keyCode == java.awt.event.KeyEvent.VK_S &&
                    e.isControlDown && e.isShiftDown && !e.isAltDown
                ) {
                    e.consume()
                    snipArea()
                }
            }
        })
        settings = s
        widget = w
        runCatching { attach(w, startProcess()) }.onFailure {
            Log.warn("failed to start terminal process: ${it.message}")
            running = false
        }
        return w
    }

    /**
     * Runs a restored tab's command for the first time. No-op for a session that has already
     * started one.
     *
     * Nothing happens here beyond clearing the flag: the host draws a placeholder in place of the
     * terminal while [deferred] holds, so dropping it is what makes the host ask for a widget, and
     * asking for a widget is what spawns the process.
     */
    fun start() {
        deferred = false
    }

    /** Repaints the terminal in new theme colours; no-op if nothing changed or not yet created. */
    fun applyColors(bg: Color, fg: Color, link: Color) {
        val s = settings ?: return
        if (s.bg == bg && s.fg == fg && s.link == link) return
        s.bg = bg
        s.fg = fg
        s.link = link
        widget?.let { w ->
            w.background = bg
            w.terminalPanel.background = bg
            w.terminalPanel.repaint()
        }
    }

    /**
     * Kills the current process tree and starts a fresh one on the same widget (the Re-run button).
     *
     * The kill + relaunch happen on a background thread because we must *wait* for the old tree to
     * fully exit before launching the new run — otherwise a server's listening socket may still be
     * held and the fresh run dies with "address already in use". A naive kill-then-immediately-start
     * races on exactly that.
     */
    fun restart() {
        val w = widget ?: return
        if (restarting) return
        restarting = true
        val old = process
        process = null
        running = true // show Stop (not Re-run) during the brief teardown, and block a double Re-run
        w.stop()
        thread(isDaemon = true, name = "terminal-restart") {
            old?.let { p ->
                val descendants = killTree(p)
                runCatching { p.waitFor() }
                descendants.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
            }
            SwingUtilities.invokeLater {
                restarting = false
                if (widget === w) {
                    runCatching { attach(w, startProcess()) }.onFailure { running = false }
                }
            }
        }
    }

    /**
     * Interrupts the current run exactly as pressing Ctrl-C in the terminal would: writes the TTY
     * interrupt character (ETX, 0x03) to the PTY, so the kernel's line discipline raises SIGINT on
     * the foreground process group. Unlike the hard [killTree] used by [dispose]/[restart], this
     * lets the program shut down gracefully — flush output, remove pid files, stop child servers —
     * and, like a real Ctrl-C, leaves a process that deliberately ignores SIGINT still running.
     *
     * We deliberately don't flip `running` or drop the `process` reference here: the child exits
     * asynchronously (if at all), so the watcher thread from [startProcess] flips `running` false
     * when it actually dies, and keeping the reference lets a following Re-run still wait on it.
     */
    fun stop() {
        val proc = process ?: return
        runCatching {
            val out = proc.outputStream
            out.write(CTRL_C)
            out.flush()
        }
        draftPending = false
    }

    /**
     * Types [text] into the terminal, exactly as the keyboard would: the bytes go to the PTY, so
     * the child reads them from its stdin and the line discipline echoes them back onto the screen.
     * Nothing happens if no process is running — there is nothing to type at.
     */
    fun sendText(text: String) {
        settings?.nudgeActive()
        noteDraft(text.toByteArray(Charsets.UTF_8))
        onUserInput?.invoke()
        write(text)
    }

    private fun write(text: String) {
        val proc = process ?: return
        runCatching {
            val out = proc.outputStream
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        SwingUtilities.invokeLater {
            (widget as? NopTerminalWidget)?.scrollToBottom()
        }
    }

    /**
     * Types [text] into the program's prompt and presses Enter, as one message however many lines
     * it has. Must run on the AWT EDT, where the keyboard's own writes happen, so the two cannot
     * interleave.
     *
     * A newline typed into a TUI's prompt is Enter, so a multi-line message goes in as a bracketed
     * paste when the program has asked for those, and is joined onto one line when it has not. Enter
     * follows after [SUBMIT_DELAY_MS] rather than in the same write: a TUI that decides what is a
     * paste by how much arrives at once would otherwise take it for a newline inside the paste.
     */
    fun submit(text: String) {
        settings?.nudgeActive()
        // Nothing in the text may reach the program as anything but text: an ESC would let it end
        // the paste early (`ESC [ 2 0 1 ~`) and carry on as keystrokes, and a bare CR or any other
        // control byte is a key of its own. Its sender cleans it too (AgentMessages); this is the
        // last line.
        val safe = text.replace("\r\n", "\n").replace('\r', '\n').filter { it == '\n' || it == '\t' || !it.isISOControl() }
        val body = if (bracketedPaste) {
            "$PASTE_START$safe$PASTE_END"
        } else {
            // Unbracketed, a newline is Enter and a tab is Tab (completion, in most of these TUIs).
            safe.trim().replace(LINE_BREAKS, " ").replace('\t', ' ')
        }
        onUserInput?.invoke()
        write(body)
        javax.swing.Timer(SUBMIT_DELAY_MS) {
            write("\r")
            lastSubmitAt = System.currentTimeMillis()
            // Enter sends the whole prompt, so whatever was in it has gone too.
            draftPending = false
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** Requests focus for this session's terminal widget. */
    fun requestFocus() {
        widget?.requestFocusInWindow()
    }

    /**
     * Starts the interactive screen region selector tool and types the selected image's path into
     * this terminal's prompt ready to send.
     */
    fun snipArea() {
        settings?.nudgeActive()
        ScreenSnip.start { path ->
            sendText(quoteForPrompt(path.toAbsolutePath().toString()) + " ")
            requestFocus()
        }
    }

    /**
     * Kills the child and everything under it, but leaves the widget — and so the last frame the
     * program drew — on screen.
     *
     * This is what ending an agent run on a quota wall needs. [dispose] would take the terminal
     * away with the process, and the last thing the CLI drew is usually the only thing that says
     * whether handing the work to another provider is the right call. The `process` reference is
     * kept deliberately, so the watcher thread still records the exit code and fires [onExit].
     */
    fun kill() {
        process?.let { killTree(it) }
    }

    /** Kills the process tree, detaches the widget from its host card, and disposes it. Idempotent. */
    fun dispose() {
        killProcess()
        widget?.let { w ->
            (w.parent as? Container)?.remove(w)
            w.close()
        }
        widget = null
    }

    /**
     * Whether the terminal is currently painted on a dark background, by the perceived brightness
     * of the colour nop's theme last pushed in. Defaults to light for a process that somehow starts
     * before the widget: black-on-white is what JediTerm itself falls back to.
     */
    private fun isDarkBackground(): Boolean {
        val bg = settings?.bg ?: return false
        return (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) < 128
    }

    private fun startProcess(): PtyProcess {
        if (adopted != null && !adoptionUsed) {
            adoptionUsed = true
            return watch(adopted.pty)
        }
        val childEnv = InheritedEnvironment.of(System.getenv())
        // Advertise a colour terminal so tools enable ANSI output and full-screen rendering.
        childEnv["TERM"] = "xterm-256color"
        // And a *24-bit* one, which TERM alone cannot say: there is no termcap entry for truecolor,
        // so every CLI that can emit it (claude, codex, bat, delta, and anything else built on
        // chalk / supports-color / crossterm) decides by looking for COLORTERM and settles for the
        // 256-colour cube when it is absent. That downgrade is visible rather than academic —
        // Claude Code's inline-code lavender #b1b9f9 arrives here as #afd7ff, xterm index 153, and
        // every other colour in its theme is likewise snapped to the nearest of 240 entries, which
        // is what flattened output in nop against the same CLI in a plain terminal. JediTerm has
        // parsed `38;2;r;g;b` since long before 3.72 (`JediEmulator.getColor256`), so nothing on
        // this side needs to change to receive the real colours.
        childEnv["COLORTERM"] = "truecolor"
        // Which way round the terminal is, stated up front rather than left to be asked for.
        //
        // JediTerm does answer the OSC 11 "what colour is your background?" query, with the themed
        // colour NopTerminalSettings is holding at the time — but a CLI that asks has to have its
        // answer before it draws its first frame, and one that gives up waiting assumes a *dark*
        // terminal. That is what puts a near-black diff panel and pale-blue inline code on nop's
        // light theme: the CLI never learned the background is white. COLORFGBG is the same answer
        // in a form nothing can race — every TUI that reads it (claude, vim, delta, bat) reads it
        // before it renders. The pair is foreground;background as xterm colour indices, and only
        // the second half is read: 15 (white) means a light terminal, 0 (black) a dark one.
        //
        // It is a snapshot, not a subscription: a theme toggle repaints the terminal but cannot
        // reach a CLI already running in it. The next run gets the new value.
        childEnv["COLORFGBG"] = if (isDarkBackground()) "15;0" else "0;15"
        // Last, so an agent session's HOME / CLAUDE_CONFIG_DIR beats the inherited one. Overriding
        // HOME is the point rather than an accident: it is how the Codex CLI is made to read one
        // account's ~/.codex instead of the machine owner's.
        childEnv.putAll(env)
        val factory = processFactory
        val proc = if (factory != null) {
            factory(command, childEnv, workingDir)
        } else {
            PtyProcessBuilder()
                .setCommand(command.toTypedArray())
                .setEnvironment(childEnv)
                .setDirectory(workingDir.absolutePath)
                .setInitialColumns(INITIAL_COLUMNS)
                .setInitialRows(INITIAL_ROWS)
                .start()
        }
        return watch(proc)
    }

    /** Makes [proc] this session's process, and starts watching for it to exit. */
    private fun watch(proc: PtyProcess): PtyProcess {
        process = proc
        running = true
        exitCode = null
        deferred = false
        // Daemon watcher flips `running` false the moment the child exits, so the header can swap
        // its Stop button for Re-run without polling.
        thread(isDaemon = true, name = "terminal-watch") {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (process === proc) {
                running = false
                exitCode = code
                onExit?.invoke(code)
            }
        }
        return proc
    }

    private fun attach(w: JediTermWidget, proc: PtyProcess) {
        // The replay belongs to the adopted program alone, and is drawn once.
        val replay = adopted?.takeIf { proc === it.pty }?.replay
        val c = PtyTtyConnector(
            process = proc,
            recorder = recorder,
            replay = replay,
            // The replay is history, so it skips [outputTap] — a quota wall in it is one the last nop
            // has already dealt with — but the paste mode it leaves on is the program's mode now.
            replayTap = { text -> noteBracketedPaste(text) },
            tap = { text ->
                noteBracketedPaste(text)
                outputTap?.invoke(text)
            },
            inputTap = { bytes ->
                noteDraft(bytes)
                if (bytes.any { it == '\r'.code.toByte() }) {
                    lastSubmitAt = System.currentTimeMillis()
                }
                if (bytes.any { it == '\r'.code.toByte() || it == '\n'.code.toByte() }) {
                    onUserInput?.invoke()
                }
            },
        )
        connector = c
        w.ttyConnector = c
        w.start()
    }

    /**
     * Gives the program running here to the next nop, which is about to replace this one in the same
     * process (see [iondrive.nop.ipc.Handover]). Null when there is nothing to give: no program has
     * been started, it has exited, or this terminal does not keep what a handover needs.
     *
     * In this order, and each step is load-bearing. The connector is told first, because stopping the
     * reads ends JediTerm's session, and JediTerm closes a connector whose session ends — which would
     * SIGTERM the program and close its PTY. Then the reads stop, and only after that is the screen
     * taken, so every byte this nop read is in the replay and every byte it did not is still waiting
     * in the PTY for the next one. From here on [dispose] leaves the program alone.
     */
    fun handOff(): PtyHandoff? {
        val proc = process ?: return null
        val rec = recorder ?: return null
        if (!proc.isAlive) return null
        val fd = masterFd(proc) ?: return null
        connector?.handedOver = true
        handedOver = true
        rec.freeze { wakeReader(proc) }
        val (cols, rows) = Posix.windowSize(fd) ?: (INITIAL_COLUMNS to INITIAL_ROWS)
        return PtyHandoff(fd = fd, pid = proc.pid(), columns = cols, rows = rows, replay = rec.replay(), process = proc)
    }

    /** Keeps [draftPending] up to date with [bytes] on their way to the program. */
    private fun noteDraft(bytes: ByteArray) {
        draftPending = draftAfter(draftPending, bytes)
    }

    /** The last word in [text] on bracketed paste wins; a switch split across two reads is missed. */
    private fun noteBracketedPaste(text: String) {
        val on = text.lastIndexOf(BRACKETED_PASTE_ON)
        val off = text.lastIndexOf(BRACKETED_PASTE_OFF)
        if (on > off) bracketedPaste = true else if (off > on) bracketedPaste = false
    }

    private fun killProcess() {
        // A program handed to the next nop is not this one's to kill: the exec that follows carries it
        // across, still running.
        if (!handedOver) process?.let { killTree(it) }
        process = null
        running = false
    }

    /**
     * SIGKILLs the whole process tree behind [p] and returns handles to its descendants (so a
     * caller that needs the port freed — see [restart] — can wait for them to exit).
     *
     * Two mechanisms, because neither alone is enough:
     *  - `destroyForcibly()` on the pty4j process does `killpg(SIGKILL)`, taking down the shell and
     *    everything still in its process group (gradle, npm, …). We must NOT use Process.descendants()
     *    the way a plain ProcessBuilder process would — pty4j processes don't implement toHandle(),
     *    so descendants() throws "toHandle() not supported".
     *  - `ProcessHandle.of(pid)` *does* work (it's independent of pty4j), so we also walk the OS
     *    subtree and kill descendants that left the shell's process group (e.g. a dev server that
     *    forks workers). A process that fully daemonizes (setsid + reparent to init) escapes both —
     *    nothing short of its own pid can reach it, which is inherent to detached processes.
     */
    private fun killTree(p: PtyProcess): List<ProcessHandle> {
        val descendants = runCatching {
            ProcessHandle.of(p.pid()).map { it.descendants().toList() }.orElse(emptyList())
        }.getOrDefault(emptyList())
        p.destroyForcibly()
        descendants.forEach { runCatching { it.destroyForcibly() } }
        return descendants
    }

    companion object {
        private const val INITIAL_COLUMNS = 80
        private const val INITIAL_ROWS = 24

        /**
         * Whether the prompt may hold a draft after [bytes] reach the program, given whether it did
         * before. An Enter sends what was there; Ctrl-C or Ctrl-U cancels/clears it; anything after the
         * last Enter/Ctrl-C/Ctrl-U is new draft, unless all of it is the terminal answering the program
         * rather than the user — see [isTerminalReport].
         */
        internal fun draftAfter(before: Boolean, bytes: ByteArray): Boolean {
            if (bytes.isEmpty() || isTerminalReport(bytes)) return before
            val clear = bytes.indexOfLast { it == '\r'.code.toByte() || it == CTRL_C.toByte() || it == CTRL_U.toByte() }
            if (clear < 0) return true
            return clear < bytes.size - 1 && !isTerminalReport(bytes.copyOfRange(clear + 1, bytes.size))
        }

        /**
         * Whether [bytes] are JediTerm replying to something the program asked it — the cursor's
         * position, the device's attributes, a mode's state, a colour, focus coming and going — or a
         * mouse report. None of those put anything in a prompt. Keys that do (arrows recalling
         * history, Alt+letters) are escape sequences too, and are not in this list.
         */
        internal fun isTerminalReport(bytes: ByteArray): Boolean {
            if (bytes.size < 2 || bytes[0] != ESC) return false
            val text = String(bytes, Charsets.ISO_8859_1)
            return REPORTS.any { it.matches(text) }
        }

        private val REPORTS = listOf(
            Regex("""\u001b\[\d+;\d+R"""),                  // cursor position
            Regex("""\u001b\[[?>=][\d;]*c"""),               // device attributes
            Regex("""\u001b\[\??[\d;]*\${'$'}y"""),              // mode state (DECRPM)
            Regex("""\u001b\[\d*n"""),                        // status
            Regex("""\u001b\[[IO]"""),                          // focus in / out
            Regex("""\u001b\[\?\d*u"""),                       // keyboard protocol flags
            Regex("""\u001b\[[\d;]*t"""),                      // window reports
            Regex("""\u001b\[<[\d;]*[Mm]"""),                  // SGR mouse
            Regex("""\u001b\[M[\s\S]{3}"""),                   // X10 mouse
            Regex("""\u001b\][\s\S]*(\u0007|\u001b\\)"""),   // OSC reply (colours, ...)
            Regex("""\u001bP[\s\S]*\u001b\\"""),                  // DCS reply
        )

        private const val ESC: Byte = 0x1b
        private const val BRACKETED_PASTE_ON = "\u001b[?2004h"
        private const val BRACKETED_PASTE_OFF = "\u001b[?2004l"
        private const val PASTE_START = "\u001b[200~"
        private const val PASTE_END = "\u001b[201~"
        private val LINE_BREAKS = Regex("""\s*\R\s*""")

        /** Between the text of a [submit] and its Enter. */
        private const val SUBMIT_DELAY_MS = 250

        val DEFAULT_BG: Color = Color(0x1E, 0x1F, 0x22)
        val DEFAULT_FG: Color = Color(0xDF, 0xE1, 0xE5)
        val DEFAULT_LINK: Color = Color(0x68, 0x97, 0xBB)

        internal var processFactory: ((List<String>, Map<String, String>, File) -> PtyProcess)? = null

        /** The TTY interrupt character (ETX) — what a terminal sends on Ctrl-C. See [stop]. */
        private const val CTRL_C = 3

        /** The line kill character (NAK) — what a terminal sends on Ctrl-U to clear the line. */
        private const val CTRL_U = 21

        private val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

        /**
         * Runs [launcher]'s command through a shell.
         *
         * No ▶ in the name, for the reason a shell carries no keyboard glyph: the tool strip draws
         * the mark itself, so it survives a rename rather than being the first thing a rename
         * deletes.
         *
         * [deferred] builds the tab without running anything — see [TerminalSession.deferred]. It is
         * how a run tab comes back at the next start.
         */
        fun forLauncher(launcher: Launcher, dir: File, deferred: Boolean = false): TerminalSession =
            TerminalSession(
                title = launcher.name,
                command = if (isWindows) listOf("cmd.exe", "/c", launcher.command)
                else listOf("sh", "-c", launcher.command),
                workingDir = dir,
                isLauncher = true,
                launcher = launcher,
                deferred = deferred,
            )

        /**
         * Opens a plain interactive shell — a normal terminal, not tied to a launcher.
         *
         * Every one of them is called the same thing. Numbering them looked tidier until you closed
         * one: the count only ever goes up, so a strip holding a single terminal would label it
         * "Term 4". A name that is the user's to set (right-click the tab) beats one nop guesses at
         * from a number that means nothing to them.
         *
         * No keyboard glyph in the name, unlike the ▶ a launcher run carries: the tool strip draws
         * that itself, so it survives a rename rather than being the first thing a rename deletes.
         */
        fun shell(dir: File, adopted: Adopted? = null): TerminalSession =
            TerminalSession(
                title = "Term",
                command = if (isWindows) listOf("cmd.exe") else listOf(loginShell()),
                workingDir = dir,
                isLauncher = false,
                adopted = adopted,
            )

        /**
         * Runs a vendor coding agent's TUI: its own rendering, its own keybindings, nothing of
         * nop's in the way. [env] carries the account's credential directory — see [Spawn].
         *
         * `isLauncher = false`, so no Stop/Re-run header appears above it. The TUI owns the whole
         * interaction, including how it is quit; a Stop button beside it would be a second, worse
         * way to end a session that is already mid-turn.
         */
        fun agent(
            command: List<String>,
            env: Map<String, String>,
            dir: File,
            title: String,
            outputTap: ((String) -> Unit)? = null,
            /** The run the last nop handed over, when this one carries it on — see [handOff]. */
            adopted: Adopted? = null,
        ): TerminalSession =
            TerminalSession(
                title = title,
                command = command,
                workingDir = dir,
                isLauncher = false,
                env = env,
                outputTap = outputTap,
                adopted = adopted,
            )

        /** The PTY master behind [proc], for a process whose master this nop can name. */
        private fun masterFd(proc: PtyProcess): Int? = when (proc) {
            is UnixPtyProcess -> proc.pty.masterFD.takeIf { it >= 0 }
            is AdoptedPty -> proc.fd
            else -> null
        }

        /**
         * Unblocks a read waiting on [proc]'s PTY without closing it. pty4j keeps the one call that
         * does this, `Pty.breakRead`, package-private; it writes to the pipe its reads poll beside
         * the master, which is exactly a wake-up and nothing more.
         */
        private fun wakeReader(proc: PtyProcess) {
            when (proc) {
                is AdoptedPty -> proc.interruptReads()
                is UnixPtyProcess -> runCatching {
                    Pty::class.java.getDeclaredMethod("breakRead").apply { isAccessible = true }.invoke(proc.pty)
                }.onFailure { Log.warn("could not wake the reader of terminal ${proc.pid()}: $it") }
            }
        }

        private fun loginShell(): String =
            System.getenv("SHELL")?.takeIf { File(it).canExecute() }
                ?: listOf("/bin/bash", "/bin/sh").firstOrNull { File(it).canExecute() }
                ?: "/bin/sh"
    }
}

/**
 * A terminal's program as one nop gives it to the next: the PTY master, the child, the size it was
 * drawn at, and what it was showing ([PtyRecorder.replay]).
 *
 * [process] is held only to keep this nop's own handle on the PTY alive until the exec: pty4j closes
 * a master whose handle is garbage collected, and closing it would hang the program up.
 */
class PtyHandoff(
    val fd: Int,
    val pid: Long,
    val columns: Int,
    val rows: Int,
    val replay: ByteArray,
    val process: Process? = null,
)

/** A handed-over program as this nop takes it up. See [TerminalSession.handOff]. */
class Adopted(val pty: AdoptedPty, val columns: Int, val rows: Int, val replay: ByteArray)
