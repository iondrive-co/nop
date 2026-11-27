package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.Path

/** A fixed reading copy of the conversation, retaining only what its participants said. */
data class ConversationSummary(
    val messages: List<Message>,
    val capturedAt: Long,
    val runStarts: List<Int> = listOf(0),
) {
    data class Message(val speaker: String, val text: String, val assistant: Boolean, val at: Long)

    fun messagesForRun(index: Int): List<Message> =
        messages.subList(runStarts[index], runStarts.getOrElse(index + 1) { messages.size })

    fun plainText(runIndex: Int? = null): String =
        (runIndex?.let(::messagesForRun) ?: messages).joinToString("\n\n") { "${it.speaker}:\n${it.text}" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val contextOnly = Regex(
            """^<(environment_context|user_instructions|local-command-caveat|local-command-stdout|command-name)>[\s\S]*</\1>$""",
        )

        /** Reads only the bytes present on opening, so an active writer cannot extend the snapshot. */
        fun read(file: Path): ConversationSummary = readEvents(file) { fromEvents(it) }

        /** Command copying needs assistant text, without retaining the rest of a long conversation. */
        internal fun forEachAssistantText(file: Path, consume: (String) -> Unit) {
            readEvents(file) { events ->
                for (event in events) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (event is AgentEvent.AssistantMessage) {
                        val text = event.blocks.filterIsInstance<Block.Text>()
                            .map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
                        if (text.isNotBlank()) consume(text)
                    }
                }
            }
        }

        private fun <T> readEvents(file: Path, consume: (Sequence<AgentEvent>) -> T): T {
            Files.newInputStream(file).use { input ->
                val length = Files.size(file)
                val bounded = object : FilterInputStream(input) {
                    var remaining = length
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        return super.read().also { if (it >= 0) remaining-- }
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        return `in`.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
                            .also { if (it > 0) remaining -= it }
                    }
                }
                return bounded.bufferedReader().useLines { lines ->
                    consume(lines.mapNotNull { line ->
                        runCatching { json.decodeFromString(AgentEvent.serializer(), line) }.getOrNull()
                    })
                }
            }
        }

        internal fun fromEvents(
            events: Sequence<AgentEvent>,
            capturedAt: Long = System.currentTimeMillis(),
        ): ConversationSummary {
            val messages = mutableListOf<Message>()
            val runStarts = mutableListOf(0)
            var run: AgentEvent.RunStarted? = null
            var launchedAt: Long? = null
            var awaitingHandoffSeed = false
            for (event in events) {
                when (event) {
                    is AgentEvent.RunStarted -> {
                        val previous = run
                        val launched = event.transcriptPath == null && event.at != launchedAt
                        // Locating the first transcript updates a run. A subsequent transcript
                        // change can start a new conversation inside the same CLI process.
                        val changedTranscript = previous?.transcriptPath != null && event.transcriptPath != null &&
                            (previous.transcriptPath != event.transcriptPath || previous.nativeSessionId != event.nativeSessionId)
                        val changedAccount = previous != null &&
                            (previous.provider != event.provider || previous.account != event.account)
                        val startsRun = launched || changedTranscript || changedAccount
                        if (startsRun && messages.size > runStarts.last()) runStarts += messages.size
                        if (previous == null || startsRun) awaitingHandoffSeed = event.seededFromHandoff
                        if (event.transcriptPath == null) launchedAt = event.at
                        run = event
                    }
                    is AgentEvent.UserMessage -> {
                        val text = event.text.trim()
                        if (text.isBlank() || contextOnly.matches(text) || text.startsWith("# AGENTS.md instructions for ")) continue
                        val handoffSeed = awaitingHandoffSeed && text.startsWith(Handoff.SEED_START)
                        awaitingHandoffSeed = false
                        if (handoffSeed) continue
                        val header = event.text.substringBefore('\n')
                        if (header.startsWith(AgentMessages.ENVELOPE_START) && header.endsWith("]")) {
                            val sender = header.removePrefix(AgentMessages.ENVELOPE_START)
                                .substringBefore(", not the user")
                            val body = event.text.substringAfter('\n', "").trimStart('\n')
                            messages += Message("From $sender", body, false, event.at)
                        } else {
                            messages += Message("You", event.text, false, event.at)
                        }
                    }
                    is AgentEvent.AssistantMessage -> {
                        val text = event.blocks.filterIsInstance<Block.Text>()
                            .map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
                        if (text.isNotBlank()) {
                            messages += Message(run?.let { "Agent · ${it.account}" } ?: "Agent", text, true, event.at)
                        }
                    }
                    else -> Unit
                }
            }
            // A run can be opened before it has any prose to show.
            if (runStarts.size > 1 && runStarts.last() == messages.size) runStarts.removeLast()
            return ConversationSummary(messages.toList(), capturedAt, runStarts.toList())
        }
    }
}

/** Reading state belongs to the tab, including while another tool panel is in front. */
class ConversationSummaryState {
    var shown by mutableStateOf(false)
        private set
    var revision by mutableStateOf(0)
        private set
    var snapshot: ConversationSummary? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    private var runsBack by mutableStateOf(0)
    val runIndex: Int get() = (snapshot?.runStarts?.lastIndex ?: 0) - runsBack
    var scrollIndex = 0
    var scrollOffset = 0

    fun showRun(index: Int) {
        val current = snapshot ?: return
        if (index !in current.runStarts.indices || index == runIndex) return
        scrollIndex = current.messagesForRun(index).lastIndex.coerceAtLeast(0)
        scrollOffset = 0
        runsBack = current.runStarts.lastIndex - index
    }

    fun toggle() {
        shown = !shown
        if (shown) refresh()
        else {
            snapshot = null
            error = null
        }
    }

    fun refresh() {
        snapshot = null
        error = null
        runsBack = 0
        scrollIndex = 0
        scrollOffset = 0
        revision++
    }
}
