package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentStatusTest {
    @Test
    fun `on a GPU the arc turns in a second focused and five without focus`() {
        assertEquals(100L, spinStepMs(focused = true, software = false))
        assertEquals(500L, spinStepMs(focused = false, software = false))
    }

    @Test
    fun `in software the arc steps at most once a second and stands still without focus`() {
        val focused = spinStepMs(focused = true, software = true)
        assertTrue(focused != null && focused >= 1_000L, "stepped every $focused ms")
        assertNull(spinStepMs(focused = false, software = true))
    }
}
