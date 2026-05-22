package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoubleShiftDetectorTest {

    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `two quick shift taps fire on the second release`() {
        val clock = FakeClock()
        val d = DoubleShiftDetector(windowMs = 400, clock = clock)
        assertFalse(d.onKeyDown(isShift = true))
        assertFalse(d.onKeyUp(isShift = true), "first release shouldn't fire")
        clock.advance(150)
        assertFalse(d.onKeyDown(isShift = true))
        assertTrue(d.onKeyUp(isShift = true), "second release within window should fire")
    }

    @Test
    fun `gap longer than the window does not fire`() {
        val clock = FakeClock()
        val d = DoubleShiftDetector(windowMs = 400, clock = clock)
        d.onKeyDown(isShift = true); d.onKeyUp(isShift = true)
        clock.advance(800)
        d.onKeyDown(isShift = true)
        assertFalse(d.onKeyUp(isShift = true))
    }

    @Test
    fun `shift used as a modifier does not count`() {
        val clock = FakeClock()
        val d = DoubleShiftDetector(windowMs = 400, clock = clock)
        // Shift+A
        d.onKeyDown(isShift = true)
        d.onKeyDown(isShift = false)
        d.onKeyUp(isShift = false)
        assertFalse(d.onKeyUp(isShift = true), "Shift+A release should not fire")
        // Now a clean single Shift tap — also should not fire (no fresh pair).
        clock.advance(100)
        d.onKeyDown(isShift = true)
        assertFalse(d.onKeyUp(isShift = true))
    }

    @Test
    fun `third tap does not immediately re-fire`() {
        val clock = FakeClock()
        val d = DoubleShiftDetector(windowMs = 400, clock = clock)
        d.onKeyDown(isShift = true); d.onKeyUp(isShift = true)
        clock.advance(150)
        d.onKeyDown(isShift = true); assertTrue(d.onKeyUp(isShift = true))
        clock.advance(150)
        d.onKeyDown(isShift = true)
        assertFalse(d.onKeyUp(isShift = true), "third tap needs to start a new pair, not re-fire")
    }

    @Test
    fun `auto-repeat key downs while shift held are tolerated`() {
        val clock = FakeClock()
        val d = DoubleShiftDetector(windowMs = 400, clock = clock)
        // Simulate AWT auto-repeat: many KeyDowns, one KeyUp.
        d.onKeyDown(isShift = true)
        d.onKeyDown(isShift = true)
        d.onKeyDown(isShift = true)
        d.onKeyUp(isShift = true)
        clock.advance(100)
        d.onKeyDown(isShift = true)
        d.onKeyDown(isShift = true)
        assertTrue(d.onKeyUp(isShift = true))
    }

    @Test
    fun `non-shift key between releases breaks the pairing`() {
        val clock = FakeClock()
        val d = DoubleShiftDetector(windowMs = 400, clock = clock)
        d.onKeyDown(isShift = true); d.onKeyUp(isShift = true)
        clock.advance(50)
        // user types something
        d.onKeyDown(isShift = false); d.onKeyUp(isShift = false)
        clock.advance(50)
        d.onKeyDown(isShift = true)
        assertFalse(d.onKeyUp(isShift = true))
    }
}
