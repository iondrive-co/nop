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
}
