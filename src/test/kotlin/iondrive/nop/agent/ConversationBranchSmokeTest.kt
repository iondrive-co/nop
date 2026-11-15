package iondrive.nop.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real vendor loaders, private homes, invented histories and a local model endpoint. */
class ConversationBranchSmokeTest {
    @TempDir lateinit var root: Path

    @Test
    @EnabledIfEnvironmentVariable(named = "NOP_BRANCH_SMOKE_CODEX", matches = ".+")
    fun `native Codex forks only through the selected completed turn`() {
        val account = Account("agent-larch", Provider.OpenAI, root.resolve("home").toString())
        Files.createDirectories(account.homePath.resolve(".codex"))
        Files.writeString(account.homePath.resolve(".codex/config.toml"), "model = \"test-orchid\"\n")
        val id = UUID.randomUUID().toString()
        val file = account.homePath.resolve(".codex/sessions/2025/04/03/rollout-2025-04-03T10-00-00-$id.jsonl")
        Files.createDirectories(file.parent)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val records = mutableListOf<JsonObject>()
        fun record(type: String, payload: JsonObject) { records += buildJsonObject {
            put("timestamp", "2025-04-03T10:00:00Z"); put("type", type); put("payload", payload)
        } }
        record("session_meta", buildJsonObject {
            put("id", id); put("timestamp", "2025-04-03T10:00:00Z"); put("cwd", root.toString())
            put("originator", "codex-tui"); put("cli_version", "0.166.0"); put("source", "cli"); put("model_provider", "openai")
        })
        for ((turn, label) in listOf(first to "orchard", second to "greenhouse")) {
            record("event_msg", buildJsonObject { put("type", "task_started"); put("turn_id", turn) })
            record("turn_context", buildJsonObject {
                put("turn_id", turn); put("cwd", root.toString()); put("approval_policy", "never")
                put("sandbox_policy", buildJsonObject { put("type", "danger-full-access") })
                put("model", "test-orchid"); put("summary", "auto")
            })
            for ((role, body) in listOf("user" to "Describe the $label.", "assistant" to "The $label is ready for spring.")) {
                record("response_item", buildJsonObject {
                    put("type", "message"); put("role", role)
                    put("content", buildJsonArray { add(buildJsonObject {
                        put("type", if (role == "user") "input_text" else "output_text"); put("text", body)
                    }) })
                })
            }
            record("event_msg", buildJsonObject { put("type", "task_complete"); put("turn_id", turn) })
        }
        Files.writeString(file, records.joinToString("\n", postfix = "\n"))
        val original = Files.readString(file)
        val point = ConversationBranch.points(account, root, id).first { it.boundary == first }
        val branched = ConversationBranch.create(point, Path.of(System.getenv("NOP_BRANCH_SMOKE_CODEX")))!!
        assertNotEquals(id, branched)
        val fork = Transcripts.find(Provider.OpenAI, account.homePath, root.toFile(), branched)!!
        val history = Files.readString(fork)
        assertTrue(history.contains("orchard"), history)
        assertFalse(history.contains("greenhouse"), history)
        assertEquals(original, Files.readString(file))
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NOP_BRANCH_SMOKE_CLAUDE", matches = ".+")
    fun `real Claude resumes the branch with the prefix and new question only`() {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            requests += body
            val response = """{"id":"msg_fixture","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[{"type":"text","text":"The branch question was received."}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":30,"output_tokens":8}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val home = root.resolve("home")
            val account = Account("agent-larch", Provider.Anthropic, home.toString())
            val id = UUID.randomUUID().toString()
            val file = home.resolve("projects").resolve(iondrive.nop.agent.transcript.ClaudeTailer.slug(root)).resolve("$id.jsonl")
            Files.createDirectories(file.parent)
            var parent: String? = null
            var selected = ""
            val entries = mutableListOf<JsonObject>()
            for ((role, body) in listOf("user" to "Describe the orchard.", "assistant" to "The orchard is ready for spring.",
                "user" to "Describe the greenhouse.", "assistant" to "The greenhouse is closed.")) {
                val uuid = UUID.randomUUID().toString()
                entries += buildJsonObject {
                    put("type", role); put("uuid", uuid); put("parentUuid", parent); put("sessionId", id)
                    put("timestamp", "2025-04-03T10:00:00Z"); put("cwd", root.toString()); put("isSidechain", false)
                    put("version", "2.1.200"); put("userType", "external")
                    put("message", buildJsonObject {
                        put("role", role)
                        if (role == "assistant") {
                            put("id", "msg_$uuid"); put("type", "message"); put("model", "claude-sonnet-4-5"); put("stop_reason", "end_turn")
                            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", body) }) })
                            put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 10) })
                        } else put("content", body)
                    })
                }
                if (body.startsWith("The orchard")) selected = uuid
                parent = uuid
            }
            Files.writeString(file, entries.joinToString("\n", postfix = "\n"))
            val original = Files.readString(file)
            val point = ConversationBranch.points(account, root, id).first { it.boundary == selected }
            val fork = ConversationBranch.create(point)!!
            val process = ProcessBuilder(System.getenv("NOP_BRANCH_SMOKE_CLAUDE"), "--bare", "--print", "--model", "claude-sonnet-4-5",
                "--resume", fork, "How should the orchard be watered?")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("claude.out").toFile()).apply {
                    environment().clear()
                    environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString(), "CLAUDE_CONFIG_DIR" to home.toString(),
                        "ANTHROPIC_API_KEY" to "offline-placeholder", "ANTHROPIC_BASE_URL" to "http://127.0.0.1:${server.address.port}",
                        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1", "DISABLE_AUTOUPDATER" to "1"))
                }.start()
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Claude did not finish the offline request")
                assertEquals(0, process.exitValue(), Files.readString(root.resolve("claude.out")))
                val modelRequest = requests.firstOrNull { it.contains("How should the orchard be watered?") }
                assertNotNull(modelRequest, requests.toString() + Files.readString(root.resolve("claude.out")))
                assertTrue(modelRequest!!.contains("The orchard is ready for spring."))
                assertFalse(modelRequest.contains("greenhouse"))
                assertEquals(original, Files.readString(file))
            } finally { process.destroyForcibly() }
        } finally { server.stop(0) }
    }
}
