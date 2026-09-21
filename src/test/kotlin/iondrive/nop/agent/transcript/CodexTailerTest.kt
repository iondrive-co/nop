package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * The Codex rollout reader, against an anonymised slice of real rollouts.
 *
 * Codex's format is nothing like Claude's — messages, tool calls and token counts arrive as
 * separate record kinds, tools are named differently, and a turn's usage is reported *after* the
 * message it belongs to. The point of these assertions is that both formats come out the other side
 * in one vocabulary, because that is the only thing that makes a summary written by one provider
 * readable by the other.
 */
class CodexTailerTest {

    private fun fixture(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/agent/codex-rollout.jsonl"))
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun parseAll(tailer: CodexTailer = CodexTailer(Path.of("/nowhere"))): List<AgentEvent> =
        fixture().flatMap { tailer.parse(it) }

    @Test
    fun `the user's prompt and the agent's answer both come through`() {
        val events = parseAll()

        assertEquals(
            listOf("Check the two commits on this branch and say whether the second approach works"),
            events.filterIsInstance<AgentEvent.UserMessage>().map { it.text },
        )
        val answers = events.filterIsInstance<AgentEvent.AssistantMessage>()
            .flatMap { it.blocks }
            .filterIsInstance<Block.Text>()
        assertTrue(answers.isNotEmpty(), "no agent_message reached the log")
    }

    @Test
    fun `reasoning becomes a thinking block, the same shape Claude's does`() {
        val thinking = parseAll()
            .filterIsInstance<AgentEvent.AssistantMessage>()
            .flatMap { it.blocks }
            .filterIsInstance<Block.Thinking>()

        assertTrue(thinking.isNotEmpty())
        assertTrue(thinking.first().text.isNotBlank())
    }

    /**
     * The whole point of [Normalize]. A handoff summary that calls the same act `exec_command` in
     * one paragraph and `Bash` in the next makes the reader work out that they are the same thing —
     * which is exactly the work the summary was supposed to have done for them.
     */
    @Test
    fun `codex tool names arrive in the shared vocabulary`() {
        val started = parseAll().filterIsInstance<AgentEvent.ToolStarted>()

        assertEquals(listOf("Bash"), started.map { it.tool })
        assertEquals("git status --short", Normalize.describe("Bash", started.single().args))
    }

    @Test
    fun `a tool result is paired back to its call and carries the exit code from the output`() {
        val events = parseAll()
        val started = events.filterIsInstance<AgentEvent.ToolStarted>().single()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()

        assertEquals(started.callId, finished.callId)
        assertEquals(0, finished.exitCode)
        assertFalse(finished.isError)
        assertNotNull(finished.durationMs)
    }

    @Test
    fun `a non-zero exit code is an error`() {
        val tailer = CodexTailer(Path.of("/nowhere"))
        tailer.parse(
            """{"type":"response_item","timestamp":"2026-09-15T00:00:00.000Z","payload":{"type":"function_call",""" +
                """"name":"exec_command","arguments":"{\"cmd\":\"make test\"}","call_id":"c1"}}""",
        )

        val finished = tailer.parse(
            """{"type":"response_item","timestamp":"2026-09-15T00:00:02.000Z","payload":{"type":"function_call_output",""" +
                """"call_id":"c1","output":"Wall time: 1s\nProcess exited with code 1\nOutput:\nFAILED"}}""",
        ).filterIsInstance<AgentEvent.ToolFinished>().single()

        assertEquals(1, finished.exitCode)
        assertTrue(finished.isError)
        assertEquals(2000, finished.durationMs)
    }

    /**
     * The exit code only ever appears in the short preamble Codex writes above a command's output.
     * Searching the whole thing would let a build log that happens to print the phrase set the code
     * of a command that in fact succeeded.
     */
    @Test
    fun `a command whose own output mentions an exit code is not misread`() {
        val tailer = CodexTailer(Path.of("/nowhere"))
        val output = "Wall time: 1s\nProcess exited with code 0\nOutput:\n" +
            "x".repeat(600) + "\nProcess exited with code 7\n"

        val finished = tailer.parse(
            """{"type":"response_item","timestamp":"2026-09-15T00:00:00.000Z","payload":{"type":"function_call_output",""" +
                """"call_id":"c1","output":${kotlinx.serialization.json.JsonPrimitive(output)}}}""",
        ).filterIsInstance<AgentEvent.ToolFinished>().single()

        assertEquals(0, finished.exitCode, "the code in the preamble is the command's own")
    }

    /**
     * Codex reports what a turn cost in its own `token_count` event rather than on the message, so
     * the count is carried to the next assistant message. The fixture ends on the count — which is
     * where a real rollout has it, after the turn it describes — so the message it attaches to is
     * added here.
     */
    @Test
    fun `a turn's token count is carried onto the next assistant message`() {
        val tailer = CodexTailer(Path.of("/nowhere"))
        parseAll(tailer)

        val next = tailer.parse(
            """{"type":"event_msg","timestamp":"2026-09-15T00:01:00.000Z","payload":{"type":"agent_message",""" +
                """"message":"Done."}}""",
        ).filterIsInstance<AgentEvent.AssistantMessage>().single()

        val usage = next.usage
        assertNotNull(usage, "the token_count in the fixture reached no turn at all")
        assertEquals(23512L, usage!!.inputTokens)
        assertEquals(440L, usage.outputTokens)
        assertEquals(2432L, usage.cacheReadTokens)
        assertEquals(196L, usage.thinkingTokens)
    }

    @Test
    fun `token_count rate_limits update Usage live reading`(@TempDir tmp: Path) {
        val tailer = CodexTailer(tmp)
        val future = java.time.Instant.now().plusSeconds(3600).epochSecond
        val line = """{"type":"event_msg","timestamp":"2026-09-15T00:00:00.000Z","payload":{"type":"token_count",""" +
            """"info":{"total_token_usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120}},""" +
            """"rate_limits":{"primary":{"used_percent":88.5,"window_minutes":300,"resets_at":$future},"secondary":null}}}"""

        tailer.parse(line)

        val live = iondrive.nop.agent.Usage.liveCodexReading(tmp)
        assertNotNull(live)
        assertEquals(88.5, live?.session?.percent)
    }

    @Test
    fun `a count is spent once, not repeated onto every later turn`() {
        val tailer = CodexTailer(Path.of("/nowhere"))
        parseAll(tailer)
        val line = """{"type":"event_msg","timestamp":"2026-09-15T00:01:00.000Z","payload":{"type":"agent_message",""" +
            """"message":"Done."}}"""

        tailer.parse(line)
        val second = tailer.parse(line).filterIsInstance<AgentEvent.AssistantMessage>().single()

        assertNull(second.usage, "the same turn's cost was counted twice")
    }

    @Test
    fun `the model comes from the turn context`() {
        val turns = parseAll().filterIsInstance<AgentEvent.AssistantMessage>()

        assertTrue(turns.all { it.model == "gpt-5.5" }, "turns reported ${turns.map { it.model }}")
    }

    @Test
    fun `an interrupted turn is recorded as one, so a handoff doesn't read as finished work`() {
        val tailer = CodexTailer(Path.of("/nowhere"))

        val aborted = tailer.parse(
            """{"type":"event_msg","timestamp":"2026-09-15T00:00:00.000Z","payload":{"type":"turn_aborted",""" +
                """"reason":"interrupted","duration_ms":36909}}""",
        ).filterIsInstance<AgentEvent.AssistantMessage>().single()

        assertEquals("aborted:interrupted", aborted.stopReason)
    }

    @Test
    fun `an exec_command_end supplies a real exit code and wall time`() {
        val tailer = CodexTailer(Path.of("/nowhere"))

        val finished = tailer.parse(
            """{"type":"event_msg","timestamp":"2026-09-15T00:00:00.000Z","payload":{"type":"exec_command_end",""" +
                """"call_id":"c9","exit_code":3,"stdout":"nope","duration":{"secs":2,"nanos":500000000}}}""",
        ).filterIsInstance<AgentEvent.ToolFinished>().single()

        assertEquals(3, finished.exitCode)
        assertEquals(2500, finished.durationMs)
        assertTrue(finished.isError)
    }

    @Test
    fun `the CLI's own preamble is not mistaken for something the user said`() {
        val tailer = CodexTailer(Path.of("/nowhere"))

        val events = tailer.parse(
            """{"type":"response_item","timestamp":"2026-09-15T00:00:00.000Z","payload":{"type":"message",""" +
                """"role":"developer","content":[{"type":"input_text","text":"<permissions instructions>…"}]}}""",
        )

        assertTrue(events.isEmpty(), "the sandbox preamble is not a turn of the conversation")
    }

    @Test
    fun `a line that isn't JSON is skipped`() {
        assertTrue(CodexTailer(Path.of("/nowhere")).parse("not json at all").isEmpty())
    }

    // ── Locating ──

    private fun rollout(home: Path, name: String, cwd: String, at: Long): Path {
        val dir = home.resolve(".codex/sessions/2026/09/15")
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.writeString(
            file,
            """{"type":"session_meta","timestamp":"2026-09-15T00:00:00.000Z","payload":""" +
                """{"id":"${name.removeSuffix(".jsonl")}","cwd":"$cwd","cli_version":"0.154.0"}}""" + "\n",
        )
        Files.setLastModifiedTime(file, FileTime.fromMillis(at))
        return file
    }

    /**
     * Codex names its own session, so the rollout has to be found rather than named. Matching on the
     * working directory as well as the time is what stops two sessions started seconds apart in two
     * projects from reading each other's transcripts.
     */
    @Test
    fun `the rollout is matched on the project it was opened in`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val now = System.currentTimeMillis()
        val other = rollout(home, "rollout-other.jsonl", "/somewhere/else", now + 1_000)
        val mine = rollout(home, "rollout-mine.jsonl", project.toAbsolutePath().normalize().toString(), now)

        val tailer = CodexTailer(home)
        val located = tailer.locate(RunContext(project, home, null, now))

        assertEquals(mine, located, "the newer rollout belongs to a different project")
        assertEquals("rollout-mine", tailer.nativeSessionId(), "the id is what a native resume needs")
        assertTrue(Files.exists(other))
    }

    @Test
    fun `a rollout written before this run started is not adopted`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val now = System.currentTimeMillis()
        rollout(home, "rollout-old.jsonl", project.toAbsolutePath().normalize().toString(), now - 600_000)

        assertNull(CodexTailer(home).locate(RunContext(project, home, null, now)))
    }

    @Test
    fun `nothing is located before the CLI has written anything`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }

        assertNull(
            CodexTailer(tmp.resolve("empty-home"))
                .locate(RunContext(project, tmp, null, System.currentTimeMillis())),
        )
    }
}
