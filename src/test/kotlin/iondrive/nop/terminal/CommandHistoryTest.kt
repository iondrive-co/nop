package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CommandHistoryTest {
    @Test
    fun `restored histories wait for attachment and never compete with each other`() {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        CommandHistory {
            firstEntered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }.use { first ->
            CommandHistory { secondEntered.countDown() }.use { second ->
                try {
                    assertFalse(firstEntered.await(50, TimeUnit.MILLISECONDS), "registration must do no disk work")
                    first.start()
                    assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                    second.start()
                    assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS), "only one history may read at once")
                    release.countDown()
                    assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `closing a queued terminal cancels its old history`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unwanted = CountDownLatch(1)
        val drained = CountDownLatch(1)
        CommandHistory {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }.use { first ->
            try {
                first.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                CommandHistory { unwanted.countDown() }.use { it.start() }
                CommandHistory { drained.countDown() }.use { last ->
                    last.start()
                    release.countDown()
                    assertTrue(drained.await(5, TimeUnit.SECONDS))
                    assertTrue(unwanted.count == 1L)
                }
            } finally {
                release.countDown()
            }
        }
    }
}
