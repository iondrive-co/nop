package iondrive.nop.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiStallWatchdogTest {
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }
    private val now = AtomicLong(0)
    private val warnings = CopyOnWriteArrayList<String>()
    private val infos = CopyOnWriteArrayList<String>()
    private val watchdog = UiStallWatchdog(
        clock = now::get,
        post = ui::execute,
        warn = warnings::add,
        info = infos::add,
    )

    @AfterEach fun stop() {
        ui.shutdownNow()
    }

    /** Waits for everything already posted to the UI thread to have run. */
    private fun settle() {
        ui.submit {}.get(5, TimeUnit.SECONDS)
    }

    // Named, so that it is a frame the report can be checked for.
    private fun stuckOnTheDisk(release: CountDownLatch) {
        release.await()
    }

    @Test fun `a stuck UI thread is reported with where it is stuck, and again once it answers`() {
        watchdog.tick()
        settle()

        val release = CountDownLatch(1)
        val stuck = CountDownLatch(1)
        var uiThread: Thread? = null
        ui.execute {
            uiThread = Thread.currentThread()
            stuck.countDown()
            stuckOnTheDisk(release)
        }
        stuck.await()
        // Past the signal and parked in stuckOnTheDisk, so that is where its stack is read.
        while (uiThread!!.state != Thread.State.WAITING) Thread.onSpinWait()

        watchdog.tick()
        now.set(1_999)
        watchdog.tick()
        assertTrue(warnings.isEmpty(), "reported before the UI thread had been gone long enough")

        now.set(2_000)
        watchdog.tick()
        assertEquals(1, warnings.size)
        assertContains(warnings.single(), "has not answered for 2000 ms")
        assertContains(warnings.single(), "stuckOnTheDisk")

        now.set(10_000)
        watchdog.tick()
        assertEquals(1, warnings.size, "reported the same stall again too soon")
        now.set(32_000)
        watchdog.tick()
        assertEquals(2, warnings.size)

        release.countDown()
        settle()
        assertEquals(listOf("UI thread answered again after 32000 ms"), infos)
    }

    @Test fun `a UI thread that answers in time is never reported`() {
        repeat(10) {
            watchdog.tick()
            settle()
            now.addAndGet(1_500)
            watchdog.tick()
        }
        assertTrue(warnings.isEmpty())
        assertTrue(infos.isEmpty())
    }
}
