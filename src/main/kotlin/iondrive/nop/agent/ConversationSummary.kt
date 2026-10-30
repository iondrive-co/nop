package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.Path

/** A fixed reading copy of the conversation, retaining only what its participants said. */
data class ConversationSummary(val messages: List<Message>, val capturedAt: Long) {
    data class Message(val speaker: String, val text: String, val assistant: Boolean, val at: Long)

    fun plainText(): String = messages.joinToString("\n\n") { "${it.speaker}:\n${it.text}" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val contextOnly = Regex(
            """^<(environment_context|user_instructions|local-command-caveat|local-command-stdout|command-name)>[\s\S]*</\1>$""",
        )

        /** Reads only the bytes present on opening, so an active writer cannot extend the snapshot. */
        fun read(file: Path): ConversationSummary {
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
                    fromEvents(lines.mapNotNull { line ->
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
            var account: String? = null
            var awaitingHandoffSeed = false
            for (event in events) {
                when (event) {
                    is AgentEvent.RunStarted -> {
                        account = event.account
                        awaitingHandoffSeed = event.seededFromHandoff
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
                            messages += Message(account?.let { "Agent · $it" } ?: "Agent", text, true, event.at)
                        }
                    }
                    else -> Unit
                }
            }
            return ConversationSummary(messages.toList(), capturedAt)
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
    var scrollIndex = 0
    var scrollOffset = 0

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
        scrollIndex = 0
        scrollOffset = 0
        revision++
    }
}
