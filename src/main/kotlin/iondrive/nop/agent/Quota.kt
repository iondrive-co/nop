package iondrive.nop.agent

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId


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
 *
 * [resetsAt] is the clock time the CLI printed for its window turning over — Claude Code's
 * "resets 10:10am (Europe/London)" — and says only when the wall ends, not which allowance it is,
 * so it times a wait without weighing in on whether to hand over.
 */
data class QuotaHit(
    val kind: String,
    val line: String,
    val matched: String = "",
    val resetsIn: Duration? = null,
    val resetsAt: Instant? = null,
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

    /** Takes a slice of raw terminal output. See [feedPlain]. */
    fun feed(text: String) = feedPlain(stripAnsi(text))

    /**
     * Takes a slice of terminal output that has been through [stripAnsi] already, for a caller whose
     * other readers need the same plain text.
     *
     * Called from the connector's read path, which is the thread feeding the terminal, so it does as
     * little as possible: keep the last few KiB, and look only at what the new text could have
     * completed.
     */
    fun feedPlain(clean: String) = synchronized(this) {
        if (fired || clean.isEmpty()) return
        tail.append(clean)
        if (tail.length > WINDOW) tail.delete(0, tail.length - WINDOW)
        val newStart = (tail.length - clean.length).coerceAtLeast(0)

        // Every feed leaves the window holding no match — it fired, it was cleared, or it was
        // looked at and found to hold none — so the only match worth looking for is one the new
        // text finished. That is cheap to rule out: it would end in one of the words in [ENDINGS],
        // with the right word in front of it, in the new text or straddling its start.
        if (!tail.mayEndAMatch((newStart - LONGEST_ENDING).coerceAtLeast(0))) return

        val from = (newStart - LOOKBACK).coerceAtLeast(0)
        val window = tail.toString()
        // Overload first, and deliberately. "The model is at capacity" matches several of the quota
        // patterns below but means the opposite thing: it is transient, the CLI retries by itself,
        // and killing a working session over it would be the worst bug this feature could have.
        // Searching only the new part loses nothing here either: an overload already in the window
        // would have cleared it when it arrived.
        if (OVERLOAD.find(window, from) != null) {
            tail.setLength(0)
            return
        }
        val match = QUOTA.find(window, from) ?: return
        fired = true
        val line = lineAround(window, match.range.first)
        onHit(
            QuotaHit(
                kind = kindOf(match.value),
                line = line,
                matched = match.value,
                resetsIn = resetsInFrom(line),
                resetsAt = resetsAtFrom(line),
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
     * Whether a match of [OVERLOAD] or [QUOTA] can end in the window from [from] on.
     *
     * Every branch of both patterns ends in a word listed in [ENDINGS], and only an ending preceded
     * by the word its branch puts there can be one. Ordinary output has the words on their own ("the
     * limit order", "was disabled") and is settled here; `QuotaTest` runs every branch and random
     * output through this and through the bare patterns and fails if the two ever disagree.
     *
     * Each character costs one array lookup, and nothing here allocates: this is the path every chunk
     * of output from every agent takes.
     */
    private fun StringBuilder.mayEndAMatch(from: Int): Boolean {
        for (i in from until length) {
            val c = this[i]
            if (c.code >= BY_FIRST.size) continue
            val bucket = BY_FIRST[c.code] ?: continue
            for (ending in bucket) {
                if (matchesAt(i, ending.word) && ending.isPreceded(this, i)) return true
            }
        }
        return false
    }

    /** Whether [word] sits at [at], given that its first character already matched. */
    private fun StringBuilder.matchesAt(at: Int, word: String): Boolean {
        if (at + word.length > length) return false
        for (j in 1 until word.length) {
            if (lowerAscii(this[at + j]) != word[j]) return false
        }
        return true
    }

    /**
     * A word that can end a match, with what must stand before it.
     *
     * [after] is the word its branch puts directly in front, whitespace apart. [onLine] is for the
     * two branches joined with `.*`, whose earlier word may be anywhere before it on the line. An
     * ending with neither is a single token, rare enough to send straight to the patterns.
     */
    private class Ending(
        val word: String,
        val after: Array<String>? = null,
        val onLine: String? = null,
    ) {
        fun isPreceded(text: StringBuilder, at: Int): Boolean {
            val words = after
            if (words != null) return words.any { text.hasWordEndingAt(lastBefore(text, at), it) }
            val needle = onLine ?: return true
            var i = at - 1
            val floor = (at - LOOKBACK).coerceAtLeast(0)
            while (i >= floor && text[i] != '\n') {
                if (lowerAscii(text[i]) == needle[0] && text.hasWordStartingAt(i, needle)) return true
                i--
            }
            return false
        }

        /** The index just past the last non-whitespace character before [at]. */
        private fun lastBefore(text: StringBuilder, at: Int): Int {
            var i = at
            val floor = (at - LOOKBACK).coerceAtLeast(0)
            while (i > floor && isRegexSpace(text[i - 1])) i--
            return i
        }

        private fun StringBuilder.hasWordEndingAt(end: Int, word: String): Boolean {
            val start = end - word.length
            return start >= 0 && hasWordStartingAt(start, word)
        }

        private fun StringBuilder.hasWordStartingAt(at: Int, word: String): Boolean {
            if (at + word.length > length) return false
            for (j in word.indices) if (lowerAscii(this[at + j]) != word[j]) return false
            return true
        }

        /** What `\\s` matches without the UNICODE_CHARACTER_CLASS flag. */
        private fun isRegexSpace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
    }

    companion object {
        /** How much recent output is kept. A limit message and its context fit in far less. */
        private const val WINDOW = 4096

        /**
         * How far before the newest text a match may start. The longest phrase is under forty
         * characters as the vendors write it; the rest is room for the whitespace `\s+` allows and
         * for what a `.*` pattern spans on its line.
         */
        private const val LOOKBACK = 512

        /**
         * The word each branch of [OVERLOAD] and [QUOTA] ends in, lower-cased, with what its branch
         * puts before it (see [Ending]).
         *
         * A bare word would not do: "limit", "reached" and "exceeded" are everyday words in an agent's
         * output, and every chunk carrying one would pay for both full alternations. Only the pairing
         * makes them rare. Adding a pattern means adding its ending here.
         */
        private val ENDINGS = arrayOf(
            Ending("limit", after = arrayOf("your", "usage", "session", "weekly", "rate")),
            Ending("quota", after = arrayOf("current", "insufficient", "generation")),
            Ending("reached", after = arrayOf("limit", "quota")),
            Ending("exceeded", after = arrayOf("limit", "quota", "been")),
            Ending("exhausted", after = arrayOf("capacity", "credit", "credits", "resource")),
            Ending("capacity", after = arrayOf("at")),
            Ending("overloaded", after = arrayOf("is", "temporarily")),
            Ending("credit", after = arrayOf("insufficient", "of")),
            Ending("required", after = arrayOf("payment")),
            Ending("suspended", after = arrayOf("account", "been")),
            Ending("disabled", after = arrayOf("account", "been")),
            Ending("requests", after = arrayOf("many")),
            // The two branches that join their words with `.*`.
            Ending("resets", onLine = "limit"),
            Ending("insufficient", onLine = "credit_balance"),
            // Whole tokens, which end in no separate word.
            Ending("overloaded_error"),
            Ending("insufficientquota"),
            Ending("insufficient_quota"),
            Ending("rate_limit_exceeded"),
            Ending("ratelimitexceeded"),
            Ending("usage_limit_exceeded"),
            Ending("billing_hard_limit_reached"),
        )

        private val LONGEST_ENDING = ENDINGS.maxOf { it.word.length }

        /**
         * The endings that can begin at a given character, indexed by the raw character so the scan
         * never case-folds. Both cases point at the same bucket.
         */
        private val BY_FIRST: Array<Array<Ending>?> = arrayOfNulls<Array<Ending>>(128).also { table ->
            ENDINGS.groupBy { it.word[0] }.forEach { (first, group) ->
                val bucket = group.toTypedArray()
                table[first.code] = bucket
                table[first.uppercaseChar().code] = bucket
            }
        }

        private fun lowerAscii(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

        /**
         * [text] with escape sequences removed, so a limit message split by a colour change still
         * reads as one phrase: `ESC [ params letter`, the charset selections `ESC ( B` and `ESC ( 0`
         * (and with `)`), `ESC =`, `ESC >`, and carriage returns. Deliberately narrow, since this text
         * is only ever matched against, never shown. By hand rather than with a pattern because it
         * runs over every chunk of every agent's output; text with nothing to remove is returned as
         * it is.
         */
        fun stripAnsi(text: String): String {
            var i = 0
            while (i < text.length && text[i] != ESC && text[i] != '\r') i++
            if (i == text.length) return text
            val out = StringBuilder(text.length)
            var from = 0
            while (i < text.length) {
                val c = text[i]
                val end = when (c) {
                    '\r' -> i + 1
                    ESC -> sequenceEnd(text, i)
                    else -> -1
                }
                if (end < 0) {
                    i++
                    continue
                }
                out.append(text, from, i)
                from = end
                i = end
            }
            out.append(text, from, text.length)
            return out.toString()
        }

        /** Where the sequence [stripAnsi] removes at the ESC at [at] ends, or -1 if none is there. */
        private fun sequenceEnd(text: String, at: Int): Int {
            val next = text.getOrNull(at + 1) ?: return -1
            when (next) {
                '[' -> {
                    var i = at + 2
                    while (i < text.length && (text[i] in '0'..'9' || text[i] == ';' || text[i] == '?')) i++
                    val letter = text.getOrNull(i) ?: return -1
                    return if (letter in 'a'..'z' || letter in 'A'..'Z') i + 1 else -1
                }
                '(', ')' -> return if (text.getOrNull(at + 2) == 'B' || text.getOrNull(at + 2) == '0') at + 3 else -1
                '=', '>' -> return at + 2
            }
            return -1
        }

        private const val ESC = '\u001b'

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
        internal val OVERLOAD = Regex(
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
        internal val QUOTA = Regex(
            listOf(
                // Claude Code's own TUI wording. The qualifiers are listed rather than left open
                // because the sentence is the one place a limit on something else would read the
                // same: "session" and "weekly" are the windows the plan actually has, and the 429
                // itself is the source of the first — "You've hit your session limit · resets 8:10pm"
                // is the text Claude Code files against the refused request. Codex uses right single quotation marks.
                """you(?:['’]ve| have)\s+hit\s+your\s+(?:usage\s+|session\s+|weekly\s+)?limit""",
                """you(?:['’]ve| have)\s+reached\s+your\s+(?:usage\s+|session\s+|weekly\s+)?limit""",
                """approaching\s+your\s+usage\s+limit.*resets""",
                """\b5-hour\s+limit\s+reached\b""",
                """\bweekly\s+limit\s+reached\b""",
                // OpenAI / Codex error codes and messages.
                """\binsufficientquota\b""",
                """\binsufficient_quota\b""",
                """\brate_limit_exceeded\b""",
                """\bratelimitexceeded\b""",
                """\busage_limit_exceeded\b""",
                """\bbilling_hard_limit_reached\b""",
                """you(?:['’]ve|\s+have)?\s+exceeded\s+your\s+current\s+quota""",
                """you(?:['’]ve|\s+have)?\s+exceeded\s+your\s+(?:rate|usage)\s+limit""",
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
         * Two formats:
         * - The compact countdown form `7m31s`, used by Antigravity.
         * - A clock time `try again at 11:40 PM`, used by Codex on task completion error.
         *
         * Only on the line the phrase was found on. This is read as the vendor naming the allowance
         * it refused against — [AgentSession.onQuotaWall] lets it outrank a usage reading that knows
         * nothing about that allowance — so the looser a shape it were allowed to take, the more of
         * the agent's own output could wear it. Claude Code's "resets 8:10pm" is not matched here: it
         * names one of the windows the account's reading covers, so it is no evidence of another
         * allowance. It is read for timing alone, by [resetsAtFrom].
         */
        internal fun resetsInFrom(
            text: String,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Duration? {
            RESETS_IN.findAll(text).firstNotNullOfOrNull { match ->
                val (hours, minutes, seconds) = match.destructured
                if (hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) {
                    null
                } else {
                    Duration.ofHours(hours.toLongOrNull() ?: 0)
                        .plusMinutes(minutes.toLongOrNull() ?: 0)
                        .plusSeconds(seconds.toLongOrNull() ?: 0)
                }
            }?.let { return it }

            return TRY_AGAIN_AT.findAll(text).firstNotNullOfOrNull { match ->
                val (hStr, mStr, meridiem) = match.destructured
                val time = clockTime(hStr, mStr, meridiem) ?: return@firstNotNullOfOrNull null
                val todayTarget = now.atZone(zone).with(time)
                val targetInstant = todayTarget.toInstant()
                when {
                    targetInstant.isAfter(now) -> Duration.between(now, targetInstant)
                    Duration.between(targetInstant, now) <= CLOCK_DRIFT_TOLERANCE -> Duration.ZERO
                    else -> Duration.between(now, todayTarget.plusDays(1).toInstant())
                }
            }
        }

        /**
         * The clock time a wall says its window resets at — Claude Code's "resets 10:10am
         * (Europe/London)" — or null when the line gives none.
         *
         * Read in the zone the CLI names in brackets, else [zone]. The time is the next one at or
         * before which the window can turn over: today's, or tomorrow's when today's is long gone. A
         * time passed within the last [RECENTLY_PASSED] is returned as it is, in the past, because
         * the screen can still show a wall drawn before its reset, and that wall is over now rather
         * than a day from now.
         *
         * A wall that names a date as well ("resets Nov 14 at 3pm") is not read: the time alone
         * would be the wrong day.
         */
        internal fun resetsAtFrom(
            text: String,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Instant? = RESETS_AT.findAll(text).firstNotNullOfOrNull { match ->
            val (hStr, mStr, meridiem, zoneName) = match.destructured
            val time = clockTime(hStr, mStr.ifEmpty { "00" }, meridiem) ?: return@firstNotNullOfOrNull null
            val wallZone = zoneName.takeIf { it.isNotEmpty() }
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            val today = now.atZone(wallZone).with(time)
            val todayInstant = today.toInstant()
            when {
                !todayInstant.isBefore(now) -> todayInstant
                Duration.between(todayInstant, now) <= RECENTLY_PASSED -> todayInstant
                else -> today.plusDays(1).toInstant()
            }
        }

        /** A 12-hour clock reading as a time of day, or null when it is no such time. */
        private fun clockTime(hours: String, minutes: String, meridiem: String): LocalTime? {
            val rawHour = hours.toIntOrNull() ?: return null
            val minute = minutes.toIntOrNull() ?: return null
            if (rawHour !in 1..12 || minute !in 0..59) return null
            val hour = when {
                meridiem.equals("am", ignoreCase = true) -> if (rawHour == 12) 0 else rawHour
                else -> if (rawHour == 12) 12 else rawHour + 12
            }
            return LocalTime.of(hour, minute)
        }

        /** "resets 10:10am", "resets 3pm", each optionally followed by "(Area/City)". */
        private val RESETS_AT = Regex(
            """\bresets\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b(?:\s*\(([A-Za-z0-9_+\-]+(?:/[A-Za-z0-9_+\-]+)*)\))?""",
            RegexOption.IGNORE_CASE,
        )

        /** How long after its time a wall's reset still counts as the one just gone, not tomorrow's. */
        private val RECENTLY_PASSED: Duration = Duration.ofHours(12)

        /** The countdown's shape. Every part is optional; [resetsInFrom] rejects a match with none. */
        private val RESETS_IN = Regex(
            """\bresets?\s+in\s+(?:(\d{1,3})h)?(?:(\d{1,3})m)?(?:(\d{1,3})s)?""",
            RegexOption.IGNORE_CASE,
        )

        /** Clock time when Codex allows retry after a usage limit wall. */
        private val TRY_AGAIN_AT = Regex(
            """\btry\s+again\s+at\s+(\d{1,2}):(\d{2})\s*(am|pm)\b""",
            RegexOption.IGNORE_CASE,
        )

        private val CLOCK_DRIFT_TOLERANCE: Duration = Duration.ofMinutes(1)

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
