package iondrive.nop.agent

import iondrive.nop.agent.transcript.ClaudeTailer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TranscriptsTest {

    private fun touch(file: Path): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}\n")
        return file
    }

    @Test
    fun `a Claude conversation is found under its project, or under any other`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val project = tmp.resolve("work/shop")
        val here = touch(home.resolve("projects").resolve(ClaudeTailer.slug(project)).resolve("a.jsonl"))
        val there = touch(home.resolve("projects/-somewhere-else/b.jsonl"))

        assertEquals(here, Transcripts.find(Provider.Anthropic, home, project.toFile(), "a"))
        assertEquals(there, Transcripts.find(Provider.Anthropic, home, project.toFile(), "b"))
        assertNull(Transcripts.find(Provider.Anthropic, home, project.toFile(), "c"))
    }

    @Test
    fun `a Codex rollout is found by the id at the end of its name`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val rollout = touch(home.resolve(".codex/sessions/2026/09/30/rollout-2026-09-30T10-00-00-abc-123.jsonl"))
        assertEquals(rollout, Transcripts.find(Provider.OpenAI, home, tmp.toFile(), "abc-123"))
        assertNull(Transcripts.find(Provider.OpenAI, home, tmp.toFile(), "zzz"))
    }

    @Test
    fun `missing is only claimed for a provider whose transcripts nop can see`(@TempDir tmp: Path) {
        val claude = Account("claude-alpha", Provider.Anthropic, tmp.resolve("c").toString())
        val agy = Account("google-alpha", Provider.Antigravity, tmp.resolve("g").toString())
        assertTrue(Transcripts.missing(claude, tmp.toFile(), "gone"))
        assertFalse(Transcripts.missing(agy, tmp.toFile(), "anything"), "an Antigravity resume is left to the CLI")
    }

    private fun claudeTranscript(tmp: Path, id: String, vararg lines: String): Account {
        val home = tmp.resolve("home")
        val file = home.resolve("projects").resolve(ClaudeTailer.slug(tmp.resolve("work"))).resolve("$id.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return Account("claude-alpha", Provider.Anthropic, home.toString(), model = "claude-fable-9-0")
    }

    private fun answer(model: String, sidechain: Boolean = false) =
        """{"type":"assistant","isSidechain":$sidechain,"message":{"model":"$model","role":"assistant",""" +
            """"content":[{"type":"text","text":"done"}]}}"""

    private fun picked(name: String) =
        """{"type":"user","message":{"role":"user","content":"<local-command-stdout>Set model to `$name`""" +
            """ and saved as your default for new sessions</local-command-stdout>"}}"""

    @Test
    fun `a Claude conversation was last in the model of its last answer`(@TempDir tmp: Path) {
        val account = claudeTranscript(
            tmp, "a",
            answer("claude-opus-9-1"),
            answer("claude-sonnet-9-2"),
            // A usage wall or login prompt is the CLI talking, not a model.
            answer("<synthetic>"),
            answer("claude-haiku-9-3", sidechain = true),
        )
        assertEquals(ConversationModel("claude-sonnet-9-2"), Transcripts.lastModel(account, tmp.resolve("work").toFile(), "a"))
    }

    @Test
    fun `a model picked with slash model after the last answer is the one the conversation is in`(@TempDir tmp: Path) {
        val account = claudeTranscript(tmp, "a", answer("claude-fable-9-0"), picked("Opus 9.1 (default)"), answer("<synthetic>"))
        assertEquals(ConversationModel("claude-opus-9-1"), Transcripts.lastModel(account, tmp.resolve("work").toFile(), "a"))

        val answeredSince = claudeTranscript(tmp, "b", picked("Opus 9.1"), answer("claude-sonnet-9-2"))
        assertEquals(
            ConversationModel("claude-sonnet-9-2"),
            Transcripts.lastModel(answeredSince, tmp.resolve("work").toFile(), "b"),
        )
    }

    @Test
    fun `a slash model menu name maps to its id, or is left to the CLI`() {
        assertEquals("claude-opus-9-1", Transcripts.claudeModelId("Opus 9.1 (default)"))
        assertEquals("claude-sonnet-9", Transcripts.claudeModelId("Sonnet 9"))
        assertEquals(DEFAULT_CHOICE, Transcripts.claudeModelId("Opus 9.1 (1M context)"))
        assertEquals(DEFAULT_CHOICE, Transcripts.claudeModelId("Default (recommended)"))
    }

    @Test
    fun `a conversation with no answer yet says nothing about its model`(@TempDir tmp: Path) {
        val account = claudeTranscript(tmp, "a", """{"type":"user","message":{"role":"user","content":"hello"}}""")
        assertNull(Transcripts.lastModel(account, tmp.resolve("work").toFile(), "a"))
        assertNull(Transcripts.lastModel(account, tmp.resolve("work").toFile(), "absent"))
    }

    @Test
    fun `the model is found behind a tail longer than the first window`(@TempDir tmp: Path) {
        val filler = """{"type":"user","message":{"role":"user","content":"${"x".repeat(4000)}"}}"""
        val account = claudeTranscript(tmp, "a", answer("claude-opus-9-1"), *Array(200) { filler })
        assertEquals(ConversationModel("claude-opus-9-1"), Transcripts.lastModel(account, tmp.resolve("work").toFile(), "a"))
    }

    @Test
    fun `a Codex thread was last in the model and effort its settings or last turn name`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val rollout = home.resolve(".codex/sessions/2026/09/30/rollout-2026-09-30T10-00-00-abc-123.jsonl")
        Files.createDirectories(rollout.parent)
        val turn = """{"type":"turn_context","payload":{"model":"gpt-9","effort":"high"}}"""
        val changed = """{"type":"event_msg","payload":{"type":"thread_settings_applied",""" +
            """"thread_settings":{"model":"gpt-9-mini","reasoning_effort":"low"}}}"""
        val account = Account("codex-alpha", Provider.OpenAI, home.toString())

        Files.writeString(rollout, "$turn\n$changed\n")
        assertEquals(ConversationModel("gpt-9-mini", "low"), Transcripts.lastModel(account, tmp.toFile(), "abc-123"))

        Files.writeString(rollout, "$changed\n$turn\n")
        assertEquals(ConversationModel("gpt-9", "high"), Transcripts.lastModel(account, tmp.toFile(), "abc-123"))
    }
}
