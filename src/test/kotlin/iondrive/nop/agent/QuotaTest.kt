package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The quota watcher: what it fires on, and — more importantly — what it does not.
 *
 * A miss costs a convenience: the run ends at the vendor's own error and the user starts another
 * themselves. A false positive kills a session that was working, mid-turn, and there is no undo for
 * that. The asymmetry is why the overload patterns are checked first and why every quota pattern is
 * a specific phrase rather than a keyword.
 */
class QuotaTest {

    private fun watch(): Pair<QuotaWatcher, MutableList<QuotaHit>> {
        val hits = mutableListOf<QuotaHit>()
        return QuotaWatcher { hits += it } to hits
    }

    private fun fire(text: String): QuotaHit? {
        val (watcher, hits) = watch()
        watcher.feed(text)
        return hits.firstOrNull()
    }

    @Test
    fun `Claude Code's own limit wording is caught`() {
        // The case the API-shaped patterns miss entirely: the TUI never shows the API error, only
        // this sentence.
        assertNotNull(fire("You've hit your usage limit. Your limit will reset at 3pm.\n"))
        assertNotNull(fire("You have reached your limit for Claude Opus.\n"))
        // The 429's own text, which Claude Code prints and files against the refused request.
        assertNotNull(fire("You've hit your session limit · resets 8:10pm (Australia/Melbourne)\n"))
        assertNotNull(fire("5-hour limit reached\n"))
        assertNotNull(fire("Weekly limit reached · resets Sunday\n"))
        val hit = fire("● Usage limit reached · continuing automatically at 2:30am · esc or type to cancel\n")
        assertNotNull(hit)
        assertEquals("usage limit", hit!!.kind)
    }

    @Test
    fun `Codex and API quota errors are caught`() {
        assertNotNull(fire("""{"error":{"code":"insufficient_quota"}}"""))
        assertNotNull(fire("Error: rate_limit_exceeded\n"))
        assertNotNull(fire("You exceeded your current quota, please check your plan.\n"))
        assertNotNull(fire("429 Too Many Requests\n"))
        assertNotNull(fire("billing_hard_limit_reached\n"))
    }

    /**
     * The one that would do real damage. "The selected model is at capacity" is transient, the CLI
     * retries by itself, and it contains phrasing several quota patterns would otherwise claim.
     */
    @Test
    fun `a transient overload is not a quota wall`() {
        assertNull(fire("The selected model is at capacity. Retrying...\n"))
        assertNull(fire("overloaded_error: the API is overloaded, retrying\n"))
        assertNull(fire("Service is temporarily overloaded\n"))
        assertNull(fire("Encountered retryable error: model capacity exhausted\n"))
    }

    /**
     * A limit on something that is not the model. `agy` has a separate allowance for generating
     * images, and its wording — both sentences it has for it — matches the generic quota patterns
     * word for word. Ending a coding session with hours of its own quota left, because a picture
     * could not be drawn, is the same damage as a false positive on an overload.
     */
    @Test
    fun `a limit on a side feature is not the account running out`() {
        assertNull(fire("image generation quota exceeded, try again later: deadline\n"))
        assertNull(fire("Your image generation quota has been exceeded. Please try again later.\n"))
    }

    @Test
    fun `ordinary output is not a quota wall`() {
        val ordinary = listOf(
            "Running tests... 42 passed\n",
            "warning: unused variable `quota`\n",
            "$ grep -rn 'rate limit' src/\n",
            "src/Limits.kt:12: // TODO: handle the usage limit properly\n",
            "Read 200 lines from /project/src/Quota.kt\n",
        )

        ordinary.forEach { assertNull(fire(it), "fired on ordinary output: $it") }
    }

    @Test
    fun `a message split across two reads still matches`() {
        val (watcher, hits) = watch()

        // Which is the normal case, not an edge one: the PTY hands over whatever has arrived.
        watcher.feed("You've hit your ")
        watcher.feed("usage limit for today.\n")

        assertEquals(1, hits.size)
    }

    @Test
    fun `colour codes between the words do not hide the message`() {
        val hit = fire("[1m[31mYou've hit your usage limit[0m for Claude Opus.\n")

        assertNotNull(hit)
        assertTrue("" !in hit!!.line, "the reported line still carries escapes: ${hit.line}")
    }

    @Test
    fun `it fires once, not on every frame the TUI redraws`() {
        val (watcher, hits) = watch()

        repeat(5) { watcher.feed("You've hit your usage limit\n") }

        assertEquals(1, hits.size, "a TUI repaints constantly; each repaint must not be a fresh wall")
    }

    @Test
    fun `a fresh run can hit a wall of its own`() {
        val (watcher, hits) = watch()
        watcher.feed("You've hit your usage limit\n")

        watcher.reset()
        watcher.feed("You've hit your usage limit\n")

        assertEquals(2, hits.size, "the replacement account must be able to run out too")
    }

    /**
     * Without this, output from long before the wall would still be in the window when it arrives,
     * and the line reported to the user could be something from minutes earlier.
     */
    @Test
    fun `only recent output is kept`() {
        val (watcher, hits) = watch()

        watcher.feed("x".repeat(100_000))
        watcher.feed("\nYou've hit your usage limit\n")

        assertEquals("You've hit your usage limit", hits.single().line)
        assertTrue(watcher.recentText().length <= 4096 + 64, "the window grew without bound")
    }

    @Test
    fun `the kind says what was hit, for the panel to report`() {
        assertEquals("rate limit", fire("429 Too Many Requests")!!.kind)
        assertEquals("usage limit", fire("You've hit your usage limit")!!.kind)
        assertEquals("billing", fire("billing_hard_limit_reached")!!.kind)
        assertEquals("out of credits", fire("insufficient credits")!!.kind)
    }

    @Test
    fun `stripping escapes leaves the text readable`() {
        assertEquals(
            "hello world",
            QuotaWatcher.stripAnsi("[2J[H[1;32mhello [0mworld\r"),
        )
    }

    /**
     * The phrase alone, separate from the line it sat on. It is what gets looked for in the
     * transcript to decide whether the vendor said it or the agent was showing it — see [QuotaEcho]
     * — and the surrounding line, full of gutters and box drawing, would never be found there.
     */
    @Test
    fun `a hit carries the phrase that matched, not just the line it was on`() {
        val hit = fire("  173 +        assertNotNull(fire(\"You've hit your usage limit\"))\n")

        assertNotNull(hit)
        assertEquals("You've hit your usage limit", hit!!.matched)
        assertTrue("173" in hit.line, "the line keeps its context, got: ${hit.line}")
    }

    /**
     * The wall that went straight past nop on 2026-09-20: an `agy` session in hermes refused twice
     * inside five minutes, and the watcher had no pattern for either. None of the wording this list
     * was built from appears in it — no limit "hit" or "reached", no quota "exceeded".
     */
    @Test
    fun `Antigravity's own refusal is caught`() {
        val hit = fire(
            "⚠ Individual quota reached. Please upgrade your subscription to increase your " +
                "limits. Resets in 7m31s.\nError ID: cf01273f-a58f-4d5d-a86f-ec071611e771-700\n",
        )

        assertNotNull(hit)
        assertEquals("usage limit", hit!!.kind)
        assertEquals(Duration.ofMinutes(7).plusSeconds(31), hit.resetsIn)
    }

    /**
     * The countdown is how [AgentSession.onQuotaWall] tells a wall on an allowance no usage reading
     * covers from one on a window it does, so what it is taken off matters as much as what it is.
     */
    @Test
    fun `only a countdown the vendor wrote is read as one`() {
        assertEquals(Duration.ofMinutes(4).plusSeconds(44), fire("Quota reached. Resets in 4m44s.")!!.resetsIn)
        assertEquals(Duration.ofSeconds(45), fire("Quota reached. Resets in 45s.")!!.resetsIn)
        assertEquals(
            Duration.ofHours(1).plusMinutes(2).plusSeconds(3),
            fire("Quota reached. Resets in 1h2m3s.")!!.resetsIn,
        )
        // Claude Code gives a clock time instead, and its accounts have a usage reading that says
        // the same thing. A wall with no countdown on it leaves that reading in charge.
        assertNull(fire("You've hit your session limit · resets 8:10pm (Australia/Melbourne)")!!.resetsIn)
        assertNull(fire("You've hit your usage limit")!!.resetsIn)
        // Words rather than the compact form the CLI writes: not a countdown this reads.
        assertNull(fire("Quota reached. Resets in about ten minutes.")!!.resetsIn)
    }

    /** `agy`'s separate allowance for pictures, in the wording this test's neighbour above missed. */
    @Test
    fun `an image quota reached is still not the account running out`() {
        assertNull(fire("Your image generation quota reached. Please try again later.\n"))
    }
}
