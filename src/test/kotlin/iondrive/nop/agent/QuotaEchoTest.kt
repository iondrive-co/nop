package iondrive.nop.agent

import iondrive.nop.agent.QuotaEcho.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

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

    /** When the run began, and the moment it is being judged: the times of the 19:48 wall. */
    private val since = Instant.parse("2026-09-18T08:14:28Z")
    private val now = Instant.parse("2026-09-18T09:48:59Z")

    private fun transcript(dir: Path, vararg lines: String): Path =
        dir.resolve("session.jsonl").also { Files.writeString(it, lines.joinToString("\n")) }

    private fun judge(file: Path?, phrase: String) = QuotaEcho.judge(file, phrase, since = since, now = now)

    /** The 429 Claude Code files against the refused request, at [at]. Real record text. */
    private fun apiError(at: Instant, text: String = "You've hit your session limit · resets 11pm") =
        """{"type":"assistant","isApiErrorMessage":true,"apiErrorStatus":429,"timestamp":"$at",""" +
            """"message":{"content":[{"type":"text","text":"$text"}]}}"""

    /** And the banner under it, which Claude Code files as one of its own notices. */
    private fun notice(at: Instant) =
        """{"type":"system","subtype":"informational","level":"notice","timestamp":"$at",""" +
            """"content":"Usage limit reached · continuing automatically at 11pm · esc or type to cancel"}"""

    /** The case that bit first: a test fixture the agent wrote, read back off its own screen. */
    @Test
    fun `a phrase the agent wrote is being shown`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"assistant","message":{"content":[{"type":"tool_use","input":""" +
                """{"file_path":"/p/QuotaTest.kt","content":"fire(\"You've hit your usage limit\")"}}]}}""",
        )

        assertEquals(Verdict.Shown, judge(file, "You've hit your usage limit"))
    }

    /**
     * The wall itself. It wears the assistant's role so the TUI can redraw it, but the model did not
     * say it — the 429 did, and this is the vendor refusing. The record lands within milliseconds of
     * the words on screen: 72ms before nop matched them, at 19:48.
     */
    @Test
    fun `a refusal the CLI filed moments ago is the vendor's`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"user","message":{"content":"carry on with the gate"}}""",
            apiError(now.minusMillis(72)),
        )

        assertEquals(Verdict.Refused, judge(file, "You've hit your session limit"))
    }

    @Test
    fun `so is the notice it files under the refusal`(@TempDir tmp: Path) {
        val file = transcript(tmp, notice(now.minusMillis(70)))

        assertEquals(Verdict.Refused, judge(file, "Usage limit reached"))
    }

    private fun codexTaskComplete(
        at: Instant,
        message: String = "You've hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), " +
            "visit https://chatgpt.com/codex/settings/usage to purchase more credits or try again at 4:14 PM.",
    ) = """{"timestamp":"$at","type":"event_msg","payload":{"type":"task_complete","error":{"message":"$message","codex_error_info":"usage_limit_exceeded"}}}"""

    @Test
    fun `a Codex task_complete error filed moments ago is a vendor refusal`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"event_msg","timestamp":"${since.plusSeconds(5)}","payload":{"type":"agent_message","message":"working on task"}}""",
            codexTaskComplete(now.minusMillis(50)),
        )

        assertEquals(Verdict.Refused, judge(file, "You've hit your usage limit"))
    }

    /**
     * The 19:48 wall, and the reason the second version of this guard still never handed a thing
     * over. The session was itself a handover: its first act was to read a handoff whose last line
     * was the previous account's wall, word for word. With the phrase in the conversation from then
     * on, its own wall was vetoed as an echo of that one — nine times over, as the user kept typing
     * into a dead session. The refusal the CLI filed is what settles it.
     */
    @Test
    fun `a fresh refusal outweighs the same words quoted in the conversation`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"user","timestamp":"${since.plusSeconds(4)}","message":{"content":""" +
                """[{"type":"tool_result","tool_use_id":"t1","content":"## Remaining Work\n""" +
                """The previous agent's last message was:\n\nYou've hit your session limit · """ +
                """resets 8:10pm (Australia/Melbourne)"}]}}""",
            apiError(now.minusMillis(72)),
            notice(now.minusMillis(70)),
        )

        assertEquals(Verdict.Refused, judge(file, "You've hit your session limit"))
        assertEquals(Verdict.Refused, judge(file, "Usage limit reached"))
    }

    /** The agent's own reply, at [at]. */
    private fun reply(at: Instant, text: String) =
        """{"type":"assistant","timestamp":"$at","message":{"content":[{"type":"text","text":"$text"}]}}"""

    /**
     * The 18:59 handover in hermes. The agent had just answered a question about an exchange
     * refusing its requests, and finished its turn; the screen showed its answer, and nop took the
     * answer for the vendor. A refused request writes no reply, so a fresh one is the agent talking.
     */
    @Test
    fun `a phrase in the agent's own reply from moments ago is said by the agent`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"user","timestamp":"${now.minusSeconds(44)}","message":{"content":"any rate limits?"}}""",
            reply(now.minusSeconds(12), """02:10Z: Hyperliquid refused a request (a 429 \"too many requests\" reply)."""),
        )

        assertEquals(Verdict.Said, judge(file, "too many requests"))
    }

    @Test
    fun `so is one in a Codex agent message`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"timestamp":"${now.minusSeconds(5)}","type":"event_msg","payload":""" +
                """{"type":"agent_message","message":"The API answered 429 Too Many Requests twice."}}""",
        )

        assertEquals(Verdict.Said, judge(file, "Too Many Requests"))
    }

    /** A vendor refusal filed now still outranks whatever the agent said a moment before it. */
    @Test
    fun `a fresh refusal outranks the agent having just said the same words`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            reply(now.minusSeconds(30), "Next I'll check whether you've hit your session limit."),
            apiError(now.minusMillis(72)),
        )

        assertEquals(Verdict.Refused, judge(file, "You've hit your session limit"))
    }

    /**
     * What the agent said long ago, or in a run before this one, is conversation like any other: a
     * redraw can put it on screen at the moment of a real wall.
     */
    @Test
    fun `an old reply is being shown, not said`(@TempDir tmp: Path) {
        val stale = transcript(tmp, reply(now.minus(QuotaEcho.FRESH).minusSeconds(1), "429 too many requests"))
        assertEquals(Verdict.Shown, judge(stale, "too many requests"))

        val earlierRun = transcript(tmp, reply(since.minusSeconds(1), "429 too many requests"))
        assertEquals(Verdict.Shown, QuotaEcho.judge(earlierRun, "too many requests", since = since, now = since))
    }

    /** The refusal wears the assistant's role, but it is not the agent's reply. */
    @Test
    fun `a refusal from before this run is not the agent's reply either`(@TempDir tmp: Path) {
        val file = transcript(tmp, apiError(now.minusSeconds(5)).replace(""""isApiErrorMessage":true,""", ""))
        assertEquals(Verdict.Said, judge(file, "You've hit your session limit"), "control: unflagged it is a reply")

        val flagged = transcript(tmp, apiError(since.minusSeconds(60)))
        assertEquals(Verdict.Shown, judge(flagged, "You've hit your session limit"))
    }

    /**
     * A resume redraws the conversation, the wall that ended the last run among it. That record was
     * the vendor speaking then; now it is text on a screen, and the account may well have reset.
     */
    @Test
    fun `a refusal from before this run is being shown, not said`(@TempDir tmp: Path) {
        val file = transcript(tmp, apiError(since.minusSeconds(3600)), notice(since.minusSeconds(3600)))

        assertEquals(Verdict.Shown, judge(file, "You've hit your session limit"))
        assertEquals(Verdict.Shown, judge(file, "Usage limit reached"))
    }

    /**
     * Claude Code waits out a reset in place and carries on in the same run, so a wall it has
     * already weathered is still in this run's transcript hours later. It vouches for nothing now.
     */
    @Test
    fun `a refusal this run weathered long ago is being shown, not said`(@TempDir tmp: Path) {
        val file = transcript(tmp, apiError(now.minus(QuotaEcho.FRESH).minusSeconds(1)))

        assertEquals(Verdict.Shown, judge(file, "You've hit your session limit"))
    }

    /** One that cannot be placed in time cannot be told from one a resume is redrawing. */
    @Test
    fun `a refusal with no timestamp is not taken as fresh`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"assistant","isApiErrorMessage":true,"message":""" +
                """{"content":[{"type":"text","text":"You've hit your session limit · resets 11pm"}]}}""",
        )

        assertEquals(Verdict.Shown, judge(file, "You've hit your session limit"))
    }

    /**
     * The CLI files plenty about itself that is not a wall. An overloaded API is transient and
     * retried; it is no confirmation of a limit phrase the screen happened to show at the same time.
     */
    @Test
    fun `the CLI's own records only confirm a wall when they carry one`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            apiError(now.minusSeconds(1), text = "API Error: Repeated 529 Overloaded errors · overloaded_error"),
            """{"type":"system","subtype":"turn_duration","timestamp":"${now.minusSeconds(1)}","durationMs":5000}""",
        )

        assertEquals(Verdict.Unrecorded, judge(file, "You've hit your usage limit"))
    }

    /**
     * The same sentence in a tool result — the agent reading nop's own log back — with no refusal
     * this run. Believing the vendor's records must not cost the guard the case it exists for.
     */
    @Test
    fun `the same sentence in a tool result is still the agent showing it`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1",""" +
                """"content":"nop.log: agent quota wall — Usage limit reached · resets 8:10pm"}]}}""",
        )

        assertEquals(Verdict.Shown, judge(file, "Usage limit reached"))
    }

    /**
     * Nor may the agent forge one. A tool result quoting a refusal record — the agent grepping a
     * transcript, as this very fix was built by doing — is conversation, whatever it contains.
     */
    @Test
    fun `a refusal record quoted inside a tool result is not the CLI's own`(@TempDir tmp: Path) {
        val quoted = apiError(now.minusSeconds(1)).replace("\"", "\\\"")
        val file = transcript(
            tmp,
            """{"type":"user","timestamp":"${now.minusSeconds(1)}","message":{"content":""" +
                """[{"type":"tool_result","tool_use_id":"t1","content":"$quoted"}]}}""",
        )

        assertEquals(Verdict.Shown, judge(file, "You've hit your session limit"))
    }

    /**
     * A record is a line, and only its own line. Flattening the whole file let a needle be assembled
     * out of two unrelated records, which is a veto nobody's output earned.
     */
    @Test
    fun `a phrase is not assembled from two separate records`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"assistant","message":{"content":[{"type":"text","text":"You've hit your"}]}}""",
            """{"type":"user","message":{"content":"usage limit of four tabs per project"}}""",
        )

        assertEquals(Verdict.Unrecorded, judge(file, "You've hit your usage limit"))
    }

    /** The case that must still fire when a provider files nothing nop recognises. */
    @Test
    fun `a phrase that is nowhere in the transcript is unrecorded`(@TempDir tmp: Path) {
        val file = transcript(
            tmp,
            """{"type":"user","message":{"content":"add a test for the stash path"}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
        )

        assertEquals(Verdict.Unrecorded, judge(file, "You've hit your usage limit"))
    }

    /**
     * The screen's copy has been through a TUI — wrapped, padded to a column, or split by a colour
     * change that left a gap once the escapes were stripped. The transcript's copy is clean text.
     */
    @Test
    fun `wrapping and case differences between the screen and the transcript do not hide it`(@TempDir tmp: Path) {
        val file = transcript(tmp, """{"text":"You've hit your usage limit for Claude Opus"}""")

        assertEquals(Verdict.Shown, judge(file, "you've   hit\n  your usage    limit"))
    }

    /**
     * A provider nop has no tailer for, or a run whose transcript has not been located yet. Nothing
     * is known, so nothing is claimed — the caller decides as it did before this existed.
     */
    @Test
    fun `with no transcript nothing is claimed`() {
        assertEquals(Verdict.Unrecorded, judge(null, "You've hit your usage limit"))
    }

    /** An empty phrase would be found in every transcript and would veto every wall. */
    @Test
    fun `an empty phrase matches nothing`(@TempDir tmp: Path) {
        val file = transcript(tmp, """{"text":"anything at all"}""")

        assertEquals(Verdict.Unrecorded, judge(file, ""))
        assertEquals(Verdict.Unrecorded, judge(file, "   "))
    }

    /** A transcript that has just been rotated, or was never written, must not take a session down. */
    @Test
    fun `a transcript that cannot be read is not an error`(@TempDir tmp: Path) {
        assertEquals(Verdict.Unrecorded, judge(tmp.resolve("never-written.jsonl"), "You've hit your usage limit"))
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

        fun judge(phrase: String, maxRead: Long = QuotaEcho.MAX_READ) =
            QuotaEcho.judge(file, phrase, since = since, now = now, maxRead = maxRead)

        assertEquals(Verdict.Shown, judge("near the end", maxRead = 64), "the tail is what gets read")
        assertEquals(
            Verdict.Unrecorded,
            judge("You've hit your usage limit", maxRead = 64),
            "and everything before the cap is out of reach, by design",
        )
        // The same file, read whole under the real cap, has both.
        assertEquals(Verdict.Shown, judge("You've hit your usage limit"))
    }
}
