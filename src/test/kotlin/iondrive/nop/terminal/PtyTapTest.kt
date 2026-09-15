package iondrive.nop.terminal

import iondrive.nop.agent.QuotaHit
import iondrive.nop.agent.QuotaWatcher
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The output tap on [PtyTtyConnector], which is how nop notices a vendor CLI announcing a quota
 * wall — a thing the CLI says in its own UI and nowhere else.
 *
 * These assert that **the tap sees the bytes**, not that the regex works; `QuotaTest` covers the
 * regex. The failure this guards against is silent and total: if JediTerm draws through some path
 * other than `ProcessTtyConnector.read`, quota detection simply never fires and every test that
 * only exercised the pattern would still pass.
 */
@DisabledOnOs(OS.WINDOWS)
class PtyTapTest {

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

    @Test
    fun `the tap sees what the child writes`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val seen = StringBuilder()
        val connector = PtyTtyConnector(ptyProcess("sh", "-c", "echo hello from the child")) { text ->
            synchronized(seen) { seen.append(text) }
        }

        connector.drainToEof()

        assertTrue(
            "hello from the child" in seen.toString(),
            "the tap never saw the child's output — quota detection would silently never fire",
        )
    }

    @Test
    fun `the terminal still receives every character the tap saw`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val seen = StringBuilder()
            val connector = PtyTtyConnector(ptyProcess("sh", "-c", "printf 'abcdefghij'")) { text ->
                synchronized(seen) { seen.append(text) }
            }

            val drawn = connector.drainToEof()

            // A tap that consumed or altered anything would show up here as a difference between
            // what was drawn and what was read.
            assertEquals(drawn, seen.toString())
        }

    @Test
    fun `a tap that throws does not cost the user their terminal`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val connector = PtyTtyConnector(ptyProcess("sh", "-c", "echo still here")) {
                error("a broken tap")
            }

            assertTrue("still here" in connector.drainToEof())
        }

    /**
     * End to end, with the real components: a child prints the sentence Claude Code prints when a
     * plan limit is reached, and the watcher fires from the tap on the live PTY.
     */
    @Test
    fun `a quota wall printed by the child reaches the watcher`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val hits = CopyOnWriteArrayList<QuotaHit>()
            val watcher = QuotaWatcher { hits += it }
            val connector = PtyTtyConnector(
                ptyProcess("sh", "-c", "echo 'Working...'; echo \"You've hit your usage limit\""),
            ) { watcher.feed(it) }

            connector.drainToEof()

            assertEquals(1, hits.size, "the watcher saw nothing on a live PTY")
            assertEquals("You've hit your usage limit", hits.single().line)
        }

    @Test
    fun `a connector with no tap behaves exactly as before`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val connector = PtyTtyConnector(ptyProcess("sh", "-c", "echo plain"))

            assertTrue("plain" in connector.drainToEof())
        }
}
