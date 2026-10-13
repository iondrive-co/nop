package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.agent.transcript.ClaudeTailer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The model a conversation was last in, as its transcript records it, and for Codex the reasoning
 * effort that goes with it. [model] is [DEFAULT_CHOICE] when the CLI's own setting decides.
 */
data class ConversationModel(val model: String, val reasoning: String? = null)

/**
 * Where each vendor keeps the transcript its resume reads back, and whether a given conversation's
 * is still there.
 *
 * A resume needs that file and nothing of nop's: the CLI reads its own transcript and no other. With
 * it gone — its home deleted, or Claude Code's own cleanup having taken it after 30 days — the CLI
 * starts, says it has no such conversation and exits, and resuming again does the same. Asking first
 * is what lets nop fetch it back from the [Backup] before the CLI looks, and say plainly when it
 * cannot.
 */
object Transcripts {

    /**
     * The file [id]'s conversation is kept in under [home], or null when there is none. Always null
     * for Antigravity, whose conversations are databases nop does not read — see [canTell].
     */
    fun find(provider: Provider, home: Path, projectDir: File, id: String): Path? = when (provider) {
        Provider.Anthropic -> claude(home, projectDir, id)
        Provider.OpenAI -> codex(home, id)
        Provider.Antigravity -> null
    }

    /** Whether [find] can answer for [provider] at all. */
    fun canTell(provider: Provider): Boolean = provider != Provider.Antigravity

    /** True when [account]'s CLI would be asked to resume [id] with nothing to resume it from. */
    fun missing(account: Account, projectDir: File, id: String): Boolean =
        canTell(account.provider) && find(account.provider, account.homePath, projectDir, id) == null

    /**
     * The model [id]'s conversation was last in, or null when its transcript does not say: none
     * yet, nothing answered in it, or an Antigravity conversation, which nop does not read.
     *
     * A resume needs this because nop starts every CLI with the account's model flag, and the flag
     * beats whatever the conversation had switched to with `/model`. That switch is often away from
     * a model the account has run out of, so coming back on the account's model walks straight
     * back into the wall.
     */
    fun lastModel(account: Account, projectDir: File, id: String): ConversationModel? {
        val read: (String) -> ConversationModel? = when (account.provider) {
            Provider.Anthropic -> ::claudeModel
            Provider.OpenAI -> ::codexModel
            Provider.Antigravity -> return null
        }
        val file = find(account.provider, account.homePath, projectDir, id) ?: return null
        return runCatching { lastFromEnd(file, read) }
            .onFailure { Log.warn("could not read the model from $file: $it") }
            .getOrNull()
    }

    /**
     * What `/model` files once the user has picked: `Set model to `Opus 5.5 (default)` and saved
     * as your default for new sessions`. The name is the menu's, not the id `--model` takes.
     */
    private val CLAUDE_MODEL_SET = Regex("^<local-command-stdout>Set model to `([^`]+)`")

    /** A menu name that maps onto an id by rule: `Opus 5.5` is `claude-opus-5-5`. */
    private val CLAUDE_MODEL_NAME = Regex("^([A-Za-z]+) (\\d+(?:\\.\\d+)?)(?: \\(default\\))?$")

    /**
     * The model a Claude transcript line says the conversation is in: an answer names the model
     * that wrote it, and a `/model` names the one picked for the next answer. Answers nop's own
     * side of the CLI wrote (`<synthetic>`: a login prompt, a usage wall) were written by no model.
     */
    private fun claudeModel(line: String): ConversationModel? {
        if ("Set model to" !in line && "\"model\"" !in line) return null
        val record = parse(line) ?: return null
        // A subagent's turns run on a model of their own and say nothing about the conversation's.
        if (record["isSidechain"].bool()) return null
        val message = record["message"].obj()
        return when (record["type"].str()) {
            "assistant" -> (record["requestedModel"].str() ?: message?.get("model").str())
                ?.takeIf { it.isNotBlank() && !it.startsWith("<") }
                ?.let { ConversationModel(it) }
            "user", "system" -> {
                val text = message?.get("content").str() ?: record["content"].str() ?: return null
                val picked = CLAUDE_MODEL_SET.find(text)?.groupValues?.get(1) ?: return null
                ConversationModel(claudeModelId(picked))
            }
            else -> null
        }
    }

    /**
     * The `--model` id for a `/model` menu name. A name that is not plainly a family and a version
     * — a context-size variant, a renamed default — is left to the CLI, which `/model` also saved
     * it to: [DEFAULT_CHOICE] starts it without the flag, on that saved choice.
     */
    internal fun claudeModelId(name: String): String {
        val match = CLAUDE_MODEL_NAME.find(name.trim()) ?: return DEFAULT_CHOICE
        val (family, version) = match.destructured
        return "claude-${family.lowercase()}-${version.replace('.', '-')}"
    }

    /**
     * The model a Codex rollout line says the thread is in: each turn records the one it ran on,
     * and a change between turns is recorded as the thread's settings being applied.
     */
    private fun codexModel(line: String): ConversationModel? {
        if ("\"turn_context\"" !in line && "\"thread_settings_applied\"" !in line) return null
        val record = parse(line) ?: return null
        val payload = record["payload"].obj() ?: return null
        val settings = when {
            record["type"].str() == "turn_context" -> payload
            payload["type"].str() == "thread_settings_applied" -> payload["thread_settings"].obj() ?: return null
            else -> return null
        }
        val model = settings["model"].str()?.takeIf { it.isNotBlank() } ?: return null
        val effort = (settings["effort"].str() ?: settings["reasoning_effort"].str())?.takeIf { it.isNotBlank() }
        return ConversationModel(model, effort)
    }

    private fun parse(line: String): JsonObject? =
        runCatching { Json.parseToJsonElement(line).obj() }.getOrNull()

    /**
     * The newest line of [file] that [read] makes something of, looked for from the end in growing
     * windows: transcripts run to tens of megabytes, and the answer is nearly always in the last
     * few records.
     */
    private fun <T : Any> lastFromEnd(file: Path, read: (String) -> T?): T? =
        Files.newByteChannel(file).use { channel ->
            val size = channel.size()
            var window = FIRST_WINDOW
            var found: T?
            do {
                val from = (size - window).coerceAtLeast(0)
                val buffer = ByteBuffer.allocate((size - from).toInt())
                channel.position(from)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                val lines = String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).split('\n')
                // A window that starts mid-file starts mid-line, and the next window reads that line whole.
                val whole = if (from > 0) lines.drop(1) else lines
                found = whole.asReversed().firstNotNullOfOrNull(read)
                window *= 8
            } while (found == null && from > 0 && window <= LAST_WINDOW)
            found
        }

    private const val FIRST_WINDOW: Long = 256L * 1024
    private const val LAST_WINDOW: Long = 16L * 1024 * 1024

    /**
     * Claude files a conversation under the directory it was started in, so that is looked at first,
     * and every other project directory after it: the same id is the same conversation wherever it
     * is filed.
     */
    private fun claude(home: Path, projectDir: File, id: String): Path? {
        val projects = home.resolve("projects")
        val name = "$id.jsonl"
        val expected = projects.resolve(ClaudeTailer.slug(projectDir.toPath())).resolve(name)
        if (Files.isRegularFile(expected)) return expected
        if (!Files.isDirectory(projects)) return null
        return runCatching {
            Files.list(projects).use { dirs ->
                dirs.map { it.resolve(name) }.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
            }
        }.getOrNull()
    }

    /** Codex files its rollouts by date: `.codex/sessions/YYYY/MM/DD/rollout-<time>-<id>.jsonl`. */
    private fun codex(home: Path, id: String): Path? {
        val root = home.resolve(".codex").resolve("sessions")
        if (!Files.isDirectory(root)) return null
        return runCatching {
            Files.walk(root).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("rollout-") && name.endsWith("-$id.jsonl") && Files.isRegularFile(path)
                }.findFirst().orElse(null)
            }
        }.getOrNull()
    }
}
