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
     * beneath sessions begun after it, however long ago they were last touched. The transcript's own
     * time is when it last did anything, and that is what the picker sorts by and shows.
     */
    @Test
    fun `a session is listed by when it was last active, not when it began`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val store = tmp.resolve("store")
        val dir = slugDir(store, project)
        Files.writeString(dir.resolve("aaaa-1420.jsonl"), prompt("LIQFADE", "2026-09-19T04:20:52.000Z") + "\n")
            .also { touch(it, "2026-09-19T08:59:40Z") }
        Files.writeString(dir.resolve("bbbb-1901.jsonl"), prompt("Plan 36", "2026-09-19T09:01:07.000Z") + "\n")
            .also { touch(it, "2026-09-19T09:02:00Z") }
        Files.writeString(dir.resolve("cccc-1700.jsonl"), prompt("brief", "2026-09-19T07:00:00.000Z") + "\n")
            .also { touch(it, "2026-09-19T07:05:00Z") }

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
