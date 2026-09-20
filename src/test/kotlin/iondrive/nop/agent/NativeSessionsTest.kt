package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reading the vendor's own store, which is where a session started outside nop lives.
 *
 * The transcripts here are the shape Claude Code writes: one JSONL file per session, named by the
 * session id, under a directory named for the working directory it was opened in.
 */
class NativeSessionsTest {

    private fun slugDir(store: Path, project: Path): Path {
        val slug = project.toAbsolutePath().normalize().toString()
            .replace('/', '-').replace('.', '-')
        return store.resolve("projects").resolve(slug).also { Files.createDirectories(it) }
    }

    private fun prompt(text: String, at: String) =
        """{"type":"user","timestamp":"$at","message":{"role":"user","content":"$text"}}"""

    private fun title(text: String, at: String) =
        """{"type":"ai-title","timestamp":"$at","aiTitle":"$text"}"""

    /**
     * A CLI left open at its prompt goes on touching its transcript — housekeeping records, file
     * history — long after the conversation stopped. Dating the row by the file's mtime put a
     * `claude` somebody started in a terminal two days ago and never closed at the top of the
     * picker, over the session they were in an hour ago, which is the one they were looking for.
     */
    @Test
    fun `a stale conversation an idle CLI keeps touching does not float to the top`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("home").resolve(".claude")
        val dir = slugDir(store, project)

        val idle = dir.resolve("aaaa-idle.jsonl")
        Files.writeString(
            idle,
            prompt("remove the pre-commit hooks", "2026-09-14T01:00:00.000Z") + "\n" +
                title("Remove pre-commit hooks", "2026-09-14T01:00:05.000Z") + "\n",
        )
        val recent = dir.resolve("bbbb-recent.jsonl")
        Files.writeString(
            recent,
            prompt("close plan 40", "2026-09-20T09:00:00.000Z") + "\n" +
                title("Plan 40 completion check", "2026-09-20T09:00:05.000Z") + "\n",
        )
        // The week-old one is the file the operating system calls newest, because its CLI is still
        // sitting there with it open.
        Files.setLastModifiedTime(idle, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.fromMillis(1_000))

        val rows = NativeSessions.claude(project, listOf(NativeSessions.Store("outside nop", store)))

        assertEquals(
            listOf("Plan 40 completion check", "Remove pre-commit hooks"),
            rows.map { it.title },
            "the picker orders by what each conversation last did, not by what touched its file",
        )
        assertTrue(
            rows.first().lastActiveAt > rows.last().lastActiveAt,
            "the row's own time has to agree with the order it is listed in",
        )
    }

    /** With no timestamped record to go on, the file's own time is still better than nothing. */
    @Test
    fun `a transcript carrying no times falls back to the file's own`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("home").resolve(".claude")
        val dir = slugDir(store, project)
        val file = dir.resolve("cccc-2222.jsonl")
        Files.writeString(
            file,
            """{"type":"user","message":{"role":"user","content":"no clock on this one"}}""" + "\n",
        )
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(5_000))

        val rows = NativeSessions.claude(project, listOf(NativeSessions.Store("outside nop", store)))

        assertEquals(5_000, rows.single().lastActiveAt)
    }

    @Test
    fun `a session run outside nop is listed, and says which store it came from`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("home").resolve(".claude")
        val dir = slugDir(store, project)
        Files.writeString(
            dir.resolve("aaaa-1111.jsonl"),
            prompt("fix the parser", "2026-09-16T01:00:00.000Z") + "\n" +
                title("Parser rewrite", "2026-09-16T01:00:05.000Z") + "\n",
        )

        val rows = NativeSessions.claude(project, listOf(NativeSessions.Store("outside nop", store)))

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("aaaa-1111", row.sessionId)
        assertEquals("aaaa-1111", row.lastNativeSessionId, "resuming one is resuming the vendor's session")
        assertEquals("Parser rewrite", row.title, "the CLI's own name beats the first thing typed")
        assertEquals("outside nop", row.lastAccount)
        assertEquals(store.toString(), row.home, "the directory is what a resume has to be launched against")
        assertEquals(Provider.Anthropic.id, row.lastProvider)
    }

    @Test
    fun `without a title the first thing the user typed names the row`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("store")
        Files.writeString(
            slugDir(store, project).resolve("bbbb-2222.jsonl"),
            prompt("why is the build slow", "2026-09-16T02:00:00.000Z") + "\n",
        )

        val rows = NativeSessions.claude(project, listOf(NativeSessions.Store("claude-work", store)))

        assertEquals("why is the build slow", rows.single().title)
    }

    /** A file the CLI has opened but not written a turn into yet is not a session to offer. */
    @Test
    fun `an empty transcript is not offered`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("store")
        Files.writeString(slugDir(store, project).resolve("cccc-3333.jsonl"), "")

        assertTrue(NativeSessions.claude(project, listOf(NativeSessions.Store("s", store))).isEmpty())
    }

    @Test
    fun `another project's sessions are not this project's`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val other = tmp.resolve("other").also { Files.createDirectories(it) }
        val store = tmp.resolve("store")
        Files.writeString(
            slugDir(store, other).resolve("dddd-4444.jsonl"),
            prompt("somewhere else entirely", "2026-09-16T03:00:00.000Z") + "\n",
        )

        assertTrue(NativeSessions.claude(project, listOf(NativeSessions.Store("s", store))).isEmpty())
    }

    @Test
    fun `every store is read, newest first`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val work = tmp.resolve("work")
        val personal = tmp.resolve("personal")
        Files.writeString(
            slugDir(work, project).resolve("eeee-5555.jsonl"),
            prompt("older", "2026-09-16T01:00:00.000Z") + "\n",
        ).also { touch(it, "2026-09-16T01:30:00Z") }
        Files.writeString(
            slugDir(personal, project).resolve("ffff-6666.jsonl"),
            prompt("newer", "2026-09-16T04:00:00.000Z") + "\n",
        ).also { touch(it, "2026-09-16T04:30:00Z") }

        val rows = NativeSessions.claude(
            project,
            listOf(NativeSessions.Store("claude-work", work), NativeSessions.Store("claude-home", personal)),
        )

        assertEquals(listOf("newer", "older"), rows.map { it.title })
        assertEquals(listOf("claude-home", "claude-work"), rows.map { it.lastAccount })
    }

    /**
     * The hermes session of 2026-09-19: begun at 14:20, worked in until 18:59, and listed as "5h ago"
     * beneath sessions begun after it, however long ago they were last touched. When a conversation
     * last did anything is what the picker sorts by and shows.
     *
     * Each transcript here carries a record at the time it was last worked in, because that is what
     * a transcript worked in until 18:59 looks like. The fixture used to say the same thing with
     * the files' mtimes alone, which was the one part of it that was not true of a real store: see
     * the idle-CLI case above, where the mtime goes on moving long after the conversation stopped.
     */
    @Test
    fun `a session is listed by when it was last active, not when it began`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("store")
        val dir = slugDir(store, project)
        Files.writeString(
            dir.resolve("aaaa-1420.jsonl"),
            prompt("LIQFADE", "2026-09-19T04:20:52.000Z") + "\n" +
                prompt("and again", "2026-09-19T08:59:40.000Z") + "\n",
        ).also { touch(it, "2026-09-19T08:59:40Z") }
        Files.writeString(
            dir.resolve("bbbb-1901.jsonl"),
            prompt("Plan 36", "2026-09-19T09:01:07.000Z") + "\n" +
                prompt("and again", "2026-09-19T09:02:00.000Z") + "\n",
        ).also { touch(it, "2026-09-19T09:02:00Z") }
        Files.writeString(
            dir.resolve("cccc-1700.jsonl"),
            prompt("brief", "2026-09-19T07:00:00.000Z") + "\n" +
                prompt("and again", "2026-09-19T07:05:00.000Z") + "\n",
        ).also { touch(it, "2026-09-19T07:05:00Z") }

        val rows = NativeSessions.claude(project, listOf(NativeSessions.Store("s", store)))

        assertEquals(listOf("Plan 36", "LIQFADE", "brief"), rows.map { it.title })
        assertEquals(java.time.Instant.parse("2026-09-19T08:59:40Z").toEpochMilli(), rows[1].lastActiveAt)
        assertEquals(java.time.Instant.parse("2026-09-19T04:20:52Z").toEpochMilli(), rows[1].startedAt)
    }

    private fun touch(file: Path, at: String) {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.parse(at)))
    }

    /**
     * A directory that has never been used is the ordinary case for a machine with one account, not
     * an error: the picker should come up with the sessions it did find.
     */
    @Test
    fun `a store with nothing in it costs nothing`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val real = tmp.resolve("real")
        Files.writeString(
            slugDir(real, project).resolve("aaaa-7777.jsonl"),
            prompt("still here", "2026-09-16T05:00:00.000Z") + "\n",
        )

        val rows = NativeSessions.claude(
            project,
            listOf(
                NativeSessions.Store("missing", tmp.resolve("never-used")),
                NativeSessions.Store("real", real),
            ),
        )

        assertEquals(listOf("still here"), rows.map { it.title })
    }

    /** The store a shell's `claude` uses is offered too — that is where the sessions nop never ran are. */
    @Test
    fun `the default config directory is added when no account already is it`() {
        val configured = listOf(
            Account("claude-work", Provider.Anthropic, "/homes/work"),
            Account("codex", Provider.OpenAI, "/homes/codex"),
        )

        val stores = NativeSessions.stores(configured)

        assertEquals(
            listOf("claude-work", NativeSessions.DEFAULT_STORE_LABEL),
            stores.map { it.label },
            "Codex keeps no projects/ directory, and the default store is what nop never launches",
        )
        assertEquals(NativeSessions.defaultConfigDir(), stores.last().dir)
    }

    @Test
    fun `an account that already is the default store is not listed twice`() {
        val configured = listOf(
            Account("claude", Provider.Anthropic, NativeSessions.defaultConfigDir().toString()),
        )

        assertEquals(listOf("claude"), NativeSessions.stores(configured).map { it.label })
    }
}
