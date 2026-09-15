package iondrive.nop.agent.transcript

import iondrive.nop.agent.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One vocabulary for tools, so a handoff summary reads the same whichever provider wrote the
 * session it came from.
 *
 * The names are Claude Code's, because they are the ones a handoff is most often *going to*: Codex
 * reading "Edit src/App.kt" understands it, while Claude reading "shell" learns nothing. What
 * matters is only that one word means one thing across both — a summary that calls the same act
 * `Bash` in one paragraph and `exec_command` in the next makes the reader work out that they are
 * the same, which is exactly the work a handoff is supposed to have already done.
 */
object Normalize {

    /** Codex's tool names, mapped onto the shared vocabulary. Anything unknown passes through. */
    private val CODEX_TOOLS = mapOf(
        "shell" to "Bash",
        "exec_command" to "Bash",
        "local_shell" to "Bash",
        "container.exec" to "Bash",
        "apply_patch" to "Edit",
        "write_file" to "Write",
        "read_file" to "Read",
        "view_image" to "Read",
        "update_plan" to "TodoWrite",
        "web_search" to "WebSearch",
        "web_search_call" to "WebSearch",
        "web_fetch" to "WebFetch",
    )

    fun tool(provider: String, name: String): String =
        if (provider == "openai") CODEX_TOOLS[name] ?: name else name

    /**
     * The arguments worth keeping: enough to say what the call did, never the whole payload. A
     * summary carrying a 200-line `apply_patch` body is a summary nobody reads.
     */
    fun args(raw: JsonObject?): Map<String, String> {
        if (raw == null) return emptyMap()
        return INTERESTING_ARGS.mapNotNull { key ->
            raw[key]?.scalar()?.take(ARG_LIMIT)?.let { key to it }
        }.toMap()
    }

    /**
     * The one-line form of a tool call, as a summary shows it: the file for a file tool, the
     * command for a shell, the pattern for a search.
     */
    fun describe(tool: String, args: Map<String, String>): String = when (tool) {
        "Read", "Write", "Edit", "NotebookEdit" -> args["file_path"] ?: args["path"] ?: ""
        "Bash" -> args["command"] ?: args["cmd"] ?: ""
        "Glob" -> args["pattern"] ?: ""
        "Grep" -> args["pattern"] ?: ""
        "Task", "Agent" -> args["description"] ?: args["prompt"] ?: ""
        "WebSearch" -> args["query"] ?: ""
        "WebFetch" -> args["url"] ?: ""
        else -> args.values.firstOrNull { it.isNotBlank() } ?: ""
    }.take(DESCRIBE_LIMIT)

    /** A tool result cut to something a summary can carry, with the middle dropped if need be. */
    fun summarise(text: String, limit: Int = RESULT_LIMIT): String {
        val trimmed = text.trim()
        if (trimmed.length <= limit) return trimmed
        // Both ends, not just the head: a command's last lines are usually the ones that say
        // whether it worked.
        val head = trimmed.take(limit * 2 / 3)
        val tail = trimmed.takeLast(limit / 3)
        return "$head\n…\n$tail"
    }

    /** A tool argument as a string. A structured one is kept verbatim when it is short enough. */
    private fun JsonElement.scalar(): String? = when (this) {
        is JsonPrimitive -> str()
        else -> toString().takeIf { it.length <= ARG_LIMIT }
    }

    /** The argument names that say what a call did, across both providers' tool sets. */
    private val INTERESTING_ARGS = listOf(
        "file_path", "path", "command", "cmd", "pattern", "query", "url",
        "description", "prompt", "old_string", "new_string", "workdir",
    )

    private const val ARG_LIMIT = 500
    private const val DESCRIBE_LIMIT = 200
    const val RESULT_LIMIT = 500
}
