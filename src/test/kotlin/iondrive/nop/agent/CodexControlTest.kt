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
    private fun withServer(model: String, action: (CodexControl.Rpc) -> Unit): List<JsonObject> {
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
                val first = incoming.readUnsignedByte()
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
                    "thread/read" -> """{"thread":{"id":"thread-orchid","canAcceptDirectInput":true,"model":"$model","reasoningEffort":"high"}}"""
                    else -> "{}"
                }
                if (request["method"].str() != "turn/start") send("ping", 9)
                // An approval with a colliding id must remain unanswered.
                send("""{"id":$id,"method":"item/commandExecution/requestApproval","params":{}}""")
                send("""{"id":$id,"result":$result}""")
                if (request["method"].str() == "turn/start") break
            }
        }
        thread(isDaemon = true) { server.run() }
        try {
            val rpc = CodexControl.Rpc(input, output)
            rpc.initialize()
            action(rpc)
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
        assertEquals(listOf("initialize", "initialized", "thread/read", "turn/start"), calls.map { it["method"].str() })
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
        assertEquals(listOf("initialize", "initialized", "thread/read", "thread/settings/update", "turn/start"),
            calls.map { it["method"].str() })
        assertEquals("model-orchid", calls[3]["params"].obj()!!["model"].str())
    }
}
