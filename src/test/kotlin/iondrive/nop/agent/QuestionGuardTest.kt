package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * The hook nop puts in front of every Claude question, and how it gets into the account's
 * settings beside the address guard. The verdicts are run through the real script with `python3`,
 * over transcripts in the shape Claude Code 2.1.319 writes them: one line per block of a reply,
 * each carrying the reply's message id, and every user line carrying its prompt's id.
 */
class QuestionGuardTest {

    private val addressGuard = "/opt/example/python3 -I -S /opt/example/nop-address-guard"
    private val questionGuard = "/opt/example/python3 -I -S /opt/example/nop-question-guard"
    private val others = """{"matcher":"*","hooks":[{"type":"command","command":"/opt/other/recorder PreToolUse"}]}"""

    private fun parse(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    private fun claude(home: Path) = Account("acct", Provider.Anthropic, home.toString())

    private fun preToolUse(home: Path) = parse(home.resolve("settings.json"))["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray

    // --- installation --------------------------------------------------------------------------

    @Test
    fun `claude gets the question hook on AskUserQuestion beside the address guard and the others`(@TempDir home: Path) {
        Files.writeString(home.resolve("settings.json"), """{"permissions":{"deny":["Agent"]},"hooks":{"PreToolUse":[$others]}}""")

        AddressGuard.hook(claude(home), addressGuard, questionGuard)

        val pre = preToolUse(home)
        assertEquals(Json.parseToJsonElement(others), pre[0])
        val byCommand = pre.drop(1).associate {
            val group = it.jsonObject
            group["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content to group["matcher"]!!.jsonPrimitive.content
        }
        assertEquals(mapOf("$addressGuard claude" to "*", questionGuard to "AskUserQuestion"), byCommand)
        assertEquals("Agent", parse(home.resolve("settings.json"))["permissions"]!!.jsonObject["deny"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `installing both twice writes nothing the second time`(@TempDir home: Path) {
        AddressGuard.hook(claude(home), addressGuard, questionGuard)
        val first = Files.readString(home.resolve("settings.json"))
        val stamp = Files.getLastModifiedTime(home.resolve("settings.json"))
        Thread.sleep(20)

        AddressGuard.hook(claude(home), addressGuard, questionGuard)

        assertEquals(first, Files.readString(home.resolve("settings.json")))
        assertEquals(stamp, Files.getLastModifiedTime(home.resolve("settings.json")))
    }

    @Test
    fun `a moved question script replaces nop's old question hook and leaves the address guard`(@TempDir home: Path) {
        AddressGuard.hook(claude(home), addressGuard, "/old/place/nop-question-guard")

        AddressGuard.hook(claude(home), addressGuard, questionGuard)

        val commands = preToolUse(home).map { it.jsonObject["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content }
        assertEquals(listOf("$addressGuard claude", questionGuard), commands)
    }

    @Test
    fun `no interpreter takes both of nop's hooks out and leaves the others`(@TempDir home: Path) {
        Files.writeString(home.resolve("settings.json"), """{"hooks":{"PreToolUse":[$others]}}""")
        AddressGuard.hook(claude(home), addressGuard, questionGuard)

        AddressGuard.hook(claude(home), null, null)

        assertEquals(Json.parseToJsonElement("""{"hooks":{"PreToolUse":[$others]}}"""), parse(home.resolve("settings.json")))
    }

    @Test
    fun `codex and agy get no question hook`(@TempDir tmp: Path) {
        val codex = Files.createDirectories(tmp.resolve("codex"))
        val agy = Files.createDirectories(tmp.resolve("agy"))

        AddressGuard.hook(Account("c", Provider.OpenAI, codex.toString()), addressGuard, questionGuard)
        AddressGuard.hook(Account("g", Provider.Antigravity, agy.toString()), addressGuard, questionGuard)

        assertTrue(QuestionGuard.SCRIPT_NAME !in Files.readString(codex.resolve(".codex").resolve("hooks.json")))
        assertTrue(QuestionGuard.SCRIPT_NAME !in Files.readString(agy.resolve(".gemini").resolve("config").resolve("hooks.json")))
    }

    // --- the guard itself ----------------------------------------------------------------------

    /** A transcript being written, and the hook run over it the way Claude Code runs it. */
    private class Session(val dir: Path, val python: String, val root: Path) {
        val transcript: Path = dir.resolve("transcript.jsonl")
        private var next = 0

        fun line(json: String) {
            Files.writeString(transcript, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }

        fun prompt(promptId: String, text: String, entrypoint: String = "cli") =
            line("""{"type":"user","promptId":"$promptId","entrypoint":"$entrypoint","message":{"role":"user","content":${q(text)}}}""")

        /** One reply: a line per block, reasoning first, and the question last. Returns the question's tool_use id. */
        fun reply(text: String? = null, entrypoint: String = "cli"): String {
            val message = "msg_${next++}"
            val toolUse = "toolu_${next++}"
            fun block(b: String) = line("""{"type":"assistant","entrypoint":"$entrypoint","message":{"id":"$message","role":"assistant","content":[$b]}}""")
            block("""{"type":"thinking","thinking":"","signature":"c2lnbmF0dXJl"}""")
            text?.let { block("""{"type":"text","text":${q(it)}}""") }
            block("""{"type":"tool_use","id":"$toolUse","name":"AskUserQuestion","input":{"questions":[{"question":"Ship the release?","header":"Release","multiSelect":false,"options":[{"label":"Ship","description":"tag it"},{"label":"Wait","description":"hold"}]}]}}""")
            return toolUse
        }

        /** What the CLI writes for the question's outcome: the hook's denial, or the user's answer. */
        fun result(promptId: String, toolUse: String, content: String, isError: Boolean = false) =
            line("""{"type":"user","promptId":"$promptId","entrypoint":"cli","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"$toolUse","content":${q(content)}${if (isError) ",\"is_error\":true" else ""}}]}}""")

        /** The denial's reason, or null when the question goes through. */
        fun ask(toolUse: String, promptId: String?, transcriptPath: Path = transcript): String? =
            run("""{"session_id":"s1","transcript_path":${q(transcriptPath.toString())},"cwd":"/work/example","hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","tool_use_id":"$toolUse"${promptId?.let { ",\"prompt_id\":\"$it\"" } ?: ""}}""")

        fun run(payload: String): String? {
            val process = ProcessBuilder(python, "-I", "-S", QuestionGuard.scriptPath(root).toString()).redirectErrorStream(true).start()
            process.outputStream.use { it.write(payload.toByteArray()) }
            val out = process.inputStream.readAllBytes().decodeToString().trim()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), out)
            if (out.isEmpty()) return null
            val decision = Json.parseToJsonElement(out).jsonObject["hookSpecificOutput"]!!.jsonObject
            assertEquals("deny", decision["permissionDecision"]!!.jsonPrimitive.content)
            return decision["permissionDecisionReason"]!!.jsonPrimitive.content
        }

        fun log(): String = QuestionGuard.logFile(root).let { if (Files.exists(it)) Files.readString(it) else "" }

        private fun q(s: String) = JsonPrimitive(s).toString()
    }

    private fun session(tmp: Path): Session {
        val python = AddressGuard.python()
        assumeTrue(python != null, "needs python3")
        val root = tmp.resolve("data")
        QuestionGuard.writeScript(root, python!!)
        return Session(Files.createDirectories(tmp.resolve("session")), python, root)
    }

    @Test
    fun `a question whose reply has text goes through`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")

        assertNull(s.ask(s.reply(text = "Release notes for 3.2: faster sync, two fixes."), "p1"))
        assertEquals("", s.log())
    }

    @Test
    fun `a question whose reply is only reasoning is held once, and the model is told why`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")

        val why = s.ask(s.reply(), "p1")

        assertTrue(why != null && why.startsWith("nop's question guard") && "not on their screen" in why, why)
        assertTrue(" held session s1 in /work/example" in s.log(), s.log())
    }

    @Test
    fun `asked again in the same prompt, with or without text, the question goes through`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")
        val first = s.reply()
        val why = s.ask(first, "p1")!!
        s.result("p1", first, "PreToolUse:AskUserQuestion hook error: $why", isError = true)

        assertNull(s.ask(s.reply(), "p1"))
        assertTrue(" asked again session s1" in s.log(), s.log())
    }

    @Test
    fun `the next prompt is checked afresh`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")
        val first = s.reply()
        s.result("p1", first, "PreToolUse:AskUserQuestion hook error: ${s.ask(first, "p1")}", isError = true)
        val second = s.reply()
        assertNull(s.ask(second, "p1"))
        s.result("p1", second, "Your questions have been answered: \"Ship the release?\"=\"Wait\".")

        s.prompt("p2", "now the changelog")

        assertTrue(s.ask(s.reply(), "p2") != null)
    }

    @Test
    fun `without a prompt id, the turn starts at the last thing the user typed`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")
        val first = s.reply()
        s.result("p1", first, "PreToolUse:AskUserQuestion hook error: ${s.ask(first, null)}", isError = true)
        assertNull(s.ask(s.reply(), null))

        s.prompt("p2", "now the changelog")

        assertTrue(s.ask(s.reply(), null) != null)
    }

    @Test
    fun `the reply is waited for when the CLI writes it while the hook runs`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")
        val toolUse = "toolu_late"
        val writer = Thread {
            Thread.sleep(400)
            s.line("""{"type":"assistant","entrypoint":"cli","message":{"id":"msg_late","role":"assistant","content":[{"type":"thinking","thinking":"","signature":"c2ln"}]}}""")
            s.line("""{"type":"assistant","entrypoint":"cli","message":{"id":"msg_late","role":"assistant","content":[{"type":"tool_use","id":"$toolUse","name":"AskUserQuestion","input":{}}]}}""")
        }.apply { start() }

        val why = s.ask(toolUse, "p1")
        writer.join()

        assertTrue(why != null, "held once the reply landed")
    }

    @Test
    fun `a headless run is left alone`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes", entrypoint = "sdk-cli")

        assertNull(s.ask(s.reply(entrypoint = "sdk-cli"), "p1"))
    }

    @Test
    fun `a reply that never reaches the transcript, or no transcript, or garbage, lets the question through`(@TempDir tmp: Path) {
        val s = session(tmp)
        s.prompt("p1", "finish the release notes")

        assertNull(s.ask("toolu_never", "p1"))
        assertNull(s.ask("toolu_never", "p1", transcriptPath = tmp.resolve("missing.jsonl")))
        assertNull(s.run("{ not json"))
        assertTrue("reply not seen" in s.log(), s.log())
        assertTrue(" error " in s.log(), s.log())
    }

    @Test
    fun `the script names its log and runs under the interpreter nop found`(@TempDir tmp: Path) {
        val s = session(tmp)
        val script = Files.readString(QuestionGuard.scriptPath(s.root))

        assertTrue(script.startsWith("#!${s.python} -I -S\n"))
        assertTrue(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(QuestionGuard.logFile(s.root).toString())) in script)
        assertTrue("@@" !in script)
    }
}
