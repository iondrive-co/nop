package iondrive.nop.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationSummaryTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `only participant prose survives in its original order across handovers`() {
        val events = sequenceOf(
            AgentEvent.RunStarted(provider = "anthropic", account = "claude-alpha", at = 1),
            AgentEvent.UserMessage("Please check the parser.", 2),
            AgentEvent.AssistantMessage(listOf(Block.Thinking("Internal reasoning"), Block.Text("I will check.")), at = 3),
            AgentEvent.ToolStarted("a", "Bash", mapOf("command" to "run-check"), 4),
            AgentEvent.ToolFinished("a", "Tool output", at = 5),
            AgentEvent.ScreenTail("Terminal decoration", 6),
            AgentEvent.AssistantMessage(listOf(Block.ToolCall("b", "Read", emptyMap())), at = 7),
            AgentEvent.RunStarted(provider = "openai", account = "codex-bravo", at = 8),
            AgentEvent.AssistantMessage(listOf(Block.Text("The parser works.")), at = 9),
        )
        val summary = ConversationSummary.fromEvents(events, 10)
        assertEquals(listOf("You", "Agent · claude-alpha", "Agent · codex-bravo"), summary.messages.map { it.speaker })
        assertEquals(listOf("Please check the parser.", "I will check.", "The parser works."), summary.messages.map { it.text })
        assertEquals(10L, summary.capturedAt)
        assertFalse(summary.plainText().contains("Internal reasoning"))
        assertFalse(summary.plainText().contains("Tool output"))
    }

    @Test
    fun `incoming agent and service messages keep their sender rather than being called user prompts`() {
        val summary = ConversationSummary.fromEvents(sequenceOf(
            AgentEvent.UserMessage(
                "[Message via nop from agent \"Review\" (agent-2, openai/codex-bravo, in sample), not the user. Reply: nop-msg send agent-2 \"...\"]\n\nPlease inspect the new branch.", 1,
            ),
            AgentEvent.UserMessage("[Message via nop from service \"Checks\", not the user; no reply.]\n\nChecks passed.", 2),
            AgentEvent.UserMessage("I agree.", 3),
        ))
        assertTrue(summary.messages[0].speaker.startsWith("From agent \"Review\""))
        assertEquals("Please inspect the new branch.", summary.messages[0].text)
        assertEquals("From service \"Checks\"", summary.messages[1].speaker)
        assertEquals("Checks passed.", summary.messages[1].text)
        assertEquals("You", summary.messages[2].speaker)
    }

    @Test
    fun `repeated prompts remain real separate turns`() {
        val summary = ConversationSummary.fromEvents(sequenceOf(
            AgentEvent.UserMessage("Continue", 1),
            AgentEvent.AssistantMessage(listOf(Block.Text("Checking.")), at = 2),
            AgentEvent.UserMessage("Continue", 3),
        ))
        assertEquals(listOf("Continue", "Checking.", "Continue"), summary.messages.map { it.text })
    }

    @Test
    fun `injected context and handover instructions are omitted while ordinary prompts remain exact`() {
        val summary = ConversationSummary.fromEvents(sequenceOf(
            AgentEvent.UserMessage("<environment_context>\n<cwd>/sample</cwd>\n</environment_context>", 1),
            AgentEvent.UserMessage("# AGENTS.md instructions for /sample\n\n<INSTRUCTIONS>Check the style.</INSTRUCTIONS>", 2),
            AgentEvent.RunStarted(provider = "openai", account = "codex-bravo", seededFromHandoff = true, at = 3),
            AgentEvent.UserMessage(Handoff.SEED_START + "another provider. Read the saved handoff.", 4),
            AgentEvent.UserMessage("  Please explain the <environment_context> tag.\n", 5),
        ))
        assertEquals(listOf("  Please explain the <environment_context> tag.\n"), summary.messages.map { it.text })
    }

    @Test
    fun `a reading copy stays fixed while the log grows and refresh sees new messages`() {
        val file = dir.resolve("session.jsonl")
        fun append(event: AgentEvent) {
            Files.writeString(file, Json.encodeToString(AgentEvent.serializer(), event) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        append(AgentEvent.UserMessage("Check the branch.", 1))
        val first = ConversationSummary.read(file)
        append(AgentEvent.AssistantMessage(listOf(Block.Text("All checks passed.")), at = 2))
        Files.writeString(file, "not json\n{\"type\":\"user_message\",", StandardOpenOption.APPEND)
        assertEquals(1, first.messages.size)
        assertEquals(listOf("Check the branch.", "All checks passed."), ConversationSummary.read(file).messages.map { it.text })
        assertEquals("You:\nCheck the branch.", first.plainText())
    }

    @Test
    fun `each tab keeps its snapshot until refresh or reopening summary`() {
        val first = ConversationSummaryState()
        val second = ConversationSummaryState()
        first.toggle()
        first.snapshot = ConversationSummary(emptyList(), 1)
        assertFalse(second.shown)
        assertTrue(first.shown)
        first.refresh()
        assertEquals(null, first.snapshot)
        first.snapshot = ConversationSummary(emptyList(), 2)
        first.toggle()
        first.toggle()
        assertEquals(null, first.snapshot)
    }
}
