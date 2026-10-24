package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.terminal.InheritedEnvironment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Queries the installed CLI's picker catalog under the selected account's configuration. */
internal object CodexModels {
    fun discover(
        account: Account,
        binary: Path? = CliTools.locate(Provider.OpenAI),
        timeout: Duration = Duration.ofSeconds(20),
    ): List<String> {
        if (binary == null) return emptyList()
        return runCatching {
            val process = ProcessBuilder(binary.toString(), "app-server")
                .directory(account.homePath.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply {
                    val clean = InheritedEnvironment.of(environment())
                    environment().clear()
                    environment().putAll(clean)
                    environment()["HOME"] = account.home
                    environment()["CODEX_HOME"] = account.homePath.resolve(".codex").toString()
                }
                .start()
            val query = FutureTask {
                process.inputStream.bufferedReader().use { input ->
                    process.outputStream.bufferedWriter().use { output -> catalog(input, output) }
                }
            }
            val worker = thread(isDaemon = true, name = "codex-models") { query.run() }
            try {
                query.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } finally {
                // The server stays alive after replying; every query owns and reaps its process.
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(500, TimeUnit.MILLISECONDS)
                }
                query.cancel(true)
                worker.join(500)
            }
        }.onFailure { Log.warn("could not discover Codex models for ${account.name}: $it") }
            .getOrDefault(emptyList())
    }

    private fun catalog(input: BufferedReader, output: BufferedWriter): List<String> {
        fun send(message: JsonObject) {
            output.write(message.toString())
            output.newLine()
            output.flush()
        }
        fun request(id: Int, method: String, params: JsonObject): JsonObject {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
            while (true) {
                val line = input.readLine() ?: error("Codex closed the model query")
                val response = Json.parseToJsonElement(line).obj() ?: continue
                if (response["id"].str() != id.toString()) continue
                return response["result"].obj() ?: error("Codex refused $method")
            }
        }
        request(1, "initialize", buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "nop")
                put("title", "nop")
                put("version", "1.0")
            })
        })
        send(buildJsonObject { put("method", "initialized") })
        val models = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var id = 2
        do {
            val page = request(id++, "model/list", buildJsonObject {
                put("includeHidden", false)
                put("limit", 100)
                cursor?.let { put("cursor", it) }
            })
            val data = page["data"] as? kotlinx.serialization.json.JsonArray
                ?: error("Codex returned no model list")
            for (item in data) {
                val model = item.obj() ?: continue
                if (model["hidden"]?.jsonPrimitive?.booleanOrNull == true) continue
                model["model"].str()?.takeIf { it.isNotBlank() }?.let(models::add)
            }
            cursor = page["nextCursor"].str()
            check(cursor == null || cursors.add(cursor)) { "Codex repeated a model cursor" }
        } while (cursor != null)
        return models.toList()
    }
}
