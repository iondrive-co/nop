package iondrive.nop.agent

/**
 * What an agent tab is doing, as far as nop can tell without reading its screen.
 *
 * It exists because of a night a tab spent on a question. A Claude session in hermes asked for two
 * rulings at 21:03 and sat on the answer for eleven and three-quarter hours while another tab was
 * selected; in the morning it looked exactly like a tab that was working, because nothing in the strip
 * said otherwise. A tab blocked on its user and a tab busy on its own are opposite situations, and
 * the strip is the one place the user looks that can tell them apart without being opened.
 */
enum class Activity {
    /**
     * Put back from the state file and not yet looked at. The CLI behind it has not been started — a
     * restored tab runs nothing until it is opened (see [AgentSessions.restore]) — so it is doing
     * nothing at all, which is worth saying rather than leaving it looking like one that is.
     */
    Asleep,

    /** Running, and nothing it has said so far says doing what. Every Antigravity tab, for now. */
    Running,

    /** In the middle of a turn: the CLI's own spinner is going. */
    Working,

    /**
     * Stopped part-way through a turn, holding a question — AskUserQuestion, a plan waiting to be
     * approved, a Codex "Action Required". Nothing happens until somebody answers, however long that
     * takes, which is the reason this state exists.
     */
    Asking,

    /** Finished its turn. The next move is the user's. */
    Idle,

    /** The CLI has exited. */
    Ended,
}

/**
 * Reads the terminal title out of a CLI's output: OSC 0 and OSC 2, `ESC ] 0 ; text BEL`.
 *
 * The title is where both vendors say whether they are working — see [TitleSignal] — and it is on its
 * way to the terminal anyway, so reading it costs nothing the tab is not already paying. Fed straight
 * off the PTY, which hands over whatever arrived in one read, so a sequence split across two reads is
 * held until its end turns up. Anything else is skipped at the cost of a scan for ESC.
 */
class TitleReader {
    /** A title sequence whose end has not arrived yet, from `ESC ]` on. Empty when none is open. */
    private val pending = StringBuilder()

    /** The last complete title in [text] (and whatever was held over from before it), or null. */
    fun feed(text: String): String? {
        var title: String? = null
        var i = 0
        if (pending.isNotEmpty()) {
            val end = terminator(text, 0, pending)
            if (end == null) {
                pending.append(text)
                // A title is a line of text. One that has not ended in this long never will, and
                // holding on to it would keep every byte after it out of the reader.
                if (pending.length > MAX_PENDING) pending.setLength(0)
                return null
            }
            pending.append(text, 0, end.first)
            title = parse(pending)
            pending.setLength(0)
            i = end.second
        }
        while (true) {
            val start = text.indexOf(OSC, i)
            if (start < 0) break
            val end = terminator(text, start + OSC.length, null)
            if (end == null) {
                pending.append(text, start, text.length)
                break
            }
            parse(text.subSequence(start, end.first))?.let { title = it }
            i = end.second
        }
        return title
    }

    /**
     * Where the sequence opened before [from] ends: the index its text stops at, and the index just
     * past its terminator. Null when it does not end in [text].
     *
     * An ESC split from its backslash by a read boundary is found through [held]: the held text
     * ending in ESC and this chunk starting with `\` is the two-character terminator straddling the
     * two, and the ESC is dropped from what was held.
     */
    private fun terminator(text: String, from: Int, held: StringBuilder?): Pair<Int, Int>? {
        if (held != null && held.endsWith(ESC) && text.startsWith('\\')) {
            held.setLength(held.length - 1)
            return 0 to 1
        }
        for (j in from until text.length) {
            when (text[j]) {
                BEL -> return j to j + 1
                ESC -> if (j + 1 < text.length && text[j + 1] == '\\') return j to j + 2
            }
        }
        return null
    }

    /** The text of one `ESC ] n ; text` when n says title, or null for any other OSC (links, colours). */
    private fun parse(sequence: CharSequence): String? {
        val body = sequence.removePrefix(OSC)
        val semi = body.indexOf(';')
        if (semi < 0) return null
        return when (body.substring(0, semi)) {
            "0", "2" -> body.substring(semi + 1)
            else -> null
        }
    }

    private companion object {
        const val ESC = '\u001b'
        const val BEL = '\u0007'
        const val OSC = "\u001b]"
        const val MAX_PENDING = 4096
    }
}

/**
 * What a terminal title says about the CLI that wrote it.
 *
 * The same reading clio makes of the same CLIs, and for the same reason: the title is the only
 * thing a vendor says out loud that tells working from stopped. Claude Code puts a spinner in front
 * of it while it works (`◐ Plan 36 review`, a new frame about twice a second) and a still glyph when
 * it stops (`✳ Plan 36 review`). Codex spins the braille frames, since 0.154 sometimes inside
 * brackets (`[ ⠹ ] Working | ops`), and says so outright when it is blocked on the person at the
 * keyboard (`[ ! ] Action Required`).
 *
 * Which glyphs these are is nobody's promise, so a title that is neither a spinner nor a known
 * stopped form is not taken to mean anything until it has stopped changing — see [STILL_MS]. A
 * spinner nobody here has heard of goes on rewriting the title, which is never still, so the tab
 * says nothing rather than claiming a working agent has stopped.
 */
object TitleSignal {
    enum class Says { Working, Blocked, Stopped }

    /** Claude Code's and Codex's spinner frames. */
    private val SPINNERS: Set<Int> = "◐◑◒◓⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".codePoints().toArray().toSet()

    /** Claude Code's stopped glyph. A title that starts with it says so at once. */
    private val STOPPED: Set<Int> = "✳".codePoints().toArray().toSet()

    /** How far into the title a spinner frame may sit — Codex's `[ ⠹ ]` puts it third. */
    private const val SPINNER_WITHIN = 4

    /** Codex blinking between `[ ! ]` and `[ . ]` in front of this while it waits on the user. */
    private val BLOCKED = Regex("""\[\s*[!.]\s*]\s*Action Required""", RegexOption.IGNORE_CASE)

    /**
     * How long a title has to sit unchanged before it counts as stopped when it does not say so.
     * Spinner frames arrive about every half second, so three of those.
     */
    const val STILL_MS: Long = 1500

    /** What [title] says by itself, or null when only its standing still can say anything. */
    fun read(title: String): Says? {
        val head = title.codePoints().limit(SPINNER_WITHIN.toLong()).toArray()
        if (head.any { it in SPINNERS }) return Says.Working
        if (BLOCKED.containsMatchIn(title)) return Says.Blocked
        if (head.firstOrNull() in STOPPED) return Says.Stopped
        return null
    }
}

/**
 * Works out a running tab's [Activity] from the two things nop hears from its CLI: the terminal
 * title and the transcript.
 *
 * The title says working or stopped and nothing more — the end of a turn and a question held in
 * front of the user look identical there. The transcript tells those two apart: a question is a tool
 * call the CLI has made and not got an answer to. So a stopped title with a call still open in the
 * turn is [Activity.Asking], and one with every call answered is [Activity.Idle]. That is only
 * sound because every CLI nop starts runs with its permission prompts turned off, which leaves an
 * open call nothing to wait on but the user.
 *
 * A question tool is [Activity.Asking] whatever the title says, and before it has said anything:
 * the call being open is the question, and it is in the transcript from the moment it is asked.
 *
 * Not thread-safe by itself; [AgentSession] feeds it from the PTY and tailer threads under its own
 * lock.
 */
class ActivityTracker {
    private var said: TitleSignal.Says? = null
    private var title: String? = null
    private var titleAt: Long = 0

    /**
     * What the tab was doing just before the current title arrived — the answer while that title has
     * not had time to mean anything. Codex's stopped title carries no glyph, so without this a tab
     * would read as [Activity.Running] for the second and a half between its last spinner frame and
     * [Activity.Idle].
     */
    private var before: Activity = Activity.Running

    /** Tool calls made in the current turn and not yet answered, by call id, with the tool's name. */
    private val open = LinkedHashMap<String, String>()

    fun onTitle(text: String, at: Long) {
        // The same title again is not news, and must not restart the wait for stillness — Claude
        // Code rewrites a stopped title now and then without anything having changed.
        if (text == title) return
        before = activity(at)
        title = text
        titleAt = at
        said = TitleSignal.read(text)
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            // A new prompt starts a new turn. Anything still open from the last one was interrupted
            // or lost to the tailer, and is not a question anybody is being asked now.
            is AgentEvent.UserMessage -> open.clear()
            is AgentEvent.ToolStarted -> open[event.callId] = event.tool
            is AgentEvent.ToolFinished -> open.remove(event.callId)
            else -> Unit
        }
    }

    /** What the tab is doing at [now], for a run that is alive. */
    fun activity(now: Long): Activity {
        if (open.values.any { it in QUESTION_TOOLS }) return Activity.Asking
        return when (said) {
            TitleSignal.Says.Working -> Activity.Working
            TitleSignal.Says.Blocked -> Activity.Asking
            TitleSignal.Says.Stopped -> stopped()
            null -> when {
                title == null -> Activity.Running
                now - titleAt >= TitleSignal.STILL_MS -> stopped()
                else -> before
            }
        }
    }

    /** When the next [activity] could differ with nothing new arriving — a title settling — or null. */
    fun settlesAt(): Long? = if (said == null && title != null) titleAt + TitleSignal.STILL_MS else null

    private fun stopped(): Activity = if (open.isEmpty()) Activity.Idle else Activity.Asking

    companion object {
        /**
         * The tools whose whole job is to wait for the user: Claude Code's question and plan approval,
         * and Codex's request for input.
         */
        val QUESTION_TOOLS: Set<String> = setOf("AskUserQuestion", "ExitPlanMode", "request_user_input")
    }
}
