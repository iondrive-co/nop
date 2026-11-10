package iondrive.nop.index

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexBuildsTest {
    private val alpha = Path.of("/home/dev/alpha")
    private val bravo = Path.of("/home/dev/bravo")
    private val charlie = Path.of("/home/dev/charlie")

    @Test
    fun `refreshes of different projects take turns`() = runBlocking {
        val builds = IndexBuilds()
        val running = AtomicInteger()
        val most = AtomicInteger()
        withTimeout(10_000) {
            listOf(alpha, bravo, charlie).map { root ->
                launch(Dispatchers.Default) {
                    builds.inTurn(root) {
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(50)
                        running.decrementAndGet()
                    }
                }
            }.joinAll()
        }
        assertEquals(1, most.get())
    }

    @Test
    fun `a project's next refresh waits its multiple of the last one's cost`() = runBlocking {
        val builds = IndexBuilds()
        var firstEnd = 0L
        builds.inTurn(alpha) {
            delay(50)
            firstEnd = System.currentTimeMillis()
        }
        var secondStart = 0L
        withTimeout(10_000) { builds.inTurn(alpha) { secondStart = System.currentTimeMillis() } }
        val gap = secondStart - firstEnd
        assertTrue(gap >= 190, "the second refresh started ${gap} ms after a 50 ms one")
    }

    @Test
    fun `one project's pacing does not hold back another's`() = runBlocking {
        val builds = IndexBuilds()
        builds.inTurn(alpha) { delay(100) }
        val asked = System.currentTimeMillis()
        var started = 0L
        builds.inTurn(bravo) { started = System.currentTimeMillis() }
        assertTrue(started - asked < 300, "bravo waited ${started - asked} ms behind alpha's pacing")
    }

    @Test
    fun `a refresh waiting its turn can be cancelled`() = runBlocking {
        val builds = IndexBuilds()
        builds.inTurn(alpha) { delay(200) }
        var ran = false
        val waiting = launch { builds.inTurn(alpha) { ran = true } }
        delay(50)
        withTimeout(2_000) {
            waiting.cancel()
            waiting.join()
        }
        assertFalse(ran)
    }
}
