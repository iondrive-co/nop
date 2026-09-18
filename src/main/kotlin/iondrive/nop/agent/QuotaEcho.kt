package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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
 * What the first version of this got wrong was assuming the other half followed: that a vendor
 * announcing it will not continue writes that to its UI alone, because a refusal is not a turn and
 * so never becomes conversation. Claude Code does not work that way. It files its own wall in the
 * very transcript this reads, twice over — an `assistant` record flagged `isApiErrorMessage` with
 * the 429's text ("You've hit your session limit · resets 8:10pm"), and a `system` notice for the
 * banner under it ("Usage limit reached · continuing automatically at 8:10pm"). Both are written
 * before the same words finish arriving on screen, so reading the transcript as flat text found the
 * phrase every single time and vetoed every genuine Claude wall. The handover that is the whole
 * point of the feature could never fire. See [isVendorsOwn] for what is skipped now.
 *
 * It still costs a missed wall in one case: a session that has genuinely discussed limit messages,
 * and then genuinely hits one, has the phrase in its conversation either way and is not acted on.
 * That is the right way round to be wrong. A miss ends the run at the vendor's own error and the
 * user picks it up — the same thing that happened before any of this existed. A false positive kills
 * a working session mid-turn, and, because the text that triggered it is in the conversation, kills
 * it again on every resume until the session cannot be re-entered at all. There is no undo for that;
 * there is for this.
 *
 * A provider with no transcript nop can read answers false here and is decided exactly as it was
 * before — see [AgentSession.onQuotaWall], which has the account's own usage reading to fall back on.
 */
object QuotaEcho {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether [phrase] appears in [transcript] as something the agent was showing.
     *
     * Line by line, because in these transcripts a line is a record: whether a phrase is the agent's
     * own or the vendor's is a property of the record carrying it, and flattening the file first
     * threw that away — along with the record boundaries, so a needle could be assembled from two
     * unrelated ones.
     *
     * Both sides are then flattened to lower case with runs of whitespace collapsed, because the
     * screen's copy has been through a TUI: it may have been wrapped, padded to a column, or split
     * by a colour change that [QuotaWatcher] stripped and left a gap behind. The transcript's copy is
     * clean text. Collapsing both is what lets the two be compared at all.
     */
    fun isEchoed(transcript: Path?, phrase: String, maxRead: Long = MAX_READ): Boolean {
        val needle = flatten(phrase)
        // Nothing to look for is not evidence of anything. An empty phrase would match every
        // transcript and veto every wall.
        if (transcript == null || needle.isBlank()) return false
        val text = read(transcript, maxRead) ?: return false
        return text.lineSequence().any { line ->
            flatten(line).contains(needle) && !isVendorsOwn(line)
        }
    }

    /**
     * Whether a record is the CLI's own account of hitting the wall rather than conversation.
     *
     * Two shapes, both Claude Code's, both written by the CLI about itself:
     *
     * - `isApiErrorMessage` — the request that was refused, kept in the transcript wearing the
     *   assistant's role so the TUI can redraw it. The model did not say it; the 429 did.
     * - `type: "system"` — the CLI's own notices, the "continuing automatically at 8:10pm" banner
     *   among them. Nothing here is a turn by either party.
     *
     * Anything else is treated as conversation, which is what keeps the guard conservative. A line
     * that is not JSON at all — a partial record at the head of the tail that was read, a provider
     * whose transcript is not line-delimited — is left exactly as it was before this existed: found,
     * and the wall vetoed.
     */
    private fun isVendorsOwn(line: String): Boolean {
        val record = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull().obj() ?: return false
        return record["isApiErrorMessage"].bool() || record["type"].str() == "system"
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
     * How much of the transcript's tail is searched. Comfortably more than a resume replays.
     *
     * Overridable only so its own test can exercise the seek without writing eight megabytes to do
     * it; nothing in the app passes anything but the default.
     */
    const val MAX_READ: Long = 8L * 1024 * 1024
}
