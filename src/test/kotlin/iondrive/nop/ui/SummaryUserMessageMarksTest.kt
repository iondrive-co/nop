package iondrive.nop.ui

import iondrive.nop.agent.ConversationSummary
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SummaryUserMessageMarksTest {
    private fun message(speaker: String, assistant: Boolean = false) =
        ConversationSummary.Message(speaker, "A message.", assistant, 0)

    @Test
    fun `only user prompts are marked and long replies shift later marks by their height`() {
        val messages = listOf(message("You"), message("Agent", true), message("From agent Review"), message("You"))
        val metrics = DiffScrollMetrics(messages.indices.map { it..it }, doubleArrayOf(100.0, 700.0, 100.0, 100.0))
        assertEquals(
            listOf(MarkerBar(0f, 6f), MarkerBar(177f, 6f)),
            summaryUserMessageMarks(messages, metrics, laneHeight = 200f, viewportHeight = 200.0, markerSize = 6f),
        )
    }

    @Test
    fun `the last prompt stays inside the scrollbar track`() {
        val messages = listOf(message("Agent", true), message("You"))
        val metrics = DiffScrollMetrics(messages.indices.map { it..it }, doubleArrayOf(999.0, 1.0))
        assertEquals(
            listOf(MarkerBar(94f, 6f)),
            summaryUserMessageMarks(messages, metrics, laneHeight = 100f, viewportHeight = 200.0, markerSize = 6f),
        )
    }

    @Test
    fun `a conversation that fits has no scrollbar marks`() {
        val messages = listOf(message("You"))
        val metrics = DiffScrollMetrics(listOf(0..0), doubleArrayOf(100.0))
        assertEquals(emptyList(), summaryUserMessageMarks(messages, metrics, 200f, 200.0, 6f))
        assertEquals(emptyList(), summaryUserMessageMarks(messages, metrics, 0f, 50.0, 6f))
    }
}
