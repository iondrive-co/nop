package iondrive.nop.terminal

import iondrive.nop.agent.Activity
import iondrive.nop.agent.ActivityTracker
import iondrive.nop.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities

class QuestionSubmissionTest {
    private fun question(): ActivityTracker = ActivityTracker().apply {
        onTitle("[ ] Idle | sample", at = 0)
        onEvent(AgentEvent.ToolStarted("survey", "request_user_input_async", emptyMap(), at = 0))
        onEvent(AgentEvent.ToolFinished("survey", "accepted", at = 0))
    }

    private fun terminal(dir: Path, tracker: ActivityTracker): TerminalSession =
        TerminalSession.shell(dir.toFile()).apply { onUserInput = { tracker.onUserInput() } }

    @Test
    fun `opening a survey with Alt Up keeps it asking through later tool completion`(@TempDir tmp: Path) {
        val tracker = question()
        val terminal = terminal(tmp, tracker)
        val event = KeyEvent(
            JPanel(), KeyEvent.KEY_PRESSED, 0, InputEvent.ALT_DOWN_MASK,
            KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED,
        )
        AltArrowKeys(terminal::sendText).keyPressed(event)
        tracker.onEvent(AgentEvent.ToolStarted("work", "Bash", emptyMap(), at = 1))
        tracker.onEvent(AgentEvent.ToolFinished("work", "done", at = 2))

        assertTrue(event.isConsumed)
        assertEquals(Activity.Asking, tracker.activity(now = 10_000))
        assertEquals(0L, terminal.lastSubmitAt)

        // The connector observes the Enter byte after the keyboard sends it.
        terminal.noteInput(byteArrayOf('\r'.code.toByte()))
        assertEquals(Activity.Idle, tracker.activity(now = 10_000))
        assertTrue(terminal.lastSubmitAt > 0)
    }

    @Test
    fun `draft typing paste and Shift Enter leave the question pending`(@TempDir tmp: Path) {
        val tracker = question()
        val terminal = terminal(tmp, tracker)
        terminal.sendText("an answer")
        ShiftEnterNewline(terminal::sendText).keyPressed(
            KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_ENTER, '\n'),
        )
        // These are the same input bytes the connector sees from a paste and a terminal report.
        terminal.noteInput("\u001b[200~first\r\nsecond\u001b[201~".toByteArray())
        terminal.noteInput("\u001b[12;40R".toByteArray())

        assertEquals(Activity.Asking, tracker.activity(now = 10_000))
        assertEquals(0L, terminal.lastSubmitAt)
        terminal.sendText("\r")
        assertEquals(Activity.Idle, tracker.activity(now = 10_000))
    }

    @Test
    fun `programmatic submission clears the question when its Enter is sent`(@TempDir tmp: Path) {
        val tracker = question()
        val terminal = terminal(tmp, tracker)
        val submitted = CountDownLatch(1)
        terminal.onUserInput = {
            tracker.onUserInput()
            submitted.countDown()
        }
        SwingUtilities.invokeAndWait {
            terminal.submit("first line\nsecond line")
            assertEquals(Activity.Asking, tracker.activity(now = 10_000))
            assertEquals(0L, terminal.lastSubmitAt)
        }

        assertTrue(submitted.await(5, TimeUnit.SECONDS), "the delayed Enter should submit the answer")
        assertEquals(Activity.Idle, tracker.activity(now = 10_000))
        assertTrue(terminal.lastSubmitAt > 0)
    }
}
