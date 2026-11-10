package iondrive.nop.recovery

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeapRecoveryTest {
    @TempDir
    lateinit var dir: Path

    private var now = 10_000_000L
    private var restarts = 0
    private var allowed = true

    @BeforeEach
    fun isolate() {
        HeapRecovery.resetForTest()
        HeapRecovery.stampFileOverride = dir.resolve("heap-restart")
        HeapRecovery.clock = { now }
        HeapRecovery.restart = {
            restarts++
            allowed
        }
    }

    @AfterEach
    fun restore() {
        HeapRecovery.resetForTest()
        HeapRecovery.stampFileOverride = null
    }

    private val minute = 60_000L

    @Test
    fun `an exhausted heap restarts nop once and writes down when`() {
        assertTrue(HeapRecovery.exhausted("test"))
        assertEquals(1, restarts)
        assertEquals(now.toString(), Files.readString(dir.resolve("heap-restart")))
    }

    @Test
    fun `signals close together ask for one restart`() {
        HeapRecovery.exhausted("first")
        now += minute
        assertFalse(HeapRecovery.exhausted("second"))
        assertEquals(1, restarts)
    }

    /** The stamp is what the restarted process reads: what filled the heap fills it again at start. */
    @Test
    fun `no second restart within the gap, even in the process the first one started`() {
        Files.writeString(dir.resolve("heap-restart"), (now - 10 * minute).toString())
        assertFalse(HeapRecovery.exhausted("again"))
        assertEquals(0, restarts)

        now += HeapRecovery.MIN_GAP_MS
        assertTrue(HeapRecovery.exhausted("much later"))
        assertEquals(1, restarts)
    }

    @Test
    fun `a restart that could not be made leaves no record and is tried again later`() {
        allowed = false
        assertFalse(HeapRecovery.exhausted("a Run tab is open"))
        assertFalse(Files.exists(dir.resolve("heap-restart")))

        allowed = true
        now += 6 * minute
        assertTrue(HeapRecovery.exhausted("still full"))
        assertEquals(2, restarts)
    }

    @Test
    fun `with nothing to restart yet it waits`() {
        HeapRecovery.restart = null
        assertFalse(HeapRecovery.exhausted("before the windows exist"))
    }

    @Test
    fun `an out-of-memory error is known through its causes, and nothing else counts`() {
        assertTrue(HeapRecovery.isOutOfMemory(OutOfMemoryError("Java heap space")))
        assertTrue(HeapRecovery.isOutOfMemory(IllegalStateException("drawing", OutOfMemoryError())))
        assertFalse(HeapRecovery.isOutOfMemory(IllegalStateException("drawing")))

        HeapRecovery.outOfMemory(IllegalArgumentException("not a heap problem"))
        assertEquals(0, restarts)
        HeapRecovery.outOfMemory(RuntimeException(OutOfMemoryError("Java heap space")))
        assertEquals(1, restarts)
    }

    /** The real JVM signal: a collection that leaves the heap over the threshold. */
    @Test
    fun `the heap watcher fires when a collection leaves the heap over its threshold`() {
        val fired = CountDownLatch(1)
        HeapRecovery.clock = System::currentTimeMillis
        HeapRecovery.restart = {
            fired.countDown()
            true
        }
        // A threshold of almost nothing, so whatever survives a collection is over it.
        HeapRecovery.install(fraction = 1e-12)
        val kept = List(1000) { ByteArray(1024) }
        System.gc()
        assertTrue(fired.await(20, TimeUnit.SECONDS), "no threshold notification after a collection")
        assertEquals(1000, kept.size)
    }
}
