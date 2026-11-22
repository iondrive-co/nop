package iondrive.nop.agent

import iondrive.nop.agent.transcript.LiveTranscripts
import iondrive.nop.ui.projectAgentNews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The Antigravity turn-lifecycle hook: installation beside the address guard in `hooks.json`, and
 * running the real Python script against nop's real agent socket to drive tab indicators.
 */
class AntigravityActivityHookTest {

    private val addressGuard = "/opt/example/python3 -I -S /opt/example/nop-address-guard"
    private val activityHook = "/opt/example/python3 -I -S /opt/example/nop-activity"
    private val opened = mutableListOf<AgentSessions>()

    @AfterEach
    fun cleanUp() {
        AgentMessages.sessions = { AgentSessionStore.all() }
        opened.forEach { state ->
            state.sessions.forEach { runCatching { Files.deleteIfExists(it.log.file) } }
            state.disposeAll()
        }
    }

    private fun parse(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    private fun agy(home: Path) = Account("agy-alpha", Provider.Antigravity, home.toString())

    private fun hooksFile(home: Path) = home.resolve(".gemini").resolve("config").resolve("hooks.json")

    // --- installation --------------------------------------------------------------------------

    @Test
    fun `agy gets flat PreInvocation and Stop hooks beside the address guard and user hooks`(@TempDir home: Path) {
        val file = hooksFile(home)
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"user-lint":{"PostToolUse":[{"matcher":"*","hooks":[{"command":"./lint.sh"}]}]}}""")

        AddressGuard.hook(agy(home), addressGuard, activity = activityHook)

        val hooks = parse(file)
        assertTrue("user-lint" in hooks)
        assertTrue(AddressGuard.SCRIPT_NAME in hooks)
        val ours = hooks[AntigravityActivityHook.SCRIPT_NAME]!!.jsonObject
        val pre = ours["PreInvocation"]!!.jsonArray.single().jsonObject
        val stop = ours["Stop"]!!.jsonArray.single().jsonObject
        assertEquals("command", pre["type"]!!.jsonPrimitive.content)
        assertEquals("$activityHook working", pre["command"]!!.jsonPrimitive.content)
        assertEquals("command", stop["type"]!!.jsonPrimitive.content)
        assertEquals("$activityHook idle", stop["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `installing twice writes nothing the second time`(@TempDir home: Path) {
        val account = agy(home)
        AddressGuard.hook(account, addressGuard, activity = activityHook)
        val file = hooksFile(home)
        val first = Files.readString(file)
        val stamp = Files.getLastModifiedTime(file)
        Thread.sleep(20)

        AddressGuard.hook(account, addressGuard, activity = activityHook)

        assertEquals(first, Files.readString(file))
        assertEquals(stamp, Files.getLastModifiedTime(file))
    }

    @Test
    fun `no interpreter removes nop's activity hook and leaves user hooks`(@TempDir home: Path) {
        val file = hooksFile(home)
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"user-lint":{"Stop":[{"type":"command","command":"./done.sh"}]}}""")
        val account = agy(home)
        AddressGuard.hook(account, addressGuard, activity = activityHook)

        AddressGuard.hook(account, null, null, null)

        val hooks = parse(file)
        assertTrue("user-lint" in hooks)
        assertFalse(AntigravityActivityHook.SCRIPT_NAME in hooks)
        assertFalse(AddressGuard.SCRIPT_NAME in hooks)
    }

    @Test
    fun `claude and codex get no agy activity hook`(@TempDir tmp: Path) {
        val claude = Files.createDirectories(tmp.resolve("claude"))
        val codex = Files.createDirectories(tmp.resolve("codex"))

        AddressGuard.hook(Account("claude-alpha", Provider.Anthropic, claude.toString()), addressGuard, activity = activityHook)
        AddressGuard.hook(Account("codex-alpha", Provider.OpenAI, codex.toString()), addressGuard, activity = activityHook)

        assertTrue(AntigravityActivityHook.SCRIPT_NAME !in Files.readString(claude.resolve("settings.json")))
        assertTrue(AntigravityActivityHook.SCRIPT_NAME !in Files.readString(codex.resolve(".codex").resolve("hooks.json")))
    }

    @Test
    fun `AddressGuard install writes the activity script and wires both hooks into an agy home`(@TempDir tmp: Path) {
        val python = AddressGuard.python()
        assumeTrue(python != null, "needs python3")
        val home = Files.createDirectories(tmp.resolve("agy-home"))
        val root = tmp.resolve("data")
        val configRoot = tmp.resolve("config")
        val account = agy(home)

        AddressGuard.install(
            config = AgentConfig(accounts = listOf(account)),
            root = root,
            userHome = tmp.resolve("user"),
            configRoot = configRoot,
            python = python,
        )

        val script = AntigravityActivityHook.scriptPath(root)
        assertTrue(Files.isExecutable(script))
        assertTrue(Files.readString(script).startsWith("#!$python -I -S\n"))
        val hooks = parse(hooksFile(home))
        assertTrue(AddressGuard.SCRIPT_NAME in hooks)
        assertTrue(AntigravityActivityHook.SCRIPT_NAME in hooks)
    }

    // --- the script against the real socket ----------------------------------------------------

    private fun runScript(
        python: String,
        script: Path,
        arg: String,
        stdin: String = "",
        env: Map<String, String> = emptyMap(),
    ): Triple<Int, String, String> {
        val pb = ProcessBuilder(python, "-I", "-S", script.toString(), arg)
        pb.environment().remove(AgentSocket.SOCKET_ENV)
        pb.environment().remove(AgentSocket.TICKET_ENV)
        pb.environment().putAll(env)
        val process = pb.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val stdout = process.inputStream.readAllBytes().decodeToString().trim()
        val stderr = process.errorStream.readAllBytes().decodeToString().trim()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return Triple(process.exitValue(), stdout, stderr)
    }

    @Test
    fun `the real hook drives working, unseen finished response, and ignores subagent conversations`(@TempDir tmp: Path) {
        val python = AddressGuard.python()
        assumeTrue(python != null, "needs python3")
        val root = tmp.resolve("data")
        AntigravityActivityHook.writeScript(root, python!!)
        val script = AntigravityActivityHook.scriptPath(root)

        val state = AgentSessions().also { opened += it }
        val tab = state.open(tmp.toFile(), Account("agy-alpha", Provider.Antigravity, tmp.resolve("home").toString()))
        val sibling = state.open(tmp.toFile(), Account("agy-alpha", Provider.Antigravity, tmp.resolve("home").toString()))
        tab.run.session.running = true
        sibling.run.session.running = true
        AgentMessages.sessions = { state.sessions }

        val env = AgentSocket.runEnv(tab.agentTicket)
        assumeTrue(env.isNotEmpty(), "needs agent socket")

        // Before any work, neither tab announces anything in the project bar.
        assertEquals(Activity.Running, tab.activity)
        assertNull(projectAgentNews(listOf(tab)))

        // PreInvocation starts the turn -> Working.
        val (c1, out1, err1) = runScript(python, script, "working", """{"conversationId":"conv-alpha","invocationNum":0}""", env)
        assertEquals(0, c1, err1)
        assertEquals("{}", out1)
        assertEquals("", err1)
        assertEquals(Activity.Working, tab.activity)
        assertTrue(tab.hasWorked)
        assertFalse(tab.unseen)

        // Once the tab's conversation is known, a subagent's Stop in another conversation is ignored.
        tab.run.nativeSessionId = "conv-alpha"
        val (cSub, outSub, errSub) = runScript(python, script, "idle", """{"conversationId":"conv-subagent","terminationReason":"NO_TOOL_CALL"}""", env)
        assertEquals(0, cSub, errSub)
        assertEquals("""{"decision":"allow"}""", outSub)
        assertEquals(Activity.Working, tab.activity, "a subagent stopping must not mark the parent tab idle")

        // Stop for this conversation finishes the turn -> Idle + unseen (tab is unwatched).
        val (c2, out2, err2) = runScript(python, script, "idle", """{"conversationId":"conv-alpha","terminationReason":"NO_TOOL_CALL"}""", env)
        assertEquals(0, c2, err2)
        assertEquals("""{"decision":"allow"}""", out2)
        assertEquals("", err2)
        assertEquals(Activity.Idle, tab.activity)
        assertTrue(tab.unseen)
        assertEquals(iondrive.nop.ui.ProjectAgentNews(Activity.Idle, unseen = true), projectAgentNews(listOf(tab)))

        // Looking at the tab clears unseen.
        tab.watch()
        assertFalse(tab.unseen)
        assertNull(projectAgentNews(listOf(tab)))
        tab.unwatch()

        // A foreign conversation already claimed by a sibling tab cannot touch an unlocated tab.
        LiveTranscripts.claim("conv-alpha", tab.sessionId)
        val siblingEnv = AgentSocket.runEnv(sibling.agentTicket)
        runScript(python, script, "working", """{"conversationId":"conv-alpha"}""", siblingEnv)
        assertEquals(Activity.Running, sibling.activity)
    }

    @Test
    fun `outside nop, with a broken socket, or on garbage input the script always answers cleanly`(@TempDir tmp: Path) {
        val python = AddressGuard.python()
        assumeTrue(python != null, "needs python3")
        val root = tmp.resolve("data")
        AntigravityActivityHook.writeScript(root, python!!)
        val script = AntigravityActivityHook.scriptPath(root)

        val (c1, out1, err1) = runScript(python, script, "working", """{"conversationId":"conv-1"}""")
        assertEquals(0, c1)
        assertEquals("{}", out1)
        assertEquals("", err1)

        val (c2, out2, err2) = runScript(python, script, "idle", """{"conversationId":"conv-1"}""")
        assertEquals(0, c2)
        assertEquals("""{"decision":"allow"}""", out2)
        assertEquals("", err2)

        val badEnv = mapOf(
            AgentSocket.SOCKET_ENV to tmp.resolve("missing.sock").toString(),
            AgentSocket.TICKET_ENV to "deadbeef",
        )
        val (c3, out3, err3) = runScript(python, script, "idle", "{ not json", badEnv)
        assertEquals(0, c3)
        assertEquals("""{"decision":"allow"}""", out3)
        assertEquals("", err3)
    }
}
