package iondrive.nop.agent

import java.time.Duration

/**
 * A quota wall the vendor's own output announced, the line that announced it, the phrase that
 * actually matched, and the countdown the vendor put on it.
 *
 * [matched] is the phrase alone, where [line] is everything around it — the gutters, the box drawing,
 * whatever else the TUI had on that row. The phrase is what can be looked for somewhere else, which
 * is how nop decides whether the vendor said it or the agent was showing it: see [QuotaEcho].
 *
 * [resetsIn] is set only for a refusal that timed itself — `agy` ends its with "Resets in 7m31s" —
 * and is null for the CLIs that give a clock time or nothing at all. It is what tells a wall apart
 * from the windows an account's usage reading covers: see [AgentSession.onQuotaWall].
 */
data class QuotaHit(
    val kind: String,
    val line: String,
    val matched: String = "",
    val resetsIn: Duration? = null,
)

/**
 * Watches a vendor CLI's terminal output for the moment it runs out of quota.
 *
 * This is the reactive half of quota handling; the poller in `Usage` is the proactive half. They
 * answer different questions. The poller says "you have 14% of the five-hour window left", which is
 * something to plan around. This says "the CLI just refused to continue", which is something to act
 * on — the session is already over, so offering to hand the work to another provider costs nothing
 * and saves restarting it by hand.
 *
 * Nothing here parses the TUI. It looks for a handful of specific phrases in a rolling window of
 * plain text, and everything else — the frames, the spinners, the user's own typing echoed back —
 * passes through untouched on its way to the terminal.
 */
class QuotaWatcher(private val onHit: (QuotaHit) -> Unit) {

    private val tail = StringBuilder()

    @Volatile
    private var fired = false

    /**
     * Takes a slice of terminal output.
     *
     * Called from the connector's read path, which is the thread feeding the terminal, so it does as
     * little as possible: strip escapes, keep the last few KiB, and run two regexes over it.
     */
    fun feed(text: String) = synchronized(this) {
        if (fired || text.isEmpty()) return
        tail.append(stripAnsi(text))
        if (tail.length > WINDOW) tail.delete(0, tail.length - WINDOW)

        // Nothing below can match a window that does not hold one of [ANCHORS], and this is the
        // path every chunk of output from every agent takes. Without it each read copies the whole
        // window out of the builder and runs two thirty-branch alternations over all of it, so the
        // cost is set by the window size and the read rate rather than by how much text arrived:
        // profiled with nine agents running it was a third of nop's CPU, almost all of it spent
        // deciding that ordinary output is ordinary. Scanning for plain words touches each
        // character once and allocates nothing.
        if (!tail.holdsAnAnchor()) return

        val window = tail.toString()
        // Overload first, and deliberately. "The model is at capacity" matches several of the quota
        // patterns below but means the opposite thing: it is transient, the CLI retries by itself,
        // and killing a working session over it would be the worst bug this feature could have.
        if (OVERLOAD.containsMatchIn(window)) {
            tail.setLength(0)
            return
        }
        val match = QUOTA.find(window) ?: return
        fired = true
        val line = lineAround(window, match.range.first)
        onHit(
            QuotaHit(
                kind = kindOf(match.value),
                line = line,
                matched = match.value,
                resetsIn = resetsInFrom(line),
            ),
        )
    }

    /** Plain text of whatever the watcher has seen most recently. Used by its tests. */
    internal fun recentText(): String = synchronized(this) { tail.toString() }

    /**
     * Lets a fresh run reuse the watcher, and re-arms one whose hit was judged to be the agent's own
     * output rather than the vendor's — see [AgentSession.onQuotaWall].
     *
     * Synchronised with [feed], which is called from the PTY's reader thread while this is called
     * from the UI thread: [tail] is a plain StringBuilder, and truncating one mid-append is how a
     * rare, unreproducible crash gets into a path whose whole job is not to disturb a running
     * session.
     */
    fun reset() = synchronized(this) {
        fired = false
        tail.setLength(0)
    }

    private fun lineAround(text: String, index: Int): String {
        val start = text.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim().take(200)
    }

    /**
     * Whether the window holds any word that [OVERLOAD] or [QUOTA] would need in order to match.
     *
     * Every branch of both patterns contains at least one of [ANCHORS], so a window with none of
     * them cannot match either — which is the overwhelming majority of terminal output. Kept
     * honest by `every phrase the watcher fires on carries an anchor`, which feeds one line per
     * branch through [feed] and would fail the moment a pattern gained a word this does not know.
     *
     * Compares lower-cased needles against lower-cased haystack characters rather than lower-casing
     * the window, because allocating a copy of the window per read is among the costs this exists
     * to remove. [ANCHOR_STARTS] makes the common character a single array lookup.
     */
    private fun StringBuilder.holdsAnAnchor(): Boolean {
        for (i in 0 until length) {
            val c = this[i]
            // One array lookup per character, and nothing else: [BY_FIRST] is indexed by the raw
            // character and holds both cases, so the scan never has to case-fold. Folding here
            // instead — Char.lowercaseChar() on all 4,096 — costs more than the regexes this is
            // meant to replace, which is a mistake worth leaving a note about.
            if (c.code >= BY_FIRST.size) continue
            val bucket = BY_FIRST[c.code] ?: continue
            for (anchor in bucket) {
                if (!matchesAt(i, anchor.word)) continue
                val near = anchor.near ?: return true
                if (hasWordBefore(near, i)) return true
            }
        }
        return false
    }

    /** Whether [word] sits at [at], given that its first character already matched. */
    private fun StringBuilder.matchesAt(at: Int, word: String): Boolean {
        if (at + word.length > length) return false
        for (j in 1 until word.length) {
            if (this[at + j].lowercaseChar() != word[j]) return false
        }
        return true
    }

    /**
     * Whether [word] begins within [NEAR] characters before [before] and is followed by whitespace,
     * which is what [Anchor.near] means.
     *
     * The whitespace is not fussiness: without it "too" matches inside "tool", and
     * `"tool_use":{"web_search_requests` — which an agent's own output is full of — would make
     * every window look like a rate-limit notice. Measured against real output it was the
     * difference between 68% and 4.7% of windows paying for the full match. The patterns this
     * mirrors all say `too\s+many`, so requiring the space is reading them exactly.
     */
    private fun StringBuilder.hasWordBefore(word: String, before: Int): Boolean {
        var i = (before - NEAR).coerceAtLeast(0)
        while (i < before) {
            if (this[i].lowercaseChar() == word[0] && matchesAt(i, word)) {
                val after = i + word.length
                if (after < length && this[after].isWhitespace()) return true
            }
            i++
        }
        return false
    }

    companion object {
        /** How much recent output is kept. A limit message and its context fit in far less. */
        private const val WINDOW = 4096

        /**
         * One word from every branch of [OVERLOAD] and [QUOTA], lower-cased.
         *
         * A word rather than a phrase because the patterns join their words with `\s+`, so no
         * two-word literal survives a message that happens to be spaced differently.
         *
         * Chosen to be *rare*, not merely sufficient, because a word that turns up in an agent's
         * ordinary output puts every window back on the slow path. The first version used
         * "account" and "requests", which read as specific enough and are in fact the working
         * vocabulary of the project this was measured on: 68% of its windows carried one. Naming
         * the two words the account branch actually needs, and pairing "requests" with the "too"
         * its own patterns demand, took that to 4.7%.
         *
         * Adding a pattern means adding its word here. The tests named in [holdsAnAnchor]'s
         * neighbours are what catch forgetting to.
         */
        private val ANCHORS = arrayOf(
            Anchor("limit"), Anchor("quota"), Anchor("credit"), Anchor("exhaust"),
            Anchor("payment"), Anchor("capacity"), Anchor("overload"),
            // `account (has been )?(suspended|disabled)` — the two endings, rather than the common
            // noun in front of them.
            Anchor("suspended"), Anchor("disabled"),
            // `too\s+many\s+requests`, both branches of it. "requests" alone is ordinary output.
            Anchor("requests", near = "too"),
        )

        /** A word a pattern needs, and optionally another that must sit just before it. */
        private class Anchor(val word: String, val near: String? = null)

        /** How far back [hasWordBefore] looks. "too many requests" spans nine. */
        private const val NEAR = 32

        /**
         * The anchors that can begin at a given character, indexed by the raw character so the
         * scan never case-folds. Both cases point at the same bucket.
         */
        private val BY_FIRST: Array<Array<Anchor>?> = arrayOfNulls<Array<Anchor>>(128).also { table ->
            ANCHORS.groupBy { it.word[0] }.forEach { (first, group) ->
                val bucket = group.toTypedArray()
                table[first.code] = bucket
                table[first.uppercaseChar().code] = bucket
            }
        }

        /**
         * Escape sequences, so a limit message split by a colour change still reads as one phrase.
         * Deliberately narrow: this text is only ever matched against, never shown.
         */
        private val ANSI = Regex("\\[[0-9;?]*[a-zA-Z]|[()][B0]|[=>]|\r")

        fun stripAnsi(text: String): String = ANSI.replace(text, "")

        /**
         * The phrase in [text] that says an account is out, or null — the test [feed] puts to the
         * screen, for text that came from somewhere else. [QuotaEcho] asks it of the records the CLI
         * files about itself, to tell a refusal from every other notice it writes.
         */
        internal fun limitPhraseIn(text: String): String? =
            if (OVERLOAD.containsMatchIn(text)) null else QUOTA.find(text)?.value

        /**
         * Something that is not the account running out, matched before the quota patterns because
         * several of them would otherwise claim it.
         *
         * Two kinds. A provider temporarily unable to serve a model — transient, and retried by the
         * CLI itself. And a limit on something that is not the model at all: `agy` says "image
         * generation quota exceeded, try again later", which is a separate allowance on a side
         * feature, and reading it as the coding quota would end a session that has hours of it
         * left. Both would be the worst bug this feature could have, which is a session killed for
         * working.
         */
        private val OVERLOAD = Regex(
            listOf(
                """selected\s+model\s+is\s+at\s+capacity""",
                """\bmodel\s+is\s+at\s+capacity\b""",
                """\b(?:api|service|server)\s+is\s+overloaded\b""",
                """\boverloaded_error\b""",
                """\btemporarily\s+overloaded\b""",
                """\bmodel\s+capacity\s+exhausted\b""",
                """\bimage\s+generation\s+quota\b""",
            ).joinToString("|") { "($it)" },
            RegexOption.IGNORE_CASE,
        )

        /**
         * The account is out. Built against real error text from these CLIs, plus the sentence Claude Code prints in its TUI when a plan limit is
         * reached — the one case the API-shaped patterns miss, because the TUI never shows the API
         * error at all.
         *
         * Every pattern is specific on purpose. A false positive kills a session that was working.
         */
        private val QUOTA = Regex(
            listOf(
                // Claude Code's own TUI wording. The qualifiers are listed rather than left open
                // because the sentence is the one place a limit on something else would read the
                // same: "session" and "weekly" are the windows the plan actually has, and the 429
                // itself is the source of the first — "You've hit your session limit · resets 8:10pm"
                // is the text Claude Code files against the refused request.
                """you(?:'ve| have)\s+hit\s+your\s+(?:usage\s+|session\s+|weekly\s+)?limit""",
                """you(?:'ve| have)\s+reached\s+your\s+(?:usage\s+|session\s+|weekly\s+)?limit""",
                """approaching\s+your\s+usage\s+limit.*resets""",
                """\b5-hour\s+limit\s+reached\b""",
                """\bweekly\s+limit\s+reached\b""",
                // OpenAI / Codex error codes and messages.
                """\binsufficientquota\b""",
                """\binsufficient_quota\b""",
                """\brate_limit_exceeded\b""",
                """\bratelimitexceeded\b""",
                """\bbilling_hard_limit_reached\b""",
                """you\s+exceeded\s+your\s+current\s+quota""",
                """you\s+have\s+exceeded\s+your\s+(?:rate|usage)\s+limit""",
                // Anthropic API.
                """\bcredit_balance\b.*\binsufficient\b""",
                """rate\s+limit\s+exceeded""",
                // Antigravity's, which names no window at all: "Individual quota reached. Please
                // upgrade your subscription to increase your limits. Resets in 7m31s." It can come
                // with hours left in both windows `/usage` reports: the allowance it refuses against
                // is a third one, minutes long, that no reading here sees. The countdown on the end is the only
                // thing that says so, and it is read separately: see [resetsInFrom].
                """\bquota\s+reached\b""",
                // Generic, and common to both.
                """\bquota\s+exceeded\b""",
                """\bquota\s+has\s+been\s+exceeded\b""",
                """\binsufficient\s+credits?\b""",
                """\binsufficient\s+quota\b""",
                """\bout\s+of\s+credits?\b""",
                """\bcredits?\s+exhausted\b""",
                """\busage\s+limit\s+(?:exceeded|reached)\b""",
                """\bbilling\s+limit\s+(?:exceeded|reached)\b""",
                """\bpayment\s+required\b""",
                """\baccount\s+(?:has\s+been\s+)?(?:suspended|disabled)\b""",
                """\btoo\s+many\s+requests\b""",
                """\bresource\s+exhausted\b""",
                """429\s+too\s+many\s+requests""",
            ).joinToString("|") { "($it)" },
            RegexOption.IGNORE_CASE,
        )

        /**
         * How long the vendor said the wall lasts, taken from the refusal itself, or null when it
         * did not time it.
         *
         * Only the compact form `7m31s`, and only on the line the phrase was found on. This is
         * read as the vendor naming the allowance it refused against — [AgentSession.onQuotaWall]
         * lets it outrank a usage reading that knows nothing about that allowance — so the looser
         * a shape it were allowed to take, the more of the agent's own output could wear it.
         * Claude Code's "resets 8:10pm" is deliberately not matched: it is a clock time rather
         * than a countdown, and for that provider the account's own reading says when the window
         * turns over anyway.
         */
        internal fun resetsInFrom(text: String): Duration? =
            RESETS_IN.findAll(text).firstNotNullOfOrNull { match ->
                val (hours, minutes, seconds) = match.destructured
                if (hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) {
                    null
                } else {
                    Duration.ofHours(hours.toLongOrNull() ?: 0)
                        .plusMinutes(minutes.toLongOrNull() ?: 0)
                        .plusSeconds(seconds.toLongOrNull() ?: 0)
                }
            }

        /** The countdown's shape. Every part is optional; [resetsInFrom] rejects a match with none. */
        private val RESETS_IN = Regex(
            """\bresets?\s+in\s+(?:(\d{1,3})h)?(?:(\d{1,3})m)?(?:(\d{1,3})s)?""",
            RegexOption.IGNORE_CASE,
        )

        /** A short name for what was hit, for the exit panel to say. */
        internal fun kindOf(matched: String): String {
            val text = matched.lowercase()
            return when {
                "rate limit" in text || "too many requests" in text || "rate_limit" in text -> "rate limit"
                "billing" in text || "payment" in text -> "billing"
                "suspended" in text || "disabled" in text -> "account suspended"
                "credit" in text -> "out of credits"
                else -> "usage limit"
            }
        }
    }
}
