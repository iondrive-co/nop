package iondrive.nop.agent

import iondrive.nop.terminal.InheritedEnvironment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One conversation with `codex app-server` under an account's own home, for the questions only the
 * CLI can answer: its model catalog, the account's reset credits.
 */
internal object CodexAppServer {
    /**
     * Starts the server for [account], runs [body] against it, and reaps the process, all within
     * [timeout]. Throws what [body] throws, or a [java.util.concurrent.TimeoutException].
     */
    fun <T> query(account: Account, binary: Path, timeout: Duration, name: String, body: (CodexRpc) -> T): T {
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
                process.outputStream.bufferedWriter().use { output -> body(CodexRpc(input, output)) }
            }
        }
        val worker = thread(isDaemon = true, name = name) { query.run() }
        try {
            return query.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
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
    }
}

/** JSON-RPC over the app-server's stdio: one request at a time, numbered from 1. */
internal class CodexRpc(private val input: BufferedReader, private val output: BufferedWriter) {
    /** The server answered [method] with an error, which [message] carries when it gave one. */
    class Refused(val method: String, override val message: String?) : Exception(message ?: "Codex refused $method")

    private var nextId = 1

    private fun send(message: JsonObject) {
        output.write(message.toString())
        output.newLine()
        output.flush()
    }

    fun notify(method: String) = send(buildJsonObject { put("method", method) })

    /** Sends [method] and waits for its answer, skipping notifications and other replies. */
    fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        send(buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        })
        while (true) {
            val line = input.readLine() ?: error("Codex closed the connection before answering $method")
            val response = runCatching { Json.parseToJsonElement(line).obj() }.getOrNull() ?: continue
            if (response["id"].str() != id.toString()) continue
            response["result"].obj()?.let { return it }
            throw Refused(method, response["error"].obj()?.get("message").str())
        }
    }

    /** The handshake every conversation starts with. */
    fun initialize() {
        request("initialize", buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "nop")
                put("title", "nop")
                put("version", "1.0")
            })
        })
        notify("initialized")
    }
}
