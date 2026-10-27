package iondrive.nop.recovery

import iondrive.nop.recovery.WindowRecovery.Outcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The ladder a failing window climbs. Run off the UI thread, where a rebuild lands at once rather
 * than on the next event, so each step can be read straight after it.
 */
class WindowRecoveryTest {
    @TempDir
    lateinit var dir: Path

    private var now = 1_000_000L
    private val window = 7L

    @BeforeEach
    fun isolate() {
        Quarantine.fileOverride = dir.resolve("quarantine")
        WindowRecovery.showingFileOverride = dir.resolve("showing")
        Quarantine.clearForTest()
        WindowRecovery.resetForTest()
        WindowRecovery.clock = { now }
    }

    @AfterEach
    fun restore() {
        // Finish queued writes before JUnit removes their temporary directory.
        WindowRecovery.flushShowing()
        Quarantine.clearForTest()
        WindowRecovery.resetForTest()
        WindowRecovery.clock = System::currentTimeMillis
        Quarantine.fileOverride = null
        WindowRecovery.showingFileOverride = null
    }

    private fun fail(message: String = "boom"): Outcome =
        WindowRecovery.failed(window, WindowRecovery.generation(window), IllegalArgumentException(message))

    @Test
    fun `a window that fails holds back the tab it was showing and is rebuilt`() {
        WindowRecovery.showing(window, "diff:/repo/docs/IDEAS.md")
        assertEquals(Outcome.REBUILT, fail("Can't represent a width of 546566 and height of 0 in Constraints"))
        assertEquals(1, WindowRecovery.generation(window))
        val held = checkNotNull(Quarantine.entry("diff:/repo/docs/IDEAS.md")) { "not held back" }
        assertTrue("546566" in held.reason, held.reason)
        assertTrue(WindowRecovery.showingIn(window).isEmpty())
    }

    @Test
    fun `the same failure repeated by the dying window is one failure`() {
        WindowRecovery.showing(window, "file:/a")
        val generation = WindowRecovery.generation(window)
        assertEquals(Outcome.REBUILT, WindowRecovery.failed(window, generation, IllegalStateException()))
        assertEquals(Outcome.IGNORED, WindowRecovery.failed(window, generation, IllegalStateException()))
        assertEquals(1, WindowRecovery.generation(window))
    }

    @Test
    fun `unsaved work is saved before the window is thrown away`() {
        var saved = 0
        WindowRecovery.onRescue(window) { saved++ }
        val removed = WindowRecovery.onRescue(window) { saved += 100 }
        WindowRecovery.remove(window, removed)
        fail()
        assertEquals(1, saved)
    }

    @Test
    fun `a rescue that throws does not stop the recovery`() {
        WindowRecovery.onRescue(window) { error("disk full") }
        WindowRecovery.showing(window, "file:/a")
        assertEquals(Outcome.REBUILT, fail())
    }

    @Test
    fun `with nothing to blame it rebuilds once, then restarts, then gives up`() {
        var restarts = 0
        WindowRecovery.restart = { restarts++; true }
        assertEquals(Outcome.REBUILT, fail())
        assertEquals(Outcome.RESTARTING, fail())
        assertEquals(1, restarts)
    }

    @Test
    fun `a restart that would lose something is not made, and the window is given up on instead`() {
        WindowRecovery.restart = { false }
        fail()
        assertEquals(Outcome.BROKEN, fail("still"))
        val reason = WindowRecovery.brokenReason(window)
        assertTrue(reason?.startsWith("IllegalArgumentException: still") == true, reason)
    }

    @Test
    fun `a launch that is itself a recovery restart does not restart again`() {
        var restarts = 0
        WindowRecovery.restart = { restarts++; true }
        WindowRecovery.restartedToRecover = true
        fail()
        assertEquals(Outcome.BROKEN, fail())
        assertEquals(0, restarts)
    }

    @Test
    fun `a window given up on draws again when the user tries again, on a fresh ladder`() {
        WindowRecovery.restart = { false }
        fail()
        fail()
        assertNotNull(WindowRecovery.brokenReason(window))
        assertEquals(Outcome.IGNORED, fail(), "a broken window's own failures change nothing")
        WindowRecovery.retry(window)
        assertNull(WindowRecovery.brokenReason(window))
        assertEquals(Outcome.REBUILT, fail(), "the first failure after trying again starts the ladder over")
    }

    @Test
    fun `failures a minute apart are separate runs`() {
        WindowRecovery.restart = { error("should not be reached") }
        assertEquals(Outcome.REBUILT, fail())
        now += 61_000
        assertEquals(Outcome.REBUILT, fail())
    }

    @Test
    fun `however much there is to blame, a window failing over and over is given up on`() {
        val outcomes = (1..6).map { i ->
            WindowRecovery.showing(window, "file:/$i")
            fail()
        }
        assertEquals(List(5) { Outcome.REBUILT } + Outcome.BROKEN, outcomes)
    }

    @Test
    fun `a tab already held back is not blamed again`() {
        Quarantine.add(listOf("file:/held"), "earlier")
        WindowRecovery.showing(window, "file:/held")
        WindowRecovery.restart = { false }
        fail()
        assertEquals("earlier", Quarantine.entry("file:/held")!!.reason)
    }

    @Test
    fun `what each window shows is written down for the next launch`() {
        WindowRecovery.showing(1, "file:/a")
        WindowRecovery.showing(2, "diff:/b")
        WindowRecovery.showing(2, "diff:/b")
        WindowRecovery.notShowing(1, "file:/a")
        WindowRecovery.flushShowing()
        assertEquals(listOf("diff:/b"), Files.readAllLines(dir.resolve("showing")))
        assertEquals(listOf("diff:/b"), WindowRecovery.lastShowing())
    }

    @Test
    fun `the error is named with the line in nop it came from`() {
        val t = RuntimeException("outer", IllegalArgumentException("inner"))
        t.cause!!.stackTrace = arrayOf(
            StackTraceElement("androidx.compose.ui.unit.ConstraintsKt", "createConstraints", "Constraints.kt", 425),
            StackTraceElement("iondrive.nop.ui.DiffRenderingKt", "diffLineWidth", "DiffRendering.kt", 336),
        )
        assertEquals("IllegalArgumentException: inner (at DiffRendering.kt:336)", WindowRecovery.describe(t))
    }
}
