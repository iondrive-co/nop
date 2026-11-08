package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.terminal.InheritedEnvironment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Delivers to the running TUI's daemon over its owner-only local control socket, without keys. */
internal object CodexControl {
    data class Observation(val model: String?, val reasoning: String?, val conversation: String? = null, val restored: Boolean = false)

    fun send(
        account: Account,
        conversation: String?,
        text: String,
        messageId: String,
        authorized: ConversationModel?,
        retrySince: Long? = null,
        socket: String? = null,
        stillCurrent: () -> Boolean = { true },
    ): Observation = connect(account, socket = socket) { rpc ->
        send(rpc, conversation, text, messageId, authorized, retrySince, stillCurrent)
    }

    internal fun send(rpc: Rpc, conversationId: String?, text: String, messageId: String, authorized: ConversationModel?, retrySince: Long? = null,
                      stillCurrent: () -> Boolean = { true }): Observation {
        check(stillCurrent()) { "The receiving agent run has ended" }
        val conversation = conversationId ?: loadedThread(rpc)
        var observed = observe(rpc, conversation)
        if (authorized != null && (observed.model != authorized.model ||
                (authorized.reasoning != null && observed.reasoning != null && authorized.reasoning != observed.reasoning))) {
            val correction = runCatching { setModel(rpc, conversation, authorized) }
                .onFailure { Log.warn("could not restore Codex's model before delivery: $it") }
            observed = observed.copy(restored = correction.isSuccess)
        }
        if (retrySince != null && received(rpc, conversation, messageId, retrySince)) return observed
        val params = buildJsonObject {
            put("threadId", conversation)
            put("clientUserMessageId", messageId)
            put("input", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", text)
            })))
            authorized?.let { choice ->
                put("model", choice.model)
                choice.reasoning?.let { put("effort", it) }
            }
        }
        check(stillCurrent()) { "The receiving agent run has ended" }
        try {
            rpc.request("turn/start", params)
        } catch (refusal: Refused) {
            check(stillCurrent()) { "The receiving agent run has ended" }
            // Even a turn the vendor will not start can receive context for its next request.
            rpc.request("thread/inject_items", buildJsonObject {
                put("threadId", conversation)
                put("items", JsonArray(listOf(buildJsonObject {
                    put("type", "message"); put("id", messageId); put("role", "user")
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "input_text"); put("text", text) })))
                })))
            })
            Log.info("Codex accepted message context after refusing a new turn: ${refusal.message}")
        }
        return observed
    }

    private fun received(rpc: Rpc, conversation: String, messageId: String, since: Long): Boolean {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = rpc.request("thread/items/list", buildJsonObject {
                put("threadId", conversation); put("limit", 100); put("sortDirection", "desc")
                cursor?.let { put("cursor", it) }
            })
            val entries = (page["data"] as? JsonArray)?.mapNotNull { it.obj() } ?: error("Codex returned no message receipts")
            if (entries.any { it["item"].obj()?.let { item -> item["clientId"].str() == messageId || item["id"].str() == messageId } == true }) return true
            if (entries.any { (it["startedAtMs"].str()?.toLongOrNull() ?: Long.MAX_VALUE) < since }) return false
            cursor = page["nextCursor"].str()
            check(cursor == null || seen.add(cursor)) { "Codex repeated a receipt cursor" }
        } while (cursor != null)
        return false
    }

    fun restore(account: Account, conversation: String, authorized: ConversationModel, socket: String? = null,
                expectedModel: String? = null, stillAuthorized: () -> Boolean = { true }): Boolean = connect(account, socket = socket) { rpc ->
        val current = observe(rpc, conversation)
        if (!stillAuthorized() || (expectedModel != null && current.model != expectedModel && current.model != authorized.model)) {
            false
        } else {
            setModel(rpc, conversation, authorized)
            true
        }
    }

    private fun observe(rpc: Rpc, conversation: String): Observation {
        val state = rpc.request("thread/read", buildJsonObject {
            put("threadId", conversation)
            put("includeTurns", false)
        })["thread"].obj() ?: error("Codex returned no thread")
        check(state["id"].str() == conversation) { "Codex returned another thread" }
        check(state["canAcceptDirectInput"].bool()) { "Codex thread is not loaded for input" }
        return Observation(state["model"].str(), state["reasoningEffort"].str(), conversation)
    }

    private fun loadedThread(rpc: Rpc): String {
        val page = rpc.request("thread/loaded/list", buildJsonObject {})
        val threads = (page["data"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
        check(threads.size == 1) { "Codex has not attached its terminal to one thread yet" }
        return threads.single()
    }

    private fun setModel(rpc: Rpc, conversation: String, authorized: ConversationModel) =
        rpc.request("thread/settings/update", buildJsonObject {
            put("threadId", conversation)
            put("model", authorized.model)
            authorized.reasoning?.let { put("effort", it) }
        })

    internal fun <T> connect(
        account: Account,
        binary: Path = CliTools.locate(Provider.OpenAI) ?: error("Codex is not installed"),
        timeout: Duration = Duration.ofSeconds(10),
        socket: String? = null,
        action: (Rpc) -> T,
    ): T {
        // The proxy only connects to the existing Unix socket; it never starts or resumes a thread.
        val argv = listOf(binary.toString(), "app-server", "proxy") + (socket?.let { listOf("--sock", it) } ?: emptyList())
        val process = ProcessBuilder(argv)
            .directory(account.homePath.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                val clean = InheritedEnvironment.of(environment())
                environment().clear()
                environment().putAll(clean)
                environment()["HOME"] = account.home
                environment()["CODEX_HOME"] = account.homePath.resolve(".codex").toString()
            }.start()
        val task = FutureTask {
            val rpc = Rpc(process.inputStream, process.outputStream)
            rpc.initialize()
            action(rpc)
        }
        val worker = thread(isDaemon = true, name = "codex-control") { task.run() }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
            }
            task.cancel(true)
            worker.join(500)
        }
    }

    /** A WebSocket over the proxy's stdio, with bounded frames and verified HTTP upgrade. */
    internal class Rpc(private val input: InputStream, private val output: OutputStream) {
        private var id = 0
        private val random = SecureRandom()

        fun initialize() {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            output.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            output.flush()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                check(header.length < 16_384) { "Codex sent an oversized upgrade header" }
                header.append(readByte().toChar())
            }
            check(header.startsWith("HTTP/1.1 101 ")) { "Codex refused the local control connection" }
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            check(header.lines().any { it.substringBefore(':').equals("sec-websocket-accept", true) &&
                it.substringAfter(':').trim() == accept }) { "Codex sent an invalid control upgrade" }
            request("initialize", buildJsonObject {
                put("clientInfo", buildJsonObject { put("name", "nop"); put("version", "1.0") })
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
            })
            send(buildJsonObject { put("method", "initialized") })
        }

        fun request(method: String, params: JsonObject): JsonObject {
            val requestId = ++id
            send(buildJsonObject { put("id", requestId); put("method", method); put("params", params) })
            while (true) {
                val message = Json.parseToJsonElement(receive()).obj() ?: continue
                // Notifications and approval requests belong to the TUI. Never answer them here.
                if (message["id"].str() != requestId.toString() || message["method"] != null) continue
                message["error"].obj()?.let { throw Refused("Codex refused $method: ${it["message"].str()}") }
                return message["result"].obj() ?: error("Codex returned no result for $method")
            }
        }

        private fun send(message: JsonObject) = frame(1, message.toString().toByteArray(Charsets.UTF_8))

        private fun frame(opcode: Int, bytes: ByteArray) {
            output.write(0x80 or opcode)
            when {
                bytes.size < 126 -> output.write(0x80 or bytes.size)
                bytes.size <= 65_535 -> {
                    output.write(0x80 or 126)
                    output.write(bytes.size ushr 8)
                    output.write(bytes.size and 255)
                }
                else -> {
                    output.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) output.write((bytes.size.toLong() ushr shift).toInt() and 255)
                }
            }
            val mask = ByteArray(4).also(random::nextBytes)
            output.write(mask)
            output.write(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor mask[i % 4].toInt()).toByte() })
            output.flush()
        }

        private fun receive(): String {
            val message = ByteArrayOutputStream()
            while (true) {
                val first = readByte()
                val second = readByte()
                check(second and 0x80 == 0) { "Codex sent a masked server frame" }
                var size = (second and 127).toLong()
                if (size == 126L) size = (readByte() shl 8 or readByte()).toLong()
                else if (size == 127L) {
                    size = 0
                    repeat(8) { size = (size shl 8) or readByte().toLong() }
                }
                check(size in 0..16_777_216 && message.size() + size <= 16_777_216) { "Codex sent an oversized control message" }
                val bytes = input.readNBytes(size.toInt())
                check(bytes.size == size.toInt()) { "Codex closed its control connection" }
                when (first and 15) {
                    8 -> error("Codex closed its control connection")
                    9 -> { frame(10, bytes); continue }
                    10 -> continue
                    0, 1 -> message.write(bytes)
                    else -> error("Codex sent an unexpected control frame")
                }
                if (first and 0x80 != 0) return message.toString(Charsets.UTF_8)
            }
        }

        private fun readByte(): Int = input.read().also { check(it >= 0) { "Codex closed its control connection" } }
    }

    private class Refused(message: String) : IllegalStateException(message)
}
