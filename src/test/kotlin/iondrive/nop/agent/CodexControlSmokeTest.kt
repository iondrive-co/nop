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
    fun `a real startup hook review has no conversation until answered and then accepts queued work`() {
        val root = Files.createTempDirectory("nch-")
        val script = root.resolve("fixture.py")
        javaClass.getResourceAsStream("/agent/codex-control-fixture.py")!!.use { Files.copy(it, script) }
        val binary = Path.of(System.getenv("NOP_CODEX_SMOKE_BINARY"))
        val command = CodexLaunch.wrap(AgentCommand(listOf(binary.toString(), "--no-alt-screen", "-C", root.resolve("work").toString()),
            mapOf("HOME" to root.resolve("home").toString()), null))
        val launch = root.resolve("launch.json")
        Files.writeString(launch, buildJsonObject {
            put("argv", JsonArray(command.argv.map(::JsonPrimitive)))
            put("env", buildJsonObject { command.env.forEach { (key, value) -> put(key, value) } })
            put("startup_hook", true)
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
            val screen = operation("capture")["screen"].str()!!
            assertNotNull(iondrive.nop.terminal.TerminalSession.codexStartupQuestion(screen.lines()), screen)
            val waiting = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                CodexControl.connect(account, binary, socket = socket) {
                    CodexControl.send(it, "unrelated-saved-thread", "Read the queued work.", "notice-orchid", null)
                }
            }
            assertTrue(waiting.cause is IllegalStateException)
            operation("review_hooks")
            var delivered: CodexControl.Observation? = null
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            do {
                delivered = runCatching {
                    CodexControl.connect(account, binary, socket = socket) {
                        CodexControl.send(it, "unrelated-saved-thread", "Read the queued work.", "notice-orchid", null)
                    }
                }.getOrNull()
                if (delivered == null) Thread.sleep(100)
            } while (delivered == null && System.nanoTime() < until)
            assertNotNull(delivered, operation("capture").toString())
            val conversation = delivered!!.conversation
            assertNotEquals("unrelated-saved-thread", conversation)
            val receiptDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var received = false
            do {
                val receipts = CodexControl.connect(account, binary, socket = socket) {
                    it.request("thread/items/list", buildJsonObject {
                        put("threadId", conversation); put("limit", 100); put("sortDirection", "desc")
                    })
                }
                received = receipts["data"]!!.jsonArray.any { it.jsonObject["item"].obj()?.get("clientId").str() == "notice-orchid" }
                if (!received) Thread.sleep(100)
            } while (!received && System.nanoTime() < receiptDeadline)
            assertTrue(received, operation("capture").toString())
        } finally {
            runCatching { output.write("""{"op":"quit"}"""); output.newLine(); output.flush() }
            if (!fixture.waitFor(30, TimeUnit.SECONDS)) fixture.destroyForcibly()
        }
    }

    @Test
    fun `socket messages leave a draft and a model picker untouched and correct unexpected models`() {
        val root = Files.createTempDirectory("ncs-")
        val script = root.resolve("fixture.py")
        javaClass.getResourceAsStream("/agent/codex-control-fixture.py")!!.use { Files.copy(it, script) }
        javaClass.getResourceAsStream("/agent/codex-terminal.sh")!!.use { Files.copy(it, root.resolve("codex-terminal.sh")) }
        val binary = Path.of(System.getenv("NOP_CODEX_SMOKE_BINARY"))
        val initial = AgentCommand(listOf(
            binary.toString(), "-c", "sandbox_mode=\"danger-full-access\"", "--no-alt-screen", "-C", root.resolve("work").toString(),
            "-c", Spawn.CODEX_APPROVAL_POLICY, "-c", "approvals_reviewer=\"user\"",
            "-c", "notice.hide_rate_limit_model_nudge=true",
            "-c", "tui.terminal_resize_reflow_max_rows=${iondrive.nop.terminal.NopTerminalSettings.SCROLLBACK_LINES}",
            "-c", "developer_instructions=\"Preserve fixture launch instructions.\"",
        ), mapOf("HOME" to root.resolve("home").toString()), null)
        val command = CodexLaunch.wrap(initial)
        val resumeId = "fixture-resume-id"
        val resume = CodexLaunch.wrap(initial.copy(
            argv = initial.argv + listOf("resume", resumeId), nativeSessionId = resumeId, isResume = true,
        ))
        val launch = root.resolve("launch.json")
        Files.writeString(launch, buildJsonObject {
            put("argv", JsonArray(command.argv.map(::JsonPrimitive)))
            put("env", buildJsonObject { command.env.forEach { (key, value) -> put(key, value) } })
            put("resume", buildJsonObject {
                put("argv", JsonArray(resume.argv.map(::JsonPrimitive)))
                put("env", buildJsonObject { resume.env.forEach { (key, value) -> put(key, value) } })
                put("id", resumeId)
            })
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
            val resumeSocket = resume.env.getValue(CodexLaunch.SOCKET_ENV)
            CodexControl.connect(account, binary, socket = resumeSocket) {
                CodexControl.send(it, conversation, "Continue after reopening.", "resumed-message", choice)
            }
            awaitScreen("Continue after reopening.")
            val resumedContext = operation("capture")["contexts"]!!.jsonArray.last().jsonObject
            assertEquals("danger-full-access", resumedContext["sandbox_policy"].obj()!!["type"].str())
            assertTrue(resumedContext["approval_policy"].obj()!!["granular"].obj()!!["mcp_elicitations"].bool())
        } finally {
            runCatching { output.write("""{"op":"quit"}"""); output.newLine(); output.flush() }
            if (!fixture.waitFor(30, TimeUnit.SECONDS)) fixture.destroyForcibly()
        }
    }
}
