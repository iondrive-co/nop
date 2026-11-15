package iondrive.nop.agent

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CodexControlTest {
    @Test
    fun `a delivery whose run ended never writes a control request`() {
        val output = java.io.ByteArrayOutputStream()
        val rpc = CodexControl.Rpc(java.io.ByteArrayInputStream(byteArrayOf()), output)
        assertThrows(IllegalStateException::class.java) {
            CodexControl.send(rpc, "thread-larch", "A queued notification", "notice-fern", null,
                stillCurrent = { false })
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `a run ending during backend inspection cannot start a turn`() {
        var checks = 0
        val calls = withServer("sample-model") { rpc ->
            val failure = assertThrows(IllegalStateException::class.java) {
                CodexControl.send(rpc, "thread-orchid", "A queued notification", "notice-fern", null,
                    stillCurrent = { ++checks == 1 })
            }
            assertEquals("The receiving agent run has ended", failure.message)
        }
        assertEquals(2, checks, "cancellation must be checked again after inspecting the backend")
        assertEquals(listOf("initialize", "initialized", "thread/loaded/list", "thread/read", "thread/read"), calls.map { it["method"].str() })
        assertFalse(calls.any { it["method"].str() == "turn/start" || it["method"].str() == "thread/inject_items" })
    }

    private fun withServer(model: String, loaded: List<String> = listOf("thread-orchid"),
                           action: (CodexControl.Rpc) -> Unit): List<JsonObject> {
        val input = PipedInputStream(1_048_576)
        val reply = DataOutputStream(PipedOutputStream(input))
        val requests = PipedInputStream(1_048_576)
        val output = PipedOutputStream(requests)
        val calls = mutableListOf<JsonObject>()
        val server = FutureTask {
            val incoming = DataInputStream(requests)
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) header.append(incoming.readUnsignedByte().toChar())
            val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            reply.writeBytes("HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
            reply.flush()
            fun send(text: String, opcode: Int = 1) {
                val bytes = text.toByteArray()
                reply.writeByte(0x80 or opcode)
                if (bytes.size < 126) reply.writeByte(bytes.size)
                else { reply.writeByte(126); reply.writeShort(bytes.size) }
                reply.write(bytes)
                reply.flush()
            }
            while (true) {
                val first = incoming.read()
                if (first == -1) break
                val second = incoming.readUnsignedByte()
                assertTrue(second and 0x80 != 0, "client frames must be masked")
                val length = when (val size = second and 127) {
                    126 -> incoming.readUnsignedShort()
                    127 -> incoming.readLong().toInt()
                    else -> size
                }
                val mask = incoming.readNBytes(4)
                val bytes = incoming.readNBytes(length)
                val text = String(ByteArray(length) { (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() })
                if (first and 15 == 10) continue
                val request = Json.parseToJsonElement(text).jsonObject
                calls.add(request)
                val id = request["id"] ?: continue
                val result = when (request["method"].str()) {
                    "initialize" -> "{}"
                    "thread/loaded/list" -> buildJsonObject { put("data", JsonArray(loaded.map(::JsonPrimitive))) }.toString()
                    "thread/read" -> buildJsonObject {
                        val threadId = request["params"].obj()!!["threadId"].str()!!
                        put("thread", buildJsonObject {
                            put("id", threadId); put("canAcceptDirectInput", true); put("model", model); put("reasoningEffort", "high")
                            if (threadId.startsWith("worker-")) put("source", buildJsonObject { put("subAgent", "review") })
                        })
                    }.toString()
                    else -> "{}"
                }
                if (request["method"].str() != "turn/start") send("ping", 9)
                // An approval with a colliding id must remain unanswered.
                send("""{"id":$id,"method":"item/commandExecution/requestApproval","params":{}}""")
                send("""{"id":$id,"result":$result}""")
            }
        }
        thread(isDaemon = true) { server.run() }
        try {
            val rpc = CodexControl.Rpc(input, output)
            rpc.initialize()
            try {
                action(rpc)
            } finally {
                // Keep the peer alive for control frames until the client finishes its requests.
                output.close()
            }
            server.get(5, TimeUnit.SECONDS)
            return calls.toList()
        } finally {
            input.close(); output.close(); reply.close(); requests.close()
            server.cancel(true)
        }
    }

    @Test
    fun `delivery uses the loaded thread without a resume or terminal keys`() {
        val text = "[Message]\n\nUnicode: 雨 " + "x".repeat(70_000)
        val calls = withServer("model-orchid") { rpc ->
            CodexControl.send(rpc, "thread-orchid", text, "message-one", ConversationModel("model-orchid", "high"))
        }
        assertEquals(listOf("initialize", "initialized", "thread/loaded/list", "thread/read", "thread/read", "turn/start"), calls.map { it["method"].str() })
        val params = calls.last()["params"].obj()!!
        assertEquals("thread-orchid", params["threadId"].str())
        assertEquals("message-one", params["clientUserMessageId"].str())
        assertEquals(text, params["input"]!!.jsonArray.single().jsonObject["text"].str())
    }

    @Test
    fun `an unexpected model is corrected while the message is still delivered`() {
        val calls = withServer("model-fern") { rpc ->
            CodexControl.send(rpc, "thread-orchid", "hello", "message-one", ConversationModel("model-orchid", "high"))
        }
        assertEquals(listOf("initialize", "initialized", "thread/loaded/list", "thread/read", "thread/read", "thread/settings/update", "turn/start"),
            calls.map { it["method"].str() })
        assertEquals("model-orchid", calls[5]["params"].obj()!!["model"].str())
    }

    @Test
    fun `a stale saved id delivers only to the private backend's loaded conversation`() {
        val calls = withServer("model-orchid") { rpc ->
            val observed = CodexControl.send(rpc, "thread-larch", "A queued notification", "notice-fern", null)
            assertEquals("thread-orchid", observed.conversation)
        }
        assertEquals("thread-orchid", calls.last()["params"].obj()!!["threadId"].str())
        assertFalse(calls.any { it["method"].str() == "thread/resume" })
    }

    @Test
    fun `a backend without a loaded conversation never receives a turn or an injection`() {
        val calls = withServer("model-orchid", emptyList()) { rpc ->
            assertThrows(IllegalStateException::class.java) {
                CodexControl.send(rpc, "thread-larch", "A queued notification", "notice-fern", null)
            }
        }
        assertEquals(listOf("initialize", "initialized", "thread/loaded/list"), calls.map { it["method"].str() })
    }

    @Test
    fun `a worker cannot become the recipient even when it is the saved hint`() {
        val calls = withServer("model-orchid", listOf("worker-kestrel", "thread-orchid")) { rpc ->
            val observed = CodexControl.send(rpc, "worker-kestrel", "Check the result", "notice-maple", null)
            assertEquals("thread-orchid", observed.conversation)
        }
        assertEquals("thread-orchid", calls.last()["params"].obj()!!["threadId"].str())
    }

    @Test
    fun `ambiguous root conversations cannot receive a message without a matching hint`() {
        val calls = withServer("model-orchid", listOf("thread-orchid", "thread-larch")) { rpc ->
            assertThrows(IllegalStateException::class.java) {
                CodexControl.send(rpc, null, "Check the result", "notice-maple", null)
            }
        }
        assertFalse(calls.any { it["method"].str() == "turn/start" || it["method"].str() == "thread/inject_items" })
    }
}
