package io.iondrive.nop.launchers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LauncherRunTest {
    @Test
    fun `start runs the command and captures stdout`(@TempDir tmp: Path) {
        val run = LauncherRun(Launcher("hello", "echo hello-world"), tmp.toFile())
        run.start()
        waitForExit(run)

        assertEquals(0, run.exitCode)
        assertTrue("hello-world" in run.output) { "expected 'hello-world' in output, got: ${run.output}" }
        assertTrue("[exit 0]" in run.output) { "expected exit marker in output, got: ${run.output}" }
    }

    @Test
    fun `non-zero exit is reported on exitCode`(@TempDir tmp: Path) {
        val run = LauncherRun(Launcher("bad", "exit 7"), tmp.toFile())
        run.start()
        waitForExit(run)

        assertEquals(7, run.exitCode)
    }

    @Test
    fun `working directory is honoured`(@TempDir tmp: Path) {
        val run = LauncherRun(Launcher("pwd", "pwd"), tmp.toFile())
        run.start()
        waitForExit(run)

        assertEquals(0, run.exitCode)
        // Use realpath in case tmp gets symlink-resolved (macOS /private/var ↔ /var) — the trimmed
        // first line of output should match the temp dir's real path.
        val expected = tmp.toRealPath().toString()
        val actual = run.output.lineSequence().first().trim()
        assertEquals(expected, actual)
    }

    @Test
    fun `stop kills a long-running process`(@TempDir tmp: Path) {
        val run = LauncherRun(Launcher("sleep", "sleep 30"), tmp.toFile())
        run.start()
        // Give the shell a moment to actually start the sleep
        Thread.sleep(200)
        assertTrue(run.running, "expected running=true after start")
        run.stop()
        waitForExit(run)

        // sleep killed by SIGTERM exits non-zero; we don't pin the exact code (varies by shell)
        assertNotEquals(0, run.exitCode)
    }

    private fun waitForExit(run: LauncherRun, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (run.exitCode == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        check(run.exitCode != null) { "process did not exit within ${timeoutMs}ms" }
    }
}
