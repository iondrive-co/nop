package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The quota watcher: what it fires on, and — more importantly — what it does not.
 *
 * A miss costs a convenience: the run ends at the vendor's own error and the user starts another
 * themselves. A false positive kills a session that was working, mid-turn, and there is no undo for
 * that. The asymmetry is why the overload patterns are checked first and why every quota pattern is
 * a specific phrase rather than a keyword.
 */
class QuotaTest {

    private val walls = listOf(
        "You've hit your usage limit",
        "You have reached your limit",
        "approaching your usage limit · resets at 5pm",
        "5-hour limit reached",
        "Weekly limit reached",
        "insufficientquota",
        "insufficient_quota",
        "rate_limit_exceeded",
        "ratelimitexceeded",
        "usage_limit_exceeded",
        "billing_hard_limit_reached",
        "You exceeded your current quota",
        "You have exceeded your rate limit",
        "credit_balance is insufficient",
        "rate limit exceeded",
        "Individual quota reached.",
        "quota exceeded",
        "quota has been exceeded",
        "insufficient credits",
        "insufficient quota",
        "out of credits",
        "credits exhausted",
        "usage limit exceeded",
        "billing limit reached",
        "payment required",
        "account has been suspended",
        "account disabled",
        "too many requests",
        "resource exhausted",
        "429 Too Many Requests",
    )

    private val overloads = listOf(
        "The selected model is at capacity",
        "The model is at capacity",
        "the API is overloaded",
        "overloaded_error",
        "temporarily overloaded",
        "model capacity exhausted",
        "image generation quota exceeded",
    )

    /** Ordinary output that holds the patterns' words in the wrong company. */
    private val nearMisses = listOf(
        "raised the connection limit to 64",
        "the position limit was reached before the fill",
        "quota for the new tenant",
        "insufficient funds in the account",
        "the feature was disabled for the account page",
        "many of the requests were retried",
        "resets the counter on each pass",
        "rate limit of 5 requests per second",
        "capacity for 12 more",
    )

    /**
     * [QuotaWatcher] with its shortcut taken out: only the two patterns, over the same window. What
     * the shortcut is allowed to change is the cost of a decision and never the decision.
     */
    private class ByThePatterns {
        private val tail = StringBuilder()
        var matched: String? = null
            private set

        fun feed(text: String) {
            if (matched != null || text.isEmpty()) return
            val clean = QuotaWatcher.stripAnsi(text)
            tail.append(clean)
            if (tail.length > 4096) tail.delete(0, tail.length - 4096)
            val from = (tail.length - clean.length - 512).coerceAtLeast(0)
            val window = tail.toString()
            if (QuotaWatcher.OVERLOAD.find(window, from) != null) {
                tail.setLength(0)
                return
            }
            matched = QuotaWatcher.QUOTA.find(window, from)?.value
        }
    }

    private fun assertAgreesWithThePatterns(chunks: List<String>, what: String) {
        val (watcher, hits) = watch()
        val reference = ByThePatterns()
        chunks.forEach {
            watcher.feed(it)
            reference.feed(it)
        }
        assertEquals(reference.matched, hits.firstOrNull()?.matched, what)
    }

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
            // The one that made the cheap pre-filter nearly useless on a real project: "too" is a
            // prefix of "tool", and a transcript is full of this. The pre-filter pairs "requests"
            // with a "too" that is followed by whitespace, exactly as `too\\s+many` does.
            """{"type":"tool_use":{"web_search_requests":3,"account":"main"}}""",
            "fetching 40 requests for account 12 — rate limiting to 5/s\n",
        )

        ordinary.forEach { assertNull(fire(it), "fired on ordinary output: $it") }
    }

    @Test
    fun `the shortcut changes nothing for any phrase split at any point`() {
        val phrases = walls + overloads + nearMisses
        for (phrase in phrases) {
            for (variant in listOf(phrase, phrase.replace(" ", "\n    "), phrase.replace(" ", "   ").uppercase())) {
                for (cut in 0..variant.length) {
                    val chunks = listOf("noise ".repeat(100), variant.substring(0, cut), variant.substring(cut), "\ntail")
                    assertAgreesWithThePatterns(chunks, "cut at $cut: $variant")
                }
            }
        }
    }

    @Test
    fun `the shortcut changes nothing however output is chunked and coloured`() {
        val rng = java.util.Random(20261111)
        repeat(400) { round ->
            val text = buildString {
                repeat(rng.nextInt(30) + 1) {
                    val pool = when {
                        rng.nextInt(10) == 0 -> walls + overloads
                        rng.nextInt(2) == 0 -> nearMisses
                        else -> listOf("  building index for segment ${rng.nextInt(99)}", "✻ Thinking…", "limit", "your", "reached")
                    }
                    var phrase = pool[rng.nextInt(pool.size)]
                    if (rng.nextBoolean()) phrase = phrase.replace(" ", " \u001b[38;5;${rng.nextInt(255)}m")
                    append(phrase).append(if (rng.nextBoolean()) "\n" else " ")
                }
            }
            var i = 0
            val chunks = buildList {
                while (i < text.length) {
                    val end = minOf(text.length, i + 1 + rng.nextInt(120))
                    add(text.substring(i, end))
                    i = end
                }
            }
            assertAgreesWithThePatterns(chunks, "round $round")
        }
    }

    /** The word before an ending can sit on the row before, however far a TUI has padded it out. */
    @Test
    fun `an ending and the word before it may be separated by a whole padded row`() {
        assertNotNull(fire("You've hit your" + " ".repeat(160) + "\n" + " ".repeat(4) + "usage limit"))
        assertNotNull(fire("rate limit" + " ".repeat(120) + "\nexceeded"))
    }

    @Test
    fun `an ending on another line from the rest of its phrase does not reach the patterns' dot`() {
        val (watcher, hits) = watch()
        watcher.feed("approaching your usage limit\nresets at 5pm")
        assertEquals(0, hits.size)
        val (again, moreHits) = watch()
        again.feed("approaching your\nusage limit · resets at 5pm")
        assertEquals(1, moreHits.size, "whitespace before the limit may break the line; only the .* after it may not")
    }

    @Test
    fun `stripping escapes by hand removes what the pattern removed`() {
        val pattern = Regex("\u001b\\[[0-9;?]*[a-zA-Z]|\u001b[()][B0]|\u001b[=>]|\r")
        val pieces = listOf("\u001b", "[", "?", "25", "h", ";", "(", ")", "B", "0", "=", ">", "\r", "a", " ", "m", "é", "\n")
        val rng = java.util.Random(7)
        repeat(4000) {
            val text = buildString { repeat(rng.nextInt(14)) { append(pieces[rng.nextInt(pieces.size)]) } }
            assertEquals(pattern.replace(text, ""), QuotaWatcher.stripAnsi(text), text.map { it.code }.toString())
        }
    }

    @Test
    fun `text with no escapes in it comes back without being copied`() {
        val text = "plain terminal output"
        assertTrue(text === QuotaWatcher.stripAnsi(text))
    }

    @Test
    fun `a message split across two reads still matches`() {
        val (watcher, hits) = watch()

        // Which is the normal case, not an edge one: the PTY hands over whatever has arrived.
        watcher.feed("You've hit your ")
        watcher.feed("usage limit for today.\n")

        assertEquals(1, hits.size)
    }

    /**
     * The anchor words turn up in ordinary output, and one already in the window must not hide a
     * wall that arrives after it, however many redraws later and however it is split.
     */
    @Test
    fun `a wall after ordinary output that mentions a limit still fires`() {
        val (watcher, hits) = watch()

        watcher.feed("raised the connection limit to 64\n")
        repeat(100) { watcher.feed("✻ Thinking… (${it}s · ↓ 1.2k tokens)") }
        assertEquals(0, hits.size)
        watcher.feed("You've hit your ")
        watcher.feed("usage limit · resets 8pm\n")

        assertEquals(1, hits.size)
        assertEquals("usage limit", hits.single().kind)
    }

    @Test
    fun `an overload split across reads still stands the watcher down`() {
        val (watcher, hits) = watch()

        watcher.feed("The selected model is at ")
        watcher.feed("capacity. You've hit your usage limit\n")

        assertEquals(0, hits.size)
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

    /**
     * The cheap test in [QuotaWatcher.feedPlain] skips both patterns unless the new text ends one
     * of a handful of words with the word its branch needs before it. That is only safe while
     * every branch has an entry, so this feeds a line per branch and would fail the moment a
     * pattern gained an ending the table does not know — which is exactly the mistake that would
     * make a real quota wall silently stop being noticed.
     */
    @Test
    fun `every phrase the watcher fires on gets past the test`() {
        walls.forEach { assertNotNull(fire(it), "the pre-filter swallowed a real wall: $it") }
    }

    /**
     * The same invariant for the overload patterns, which cannot be checked by asserting no hit:
     * a branch the pre-filter skipped would produce no hit either, and pass for the wrong reason.
     * Matching [QuotaWatcher.OVERLOAD] clears the window, so an empty window afterwards is proof
     * the text actually reached the pattern.
     */
    @Test
    fun `every phrase the watcher stands down on gets past the test`() {
        overloads.forEach { text ->
            val (watcher, hits) = watch()
            watcher.feed(text)
            assertTrue(hits.isEmpty(), "fired on a transient overload: $text")
            assertEquals(
                "",
                watcher.recentText(),
                "the pre-filter skipped an overload branch, so it never stood down: $text",
            )
        }
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
     * `agy`'s refusal, in which none of the wording the rest of this list is built from appears — no
     * limit "hit" or "reached", no quota "exceeded".
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

    @Test
    fun `curly apostrophe in limit phrasing is caught`() {
        val hit = fire("You’ve hit your usage limit. Upgrade your account.\n")
        assertNotNull(hit)
        assertEquals("usage limit", hit!!.kind)
    }

    @Test
    fun `a vendor clock time is parsed into a reset duration`() {
        val now = Instant.parse("2026-06-15T10:00:00Z")
        val zone = ZoneOffset.UTC
        val line = "You’ve reached your usage limit. Try again at 11:30 AM."
        val resetsIn = QuotaWatcher.resetsInFrom(line, now, zone)
        assertEquals(Duration.ofHours(1).plusMinutes(30), resetsIn)
    }

    @Test
    fun `a vendor clock time past midnight is parsed across the day boundary`() {
        val now = Instant.parse("2026-06-15T23:30:00Z")
        val zone = ZoneOffset.UTC
        val line = "You’ve hit your usage limit. Try again at 1:45 AM."
        val resetsIn = QuotaWatcher.resetsInFrom(line, now, zone)
        assertEquals(Duration.ofHours(2).plusMinutes(15), resetsIn)
    }

    @Test
    fun `the reset time on a wall is read in the zone it names`() {
        // 13:05 in Lisbon (UTC+1 in June) is 12:05Z; the wall says 4:20pm there, 15:20Z.
        val now = Instant.parse("2026-06-15T12:05:00Z")
        val line = "You've hit your session limit · resets 4:20pm (Europe/Lisbon)"
        assertEquals(Instant.parse("2026-06-15T15:20:00Z"), QuotaWatcher.resetsAtFrom(line, now, ZoneOffset.UTC))
    }

    @Test
    fun `a reset time with no zone is read in the local one and may name a whole hour`() {
        val now = Instant.parse("2026-06-15T08:00:00Z")
        val line = "You've hit your usage limit · resets 11am"
        assertEquals(Instant.parse("2026-06-15T11:00:00Z"), QuotaWatcher.resetsAtFrom(line, now, ZoneOffset.UTC))
    }

    @Test
    fun `the watcher puts the reset time on the hit and leaves the countdown alone`() {
        val hit = fire("You've hit your session limit · resets 7:45pm (Asia/Tokyo)\n")
        assertNotNull(hit)
        assertNotNull(hit!!.resetsAt)
        assertNull(hit.resetsIn, "a clock time is no countdown: it says nothing about another allowance")
    }

    @Test
    fun `a reset time just gone is the wall that just lifted, not tomorrow's`() {
        val now = Instant.parse("2026-06-15T18:30:00Z")
        val line = "You've hit your session limit · resets 6:10pm"
        assertEquals(Instant.parse("2026-06-15T18:10:00Z"), QuotaWatcher.resetsAtFrom(line, now, ZoneOffset.UTC))
    }

    @Test
    fun `a reset time long gone today is tomorrow's`() {
        val now = Instant.parse("2026-06-15T23:30:00Z")
        val line = "You've hit your session limit · resets 2am"
        assertEquals(Instant.parse("2026-06-16T02:00:00Z"), QuotaWatcher.resetsAtFrom(line, now, ZoneOffset.UTC))
    }

    @Test
    fun `a wall without a clock time, or with a date, gives no reset time`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertNull(QuotaWatcher.resetsAtFrom("Individual quota reached. Resets in 7m31s.", now, ZoneOffset.UTC))
        assertNull(QuotaWatcher.resetsAtFrom("You've hit your weekly limit · resets Jun 18 at 3pm", now, ZoneOffset.UTC))
        assertNull(QuotaWatcher.resetsAtFrom("You've hit your usage limit", now, ZoneOffset.UTC))
    }

    @Test
    fun `an unknown zone name falls back to the local zone`() {
        val now = Instant.parse("2026-06-15T08:00:00Z")
        val line = "You've hit your usage limit · resets 9:30am (Nowhere/Place)"
        assertEquals(Instant.parse("2026-06-15T09:30:00Z"), QuotaWatcher.resetsAtFrom(line, now, ZoneOffset.UTC))
    }
}

