package iondrive.nop.git

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkPacerTest {
    @Test
    fun `the first walk is always due`() {
        assertTrue(WalkPacer(nowMs = { 0L }).due())
    }

    @Test
    fun `a costly walk waits its multiple before the next`() {
        var now = 1_000L
        val pacer = WalkPacer(multiple = 4, nowMs = { now })
        pacer.walk { now += 1_300 } // a 1.3 s walk, like a 220k-file checkout
        now += 3_000
        assertFalse(pacer.due(), "3 s after a 1.3 s walk is inside the 5.2 s gap")
        now += 2_200
        assertTrue(pacer.due())
    }

    @Test
    fun `a cheap walk leaves the poll's cadence alone`() {
        var now = 1_000L
        val pacer = WalkPacer(nowMs = { now })
        pacer.walk { now += 20 }
        now += 3_000
        assertTrue(pacer.due())
    }

    @Test
    fun `a walk that throws is still timed`() {
        var now = 1_000L
        val pacer = WalkPacer(multiple = 4, nowMs = { now })
        runCatching { pacer.walk { now += 1_000; error("boom") } }
        now += 1_000
        assertFalse(pacer.due())
    }
}
