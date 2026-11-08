package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Exercises the pty4j + [PtyTtyConnector] core without any Swing/JediTerm UI. The headline test is
 * [child sees a real TTY]: it's the whole reason this feature exists — a pipe-based runner prints
 * NOTTY and can never accept a typed password. Unix-only because the assertions use
 * `sh`; the JediTerm rendering itself is verified separately under Xvfb.
 */
@DisabledOnOs(OS.WINDOWS)
class PtyTerminalSessionTest {
    @Test
    fun `provider switch waits for the CLI and its backend to stop`() {
        val process = ptyProcess("sh", "-c", "sleep 40 & echo backend:$!; wait")
        val session = TerminalSession.agent(listOf("unused-test-command"), emptyMap(), File("/"), "sample")
        try {
            val line = process.inputStream.bufferedReader().readLine()
            val childPid = line.substringAfter("backend:").trim().toLong()
            session.javaClass.getDeclaredField("process").apply { isAccessible = true }.set(session, process)
            session.killAndWait()
            assertFalse(process.isAlive)
            val state = runCatching { java.nio.file.Files.readString(java.nio.file.Path.of("/proc/$childPid/stat"))
                .substringAfterLast(") ").first() }.getOrNull()
            assertTrue(state == null || state == 'Z', "a backend must not be executing after switch returns")
        } finally {
            session.dispose()
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }


    private fun ptyProcess(vararg command: String): PtyProcess {
        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        return PtyProcessBuilder()
            .setCommand(arrayOf(*command))
            .setEnvironment(env)
            .setDirectory(System.getProperty("java.io.tmpdir"))
            .setInitialColumns(80)
            .setInitialRows(24)
            .start()
    }

    /** Drains the connector until the child closes the PTY (read returns <= 0 at EOF). */
    private fun PtyTtyConnector.drainToEof(): String {
        val sb = StringBuilder()
        val buf = CharArray(4096)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n <= 0) break
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    /** Reads from the connector until [marker] appears (or the PTY closes). */
    private fun PtyTtyConnector.readUntil(marker: String): String {
        val sb = StringBuilder()
        val buf = CharArray(1024)
        while (marker !in sb) {
            val n = read(buf, 0, buf.size)
            if (n <= 0) break
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    /**
     * JediTerm closes the connector whenever its session ends, including when an OutOfMemoryError
     * ends it, and closing signals the program. That turned nop running out of heap into agents being
     * SIGTERMed mid-work, so a close with the program alive and nothing ending it must leave it be.
     */
    @Test
    fun `a close nobody asked for leaves a running program alone`() {
        val process = ptyProcess("sleep", "30")
        try {
            val connector = PtyTtyConnector(process)
            var told = false
            connector.onReaderLost = { told = true }
            connector.close()
            Thread.sleep(300)
            assertTrue(process.isAlive, "the reader failing is no reason to end the program")
            assertTrue(connector.readerLost && told, "whoever holds the terminal has to hear of it")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a close from whoever is ending the terminal still ends the program`() {
        // A program is only signalled once it runs: between pty4j's fork and the exec, the child still
        // has the JVM's SIGTERM handler, which takes the signal and does nothing with it.
        val process = ptyProcess("sh", "-c", "echo started; exec sleep 30")
        try {
            val connector = PtyTtyConnector(process)
            connector.readUntil("started")
            connector.releasing = true
            connector.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "closing the tab must not leave its program running")
        } finally {
            process.destroyForcibly()
        }
    }

    /** It stops writing to a terminal and carries on, which SIGTERM has always been what ends. */
    @Test
    fun `a close after the output ran out still ends a program left behind`() {
        val process = ptyProcess("sh", "-c", "exec 0<&- 1>&- 2>&-; sleep 30")
        try {
            val connector = PtyTtyConnector(process)
            connector.drainToEof()
            connector.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "nothing can read it, so nothing will ever show it")
            assertFalse(connector.readerLost)
        } finally {
            process.destroyForcibly()
        }
    }

    /** The whole of it, through JediTerm's own session: the error, the close, and reading again. */
    @Test
    fun `a terminal keeps reading its program after an OutOfMemoryError ends the session`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display to build the widget")
        val process = ptyProcess("sh", "-c", "while :; do echo tick; sleep 0.05; done")
        val seen = StringBuffer()
        val connector = PtyTtyConnector(process, tap = { seen.append(it) })
        var widget: JediTermWidget? = null
        try {
            SwingUtilities.invokeAndWait {
                val w = JediTermWidget(80, 24, DefaultSettingsProvider())
                widget = w
                connector.onReaderLost = { resumeReading(w, connector, retryMs = 50) { true } }
                w.ttyConnector = connector
                w.start()
            }
            awaitTrue("the program's output reaches the terminal") { "tick" in seen }

            connector.failNextRead = OutOfMemoryError("test")
            awaitTrue("the error reaches the reader") { connector.failNextRead == null }
            Thread.sleep(200)
            assertTrue(process.isAlive, "the program must survive the session it was read by")

            seen.setLength(0)
            awaitTrue("output must reach the terminal again") { "tick" in seen }
            assertFalse(connector.readerLost, "a session is reading again")
        } finally {
            connector.releasing = true
            process.destroyForcibly()
            SwingUtilities.invokeAndWait { widget?.close() }
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), what)
    }

    @Test
    fun `child sees a real TTY`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val connector = PtyTtyConnector(ptyProcess("sh", "-c", "[ -t 0 ] && echo HASTTY || echo NOTTY"))
        val output = connector.drainToEof()
        assertTrue("HASTTY" in output, "expected a TTY-backed child, got: $output")
    }

    @Test
    fun `typed input reaches the child`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val connector = PtyTtyConnector(ptyProcess("sh", "-c", "read line; echo GOT:\$line"))
        connector.write("world\n")
        val output = connector.drainToEof()
        assertTrue("GOT:world" in output, "stdin did not reach the child, got: $output")
    }

    @Test
    fun `waitFor returns the child exit code`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val connector = PtyTtyConnector(ptyProcess("sh", "-c", "exit 7"))
        connector.drainToEof()
        assertEquals(7, connector.waitFor())
    }

    @Test
    fun `resize forwards a window size without throwing`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val proc = ptyProcess("sh", "-c", "sleep 0.5")
        val connector = PtyTtyConnector(proc)
        connector.resize(TermSize(120, 40))
        assertTrue(connector.isConnected)
        connector.drainToEof()
        connector.waitFor()
    }

    /**
     * TerminalSession kills a run with [Process.destroyForcibly], which on a pty4j process signals
     * the whole process group — so the shell *and* the command it spawned both die. (Using
     * Process.descendants() instead, as a plain ProcessBuilder process would, throws "toHandle()
     * not supported" on pty4j and leaves the child orphaned.) This guards that contract.
     */
    @Test
    fun `destroyForcibly reaps the child process not just the shell`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            // The shell backgrounds a long sleep, prints its pid, then waits — so the sleep is a
            // live child in the shell's process group when we kill it.
            val proc = ptyProcess("sh", "-c", "sleep 30 & echo PID:\$!; wait")
            val connector = PtyTtyConnector(proc)
            val childPid = Regex("PID:(\\d+)").find(connector.readUntil("PID:"))
                ?.groupValues?.get(1)?.toLong()
                ?: error("did not see the child pid in the PTY output")

            proc.destroyForcibly()
            proc.waitFor()

            // The child is in the killed process group, so it must be gone shortly after.
            var alive = true
            repeat(30) {
                if (!File("/proc/$childPid").exists()) { alive = false; return@repeat }
                Thread.sleep(50)
            }
            assertFalse(alive, "child process $childPid survived destroyForcibly — group was not killed")
        }

    /**
     * The Stop button does not hard-kill: TerminalSession.stop() writes the TTY interrupt
     * character (ETX, 0x03) so the line discipline raises SIGINT on the foreground process group —
     * exactly what pressing Ctrl-C does. This guards that contract: the child traps SIGINT and exits
     * 42, so seeing exit 42 proves it got a clean SIGINT. A SIGKILL would skip
     * the trap entirely, so the test would never see 42.
     */
    @Test
    fun `the interrupt char delivers SIGINT so the child can exit cleanly`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val proc = ptyProcess(
                "sh", "-c",
                "trap 'echo INTERRUPTED; exit 42' INT; echo READY; while :; do sleep 0.1; done",
            )
            val connector = PtyTtyConnector(proc)
            connector.readUntil("READY")

            // Exactly what stop() does: write Ctrl-C to the PTY.
            proc.outputStream.write(3)
            proc.outputStream.flush()

            val output = connector.drainToEof()
            assertEquals(42, proc.waitFor(), "child should exit via its SIGINT trap, not be killed; output: $output")
            assertTrue("INTERRUPTED" in output, "the SIGINT trap should have run; output: $output")
        }

    /**
     * Re-running a launcher that binds a port (a dev server) must free the port before the new run
     * starts, or the relaunch dies with "address already in use". TerminalSession.restart() does
     * this by killing the old tree and *waiting* for it to exit before relaunching; here we mirror
     * that kill→wait→rebind and assert the second bind succeeds. (python3 just gives us a trivial
     * port-binding process; skipped if it isn't on PATH.)
     */
    @Test
    fun `re-running a port-bound server rebinds the same port`() =
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assumeTrue(commandExists("python3"), "python3 not on PATH")
            val port = freePort()
            val cmd = "python3 -m http.server $port"

            val first = PtyTtyConnector(ptyProcess("sh", "-c", cmd))
            assertTrue("Serving HTTP" in first.readUntil("Serving HTTP"), "first server never bound")

            // Fully tear down (and wait) before relaunching.
            first.getProcess().destroyForcibly()
            first.waitFor()

            val second = PtyTtyConnector(ptyProcess("sh", "-c", cmd))
            val out = second.readUntil("Serving HTTP")
            second.getProcess().destroyForcibly()
            second.waitFor()
            assertTrue("Serving HTTP" in out, "relaunch failed to rebind port $port; output was: $out")
        }

    private fun commandExists(cmd: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, cmd).canExecute() }

    /** A momentarily-free TCP port. Racy in theory, fine for a single local test process. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
