package iondrive.nop.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.ui.JediTermWidget
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import iondrive.nop.launchers.Launcher
import java.awt.Color
import java.awt.Container
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
 * `SIGWINCH`-on-resize work — none of which the old pipe-based `LauncherRun` could do.
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
) {
    /** Whether the child process is currently alive. Compose-observable so the header updates. */
    var running: Boolean by mutableStateOf(false)
        private set

    /**
     * True for a run tab restored from the last time nop was open: the tab is back, the command has
     * not been run again, and [start] is what runs it.
     *
     * Restored rather than re-run deliberately. A launcher is an arbitrary shell command — a
     * deploy, a database migration, a release — and starting nop is not consent to run it. The tab
     * is the useful half of remembering it; pressing Run is the user's half.
     */
    var deferred: Boolean by mutableStateOf(deferred)
        private set

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

    private var widget: JediTermWidget? = null
    private var settings: NopTerminalSettings? = null
    private var process: PtyProcess? = null

    @Volatile
    private var restarting = false

    /**
     * Builds the widget + starts the PTY on first call, returning the same widget thereafter (so
     * the host can re-attach it to its card without respawning). Must run on the AWT EDT.
     */
    fun getOrCreateWidget(bg: Color, fg: Color, link: Color): JediTermWidget {
        widget?.let { return it }
        val s = NopTerminalSettings(bg, fg, link)
        val w = NopTerminalWidget(INITIAL_COLUMNS, INITIAL_ROWS, s)
        // Underlines the http(s) URLs the run prints and makes them open in the browser on click.
        // Must be installed before the process starts writing, or early output misses out: JediTerm
        // only runs the filters over a line as it is written.
        w.addHyperlinkFilter(UrlHyperlinkFilter())
        // Dropped files are typed in as paths — see TerminalFileDrop. On the terminal panel rather
        // than on the widget: the panel is what fills the widget, so it is what the pointer is over.
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
        settings = s
        widget = w
        attach(w, startProcess())
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
     * (which is what this used to do) races on exactly that.
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
    }

    /**
     * Types [text] into the terminal, exactly as the keyboard would: the bytes go to the PTY, so
     * the child reads them from its stdin and the line discipline echoes them back onto the screen.
     * Nothing happens if no process is running — there is nothing to type at.
     */
    fun sendText(text: String) {
        val proc = process ?: return
        runCatching {
            val out = proc.outputStream
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
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
        val childEnv = HashMap(System.getenv())
        // Advertise a colour terminal so tools enable ANSI output and full-screen rendering.
        childEnv["TERM"] = "xterm-256color"
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
        val proc = PtyProcessBuilder()
            .setCommand(command.toTypedArray())
            .setEnvironment(childEnv)
            .setDirectory(workingDir.absolutePath)
            .setInitialColumns(INITIAL_COLUMNS)
            .setInitialRows(INITIAL_ROWS)
            .start()
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
        w.ttyConnector = PtyTtyConnector(proc, outputTap)
        w.start()
    }

    private fun killProcess() {
        process?.let { killTree(it) }
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

        /** The TTY interrupt character (ETX) — what a terminal sends on Ctrl-C. See [stop]. */
        private const val CTRL_C = 3

        private val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

        /**
         * Runs [launcher]'s command through a shell, mirroring the old runner's invocation.
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
        fun shell(dir: File): TerminalSession =
            TerminalSession(
                title = "Term",
                command = if (isWindows) listOf("cmd.exe") else listOf(loginShell()),
                workingDir = dir,
                isLauncher = false,
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
        ): TerminalSession =
            TerminalSession(
                title = title,
                command = command,
                workingDir = dir,
                isLauncher = false,
                env = env,
                outputTap = outputTap,
            )

        private fun loginShell(): String =
            System.getenv("SHELL")?.takeIf { File(it).canExecute() }
                ?: listOf("/bin/bash", "/bin/sh").firstOrNull { File(it).canExecute() }
                ?: "/bin/sh"
    }
}
