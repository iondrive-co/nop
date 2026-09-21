package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Antigravity
import iondrive.nop.agent.long
import iondrive.nop.agent.obj
import iondrive.nop.agent.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads as much of an Antigravity session as `agy` leaves in a form anything can read.
 *
 * Which is not much, and the reason is worth stating where the consequences are. The CLI keeps each
 * conversation in a SQLite database of its own — `conversations/<id>.db` — whose `steps` rows carry
 * the turn itself as a **protobuf blob** against Google's internal schema. Nothing short of
 * reverse-engineering that schema, and re-doing it at every CLI update, turns those into the
 * assistant text, tool calls and token counts the other two tailers hand back. That is a
 * standing cost against one provider, and a wrong answer decoded out of a changed schema would be
 * worse than no answer at all.
 *
 * What is readable is `history.jsonl`: one line per prompt the user submits, carrying the text, the
 * time, the workspace it was typed in and the conversation it went to. So this tailer produces
 * [AgentEvent.UserMessage] and nothing else, and the transcript panel shows a session's prompts
 * with the replies missing. Everything else about an antigravity run comes from the terminal, which
 * is showing the whole TUI anyway — including the screen tail that a handoff falls back to when a
 * transcript cannot be read (see [iondrive.nop.agent.Handoff]).
 *
 * The one thing it has to get exactly right is the conversation id, because that is what `--conversation`
 * resumes and what a restored tab comes back into. Two places carry it and both are used: the
 * history line for this run, and `cache/last_conversations.json`, which maps a workspace to the
 * conversation last opened there and is written when the CLI opens one rather than when the user
 * types. A session resumed and closed without a word appears only in the second.
 *
 * One known limit, left alone deliberately: `history.jsonl` is a single file shared by every run in
 * every project, so a `/clear` inside the TUI moves the CLI to a new conversation without moving
 * the file. [switched] is what the other tailers use to follow that, and following it here would
 * mean re-reading a shared file from the top and emitting this run's prompts a second time. nop
 * keeps the id the run started in instead, which is stale after a `/clear` and correct otherwise.
 */
class AntigravityTailer(private val home: Path) : Tailer {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var conversationId: String? = null

    @Volatile
    private var titledConversationId: String? = null

    /** The run being followed, kept so [parse] can tell this session's prompts from the file's. */
    @Volatile
    private var context: RunContext? = null

    /** Size of the history file at the last scan, so a quiet file is not re-read every 250ms. */
    private var scannedAt: Long = -1

    override fun nativeSessionId(): String? = conversationId

    override fun poll(): List<AgentEvent> {
        val id = conversationId ?: return emptyList()
        if (id == titledConversationId) return emptyList()

        val title = Antigravity.conversationTitle(home, id) ?: return emptyList()
        titledConversationId = id
        return listOf(AgentEvent.SessionTitled(title, System.currentTimeMillis()))
    }

    /**
     * The history file, once this run has a conversation to put in it.
     *
     * Gated rather than returned on sight, because the file is there from the last session in any
     * project: handing it over immediately would record a transcript path for a run that has not
     * started a conversation, and `EventLog` reads exactly that as "there is something to resume".
     */
    override fun locate(run: RunContext): Path? {
        context = run
        val id = conversationFromHistory(run) ?: conversationFromCache(run) ?: return null
        conversationId = id
        return Antigravity.historyFile(home)
    }

    /**
     * One history line, kept only if it belongs to this run.
     *
     * Both halves of that matter. The file is shared across projects, so the workspace has to
     * match; and it is shared across time, so a line older than the spawn is a prompt from a
     * previous session, which would otherwise arrive in this session's log as though it had just
     * been typed.
     */
    override fun parse(line: String): List<AgentEvent> {
        val run = context ?: return emptyList()
        val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        if (!isOurs(record, run)) return emptyList()

        // Fresher than whatever located the run, and the only signal that sees a conversation the
        // CLI moved to while nop was following it.
        record["conversationId"].str()?.takeIf { !run.foreign(it) }?.let { conversationId = it }

        // `/exit`, `/usage` and the rest are commands to the CLI, not messages to a model. Logging
        // them as user messages would put them in a handoff summary as things the user asked for.
        if (record["type"].str() == "slash_command") return emptyList()

        val text = record["display"].str()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(AgentEvent.UserMessage(text, record["timestamp"].long() ?: System.currentTimeMillis()))
    }

    private fun isOurs(record: kotlinx.serialization.json.JsonObject, run: RunContext): Boolean {
        val at = record["timestamp"].long() ?: return false
        if (at < run.startedAt - CLOCK_SLACK_MS) return false
        val workspace = record["workspace"].str() ?: return false
        return workspace == run.projectDir.toAbsolutePath().normalize().toString()
    }

    /**
     * The conversation a prompt of this run's went to, or null while none has been typed.
     *
     * Bounded by the file's size rather than by a poll: until this returns something, [locate] is
     * called four times a second, and re-reading a prompt history that has not changed is work that
     * buys nothing.
     */
    private fun conversationFromHistory(run: RunContext): String? {
        val file = Antigravity.historyFile(home)
        val size = runCatching { Files.size(file) }.getOrNull() ?: return null
        if (size == scannedAt) return null
        scannedAt = size

        return runCatching {
            Files.readAllLines(file).asReversed().firstNotNullOfOrNull { line ->
                val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                if (!isOurs(record, run)) return@firstNotNullOfOrNull null
                record["conversationId"].str()?.takeIf { !run.foreign(it) }
            }
        }.getOrNull()
    }

    /**
     * The conversation the CLI last opened in this project, when it opened it during this run.
     *
     * The mtime check is the whole safeguard: the entry for a project outlives the session that
     * made it, so without it every run would adopt the id of the one before and two tabs would
     * claim one conversation. [RunContext.foreign] catches the rest — another nop tab that got to
     * the same project first is not this run, however recently the file was written.
     */
    private fun conversationFromCache(run: RunContext): String? {
        val file = Antigravity.lastConversationsFile(home)
        val written = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return null
        if (written < run.startedAt - CLOCK_SLACK_MS) return null
        val wanted = run.projectDir.toAbsolutePath().normalize().toString()
        return runCatching {
            json.parseToJsonElement(Files.readString(file)).obj()?.get(wanted).str()
        }.getOrNull()?.takeIf { !run.foreign(it) }
    }

    private companion object {
        /**
         * The CLI stamps these from its own clock and writes them a moment after it starts, so a
         * line belonging to this run can read as a hair older than the spawn that made it. The same
         * allowance [CodexTailer] makes, for the same reason.
         */
        const val CLOCK_SLACK_MS = 5_000L
    }
}
