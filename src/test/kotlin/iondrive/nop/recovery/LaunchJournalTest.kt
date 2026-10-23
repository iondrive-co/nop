package iondrive.nop.recovery

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LaunchJournalTest {
    @TempDir
    lateinit var dir: Path

    private val dead: (Long) -> Boolean = { false }

    @BeforeEach
    fun isolate() {
        LaunchJournal.fileOverride = dir.resolve("launch")
        Quarantine.fileOverride = dir.resolve("quarantine")
        WindowRecovery.showingFileOverride = dir.resolve("showing")
        Quarantine.clearForTest()
        WindowRecovery.resetForTest()
    }

    @AfterEach
    fun restore() {
        LaunchJournal.fileOverride = null
        Quarantine.fileOverride = null
        WindowRecovery.showingFileOverride = null
        Quarantine.clearForTest()
        WindowRecovery.resetForTest()
    }

    @Test
    fun `a first launch has nothing to answer for`() {
        assertEquals(LaunchJournal.Previous.CLEAN, LaunchJournal.begin(100, dead))
    }

    @Test
    fun `a launch that never got up is caught by the next`() {
        LaunchJournal.begin(100, dead)
        assertEquals(LaunchJournal.Previous.DIED_STARTING, LaunchJournal.begin(200, dead))
    }

    @Test
    fun `a launch that got up, quit or restarted ended well`() {
        LaunchJournal.begin(100, dead)
        LaunchJournal.up(100)
        assertEquals(LaunchJournal.Previous.CLEAN, LaunchJournal.begin(200, dead))
        LaunchJournal.stopped(200)
        assertEquals(LaunchJournal.Previous.CLEAN, LaunchJournal.begin(300, dead))
    }

    @Test
    fun `a launch still starting under another live pid is not held against it`() {
        LaunchJournal.begin(100, dead)
        assertEquals(LaunchJournal.Previous.CLEAN, LaunchJournal.begin(200) { it == 100L })
    }

    @Test
    fun `a restart in place keeps its pid and still counts a death on the way up`() {
        // The exec that restarts nop in place keeps the process; "starting" under our own pid is the
        // nop before the exec, which got no further.
        LaunchJournal.begin(100, dead)
        assertEquals(LaunchJournal.Previous.DIED_STARTING, LaunchJournal.begin(100) { true })
    }

    @Test
    fun `a launch that died starting has what it showed held back, once`() {
        // Launch one shows a tab, then dies before it is up. (A pid no process can have: the next
        // launch checks for real whether it is still alive.)
        LaunchJournal.begin(Long.MAX_VALUE - 7, dead)
        WindowRecovery.showing(1, "diff:/repo/docs/IDEAS.md")
        WindowRecovery.flushShowing()

        // Launch two holds that tab back, and says why.
        assertEquals(LaunchJournal.Previous.DIED_STARTING, recoverFromLastLaunch(pid = Long.MAX_VALUE - 1))
        val held = checkNotNull(Quarantine.entry("diff:/repo/docs/IDEAS.md")) { "not held back" }
        assertTrue("did not start cleanly" in held.reason, held.reason)
        assertFalse(WindowRecovery.restartedToRecover)
    }

    @Test
    fun `a launch after a recovery restart holds back what was showing and will not restart again`() {
        LaunchJournal.begin(100, dead)
        WindowRecovery.showing(1, "file:/x")
        WindowRecovery.flushShowing()
        LaunchJournal.recovering(100)

        assertEquals(LaunchJournal.Previous.RECOVERING, recoverFromLastLaunch(pid = 100))
        assertNotNull(Quarantine.entry("file:/x"))
        assertTrue(WindowRecovery.restartedToRecover)
    }

    @Test
    fun `a clean launch holds nothing back`() {
        WindowRecovery.showing(1, "file:/x")
        WindowRecovery.flushShowing()
        LaunchJournal.begin(100, dead)
        LaunchJournal.up(100)
        assertEquals(LaunchJournal.Previous.CLEAN, recoverFromLastLaunch(pid = 200))
        assertNull(Quarantine.entry("file:/x"))
    }

    @Test
    fun `a damaged journal reads as a clean start`() {
        Files.writeString(dir.resolve("launch"), "\u0000nonsense")
        assertEquals(LaunchJournal.Previous.CLEAN, LaunchJournal.begin(100, dead))
    }
}
