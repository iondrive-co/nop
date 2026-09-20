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

    companion object {
        /** How much recent output is kept. A limit message and its context fit in far less. */
        private const val WINDOW = 4096

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
         * The account is out. Ported from chad's own list, which was built against real error text
         * from these CLIs, plus the sentence Claude Code prints in its TUI when a plan limit is
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
                // upgrade your subscription to increase your limits. Resets in 7m31s." The `agy`
                // account nop watched run out on 2026-09-20 had hours left in both windows its
                // `/usage` reports — the allowance this refuses against is a third one, minutes
                // long, that no reading here has ever seen. The countdown on the end is the only
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
