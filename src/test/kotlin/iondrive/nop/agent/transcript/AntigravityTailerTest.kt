package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Antigravity
import iondrive.nop.agent.Block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * The Antigravity reader: which conversation is this run's, and what is in it.
 *
 * The account's prompt history is shared by every tab the account runs, and a conversation's first
 * prompt there names no conversation, so two tabs on one project cannot be told apart by it. The
 * tests that matter most here are the ones where a sibling tab's conversation is the newest thing
 * on disk and must still not be taken.
 */
class AntigravityTailerTest {

    private val project = Path.of("/home/dev/nop")
    private val startedAt = 1_700_000_000_000L
    private val agyPid = 4242L

    private fun run(
        home: Path,
        pid: (() -> Long?)? = { agyPid },
        resumeId: String? = null,
        foreign: (String) -> Boolean = { false },
    ) = RunContext(
        projectDir = project,
        home = home,
        nativeSessionId = resumeId,
        startedAt = startedAt,
        foreign = foreign,
        pid = pid,
    )

    /** A tailer whose process holds whatever [locks] says at the moment it is asked. */
    private fun tailer(home: Path, locks: () -> List<String>?) =
        AntigravityTailer(home) { _, pid -> if (pid == agyPid) locks() else null }

    private fun transcript(home: Path, id: String, vararg rows: String, modifiedAt: Long = startedAt): Path {
        val file = Antigravity.conversationTranscript(home, id)
        Files.createDirectories(file.parent)
        Files.writeString(file, rows.joinToString("") { "$it\n" })
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAt))
        return file
    }

    private fun history(home: Path, vararg lines: String) {
        val file = Antigravity.historyFile(home)
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n") + "\n")
    }

    private fun prompt(text: String, at: Long = startedAt + 1_000, workspace: String = project.toString(), conversation: String? = null) =
        buildString {
            append("""{"display":"$text","timestamp":$at,"workspace":"$workspace"""")
            conversation?.let { append(""","conversationId":"$it"""") }
            append("}")
        }

    private fun userInput(text: String) =
        """{"step_index":0,"type":"USER_INPUT","status":"DONE","created_at":"2023-11-14T22:13:21Z",""" +
            """"content":"<USER_REQUEST>\n$text\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nThe current local time is: now.\n</ADDITIONAL_METADATA>"}"""

    // ── which conversation is this run's ──

    /**
     * Two tabs on one project, one account. The sibling's conversation is the newest one on disk
     * and the last one the shared history mentions; this run's process holds the lock for its own.
     */
    @Test
    fun `the conversation is the one this run's process holds, not the newest in the project`(@TempDir tmp: Path) {
        transcript(tmp, "conv-mine", userInput("why is the tab not orange"), modifiedAt = startedAt + 1_000)
        transcript(tmp, "conv-sibling", userInput("resize the panels"), modifiedAt = startedAt + 9_000)
        history(tmp, prompt("why is the tab not orange"), prompt("resize the panels", at = startedAt + 9_000, conversation = "conv-sibling"))
        val tailer = tailer(tmp) { listOf("conv-mine") }

        assertEquals(Antigravity.conversationTranscript(tmp, "conv-mine"), tailer.locate(run(tmp)))
        assertEquals("conv-mine", tailer.nativeSessionId())
    }

    @Test
    fun `nothing is located while the process is in no conversation, whatever the history says`(@TempDir tmp: Path) {
        transcript(tmp, "conv-sibling", userInput("theirs"))
        history(tmp, prompt("theirs", conversation = "conv-sibling"))
        val tailer = tailer(tmp) { emptyList() }

        assertNull(tailer.locate(run(tmp)))
        assertNull(tailer.nativeSessionId())
    }

    @Test
    fun `nothing is located before the process has started`(@TempDir tmp: Path) {
        transcript(tmp, "conv-sibling", userInput("theirs"))
        history(tmp, prompt("theirs", conversation = "conv-sibling"))
        val tailer = tailer(tmp) { listOf("conv-sibling") }

        assertNull(tailer.locate(run(tmp, pid = { null })))
    }

    @Test
    fun `a conversation whose transcript is not written yet is waited for`(@TempDir tmp: Path) {
        val tailer = tailer(tmp) { listOf("conv-mine") }
        assertNull(tailer.locate(run(tmp)))
    }

    @Test
    fun `a lock another tab is following is left to it`(@TempDir tmp: Path) {
        transcript(tmp, "conv-theirs", userInput("theirs"))
        val tailer = tailer(tmp) { listOf("conv-theirs") }

        assertNull(tailer.locate(run(tmp, foreign = { it == "conv-theirs" })))
    }

    /** A `/clear` inside the TUI moves the CLI to a new conversation, and its lock with it. */
    @Test
    fun `a conversation the CLI moves to is followed`(@TempDir tmp: Path) {
        transcript(tmp, "conv-1", userInput("first"))
        var locks = listOf("conv-1")
        val tailer = tailer(tmp) { locks }
        val context = run(tmp)
        val first = tailer.locate(context)!!

        locks = listOf("conv-2")
        transcript(tmp, "conv-2", userInput("after a clear"))
        Thread.sleep(1_100)

        assertEquals(Antigravity.conversationTranscript(tmp, "conv-2"), tailer.switched(context, first))
        assertEquals("conv-2", tailer.nativeSessionId())
    }

    @Test
    fun `the conversation being followed is kept while its lock is still held`(@TempDir tmp: Path) {
        transcript(tmp, "conv-1", userInput("first"), modifiedAt = startedAt)
        transcript(tmp, "conv-2", userInput("other"), modifiedAt = startedAt + 5_000)
        var locks = listOf("conv-1")
        val tailer = tailer(tmp) { locks }
        val context = run(tmp)
        val first = tailer.locate(context)!!

        locks = listOf("conv-1", "conv-2")
        Thread.sleep(1_100)

        assertNull(tailer.switched(context, first))
        assertEquals("conv-1", tailer.nativeSessionId())
    }

    /** Without a process to ask, a resumed run is in the conversation it was resumed with. */
    @Test
    fun `with no process to ask, the resumed conversation is used`(@TempDir tmp: Path) {
        transcript(tmp, "conv-resumed", userInput("earlier"))
        val tailer = AntigravityTailer(tmp) { _, _ -> null }

        assertEquals(
            Antigravity.conversationTranscript(tmp, "conv-resumed"),
            tailer.locate(run(tmp, pid = null, resumeId = "conv-resumed")),
        )
    }

    @Test
    fun `with no process to ask, only a history line that names its conversation counts`(@TempDir tmp: Path) {
        transcript(tmp, "conv-named", userInput("second prompt"))
        val tailer = AntigravityTailer(tmp) { _, _ -> null }
        history(
            tmp,
            prompt("last week", at = startedAt - 86_400_000, conversation = "conv-old"),
            prompt("elsewhere", workspace = "/home/dev/other", conversation = "conv-elsewhere"),
            prompt("first prompt"),
        )
        assertNull(tailer.locate(run(tmp, pid = null)))

        history(tmp, prompt("first prompt"), prompt("second prompt", at = startedAt + 2_000, conversation = "conv-named"))
        assertEquals(Antigravity.conversationTranscript(tmp, "conv-named"), tailer.locate(run(tmp, pid = null)))
    }

    /** The real `/proc` reader, against a lock this test's own process holds open. */
    @Test
    fun `a presence lock held open by a process is found through proc`(@TempDir tmp: Path) {
        if (!Files.isDirectory(Path.of("/proc/self/fd"))) return
        val dir = Antigravity.presenceDir(tmp)
        Files.createDirectories(dir)
        Files.createFile(dir.resolve("conv-unheld.lock"))
        FileChannel.open(dir.resolve("conv-held.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
            val held = heldConversations(tmp, ProcessHandle.current().pid())!!
            assertTrue("conv-held" in held)
            assertFalse("conv-unheld" in held)
        }
    }

    @Test
    fun `a process that is gone cannot be asked`(@TempDir tmp: Path) {
        assertNull(heldConversations(tmp, Long.MAX_VALUE))
    }

    // ── what is in a conversation ──

    private fun located(tmp: Path): AntigravityTailer {
        transcript(tmp, "conv-1", userInput("x"))
        return tailer(tmp) { listOf("conv-1") }.also { it.locate(run(tmp)) }
    }

    @Test
    fun `a prompt is the request the user typed, without the CLI's wrapping`(@TempDir tmp: Path) {
        val events = located(tmp).parse(userInput("fix the failing test"))

        assertEquals(listOf("fix the failing test"), events.filterIsInstance<AgentEvent.UserMessage>().map { it.text })
    }

    @Test
    fun `a model turn carries its reasoning, its reply and its calls in the handoff's vocabulary`(@TempDir tmp: Path) {
        val events = located(tmp).parse(
            """{"step_index":3,"type":"PLANNER_RESPONSE","status":"DONE","created_at":"2023-11-14T22:13:25Z",""" +
                """"input_tokens":1200,"output_tokens":80,"cache_read_tokens":900,"thinking":"Look first.","content":"Checking.",""" +
                """"tool_calls":[""" +
                """{"name":"run_command","args":{"CommandLine":"\"./gradlew test\"","Cwd":"\"/home/dev/nop\"","toolAction":"\"Running\""}},""" +
                """{"name":"replace_file_content","args":{"TargetFile":"\"/home/dev/nop/src/A.kt\"","ReplacementContent":"\"x\""}},""" +
                """{"name":"write_to_file","args":{"TargetFile":"\"/home/dev/nop/src/B.kt\"","CodeContent":"\"y\""}}]}""",
        )

        val turn = events.first() as AgentEvent.AssistantMessage
        assertEquals(Block.Thinking("Look first."), turn.blocks[0])
        assertEquals(Block.Text("Checking."), turn.blocks[1])
        assertEquals(1200L, turn.usage?.inputTokens)
        val started = events.filterIsInstance<AgentEvent.ToolStarted>()
        assertEquals(listOf("Bash", "Edit", "Write"), started.map { it.tool })
        assertEquals("./gradlew test", started[0].args["command"])
        assertEquals("/home/dev/nop/src/A.kt", started[1].args["file_path"])
        assertEquals(3, started.map { it.callId }.toSet().size)
    }

    @Test
    fun `tool results answer a turn's calls in order, with the command's exit code`(@TempDir tmp: Path) {
        val tailer = located(tmp)
        val started = tailer.parse(
            """{"step_index":3,"type":"PLANNER_RESPONSE","created_at":"2023-11-14T22:13:25Z","tool_calls":[""" +
                """{"name":"run_command","args":{"CommandLine":"\"make\""}},{"name":"view_file","args":{"AbsolutePath":"\"/a\""}}]}""",
        ).filterIsInstance<AgentEvent.ToolStarted>()

        val first = tailer.parse(
            """{"step_index":4,"type":"GENERIC","status":"DONE","created_at":"2023-11-14T22:13:27Z",""" +
                """"content":"Created At: now\nCompleted At: now\nbuild broke\nThe command exited with code 2"}""",
        ).single() as AgentEvent.ToolFinished
        val second = tailer.parse(
            """{"step_index":5,"type":"GENERIC","status":"ERROR","error":"no such file","created_at":"2023-11-14T22:13:28Z","content":"x"}""",
        ).single() as AgentEvent.ToolFinished

        assertEquals(started[0].callId, first.callId)
        assertEquals(2, first.exitCode)
        assertTrue(first.summary.startsWith("build broke"))
        assertEquals(2_000L, first.durationMs)
        assertEquals(started[1].callId, second.callId)
        assertTrue(second.isError)
        assertEquals("no such file", second.summary)
    }

    /** A call left without a result row is over by the time the model speaks again. */
    @Test
    fun `calls still open when the model speaks again are closed`(@TempDir tmp: Path) {
        val tailer = located(tmp)
        val call = tailer.parse(
            """{"step_index":3,"type":"PLANNER_RESPONSE","tool_calls":[{"name":"manage_task","args":{"Action":"\"status\""}}]}""",
        ).filterIsInstance<AgentEvent.ToolStarted>().single()

        val next = tailer.parse("""{"step_index":5,"type":"PLANNER_RESPONSE","content":"Done."}""")

        assertEquals(call.callId, (next.first() as AgentEvent.ToolFinished).callId)
    }

    /** The open question is what makes the tab show it is waiting for the user. */
    @Test
    fun `a question stays open until it is answered`(@TempDir tmp: Path) {
        val tailer = located(tmp)
        val asked = tailer.parse(
            """{"step_index":3,"type":"PLANNER_RESPONSE","tool_calls":[{"name":"ask_question","args":{"questions":"[]"}}]}""",
        ).filterIsInstance<AgentEvent.ToolStarted>().single()

        assertTrue(asked.tool in iondrive.nop.agent.ActivityTracker.QUESTION_TOOLS)
    }

    @Test
    fun `the CLI's own notices and lines that are not JSON produce nothing`(@TempDir tmp: Path) {
        val tailer = located(tmp)

        assertTrue(tailer.parse("""{"type":"SYSTEM_MESSAGE","content":"a task finished"}""").isEmpty())
        assertTrue(tailer.parse("""{"type":"CHECKPOINT"}""").isEmpty())
        assertTrue(tailer.parse("""{"type":"GENERIC","content":"no call waiting"}""").isEmpty())
        assertTrue(tailer.parse("{ not json").isEmpty())
        assertTrue(tailer.parse("").isEmpty())
    }

    // ── titles ──

    @Test
    fun `poll returns the title once the CLI has written one, and only once`(@TempDir tmp: Path) {
        val tailer = located(tmp)
        assertTrue(tailer.poll().isEmpty())

        val annotation = Antigravity.annotationsFile(tmp, "conv-1")
        Files.createDirectories(annotation.parent)
        Files.writeString(annotation, "title:\"Work on feature X\"\n")

        assertEquals(listOf("Work on feature X"), tailer.poll().filterIsInstance<AgentEvent.SessionTitled>().map { it.title })
        assertTrue(tailer.poll().isEmpty())
    }
}
