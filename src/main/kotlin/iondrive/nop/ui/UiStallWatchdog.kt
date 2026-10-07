package iondrive.nop.ui

import iondrive.nop.Log
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Notices when the UI thread stops answering, and writes down where it is stuck.
 *
 * Every window nop has draws on the one AWT thread, so anything slow on it — a stat into a mount
 * that is not there, a read of a file the size of a log — freezes all of them at once, and from the
 * outside that looks like nothing at all: no exception, no log line, just a window that will not
 * draw. Finding the cause took a `jstack` of a live process. With this running, the log says where
 * the thread is after [stallAfterMs] without an answer, again every [repeatEveryMs] while it stays
 * stuck, and how long it was gone once it answers.
 *
 * It works by posting a no-op to the UI thread ([post]) and timing how long that takes to run;
 * [tick] is called from a thread of its own, which is the one thing a stuck UI thread cannot stop.
 */
internal class UiStallWatchdog(
    private val clock: () -> Long,
    private val post: (Runnable) -> Unit,
    private val warn: (String) -> Unit,
    private val info: (String) -> Unit,
    private val stallAfterMs: Long = 2_000,
    private val repeatEveryMs: Long = 30_000,
) {
    private val lock = Any()

    /** When the heartbeat in flight was posted, or -1 when none is. */
    private var sentAt = -1L

    /** When this stall was last written down, or -1 when it has not been. */
    private var reportedAt = -1L

    /** The UI thread, as learned from the first heartbeat it ran. */
    @Volatile
    private var uiThread: Thread? = null

    fun tick() {
        val now = clock()
        val waited = synchronized(lock) {
            if (sentAt < 0) {
                sentAt = now
                post(Runnable { answered() })
                return
            }
            val waited = now - sentAt
            if (waited < stallAfterMs) return
            if (reportedAt >= 0 && now - reportedAt < repeatEveryMs) return
            reportedAt = now
            waited
        }
        warn("UI thread has not answered for $waited ms; every window is frozen until it does. It is at:\n${uiStack()}")
    }

    private fun answered() {
        uiThread = Thread.currentThread()
        val stalledFor = synchronized(lock) {
            val stalledFor = if (reportedAt >= 0) clock() - sentAt else null
            sentAt = -1
            reportedAt = -1
            stalledFor
        }
        stalledFor?.let { info("UI thread answered again after $it ms") }
    }

    private fun uiStack(): String {
        val ui = uiThread
            ?: Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith("AWT-EventQueue") }
            ?: return "\t(the UI thread could not be found)"
        return ui.stackTrace.take(STACK_FRAMES).joinToString("\n") { "\tat $it" }
    }

    companion object {
        private const val STACK_FRAMES = 40
        private const val TICK_MS = 500L

        /** Starts watching nop's UI thread, for as long as nop runs. */
        fun start() {
            val watchdog = UiStallWatchdog(
                clock = { System.nanoTime() / 1_000_000 },
                post = SwingUtilities::invokeLater,
                warn = Log::warn,
                info = Log::info,
            )
            thread(isDaemon = true, name = "ui-stall-watchdog") {
                while (true) {
                    Thread.sleep(TICK_MS)
                    watchdog.tick()
                }
            }
        }
    }
}
