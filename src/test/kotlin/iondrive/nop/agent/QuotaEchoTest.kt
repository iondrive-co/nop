package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The provider-independent half of deciding a quota wall: did the vendor say it, or was the agent
 * showing it?
 *
 * The bug this answers had no undo. A session working on nop's own quota code printed the phrases
 * those tests are built from, the watcher read them off the screen and killed the run mid-turn — and
 * because the text was then in the conversation, every resume replayed it and was killed again, until
 * the session could not be re-entered at all. The transcript is what tells the two apart without
 * asking the vendor anything.
 */
class QuotaEchoTest {

    private fun transcript(dir: Path, vararg lines: String): Path =
        dir.resolve("session.jsonl").also { Files.writeString(it, lines.joinToString("\n")) }

    /** The case that bit: a test fixture the agent wrote, read back off its own screen. */
    @Test
    fun `a phrase the agent wrote is found in the transcript`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"assistant","message":{"content":[{"type":"tool_use","input":""" +
                """{"file_path":"/p/QuotaTest.kt","content":"fire(\"You've hit your usage limit\")"}}]}}""",
        )

        assertTrue(QuotaEcho.isEchoed(file, "You've hit your usage limit"))
    }

    /** The case that must still fire: the vendor's own chrome is not conversation. */
    @Test
    fun `a phrase that is nowhere in the conversation is the vendor's own`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"user","message":{"content":"add a test for the stash path"}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
        )

        assertFalse(QuotaEcho.isEchoed(file, "You've hit your usage limit"))
    }

    /**
     * The screen's copy has been through a TUI — wrapped, padded to a column, or split by a colour
     * change that left a gap once the escapes were stripped. The transcript's copy is clean text.
     */
    @Test
    fun `wrapping and case differences between the screen and the transcript do not hide it`(@TempDir tmp: Path) {
        val file = transcript(tmp, """{"text":"You've hit your usage limit for Claude Opus"}""")

        assertTrue(QuotaEcho.isEchoed(file, "you've   hit\n  your usage    limit"))
    }

    /**
     * A provider nop has no tailer for, or a run whose transcript has not been located yet. Nothing
     * is known, so nothing is claimed — the caller decides as it did before this existed.
     */
    @Test
    fun `with no transcript nothing is claimed`() {
        assertFalse(QuotaEcho.isEchoed(null, "You've hit your usage limit"))
    }

    /** An empty phrase would be found in every transcript and would veto every wall. */
    @Test
    fun `an empty phrase matches nothing`(@TempDir tmp: Path) {
        val file = transcript(tmp, """{"text":"anything at all"}""")

        assertFalse(QuotaEcho.isEchoed(file, ""))
        assertFalse(QuotaEcho.isEchoed(file, "   "))
    }

    /** A transcript that has just been rotated, or was never written, must not take a session down. */
    @Test
    fun `a transcript that cannot be read is not an error`(@TempDir tmp: Path) {
        assertFalse(QuotaEcho.isEchoed(tmp.resolve("never-written.jsonl"), "You've hit your usage limit"))
    }

    /**
     * Long sessions run to megabytes, so only the tail is read — which is where both a resume's
     * replay and the agent's recent work are. Driven through a tiny cap rather than by writing a
     * real eight megabytes: the seek is the behaviour under test, not the file size.
     */
    @Test
    fun `only the tail of an over-long transcript is searched`(@TempDir tmp: Path) {
        val file = tmp.resolve("long.jsonl")
        Files.writeString(file, "You've hit your usage limit" + "-".repeat(400) + "near the end")

        assertTrue(
            QuotaEcho.isEchoed(file, "near the end", maxRead = 64),
            "the tail is what gets read",
        )
        assertFalse(
            QuotaEcho.isEchoed(file, "You've hit your usage limit", maxRead = 64),
            "and everything before the cap is out of reach, by design",
        )
        // The same file, read whole under the real cap, has both.
        assertTrue(QuotaEcho.isEchoed(file, "You've hit your usage limit"))
    }
}
