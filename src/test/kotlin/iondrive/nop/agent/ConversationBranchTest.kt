package iondrive.nop.agent

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConversationBranchTest {
    @TempDir lateinit var root: Path
    private val conversation = "4deb9c2a-02cf-4aa7-8669-28cb86e8f890"
    private fun account(provider: Provider) = Account("agent-larch", provider, root.resolve("home").toString())
    private fun claude(vararg entries: String): Pair<Account, Path> {
        val account = account(Provider.Anthropic)
        val file = account.homePath.resolve("projects").resolve(iondrive.nop.agent.transcript.ClaudeTailer.slug(root))
            .resolve("$conversation.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, entries.joinToString("\n", postfix = "\n"))
        return account to file
    }
    private fun user(id: String, parent: String?, body: String): String = buildJsonObject {
        put("type", "user"); put("uuid", id); put("parentUuid", parent); put("sessionId", conversation)
        put("message", buildJsonObject { put("role", "user"); put("content", body) })
    }.toString()
    private fun reply(id: String, parent: String, body: String, stop: String = "end_turn") = buildJsonObject {
        put("type", "assistant"); put("uuid", id); put("parentUuid", parent); put("sessionId", conversation)
        put("message", buildJsonObject {
            put("role", "assistant"); put("stop_reason", stop)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", body) }) })
        })
    }.toString()

    @Test fun `Claude fork retains native history and excludes the shelved continuation`() {
        val (account, file) = claude(user("prompt-a", null, "Explain the orchard design."),
            reply("tool-a", "prompt-a", "Checking the orchard records.", "tool_use"),
            """{"type":"user","uuid":"result-a","parentUuid":"tool-a","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"call-a","content":"Full original result"}]}}""",
            reply("answer-a", "result-a", "The orchard uses a rotating harvest schedule."),
            user("prompt-b", "answer-a", "Discuss an unrelated greenhouse."),
            reply("answer-b", "prompt-b", "The greenhouse is the shelved continuation."))
        val original = Files.readString(file)
        val points = ConversationBranch.points(account, root, conversation)
        assertEquals(listOf(null, "answer-a", "answer-b"), points.map { it.boundary })
        val point = points[1]
        val id = ConversationBranch.create(point)!!
        val copy = Files.readString(file.resolveSibling("$id.jsonl"))
        assertTrue(copy.contains("Full original result"))
        assertTrue(copy.contains("rotating harvest schedule"))
        assertFalse(copy.contains("greenhouse"))
        assertFalse(copy.contains(conversation))
        assertTrue(iondrive.nop.agent.transcript.LiveTranscripts.isForeign(id, "another-tab"))
        assertEquals(original, Files.readString(file))
        assertTrue(point.preview.endsWith("The orchard uses a rotating harvest schedule."))
        assertFalse(point.preview.contains("greenhouse"))
    }

    @Test fun `Claude boundaries follow the surviving parent chain after rewind`() {
        val (account, _) = claude(user("prompt-a", null, "Consider the orchard layout."),
            reply("answer-a", "prompt-a", "Retain the eastern orchard."),
            user("discarded-prompt", "answer-a", "Drop the southern path."),
            reply("discarded-answer", "discarded-prompt", "A discarded answer."),
            user("prompt-b", "answer-a", "Keep the southern path."),
            reply("answer-b", "prompt-b", "The southern path remains open."))
        assertEquals(listOf(null, "answer-a", "answer-b"), ConversationBranch.points(account, root, conversation).map { it.boundary })
    }

    @Test fun `a streaming answer and outstanding tool call are not branch boundaries`() {
        val (account, _) = claude(user("prompt-a", null, "Describe the irrigation."),
            reply("partial", "prompt-a", "I am still checking the irrigation.", "tool_use"))
        assertEquals(listOf<String?>(null), ConversationBranch.points(account, root, conversation).map { it.boundary })
    }

    @Test fun `Claude compaction preserves earlier branch boundaries through its logical parent`() {
        val (account, _) = claude(user("prompt-a", null, "Consider the orchard layout."),
            reply("answer-a", "prompt-a", "Retain the eastern orchard."),
            """{"type":"system","uuid":"compact-a","parentUuid":null,"logicalParentUuid":"answer-a","subtype":"compact_boundary"}""",
            user("prompt-b", "compact-a", "Consider the orchard gates."),
            reply("answer-b", "prompt-b", "Retain the northern gate."))
        assertEquals(listOf(null, "answer-a", "answer-b"), ConversationBranch.points(account, root, conversation).map { it.boundary })
    }

    @Test fun `unparented system bookkeeping does not hide the conversation`() {
        val (account, _) = claude(user("prompt-a", null, "Consider the orchard layout."),
            reply("answer-a", "prompt-a", "Retain the eastern orchard."),
            """{"type":"system","uuid":"bookkeeping-a","subtype":"status"}""")
        assertEquals(listOf(null, "answer-a"), ConversationBranch.points(account, root, conversation).map { it.boundary })
    }

    @Test fun `missing boundary fails without publishing a partial Claude fork`() {
        val (account, file) = claude(user("prompt-a", null, "Describe the irrigation."), reply("answer-a", "prompt-a", "Irrigation is timed."))
        val point = ConversationBranch.points(account, root, conversation).last().copy(boundary = "missing")
        assertThrows(IllegalStateException::class.java) { ConversationBranch.create(point) }
        assertEquals(listOf(file), Files.list(file.parent).use { it.toList() })
    }

    @Test fun `Codex boundaries include only completed turns and ignore environment messages`() {
        val account = account(Provider.OpenAI)
        val file = account.homePath.resolve(".codex/sessions/2025/04/03/rollout-test-$conversation.jsonl")
        Files.createDirectories(file.parent)
        fun event(type: String, payload: JsonObject) = buildJsonObject { put("type", type); put("payload", payload) }.toString()
        fun message(role: String, body: String) = event("response_item", buildJsonObject {
            put("type", "message"); put("role", role)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", body) }) })
        })
        Files.writeString(file, listOf(
            message("user", "<environment_context>settings</environment_context>"),
            event("turn_context", buildJsonObject { put("turn_id", "turn-a") }),
            message("user", "Explain the rainwater collection."), message("assistant", "Rainwater feeds the orchard tanks."),
            event("event_msg", buildJsonObject { put("type", "task_complete"); put("turn_id", "turn-a"); put("error", JsonNull) }),
            event("turn_context", buildJsonObject { put("turn_id", "turn-b") }),
            message("user", "Now inspect the storage shed."), message("assistant", "Still inspecting the storage shed."),
        ).joinToString("\n"))
        val points = ConversationBranch.points(account, root, conversation)
        assertEquals(listOf(null, "turn-a"), points.map { it.boundary })
        assertFalse(points.last().preview.contains("storage shed"))
        assertEquals("Now inspect the storage shed.", points.last().following)
    }

    private fun point(id: String, preceding: String, following: String = "") =
        ConversationBranchPoint(account(Provider.Anthropic), conversation, root.resolve("source.jsonl"), id, preceding, preceding, following)

    @Test fun `nearest boundary uses screen rows instead of character counts`() {
        val early = point("early", "The earlier completed reply.")
        val late = point("late", "The later completed reply.")
        val lines = listOf(early.preceding, "x".repeat(400), "", "", "", late.preceding)
        assertEquals(early, ConversationBranch.nearest(listOf(early, late), ConversationClick(lines, 2)))
    }

    @Test fun `wrapped Markdown and Unicode identify the same boundary`() {
        val selected = point("selected", "The **桜 orchard** is ready for the spring harvest.")
        assertEquals(selected, ConversationBranch.nearest(listOf(selected), ConversationClick(
            listOf("• The 桜 orchard is ready", "  for the spring harvest.", ""), 1)))
    }

    @Test fun `short replies can match a whole terminal row without matching parts of words`() {
        val short = point("short", "Yes")
        assertEquals(short, ConversationBranch.nearest(listOf(short), ConversationClick(listOf("• Yes"), 0)))
        assertThrows(IllegalStateException::class.java) {
            ConversationBranch.nearest(listOf(short), ConversationClick(listOf("Yesterday"), 0))
        }
    }

    @Test fun `repeated replies map to their native turn in conversation order`() {
        val one = point("one", "The same completed answer.")
        val two = point("two", "The same completed answer.")
        val lines = listOf(one.preceding, "A different question.", two.preceding)
        assertEquals(one, ConversationBranch.nearest(listOf(one, two), ConversationClick(lines, 0)))
        assertEquals(two, ConversationBranch.nearest(listOf(one, two), ConversationClick(lines, 2)))
        assertEquals(two, ConversationBranch.nearest(listOf(one, two), ConversationClick(listOf(one.preceding), 0)))
    }

    @Test fun `thinking blocks in one completed reply expose only its last native entry`() {
        val (account, _) = claude(user("prompt-a", null, "Consider the orchard layout."),
            reply("answer-a", "prompt-a", "Retain the eastern orchard."),
            """{"type":"assistant","uuid":"thinking-a","parentUuid":"answer-a","message":{"role":"assistant","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"A private calculation"}]}}""")
        assertEquals(listOf(null, "thinking-a"), ConversationBranch.points(account, root, conversation).map { it.boundary })
    }

    @Test fun `a reply completed after the menu opened cannot steal a repeated passage`() {
        val one = point("one", "The same completed answer.").copy(at = 10)
        val future = point("future", one.preceding).copy(at = 30)
        assertEquals(one, ConversationBranch.nearest(listOf(one, future), ConversationClick(listOf(one.preceding), 0, capturedAt = 20)))
    }

    @Test fun `the summary message timestamp identifies an earlier repeated answer`() {
        val first = point("first", "The same completed answer.").copy(at = 10)
        val second = point("second", first.preceding).copy(at = 30)
        assertEquals(first, ConversationBranch.nearest(listOf(first, second),
            ConversationClick(listOf(first.preceding), 0, capturedAt = 40, messageAt = 10)))
    }

    @Test fun `a long history of identical replies aligns the visible suffix without a match cross product`() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5)) {
            val points = (0 until 5000).map { point("reply-$it", "The same completed answer.") }
            val lines = List(500) { "The same completed answer." }
            assertEquals(points[4500], ConversationBranch.nearest(points, ConversationClick(lines, 0)))
            assertEquals(points.last(), ConversationBranch.nearest(points, ConversationClick(lines, lines.lastIndex)))
        }
    }

    @Test fun `unrecognised screen content cannot silently branch at the latest turn`() {
        assertThrows(IllegalStateException::class.java) {
            ConversationBranch.nearest(listOf(point("one", "An original answer.")), ConversationClick(listOf("Unrelated output"), 0))
        }
    }
}
