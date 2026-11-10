package iondrive.nop.recovery

import iondrive.nop.Log
import iondrive.nop.Settings
import java.lang.management.ManagementFactory
import java.lang.management.MemoryNotificationInfo
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

/**
 * Restarts nop in place when its heap runs out, rather than leaving it running on threads an
 * OutOfMemoryError has broken.
 *
 * The error lands in whichever thread allocates next, so after one nop can be left with a terminal
 * reader, a transcript follower or the updater's connection dead while its windows still draw. A
 * restart in place hands every agent and terminal over to a fresh process, as the bar's restart
 * button does, and costs a few seconds and none of the work.
 *
 * Two signals: an OutOfMemoryError reported to [outOfMemory], and the heap still nearly full right
 * after a collection ([install]), which is where one is headed. Nothing on screen is held back
 * afterwards: a full heap is no one tab's fault. No second restart is made within [MIN_GAP_MS] of
 * the last one, which is written down so the restarted process knows it: what fills the heap then
 * fills it at every start, and restarting again would only loop.
 */
object HeapRecovery {
    /** How full the heap may still be after a collection before nop counts it as exhausted. */
    const val THRESHOLD = 0.95

    /** The least time between two restarts for the heap. */
    const val MIN_GAP_MS = 30L * 60 * 1000

    /** Between two tries when a restart could not be made, so a full heap does not ask on every collection. */
    private const val RETRY_MS = 5L * 60 * 1000

    /**
     * Restarts nop in place, handing agents and terminals over. Returns false when it cannot without
     * losing something — a Run tab that would stop — and so did not. Set once the windows exist.
     */
    @Volatile var restart: (() -> Boolean)? = null

    internal var clock: () -> Long = System::currentTimeMillis
    internal var stampFileOverride: Path? = null
    private val stampFile: Path get() = stampFileOverride ?: Settings.configRoot.resolve("nop").resolve("heap-restart")

    private val lastTry = AtomicLong(Long.MIN_VALUE / 2)
    private var listener: NotificationListener? = null

    /** Watches the heap: once it is still [fraction] full after a collection, nop restarts. */
    fun install(fraction: Double = THRESHOLD) {
        uninstall()
        ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP && it.isCollectionUsageThresholdSupported }
            .forEach { pool ->
                val max = pool.usage.max
                if (max > 0) pool.collectionUsageThreshold = (max * fraction).toLong().coerceAtLeast(1)
            }
        val watch = NotificationListener { notification, _ ->
            if (notification.type != MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED) return@NotificationListener
            val info = MemoryNotificationInfo.from(notification.userData as CompositeData)
            val usage = info.usage
            val full = if (usage.max > 0) usage.used * 100 / usage.max else 100
            exhausted("${info.poolName} still $full% full after a collection")
        }
        (ManagementFactory.getMemoryMXBean() as NotificationEmitter).addNotificationListener(watch, null, null)
        listener = watch
    }

    /** Stops watching, and turns the thresholds [install] set off again. */
    fun uninstall() {
        listener?.let { runCatching { (ManagementFactory.getMemoryMXBean() as NotificationEmitter).removeNotificationListener(it) } }
        listener = null
        ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP && it.isCollectionUsageThresholdSupported }
            .forEach { runCatching { it.collectionUsageThreshold = 0 } }
    }

    /** [t], if it is or was caused by an OutOfMemoryError, as a sign the heap has run out. */
    fun outOfMemory(t: Throwable) {
        if (isOutOfMemory(t)) exhausted("${t.javaClass.simpleName}: ${t.message ?: "no message"}")
    }

    fun isOutOfMemory(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(32).any { it is OutOfMemoryError }

    /** The heap has run out: restarts nop in place, unless one was made too recently. Returns whether it asked to. */
    internal fun exhausted(why: String): Boolean {
        val now = clock()
        val previous = lastTry.get()
        if (now - previous < RETRY_MS || !lastTry.compareAndSet(previous, now)) return false
        val last = lastRestart()
        if (last != null && now - last in 0 until MIN_GAP_MS) {
            Log.warn("heap exhausted ($why), ${(now - last) / 60_000} min after the last restart for it; not restarting again")
            return false
        }
        val restartInPlace = restart
        if (restartInPlace == null) {
            Log.warn("heap exhausted ($why); nop cannot restart in place yet")
            return false
        }
        Log.warn("heap exhausted ($why); restarting nop in place to recover")
        // Written first, so a restart the UI thread gets to only after this gave up waiting still
        // counts against the next one.
        runCatching {
            Files.createDirectories(stampFile.parent)
            Files.writeString(stampFile, now.toString())
        }
        if (!restartInPlace()) {
            runCatching { Files.deleteIfExists(stampFile) }
            Log.warn("heap exhausted, but nop could not restart in place now: a Run tab would stop, or the UI did not answer")
            return false
        }
        return true
    }

    private fun lastRestart(): Long? = runCatching { Files.readString(stampFile).trim().toLong() }.getOrNull()

    internal fun resetForTest() {
        uninstall()
        restart = null
        lastTry.set(Long.MIN_VALUE / 2)
        clock = System::currentTimeMillis
    }
}
