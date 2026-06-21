package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.net.ServerSocket
import java.time.Duration

/**
 * Exercises the pty4j + [PtyTtyConnector] core without any Swing/JediTerm UI. The headline test is
 * [child sees a real TTY]: it's the whole reason this feature exists — the old pipe-based runner
 * would print NOTTY and could never accept a typed password. Unix-only because the assertions use
 * `sh`; the JediTerm rendering itself is verified separately under Xvfb.
 */
@DisabledOnOs(OS.WINDOWS)
class PtyTerminalSessionTest {

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

            // The fix: fully tear down (and wait) before relaunching.
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
