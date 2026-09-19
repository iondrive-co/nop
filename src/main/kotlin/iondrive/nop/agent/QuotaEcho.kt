package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Whether a limit phrase on the screen is one the *agent* put there.
 *
 * This is the provider-independent half of deciding a quota wall, and it rests on nop already
 * reading two separate channels. [QuotaWatcher] reads the screen, which carries everything — the
 * vendor's chrome, the file the agent just wrote, the diff it is showing, the whole conversation
 * being redrawn on a resume. The tailers read the CLI's own transcript, which carries what was
 * actually said and done.
 *
 * That difference is the test. A limit phrase the agent produced is conversation by construction —
 * it arrived as a tool result, a file it wrote, or a message either party sent — so it is in the
 * transcript as one of those. Finding it there says the screen was showing something, not refusing
 * something.
 *
 * Two versions of this got the vendor's side wrong, and each cost every real wall of some session.
 *
 * The first assumed a vendor announcing it will not continue writes that to its UI alone, because a
 * refusal is not a turn and so never becomes conversation. Claude Code does not work that way. It
 * files its own wall in the very transcript this reads, twice over — an `assistant` record flagged
 * `isApiErrorMessage` with the 429's text ("You've hit your session limit · resets 8:10pm"), and a
 * `system` notice for the banner under it ("Usage limit reached · continuing automatically at
 * 8:10pm") — so reading the transcript as flat text found the phrase on every genuine Claude wall
 * and vetoed it.
 *
 * The second skipped those records and still asked only whether the phrase was anywhere else in the
 * conversation. It usually is, in exactly the sessions that need the handover most. A session nop
 * handed over reads a handoff whose last line is the previous account's wall, word for word, so its
 * own wall is vetoed as an echo of that one; a user who pastes a wall to ask about it has done the
 * same by hand. The phrase being in the conversation says the agent *could* be showing it, not that
 * the vendor did not say it.
 *
 * So the vendor's own record is now positive evidence, and it outranks any echo: a refusal the CLI
 * filed during this run, moments ago, is the vendor saying it — see [Verdict.Refused]. What remains
 * of the guard is the case with no such record, where the phrase being in the conversation still
 * vetoes, and costs a missed wall only when a provider files nothing nop recognises. That is the
 * right way round to be wrong. A miss ends the run at the vendor's own error and the user picks it
 * up — the same thing that happened before any of this existed. A false positive kills a working
 * session mid-turn, and, because the text that triggered it is in the conversation, kills it again
 * on every resume until the session cannot be re-entered at all. There is no undo for that; there
 * is for this.
 *
 * One kind of echo outranks even the account's own usage reading: the agent's own reply, written
 * moments ago — see [Verdict.Said]. A reading at 99% says a wall is believable, not that the text on
 * screen is one, and a session that has just finished explaining a rate limit is not out of quota.
 *
 * A provider with no transcript nop can read is [Verdict.Unrecorded] here and is decided exactly as
 * it was before — see [AgentSession.onQuotaWall], which has the account's own usage reading as well.
 */
object QuotaEcho {

    /** What the transcript makes of a limit phrase the screen has just shown. */
    enum class Verdict {
        /**
         * The CLI filed a refusal of its own during this run, moments ago. The vendor said it,
         * whatever the conversation also happens to quote.
         */
        Refused,

        /**
         * No fresh refusal, and the agent itself wrote the phrase moments ago, in a reply of this
         * run. A refused request produces no reply at all, so the words on screen are the agent's —
         * the 18:59 handover in hermes was of a session that had just answered a question about a
         * 429 "too many requests" from an exchange, and had finished its turn doing so.
         */
        Said,

        /**
         * No fresh refusal, and the phrase is in the transcript anyway: in the conversation, or in
         * a refusal from before this run that a resume is redrawing. The screen was showing it.
         */
        Shown,

        /** Nothing in the transcript speaks to it either way, or there is no transcript to read. */
        Unrecorded,
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * What [transcript] says about [phrase], for a run that started at [since].
     *
     * Line by line, because in these transcripts a line is a record: whether a phrase is the agent's
     * own or the vendor's is a property of the record carrying it, and flattening the file first
     * threw that away — along with the record boundaries, so a needle could be assembled from two
     * unrelated ones.
     *
     * A refusal only counts as the vendor speaking while it is fresh: written since this run
     * started, and within [FRESH] of [now]. The first bound is what keeps a resume safe — the
     * conversation it redraws includes the wall that ended the last run, filed under that run's
     * time, and an account that has since reset must not be walked straight back out of it. The
     * second keeps a wall this run already weathered — Claude Code waits out a reset in place and
     * carries on — from confirming something shown hours later. A real wall needs neither
     * allowance: the record lands within milliseconds of the words on screen, and the watcher
     * catches them again on the next redraw if it was a moment early.
     *
     * Both sides of the phrase comparison are flattened to lower case with runs of whitespace
     * collapsed, because the screen's copy has been through a TUI: it may have been wrapped, padded
     * to a column, or split by a colour change that [QuotaWatcher] stripped and left a gap behind.
     * The transcript's copy is clean text. Collapsing both is what lets the two be compared at all.
     */
    fun judge(
        transcript: Path?,
        phrase: String,
        since: Instant,
        now: Instant = Instant.now(),
        maxRead: Long = MAX_READ,
    ): Verdict {
        val text = transcript?.let { read(it, maxRead) } ?: return Verdict.Unrecorded
        // Nothing to look for is not evidence of anything. An empty phrase would match every
        // transcript and veto every wall — but it cannot hide a refusal, so the scan still runs.
        val needle = flatten(phrase).takeIf { it.isNotBlank() }
        val freshFrom = maxOf(since, now.minus(FRESH))
        var said = false
        var shown = false
        for (line in text.lineSequence()) {
            val refusedAt = refusal(line)
            if (refusedAt != null && !refusedAt.isBefore(freshFrom)) return Verdict.Refused
            if (needle == null || said || !flatten(line).contains(needle)) continue
            shown = true
            val reply = reply(line) ?: continue
            if (!reply.first.isBefore(freshFrom) && flatten(reply.second).contains(needle)) said = true
        }
        return when {
            said -> Verdict.Said
            shown -> Verdict.Shown
            else -> Verdict.Unrecorded
        }
    }

    /**
     * When the agent wrote [line] as a reply of its own, and the text of it; null for anything else.
     *
     * Claude's `assistant` records, less the ones flagged `isApiErrorMessage` (the vendor wearing the
     * assistant's role — see [refusal]), and Codex's `agent_message` events and `assistant` messages.
     * Only the text: a tool call's input is the agent handling something, which is [Verdict.Shown]'s
     * territory. Antigravity files no replies nop can read, so it never gets this far.
     */
    private fun reply(line: String): Pair<Instant, String>? {
        val record = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull().obj() ?: return null
        val at = record["timestamp"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val payload = record["payload"].obj()
        val text = when (record["type"].str()) {
            "assistant" ->
                if (record["isApiErrorMessage"].bool()) null else texts(record["message"].obj()?.get("content"))
            "event_msg" -> if (payload?.get("type").str() == "agent_message") payload?.get("message").str() else null
            "response_item" ->
                if (payload?.get("type").str() == "message" && payload?.get("role").str() == "assistant") {
                    texts(payload?.get("content"))
                } else {
                    null
                }
            else -> null
        }
        return text?.let { at to it }
    }

    /** The `text` of each block in a message's content list, joined. */
    private fun texts(content: JsonElement?): String? =
        content.arr()?.mapNotNull { it.obj()?.get("text").str() }?.joinToString("\n")

    /**
     * When the CLI filed [line] as its own account of a refusal, or null when it is anything else.
     *
     * Two shapes, both Claude Code's, both written by the CLI about itself:
     *
     * - `isApiErrorMessage` — the request that was refused, kept in the transcript wearing the
     *   assistant's role so the TUI can redraw it. The model did not say it; the 429 did.
     * - `type: "system"` — the CLI's own notices, the "continuing automatically at 8:10pm" banner
     *   and a `/compact` that failed for want of quota among them. Nothing here is a turn by either
     *   party.
     *
     * Either one only counts when it carries a limit phrase, since both shapes carry plenty that is
     * not a wall — an overloaded API, a turn's duration — and only with a timestamp that parses,
     * since a refusal that cannot be placed in time cannot be told from one a resume is redrawing.
     * Anything else is treated as conversation, which is what keeps the guard conservative. So is a
     * line that is not JSON at all — a partial record at the head of the tail that was read, or a
     * provider whose transcript is not line-delimited.
     */
    private fun refusal(line: String): Instant? {
        // A substring test first. This runs over every line of up to [MAX_READ] on the thread that
        // noticed the wall, and all but a handful of those lines are conversation.
        if ("\"isApiErrorMessage\"" !in line && "\"system\"" !in line) return null
        val record: JsonObject = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull().obj()
            ?: return null
        if (!record["isApiErrorMessage"].bool() && record["type"].str() != "system") return null
        if (QuotaWatcher.limitPhraseIn(line) == null) return null
        return record["timestamp"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    /**
     * The transcript's text, or null when it cannot be read.
     *
     * Capped, and from the end. A long session's transcript runs to several megabytes and this is
     * read on the thread that noticed the wall; the tail is also where a resume's replay and the
     * agent's recent work both are. Failure is null rather than a throw: this is a guard on a path
     * whose entire job is not to disturb a running session, and a transcript that has just been
     * rotated or half-written must not be the thing that takes one down.
     */
    private fun read(path: Path, maxRead: Long): String? = runCatching {
        val size = Files.size(path)
        if (size <= maxRead) return@runCatching Files.readString(path)
        Files.newByteChannel(path).use { channel ->
            channel.position(size - maxRead)
            val buffer = java.nio.ByteBuffer.allocate(maxRead.toInt())
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            buffer.flip()
            String(ByteArray(buffer.remaining()).also { buffer.get(it) }, StandardCharsets.UTF_8)
        }
    }.onFailure { Log.warn("could not read the transcript to check a limit phrase: $it") }.getOrNull()

    private fun flatten(text: String): String = WHITESPACE.replace(text, " ").lowercase()

    private val WHITESPACE = Regex("""\s+""")

    /**
     * How long a refusal the CLI filed goes on counting as the vendor speaking. Minutes, not
     * seconds: generous against a transcript flushed a little late, and still far too short for
     * one wall to vouch for text shown after the account has reset.
     */
    val FRESH: Duration = Duration.ofMinutes(2)

    /**
     * How much of the transcript's tail is searched. Comfortably more than a resume replays.
     *
     * Overridable only so its own test can exercise the seek without writing eight megabytes to do
     * it; nothing in the app passes anything but the default.
     */
    const val MAX_READ: Long = 8L * 1024 * 1024
}
