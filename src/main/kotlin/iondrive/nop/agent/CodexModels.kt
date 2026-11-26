package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Duration

/** Queries the installed CLI's picker catalog under the selected account's configuration. */
internal object CodexModels {
    fun discover(
        account: Account,
        binary: Path? = CliTools.locate(Provider.OpenAI),
        timeout: Duration = Duration.ofSeconds(20),
    ): List<String> {
        if (binary == null) return emptyList()
        return runCatching { CodexAppServer.query(account, binary, timeout, "codex-models", ::catalog) }
            .onFailure { Log.warn("could not discover Codex models for ${account.name}: $it") }
            .getOrDefault(emptyList())
    }

    private fun catalog(rpc: CodexRpc): List<String> {
        rpc.initialize()
        val models = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = rpc.request("model/list", buildJsonObject {
                put("includeHidden", false)
                put("limit", 100)
                cursor?.let { put("cursor", it) }
            })
            val data = page["data"].arr() ?: error("Codex returned no model list")
            for (item in data) {
                val model = item.obj() ?: continue
                if (model["hidden"].bool()) continue
                model["model"].str()?.takeIf { it.isNotBlank() }?.let(models::add)
            }
            cursor = page["nextCursor"].str()
            check(cursor == null || cursors.add(cursor)) { "Codex repeated a model cursor" }
        } while (cursor != null)
        return models.toList()
    }
}
