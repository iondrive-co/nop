package iondrive.nop.agent

import iondrive.nop.agent.transcript.ClaudeTailer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * The session backup: what it copies, that it only ever adds, and that a missing conversation can be
 * put back from it. Every path here is under a temporary directory, never the user's own.
 */
class BackupTest {

    private class World(root: Path) {
        val data: Path = root.resolve("data")
        val configFile: Path = root.resolve("config/agent.json")
        val dest: Path = root.resolve("backup")
        val project: Path = root.resolve("work/shop")
        val claude = Account("claude-alpha", Provider.Anthropic, root.resolve("homes/claude-alpha").toString())
        val codex = Account("codex-bravo", Provider.OpenAI, root.resolve("homes/codex-bravo").toString())
        val agy = Account("google-alpha", Provider.Antigravity, root.resolve("homes/google-alpha").toString())
        val config = AgentConfig(listOf(claude, codex, agy), backupDir = dest.toString())

        fun claudeTranscript(id: String): Path =
            claude.homePath.resolve("projects").resolve(ClaudeTailer.slug(project)).resolve("$id.jsonl")

        fun codexRollout(id: String): Path =
            codex.homePath.resolve(".codex/sessions/2026/09/30/rollout-2026-09-30T10-00-00-$id.jsonl")

        fun run(
            now: Instant = Instant.now(),
            record: BackupRecord = BackupRecord.inMemory(dest),
            fullCheck: Boolean = false,
        ) = Backup.run(config, dest, data, configFile, now, record, fullCheck)
    }

    private fun stampOf(file: Path) = BackupRecord.Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis())

    private fun write(file: Path, text: String): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    private fun populated(tmp: Path): World = World(tmp).also { w ->
        write(w.data.resolve("sessions/tab-1.jsonl"), "{\"type\":\"session_started\"}\n")
        write(w.data.resolve("handoffs/tab-1/handoff-1.md"), "# handoff\n")
        write(w.data.resolve("memory/memory.md"), "# memory\n")
        write(w.configFile, "{\"accounts\":[]}")
        write(w.claudeTranscript("conv-1"), "{\"type\":\"user\"}\n")
        write(w.claude.homePath.resolve("settings.json"), "{}")
        write(w.claude.homePath.resolve(".credentials.json"), "secret")
        write(w.codexRollout("roll-1"), "{\"type\":\"session_meta\"}\n")
        write(w.codex.homePath.resolve(".codex/config.toml"), "model = \"x\"\n")
        write(w.codex.homePath.resolve(".codex/auth.json"), "secret")
        write(w.agy.homePath.resolve(".gemini/antigravity-cli/history.jsonl"), "{}\n")
        write(w.agy.homePath.resolve(".gemini/antigravity-cli/antigravity-oauth-token"), "secret")
        write(w.agy.homePath.resolve(".gemini/antigravity-cli/conversations/c1.db"), "db")
        write(w.agy.homePath.resolve(".gemini/antigravity-cli/conversations/c1.db-wal"), "wal")
    }

    @Test
    fun `a run copies the logs, transcripts and settings, and no credentials`(@TempDir tmp: Path) {
        val w = populated(tmp)

        w.run()

        for (copied in listOf(
            "nop/sessions/tab-1.jsonl",
            "nop/handoffs/tab-1/handoff-1.md",
            "nop/memory/memory.md",
            "agent.json",
            "homes/claude-alpha/projects/${ClaudeTailer.slug(w.project)}/conv-1.jsonl",
            "homes/claude-alpha/settings.json",
            "homes/codex-bravo/.codex/sessions/2026/09/30/rollout-2026-09-30T10-00-00-roll-1.jsonl",
            "homes/codex-bravo/.codex/config.toml",
            "homes/google-alpha/.gemini/antigravity-cli/history.jsonl",
            "homes/google-alpha/.gemini/antigravity-cli/conversations/c1.db",
        )) {
            assertTrue(Files.isRegularFile(w.dest.resolve(copied)), "missing from the backup: $copied")
        }
        Files.walk(w.dest).use { all ->
            val names = all.map { it.fileName.toString() }.toList()
            assertFalse(".credentials.json" in names)
            assertFalse("auth.json" in names)
            assertFalse("antigravity-oauth-token" in names)
            assertFalse("c1.db-wal" in names, "a live database's journal is not a file to copy")
        }
    }

    @Test
    fun `a grown log takes only its new bytes and comes out identical`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        val log = w.claudeTranscript("conv-1")
        val more = "{\"type\":\"assistant\",\"text\":\"done\"}\n"
        Files.writeString(log, more, java.nio.file.StandardOpenOption.APPEND)

        val second = w.run()

        assertEquals(1, second.files)
        assertEquals(more.length.toLong(), second.bytes)
        val copy = w.dest.resolve("homes/claude-alpha/projects/${ClaudeTailer.slug(w.project)}/conv-1.jsonl")
        assertEquals(Files.readString(log), Files.readString(copy))
    }

    @Test
    fun `a second run over an unchanged tree copies nothing`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        assertEquals(Backup.Copied(0, 0), w.run())
    }

    /** The point of the whole thing: the source going does not take the copy with it. */
    @Test
    fun `nothing is removed from the backup when the source is deleted`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        w.claude.homePath.toFile().deleteRecursively()
        w.data.resolve("sessions").toFile().deleteRecursively()

        w.run()

        assertTrue(Files.isRegularFile(w.dest.resolve("nop/sessions/tab-1.jsonl")))
        assertTrue(Files.isRegularFile(w.dest.resolve("homes/claude-alpha/projects/${ClaudeTailer.slug(w.project)}/conv-1.jsonl")))
    }

    @Test
    fun `a file that shrank keeps the backed-up version aside`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        Files.writeString(w.data.resolve("memory/memory.md"), "#")

        w.run(Instant.parse("2026-09-30T01:02:03Z"))

        val memory = w.dest.resolve("nop/memory")
        assertEquals("#", Files.readString(memory.resolve("memory.md")))
        val aside = Files.list(memory).use { files -> files.map { it.fileName.toString() }.toList() }
            .single { it.startsWith("memory.before-") && it.endsWith(".md") }
        assertEquals("# memory\n", Files.readString(memory.resolve(aside)))
    }

    @Test
    fun `a log rewritten to a longer one is copied whole, and the old copy kept aside`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        val log = w.data.resolve("sessions/tab-1.jsonl")
        Files.writeString(log, "{\"type\":\"something_else_entirely\"}\n")

        w.run()

        val sessions = w.dest.resolve("nop/sessions")
        assertEquals(Files.readString(log), Files.readString(sessions.resolve("tab-1.jsonl")))
        val aside = Files.list(sessions).use { files -> files.map { it.fileName.toString() }.toList() }
            .single { it.startsWith("tab-1.before-") }
        assertEquals("{\"type\":\"session_started\"}\n", Files.readString(sessions.resolve(aside)))
    }

    @Test
    fun `a file the record has as copied is not looked at in the folder until the next full check`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val record = BackupRecord.inMemory(w.dest)
        val start = Instant.parse("2026-09-30T10:00:00Z")
        w.run(start, record)
        val copy = w.dest.resolve("nop/memory/memory.md")
        Files.delete(copy)

        // Taken on the record's word: nothing in the folder was looked at, so the loss is not seen yet.
        assertEquals(Backup.Copied(0, 0), w.run(start.plusSeconds(600), record))
        assertFalse(Files.exists(copy))

        assertEquals(1, w.run(start.plus(BackupRecord.FULL_CHECK), record).files)
        assertEquals("# memory\n", Files.readString(copy))
    }

    @Test
    fun `a run asked for looks at every copy`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val record = BackupRecord.inMemory(w.dest)
        w.run(record = record)
        Files.delete(w.dest.resolve("agent.json"))

        assertEquals(1, w.run(record = record, fullCheck = true).files)
        assertTrue(Files.isRegularFile(w.dest.resolve("agent.json")))
    }

    @Test
    fun `a file changed since the version the record has is copied, even at the same size`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val record = BackupRecord.inMemory(w.dest)
        w.run(record = record)
        val memory = w.data.resolve("memory/memory.md")
        Files.writeString(memory, "# MEMORY\n")
        Files.setLastModifiedTime(memory, FileTime.from(Files.getLastModifiedTime(memory).toInstant().plusSeconds(60)))

        assertEquals(1, w.run(record = record).files)
        assertEquals("# MEMORY\n", Files.readString(w.dest.resolve("nop/memory/memory.md")))
    }

    /** A share that will not take a copy's time leaves each copy dated when it was made. */
    @Test
    fun `a copy whose time the folder did not keep is not copied again`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val record = BackupRecord.inMemory(w.dest)
        w.run(record = record)
        Files.setLastModifiedTime(w.dest.resolve("nop/memory/memory.md"), FileTime.from(Instant.now().plusSeconds(3600)))

        assertEquals(Backup.Copied(0, 0), w.run(record = record, fullCheck = true))
        // Without the record, the time is all there is to go on.
        assertEquals(1, w.run().files)
    }

    @Test
    fun `the record outlives a restart, and is only of the folder it describes`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val file = tmp.resolve("state/backup-record")
        w.run(record = BackupRecord.load(file, w.dest))
        val log = w.data.resolve("sessions/tab-1.jsonl")
        Files.writeString(log, "{\"type\":\"run_ended\"}\n", StandardOpenOption.APPEND)
        Files.delete(w.dest.resolve("nop/memory/memory.md"))

        // Read back, it still says when every copy was last looked at, so this run trusts it.
        assertEquals(1, w.run(record = BackupRecord.load(file, w.dest)).files)
        assertFalse(Files.exists(w.dest.resolve("nop/memory/memory.md")))

        val reloaded = BackupRecord.load(file, w.dest)
        assertTrue(reloaded.holds("nop/sessions/tab-1.jsonl", stampOf(log)))
        assertTrue(reloaded.holds("agent.json", stampOf(w.configFile)))
        assertFalse(BackupRecord.load(file, tmp.resolve("elsewhere")).holds("agent.json", stampOf(w.configFile)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a full check forgets the files whose sources have gone`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val record = BackupRecord.inMemory(w.dest)
        w.run(record = record)
        val memory = w.data.resolve("memory/memory.md")
        val stamp = stampOf(memory)
        Files.delete(memory)

        w.run(record = record, fullCheck = true)

        assertFalse(record.holds("nop/memory/memory.md", stamp))
        assertTrue(Files.isRegularFile(w.dest.resolve("nop/memory/memory.md")))
    }

    @Test
    fun `a folder that cannot be reached fails the run rather than looking like nothing changed`(@TempDir tmp: Path) {
        val w = populated(tmp)
        val blocked = write(tmp.resolve("blocked"), "not a folder")
        assertThrows(IOException::class.java) {
            Backup.run(w.config, blocked.resolve("backup"), w.data, w.configFile)
        }
    }

    @Test
    fun `what it writes only its owner can read`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        val perms = Files.getPosixFilePermissions(w.dest.resolve("nop/sessions/tab-1.jsonl"))
        assertEquals("rw-------", PosixFilePermissions.toString(perms))
    }

    @Test
    fun `a Claude conversation the home has lost is put back where the CLI looks`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        val lost = w.claudeTranscript("conv-1")
        val text = Files.readString(lost)
        Files.delete(lost)

        assertTrue(Backup.restore(w.config, w.claude, w.project.toFile(), "conv-1"))
        assertEquals(text, Files.readString(lost))
        assertFalse(Backup.restore(w.config, w.claude, w.project.toFile(), "never-was"))
    }

    @Test
    fun `a Codex rollout the home has lost is put back under its date`(@TempDir tmp: Path) {
        val w = populated(tmp)
        w.run()
        val lost = w.codexRollout("roll-1")
        Files.delete(lost)

        assertTrue(Backup.restore(w.config, w.codex, w.project.toFile(), "roll-1"))
        assertTrue(Files.isRegularFile(lost))
    }

    @Test
    fun `nothing is restored when no backup folder is set`(@TempDir tmp: Path) {
        val w = populated(tmp)
        assertFalse(Backup.restore(w.config.copy(backupDir = null), w.claude, w.project.toFile(), "conv-1"))
    }

    @Test
    fun `a folder inside nop's data or an account's home is refused`(@TempDir tmp: Path) {
        val w = populated(tmp)
        assertNotNull(Backup.refusal(w.data.resolve("backup"), w.config, w.data))
        assertNotNull(Backup.refusal(w.claude.homePath.resolve("backup"), w.config, w.data))
        assertNull(Backup.refusal(w.dest, w.config, w.data))
        assertThrows(IOException::class.java) {
            Backup.run(w.config, w.data.resolve("backup"), w.data, w.configFile)
        }
    }
}
