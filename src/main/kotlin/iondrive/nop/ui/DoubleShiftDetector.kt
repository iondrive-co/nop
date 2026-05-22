package iondrive.nop.ui

/**
 * Tracks two isolated Shift taps in a row and reports when the second release lands inside the
 * configured time window. "Isolated" means no other keys were pressed while Shift was held —
 * Shift+A typing capital A must not count as a Shift press.
 *
 * Pure state machine: the call sites in [iondrive.nop.Main] map Compose's KeyDown/KeyUp events
 * onto [onKeyDown]/[onKeyUp]. Tests inject a clock to drive the timing deterministically.
 */
class DoubleShiftDetector(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var shiftDown = false
    private var consumedSinceShiftDown = false
    private var lastShiftReleaseMs = 0L

    fun onKeyDown(isShift: Boolean): Boolean {
        if (isShift) {
            // Filter auto-repeats: only the first KeyDown of a fresh press resets the "isolated"
            // flag. While shift stays down, the flag accumulates so any non-shift in between
            // cancels the streak.
            if (!shiftDown) {
                shiftDown = true
                consumedSinceShiftDown = false
            }
        } else {
            // Any non-shift KeyDown breaks isolation, both for the in-flight press and the
            // pairing with the previous release (so Shift, A, Shift never fires).
            consumedSinceShiftDown = true
            lastShiftReleaseMs = 0L
        }
        return false
    }

    fun onKeyUp(isShift: Boolean): Boolean {
        if (!isShift) return false
        shiftDown = false
        if (consumedSinceShiftDown) {
            lastShiftReleaseMs = 0L
            return false
        }
        val now = clock()
        val gap = now - lastShiftReleaseMs
        return if (lastShiftReleaseMs > 0L && gap in 1..windowMs) {
            // Consume the pair so the third tap doesn't immediately re-fire with the second.
            lastShiftReleaseMs = 0L
            true
        } else {
            lastShiftReleaseMs = now
            false
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 400L
    }
}
