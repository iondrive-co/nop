package iondrive.nop.agent

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Opt-in: exercises the installed real CLI against an offline Responses endpoint and a private tmux. */
@EnabledIfEnvironmentVariable(named = "NOP_CODEX_SMOKE_BINARY", matches = ".+")
class CodexControlSmokeTest {
    @Test
    fun `socket messages leave a draft and a model picker untouched and correct unexpected models`() {
        val root = Files.createTempDirectory("ncs-")
        val script = root.resolve("fixture.py")
        javaClass.getResourceAsStream("/agent/codex-control-fixture.py")!!.use { Files.copy(it, script) }
        javaClass.getResourceAsStream("/agent/codex-terminal.sh")!!.use { Files.copy(it, root.resolve("codex-terminal.sh")) }
        val binary = Path.of(System.getenv("NOP_CODEX_SMOKE_BINARY"))
        val command = CodexLaunch.wrap(AgentCommand(listOf(
            binary.toString(), "-c", "sandbox_mode=\"danger-full-access\"", "--no-alt-screen", "-C", root.resolve("work").toString(),
            "-c", Spawn.CODEX_APPROVAL_POLICY, "-c", "approvals_reviewer=\"user\"",
            "-c", "notice.hide_rate_limit_model_nudge=true",
            "-c", "tui.terminal_resize_reflow_max_rows=${iondrive.nop.terminal.NopTerminalSettings.SCROLLBACK_LINES}",
            "-c", "developer_instructions=\"Preserve fixture launch instructions.\"",
        ), mapOf("HOME" to root.resolve("home").toString()), null))
        val launch = root.resolve("launch.json")
        Files.writeString(launch, buildJsonObject {
            put("argv", JsonArray(command.argv.map(::JsonPrimitive)))
            put("env", buildJsonObject { command.env.forEach { (key, value) -> put(key, value) } })
        }.toString())
        val fixture = ProcessBuilder("python3", script.toString(), binary.toString(), root.toString(), launch.toString())
            .redirectError(root.resolve("fixture.err").toFile()).start()
        val input = fixture.inputStream.bufferedReader()
        val output = fixture.outputStream.bufferedWriter()
        fun operation(op: String): JsonObject {
            output.write("""{"op":"$op"}"""); output.newLine(); output.flush()
            return Json.parseToJsonElement(input.readLine() ?: error(Files.readString(root.resolve("fixture.err")))).jsonObject
        }
        try {
            val ready = Json.parseToJsonElement(input.readLine() ?: error(Files.readString(root.resolve("fixture.err")))).jsonObject
            val account = Account("fixture", Provider.OpenAI, ready["home"].str()!!)
            val socket = ready["socket"].str()!!
            val choice = ConversationModel("test-orchid")
            val conversation = CodexControl.connect(account, binary, socket = socket) {
                CodexControl.send(it, null, "Initial fixture message.", "first-message", choice).conversation
            }!!
            fun deliver(text: String) = CodexControl.connect(account, binary, Duration.ofSeconds(15), socket) {
                CodexControl.send(it, conversation, text, text, choice)
            }
            fun awaitScreen(text: String): String {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var screen: String
                do {
                    screen = operation("capture")["screen"].str()!!
                    if (text in screen) return screen
                    Thread.sleep(50)
                } while (System.nanoTime() < until)
                error(screen)
            }
            operation("draft")
            deliver("Automated message ahead of the draft.")
            var screen = awaitScreen("Automated message ahead of the draft.")
            assertTrue("Keep this unsent draft." in screen, screen)
            assertTrue("Automated message ahead of the draft." in screen, screen)
            assertTrue("Preserve fixture launch instructions." in operation("capture")["requests"].toString())
            val context = operation("capture")["contexts"]!!.jsonArray.last().jsonObject
            assertTrue(context["approval_policy"].obj()!!["granular"].obj()!!["mcp_elicitations"].bool())
            assertEquals("danger-full-access", context["sandbox_policy"].obj()!!["type"].str())
            operation("picker")
            deliver("Automated message while picking a model.")
            screen = awaitScreen("Automated message while picking a model.")
            assertTrue("Select Model" in screen, screen)
            assertTrue("Automated message while picking a model." in screen, screen)
            val retried = "Automated message while picking a model."
            CodexControl.connect(account, binary, socket = socket) {
                CodexControl.send(it, conversation, retried, retried, choice, 1)
            }
            val receipts = CodexControl.connect(account, binary, socket = socket) {
                it.request("thread/items/list", buildJsonObject {
                    put("threadId", conversation); put("limit", 100); put("sortDirection", "desc")
                })
            }
            assertEquals(1, receipts["data"]!!.jsonArray.count {
                it.jsonObject["item"].obj()?.get("clientId").str() == retried
            })
            CodexControl.connect(account, binary, socket = socket) {
                it.request("thread/settings/update", buildJsonObject {
                    put("threadId", conversation); put("model", "test-fern")
                })
            }
            deliver("Continue on the authorized model.")
            val state = CodexControl.connect(account, binary, socket = socket) {
                it.request("thread/read", buildJsonObject { put("threadId", conversation); put("includeTurns", false) })
            }
            assertEquals("test-orchid", state["thread"].obj()!!["model"].str())
            awaitScreen("Continue on the authorized model.")
            operation("close_picker")
            val turnsBeforeResume = operation("capture")["turns_started"]
            val resumed = operation("resume")
            assertTrue("Continue on the authorized model." in resumed["screen"].str()!!, resumed.toString())
            assertEquals(turnsBeforeResume, resumed["turns_started"],
                "Opening an existing conversation must not submit another prompt")
        } finally {
            runCatching { output.write("""{"op":"quit"}"""); output.newLine(); output.flush() }
            if (!fixture.waitFor(30, TimeUnit.SECONDS)) fixture.destroyForcibly()
        }
    }
}
