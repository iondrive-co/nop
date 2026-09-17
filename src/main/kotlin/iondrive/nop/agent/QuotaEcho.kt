package iondrive.nop.agent

import iondrive.nop.Log
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether a limit phrase on the screen is one the *agent* put there.
 *
 * This is the provider-independent half of deciding a quota wall, and it rests on nop already
 * reading two separate channels. [QuotaWatcher] reads the screen, which carries everything — the
 * vendor's chrome, the file the agent just wrote, the diff it is showing, the whole conversation
 * being redrawn on a resume. The tailers read the CLI's own transcript, which carries only what was
 * actually said and done.
 *
 * That difference is the test. A vendor announcing it will not continue writes that to its UI; it
 * is not a turn, so it does not become conversation. A limit phrase the agent produced is conversation
 * by construction — it arrived as a tool result, a file it wrote, or a message either party sent — so
 * it is in the transcript. Finding the phrase there says the screen was showing something, not
 * refusing something.
 *
 * It costs a missed wall in one case: a session that has genuinely discussed limit messages, and then
 * genuinely hits one, has the phrase in its transcript either way and is not acted on. That is the
 * right way round to be wrong. A miss ends the run at the vendor's own error and the user picks it up
 * — the same thing that happened before any of this existed. A false positive kills a working session
 * mid-turn, and, because the text that triggered it is in the conversation, kills it again on every
 * resume until the session cannot be re-entered at all. There is no undo for that; there is for this.
 *
 * A provider with no transcript nop can read answers false here and is decided exactly as it was
 * before — see [AgentSession.onQuotaWall], which has the account's own usage reading to fall back on.
 */
object QuotaEcho {

    /**
     * Whether [phrase] appears in [transcript].
     *
     * Both sides are flattened to lower case with runs of whitespace collapsed, because the screen's
     * copy has been through a TUI: it may have been wrapped, padded to a column, or split by a colour
     * change that [QuotaWatcher] stripped and left a gap behind. The transcript's copy is clean text.
     * Collapsing both is what lets the two be compared at all.
     */
    fun isEchoed(transcript: Path?, phrase: String, maxRead: Long = MAX_READ): Boolean {
        val needle = flatten(phrase)
        // Nothing to look for is not evidence of anything. An empty phrase would match every
        // transcript and veto every wall.
        if (transcript == null || needle.isBlank()) return false
        val text = read(transcript, maxRead) ?: return false
        return flatten(text).contains(needle)
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
