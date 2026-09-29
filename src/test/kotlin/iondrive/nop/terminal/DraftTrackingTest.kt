package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a prompt may be holding something the user has not sent, read off what goes to the
 * program. A message from another agent is submitted with Enter, which would send that along with it.
 */
class DraftTrackingTest {

    private fun after(before: Boolean, text: String) = TerminalSession.draftAfter(before, text.toByteArray())

    @Test
    fun `typing starts a draft and Enter sends it`() {
        assertTrue(after(false, "fix the"))
        assertFalse(after(true, "\r"))
        assertFalse(after(false, "whole line\r"), "a line and its Enter in one write leaves nothing behind")
        assertTrue(after(false, "first\rsecond"), "what comes after the last Enter is a new draft")
    }

    @Test
    fun `the terminal answering the program is not the user writing`() {
        for (report in listOf("\u001b[12;40R", "\u001b[?62;22c", "\u001b[?2004;1\$y", "\u001b[I", "\u001b[O",
            "\u001b]11;rgb:1e1e/1f1f/2222\u001b\\", "\u001b]10;rgb:ffff/ffff/ffff\u0007", "\u001b[<0;10;5M", "\u001b[?1u")) {
            assertTrue(TerminalSession.isTerminalReport(report.toByteArray()), report)
            assertFalse(after(false, report), "a report leaves an empty prompt empty: $report")
            assertTrue(after(true, report), "and a draft a draft: $report")
        }
    }

    @Test
    fun `keys that can fill a prompt count, arrows recalling history among them`() {
        assertFalse(TerminalSession.isTerminalReport("\u001b[A".toByteArray()))
        assertTrue(after(false, "\u001b[A"), "up recalls the last prompt into this one")
        assertTrue(after(false, "\u001bb"), "Alt+b is a key")
    }
}
