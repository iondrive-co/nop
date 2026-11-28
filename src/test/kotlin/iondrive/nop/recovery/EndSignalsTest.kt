package iondrive.nop.recovery

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EndSignalsTest {
    @TempDir
    lateinit var dir: Path

    private val exits = CopyOnWriteArrayList<Int>()
    private var now = 1_000_000L

    /** 16 GB machine, with [percent] of it available. */
    private fun memory(percent: Int) = EndSignals.Memory(availableKb = 160_000L * percent, totalKb = 16_000_000, rssKb = 900_000)

    private val short = memory(5)
    private val plenty = memory(40)

    @BeforeEach
    fun isolate() {
        EndSignals.resetForTest()
        EndSignals.stampFileOverride = dir.resolve("signal-reprieve")
        EndSignals.clock = { now }
        EndSignals.exit = { exits += it }
        LaunchJournal.fileOverride = dir.resolve("launch")
    }

    @AfterEach
    fun restore() {
        EndSignals.resetForTest()
        LaunchJournal.fileOverride = null
    }

    @Test
    fun `a signal with memory to spare is somebody asking nop to quit`() {
        assertEquals(EndSignals.Action.QUIT, EndSignals.decide(plenty, sinceReprieveMs = null))
        assertEquals(EndSignals.Action.QUIT, EndSignals.decide(null, sinceReprieveMs = null))
    }

    @Test
    fun `a signal with memory short is answered by restarting, once in a while`() {
        assertEquals(EndSignals.Action.RESTART_IN_PLACE, EndSignals.decide(short, sinceReprieveMs = null))
        assertEquals(EndSignals.Action.QUIT, EndSignals.decide(short, sinceReprieveMs = 3 * 60_000L))
        assertEquals(EndSignals.Action.RESTART_IN_PLACE, EndSignals.decide(short, sinceReprieveMs = EndSignals.REPRIEVE_GAP_MS))
        // A stamp from the future is a clock that was set back, not a recent reprieve.
        assertEquals(EndSignals.Action.RESTART_IN_PLACE, EndSignals.decide(short, sinceReprieveMs = -5_000L))
    }

    @Test
    fun `memory is read from what the kernel reports`() {
        val meminfo = "MemTotal:       16000000 kB\nMemFree:          300000 kB\nMemAvailable:    1200000 kB\n"
        val status = "Name:\tnop\nVmPeak:\t 9000000 kB\nVmRSS:\t  750000 kB\n"
        val read = checkNotNull(EndSignals.parseMemory(meminfo, status))
        assertEquals(EndSignals.Memory(availableKb = 1_200_000, totalKb = 16_000_000, rssKb = 750_000), read)
        assertEquals(7.5, read.availablePercent)
        assertNull(EndSignals.parseMemory("MemTotal: 16000000 kB\n", status))
        assertEquals(0, checkNotNull(EndSignals.parseMemory(meminfo, "")).rssKb)
    }

    @Test
    fun `short of memory, nop restarts in place and does not quit`() {
        var restarts = 0
        var quits = 0
        EndSignals.memory = { short }
        EndSignals.restart = { restarts++; true }
        EndSignals.quit = { quits++ }

        EndSignals.received("TERM")

        assertEquals(1, restarts)
        assertEquals(0, quits)
        assertTrue(exits.isEmpty())
        assertEquals(now.toString(), Files.readString(dir.resolve("signal-reprieve")))
    }

    @Test
    fun `a second signal soon after a reprieve quits`() {
        var restarts = 0
        var quits = 0
        EndSignals.memory = { short }
        EndSignals.restart = { restarts++; true }
        EndSignals.quit = { quits++ }
        EndSignals.quitWaitMs = 60_000

        EndSignals.received("TERM")
        now += 90_000
        EndSignals.received("TERM")

        assertEquals(1, restarts)
        assertEquals(1, quits)
    }

    @Test
    fun `a restart that cannot be done leaves nop up, and the next signal quits`() {
        var quits = 0
        EndSignals.memory = { short }
        EndSignals.restart = { false }
        EndSignals.quit = { quits++ }
        EndSignals.quitWaitMs = 60_000

        EndSignals.received("TERM")
        assertEquals(0, quits)
        assertTrue(exits.isEmpty())

        now += 20_000
        EndSignals.received("TERM")
        assertEquals(1, quits)
    }

    @Test
    fun `a signal before the windows exist ends nop at once and counts as a stop`() {
        val pid = ProcessHandle.current().pid()
        LaunchJournal.up(pid)
        EndSignals.memory = { plenty }

        EndSignals.received("HUP")

        assertEquals(listOf(143), exits.toList())
        assertEquals(LaunchJournal.State.STOPPED to pid, LaunchJournal.read())
    }

    @Test
    fun `ending by signal leaves another launch's journal alone`() {
        LaunchJournal.up(Long.MAX_VALUE - 7)
        EndSignals.memory = { plenty }

        EndSignals.received("TERM")

        assertEquals(listOf(143), exits.toList())
        assertEquals(LaunchJournal.State.UP to Long.MAX_VALUE - 7, LaunchJournal.read())
    }

    @Test
    fun `windows that will not close do not keep nop from ending`() {
        val ended = CountDownLatch(1)
        var quits = 0
        EndSignals.memory = { plenty }
        EndSignals.quit = { quits++ }
        EndSignals.quitWaitMs = 30
        EndSignals.exit = { exits += it; ended.countDown() }

        EndSignals.received("INT")
        assertEquals(1, quits)

        assertTrue(ended.await(10, TimeUnit.SECONDS), "never ended")
        assertEquals(listOf(143), exits.toList())
    }

    @Test
    fun `a signal while nop is already quitting changes nothing`() {
        var quits = 0
        EndSignals.memory = { plenty }
        EndSignals.quit = { quits++ }
        EndSignals.quitWaitMs = 60_000

        EndSignals.received("TERM")
        EndSignals.received("TERM")

        assertEquals(1, quits)
        assertFalse(exits.contains(0))
        assertTrue(exits.isEmpty())
    }
}
