package iondrive.nop.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Tells nop when an Antigravity turn starts and stops, so its tabs show working and unread-response
 * marks like Claude Code and Codex tabs.
 *
 * Unlike the other two CLIs, `agy` writes no terminal title carrying a spinner or idle glyph. Its
 * transcript records finished model replies and open questions, while lifecycle hooks (`PreInvocation`
 * and `Stop`) also cover turn starts before the transcript is located, background-task wakeups,
 * user cancellations and turn-ending errors. The hook talks back to this tab over [AgentSocket]
 * using the `NOP_SOCKET` and `NOP_AGENT_TICKET` already in the tab's environment, carrying the
 * payload's `conversationId` so child subagents in other conversations are ignored.
 *
 * Installed by [AddressGuard] into `$HOME/.gemini/config/hooks.json` in the same edit as the
 * address guard, under its own top-level key so user and project hooks stay untouched.
 */
internal object AntigravityActivityHook {

    const val SCRIPT_NAME = "nop-activity"
    private const val RESOURCE = "/iondrive/nop/agent/agy-activity.py"
    private const val PRE_INVOCATION = "PreInvocation"
    private const val STOP = "Stop"
    private const val TIMEOUT_S = 5

    fun scriptPath(root: Path = Accounts.dataRoot()): Path = root.resolve("bin").resolve(SCRIPT_NAME)

    /** [file] (`hooks.json`) with nop's activity hook in place, or taken out when [command] is null. */
    fun withAgyHook(file: JsonObject, command: String?): JsonObject {
        val ours = command?.let {
            buildJsonObject {
                put(PRE_INVOCATION, buildJsonArray { add(handler(it, "working")) })
                put(STOP, buildJsonArray { add(handler(it, "idle")) })
            }
        }
        return JsonObject(file.toMutableMap().apply { if (ours != null) put(SCRIPT_NAME, ours) else remove(SCRIPT_NAME) })
    }

    private fun handler(command: String, state: String): JsonObject = buildJsonObject {
        put("type", "command")
        put("command", "$command $state")
        put("timeout", TIMEOUT_S)
    }

    /** Writes the hook script under `<dataRoot>/bin`, replacing it when this build's differs. */
    fun writeScript(root: Path, python: String) {
        val source = checkNotNull(AntigravityActivityHook::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }
        val text = "#!$python -I -S\n" + source
        AddressGuard.writeIfChanged(scriptPath(root), text, "rwx------")
    }
}
