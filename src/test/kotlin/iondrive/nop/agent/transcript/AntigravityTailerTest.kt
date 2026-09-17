package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Antigravity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * The Antigravity reader, which reads the only thing `agy` writes in a form anything else can read.
 *
 * Its whole difficulty is that `history.jsonl` is *shared*: one file per home, appended to by every
 * run in every project that account has ever opened. Claude and Codex each write a file per
 * session, so for them "this file is this run's" is a given; here it has to be established a line
 * at a time, and getting it wrong means one tab logging another tab's prompts — or replaying a
 * conversation from last week into a session that just started.
 */
class AntigravityTailerTest {

    private val project = Path.of("/home/dev/nop")
    private val startedAt = 1_700_000_000_000L

    private fun run(
        home: Path,
        projectDir: Path = project,
        foreign: (String) -> Boolean = { false },
    ) = RunContext(
        projectDir = projectDir,
        home = home,
        nativeSessionId = null,
        startedAt = startedAt,
        foreign = foreign,
    )

    private fun history(home: Path, vararg lines: String) {
        val file = Antigravity.historyFile(home)
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n") + "\n")
    }

    private fun prompt(
        text: String,
        at: Long = startedAt + 1_000,
        workspace: String = project.toString(),
        conversation: String? = "conv-1",
        type: String? = null,
    ): String = buildString {
        append("""{"display":"$text","timestamp":$at,"workspace":"$workspace"""")
        conversation?.let { append(""","conversationId":"$it"""") }
        type?.let { append(""","type":"$it"""") }
        append("}")
    }

    private fun lastConversations(home: Path, body: String, at: Long) {
        val file = Antigravity.lastConversationsFile(home)
        Files.createDirectories(file.parent)
        Files.writeString(file, body)
        Files.setLastModifiedTime(file, FileTime.fromMillis(at))
    }

    // ── which lines are this run's ──

    @Test
    fun `a prompt typed in this project during this run comes through`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("fix the failing test"))
        tailer.locate(run(tmp))

        val events = tailer.parse(prompt("fix the failing test"))

        assertEquals(listOf("fix the failing test"), events.filterIsInstance<AgentEvent.UserMessage>().map { it.text })
    }

    /**
     * The same file holds every project's prompts. Without this a session in one checkout logs
     * what was typed in another, and a handoff summary describes work that happened somewhere else.
     */
    @Test
    fun `a prompt typed in another project is not this run's`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("hello"))
        tailer.locate(run(tmp))

        assertTrue(tailer.parse(prompt("something else", workspace = "/home/dev/other")).isEmpty())
    }

    /**
     * And every *previous* session's. A file located mid-run is read from the top, so the history
     * of the account arrives at the parser before anything this run did.
     */
    @Test
    fun `a prompt from before this run started is not replayed into it`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("current"))
        tailer.locate(run(tmp))

        assertTrue(tailer.parse(prompt("last week", at = startedAt - 86_400_000)).isEmpty())
        assertEquals(1, tailer.parse(prompt("current")).size)
    }

    @Test
    fun `a slash command is not logged as something the user asked the model`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("real prompt"))
        tailer.locate(run(tmp))

        assertTrue(tailer.parse(prompt("/exit", type = "slash_command")).isEmpty())
    }

    @Test
    fun `a line that is not JSON is skipped rather than throwing at the tailer thread`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("real prompt"))
        tailer.locate(run(tmp))

        assertTrue(tailer.parse("{ not json").isEmpty())
        assertTrue(tailer.parse("").isEmpty())
    }

    // ── the conversation id, which is what --conversation resumes ──

    @Test
    fun `nothing is located until this run has a conversation of its own`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        // The account has a history, all of it from before this run.
        history(tmp, prompt("yesterday", at = startedAt - 86_400_000, conversation = "conv-old"))

        assertNull(tailer.locate(run(tmp)))
        assertNull(tailer.nativeSessionId())
    }

    @Test
    fun `the id comes from the prompt this run typed`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(
            tmp,
            prompt("yesterday", at = startedAt - 86_400_000, conversation = "conv-old"),
            prompt("today", conversation = "conv-new"),
        )

        assertEquals(Antigravity.historyFile(tmp), tailer.locate(run(tmp)))
        assertEquals("conv-new", tailer.nativeSessionId())
    }

    /**
     * A session resumed and closed without a word typed appears in no history line at all, and is
     * exactly the session a restored tab has to come back into. The CLI writes this file when it
     * opens a conversation rather than when the user types.
     */
    @Test
    fun `a run that opened a conversation without typing is still found`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        lastConversations(tmp, """{"$project":"conv-reopened"}""", at = startedAt + 2_000)

        assertEquals(Antigravity.historyFile(tmp), tailer.locate(run(tmp)))
        assertEquals("conv-reopened", tailer.nativeSessionId())
    }

    /**
     * The entry outlives the session that made it. Adopting a stale one would have every run come
     * back into the conversation before it — and two tabs claim one conversation between them.
     */
    @Test
    fun `the conversation this project was last in is not adopted unless this run opened it`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        lastConversations(tmp, """{"$project":"conv-from-last-time"}""", at = startedAt - 60_000)

        assertNull(tailer.locate(run(tmp)))
    }

    @Test
    fun `another project's conversation is not this run's however recent it is`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        lastConversations(tmp, """{"/home/dev/other":"conv-elsewhere"}""", at = startedAt + 2_000)

        assertNull(tailer.locate(run(tmp)))
    }

    /**
     * Two nop tabs on one project both match everything above; [RunContext.foreign] is the only
     * thing that separates them, and the one that lost the race must not take the other's session.
     */
    @Test
    fun `a conversation another tab is already following is left to it`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("theirs", conversation = "conv-theirs"))
        lastConversations(tmp, """{"$project":"conv-theirs"}""", at = startedAt + 2_000)

        assertNull(tailer.locate(run(tmp, foreign = { it == "conv-theirs" })))
    }

    /**
     * The CLI moves the run to a new conversation on `/clear`, and the id nop hands out has to move
     * with it or a restored tab reopens the conversation the user just abandoned.
     */
    @Test
    fun `an id the CLI moves to mid-run replaces the one the run started in`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        history(tmp, prompt("first", conversation = "conv-1"))
        tailer.locate(run(tmp))
        assertEquals("conv-1", tailer.nativeSessionId())

        tailer.parse(prompt("after a clear", at = startedAt + 5_000, conversation = "conv-2"))

        assertEquals("conv-2", tailer.nativeSessionId())
    }

    @Test
    fun `a history file that appears only after the run started is picked up`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        val context = run(tmp)

        // Nothing there at all: a home whose CLI has never been run interactively.
        assertNull(tailer.locate(context))

        history(tmp, prompt("the first thing ever typed"))
        assertEquals(Antigravity.historyFile(tmp), tailer.locate(context))
    }

    /**
     * The scan is skipped while the file has not grown, so that a poll four times a second does not
     * re-read a prompt history that has not changed. It must not skip a file that *has*.
     */
    @Test
    fun `a prompt appended after an empty-handed scan is still found`(@TempDir tmp: Path) {
        val tailer = AntigravityTailer(tmp)
        val context = run(tmp)
        history(tmp, prompt("yesterday", at = startedAt - 86_400_000, conversation = "conv-old"))
        assertNull(tailer.locate(context))

        Files.writeString(
            Antigravity.historyFile(tmp),
            prompt("today", conversation = "conv-new") + "\n",
            StandardOpenOption.APPEND,
        )

        assertEquals(Antigravity.historyFile(tmp), tailer.locate(context))
        assertEquals("conv-new", tailer.nativeSessionId())
    }
}
