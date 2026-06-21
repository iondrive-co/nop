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
 * Build one with [forLauncher] or [shell]. The widget + process are created lazily on the AWT
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
) {
    /** Whether the child process is currently alive. Compose-observable so the header updates. */
    var running: Boolean by mutableStateOf(false)
        private set

    private var widget: JediTermWidget? = null
    private var settings: NopTerminalSettings? = null
    private var process: PtyProcess? = null

    @Volatile
    private var restarting = false

    /**
     * Builds the widget + starts the PTY on first call, returning the same widget thereafter (so
     * the host can re-attach it to its card without respawning). Must run on the AWT EDT.
     */
    fun getOrCreateWidget(bg: Color, fg: Color): JediTermWidget {
        widget?.let { return it }
        val s = NopTerminalSettings(bg, fg)
        val w = JediTermWidget(INITIAL_COLUMNS, INITIAL_ROWS, s)
        settings = s
        widget = w
        attach(w, startProcess())
        return w
    }

    /** Repaints the terminal in new theme colours; no-op if nothing changed or not yet created. */
    fun applyColors(bg: Color, fg: Color) {
        val s = settings ?: return
        if (s.bg == bg && s.fg == fg) return
        s.bg = bg
        s.fg = fg
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
     * Kills the process but leaves the widget so its final output stays readable (the Stop button).
     * Deliberately keeps the `process` reference: if the user hits Re-run next, [restart] waits for
     * this tree to fully exit (freeing any listening ports) before relaunching, so a Stop→Re-run
     * doesn't race the same way a bare relaunch would.
     */
    fun stop() {
        process?.let { killTree(it) }
        running = false
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

    private fun startProcess(): PtyProcess {
        val env = HashMap(System.getenv())
        // Advertise a colour terminal so tools enable ANSI output and full-screen rendering.
        env["TERM"] = "xterm-256color"
        val proc = PtyProcessBuilder()
            .setCommand(command.toTypedArray())
            .setEnvironment(env)
            .setDirectory(workingDir.absolutePath)
            .setInitialColumns(INITIAL_COLUMNS)
            .setInitialRows(INITIAL_ROWS)
            .start()
        process = proc
        running = true
        // Daemon watcher flips `running` false the moment the child exits, so the header can swap
        // its Stop button for Re-run without polling.
        thread(isDaemon = true, name = "terminal-watch") {
            runCatching { proc.waitFor() }
            if (process === proc) running = false
        }
        return proc
    }

    private fun attach(w: JediTermWidget, proc: PtyProcess) {
        w.ttyConnector = PtyTtyConnector(proc)
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

        private val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

        /** Runs [launcher]'s command through a shell, mirroring the old runner's invocation. */
        fun forLauncher(launcher: Launcher, dir: File): TerminalSession =
            TerminalSession(
                title = "▶ ${launcher.name}",
                command = if (isWindows) listOf("cmd.exe", "/c", launcher.command)
                else listOf("sh", "-c", launcher.command),
                workingDir = dir,
                isLauncher = true,
            )

        /** Opens a plain interactive shell — a normal terminal, not tied to a launcher. */
        fun shell(dir: File): TerminalSession =
            TerminalSession(
                title = "⌨ Terminal",
                command = if (isWindows) listOf("cmd.exe") else listOf(loginShell()),
                workingDir = dir,
                isLauncher = false,
            )

        private fun loginShell(): String =
            System.getenv("SHELL")?.takeIf { File(it).canExecute() }
                ?: listOf("/bin/bash", "/bin/sh").firstOrNull { File(it).canExecute() }
                ?: "/bin/sh"
    }
}
