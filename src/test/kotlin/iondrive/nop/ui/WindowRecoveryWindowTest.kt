package iondrive.nop.ui

import iondrive.nop.recovery.Quarantine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A tab's content failing in a real window costs that window, rebuilt, and nothing else — and the
 * next launch still holds the tab back. See [WindowRecoveryScenario] for the steps.
 *
 * Real windows, so a display: one of its own from xvfb-run, in a JVM of its own, never the user's.
 */
class WindowRecoveryWindowTest {
    @Test
    fun `a failing tab costs its window a rebuild, and nothing else`(@TempDir dir: Path) {
        val xvfbRun = listOf("/usr/bin/xvfb-run", "/bin/xvfb-run").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(xvfbRun != null, "needs xvfb-run for a display of its own")

        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = listOf(
            xvfbRun!!.path, "-a", "-s", "-screen 0 1600x1000x24",
            java, "-cp", System.getProperty("java.class.path"),
            WindowRecoveryScenario::class.java.name, dir.toString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment().remove("DISPLAY")
            environment().remove("WAYLAND_DISPLAY")
        }.start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000)
        val checks = output.lines().filter { it.startsWith("CHECK") || it.startsWith("RESULT") }.joinToString("\n")
        println(checks)
        assertEquals(0, if (finished) process.exitValue() else -1, "scenario failed:\n$output")

        // The next launch reads the tab back as held back.
        Quarantine.fileOverride = dir.resolve("quarantine")
        try {
            Quarantine.load()
            assertNotNull(Quarantine.entry(Tab.FileView(dir.resolve("IDEAS.md").toFile()).recoveryKey))
        } finally {
            Quarantine.clearForTest()
            Quarantine.fileOverride = null
        }
    }
}
