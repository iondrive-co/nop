package iondrive.nop.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Stops a Claude question from going out over text the user never saw.
 *
 * Claude Code shows a question (AskUserQuestion) on its own, and the model sometimes writes what
 * the user needs before answering (a commit message, a summary, what it found) into its hidden
 * reasoning while believing it wrote it as text, then asks "Ready to commit?" over nothing.
 * Nothing can bring that text back: the CLI's transcript keeps reasoning as an empty block with
 * only a signature. What the transcript does show is whether the reply that carries the question
 * has any text in it at all.
 *
 * So nop puts `nop-question-guard`, a short Python script, in front of every Claude question as a
 * `PreToolUse` hook. When the question's reply holds no text, the first such question in a prompt
 * is denied with a reason that tells the model nothing it wrote there reached the screen. The
 * model then writes it out and asks again, or asks again unchanged, and that question goes
 * through. The guard finds its own earlier denial in the transcript, so it keeps no state, and it
 * costs at most one extra model call per prompt. Each verdict other than a plain pass is a line in
 * [logFile].
 *
 * Installed by [AddressGuard], into the same `settings.json` in the same edit, so the two cannot
 * overwrite each other's change. Claude only: Codex and agy have no report of this.
 */
internal object QuestionGuard {

    const val SCRIPT_NAME = "nop-question-guard"
    private const val RESOURCE = "/iondrive/nop/agent/question-guard.py"
    private const val MATCHER = "AskUserQuestion"
    private const val TIMEOUT_S = 10

    fun scriptPath(root: Path = Accounts.dataRoot()): Path = root.resolve("bin").resolve(SCRIPT_NAME)
    fun logFile(root: Path = Accounts.dataRoot()): Path = root.resolve("question-guard").resolve("guard.log")

    /** [settings] with nop's question hook in place, or taken out when [command] is null. */
    fun withClaudeHook(settings: JsonObject, command: String?): JsonObject {
        val handler = command?.let {
            buildJsonObject {
                put("type", "command")
                put("command", it)
                put("timeout", TIMEOUT_S)
            }
        }
        val hooks = AddressGuard.withPreToolUse(settings["hooks"].obj(), handler, SCRIPT_NAME, MATCHER)
        return JsonObject(settings.toMutableMap().apply { if (hooks != null) put("hooks", hooks) else remove("hooks") })
    }

    /** The guard, with its log named in it. Rewritten when this build's differs. */
    fun writeScript(root: Path, python: String) {
        val source = checkNotNull(QuestionGuard::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }
        val text = "#!$python -I -S\n" + source.replace("\"@@LOG@@\"", JsonPrimitive(logFile(root).toString()).toString())
        AddressGuard.writeIfChanged(scriptPath(root), text, "rwx------")
    }
}
