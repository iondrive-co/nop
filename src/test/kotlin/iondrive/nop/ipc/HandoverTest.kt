package iondrive.nop.ipc

import com.pty4j.PtyProcessBuilder
import com.pty4j.unix.UnixPtyProcess
import iondrive.nop.agent.ActivityTracker
import iondrive.nop.terminal.PtyHandoff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The manifest a restart writes, and the checks the next build makes before it believes it. The exec
 * between the two is not here — a test cannot replace its own JVM — but everything either side of it
 * is: a real PTY child is written down, taken up again from the manifest, and still answers.
 */
@EnabledOnOs(OS.LINUX)
class HandoverTest {

    private fun child(script: String): UnixPtyProcess = PtyProcessBuilder()
        .setCommand(arrayOf("sh", "-c", script))
        .setEnvironment(HashMap(System.getenv()).apply { put("TERM", "dumb") })
        .setInitialColumns(100)
        .setInitialRows(30)
        .start() as UnixPtyProcess

    private fun record(id: String) = Handover.AgentRecord(
        sessionId = id,
        root = "/tmp/project",
        projects = listOf("/tmp/project"),
        provider = "anthropic",
        account = "claude-test",
        nativeSessionId = "native-$id",
        argv = listOf("claude", "--session-id", "native-$id"),
        isResume = false,
        seededFromHandoff = false,
        startedAt = 1_000L,
        userPromptSubmitted = true,
        transcriptPath = "/tmp/t.jsonl",
        transcriptOffset = 42L,
        tracker = ActivityTracker.Snapshot(mapOf("call-1" to "AskUserQuestion"), "✳ Waiting"),
        title = "Restart test",
        titleIsUsers = true,
        ticket = "ticket-$id",
        selected = true,
    )

    @Test
    fun `a PTY written down is taken up again, still running, with what it showed`() =
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            val proc = child("printf 'ready\\n'; read x; echo \"got:\$x\"; sleep 5")
            val out = Handover.Outgoing()
            out.agents += record("a1") to PtyHandoff(
                fd = proc.pty.masterFD, pid = proc.pid(), columns = 100, rows = 30,
                replay = "what it showed".toByteArray(), process = proc,
            )
            val manifest = Handover.write(out)

            assertTrue(Handover.adopt(manifest, inherited = listOf(proc.pty.masterFD)))
            assertFalse(Files.exists(manifest.parent), "the handover is deleted once read")
            val (rec, adopted) = Handover.claimAgent("a1") ?: error("the tab was not handed over")
            assertNull(Handover.claimAgent("a1"), "each tab is given out once")

            assertEquals("Restart test", rec.title)
            assertEquals(42L, rec.transcriptOffset)
            assertEquals(mapOf("call-1" to "AskUserQuestion"), rec.tracker.openCalls)
            assertEquals("what it showed", String(adopted.replay))
            assertEquals(100 to 30, adopted.columns to adopted.rows)

            // The child is reachable through the adopted descriptor alone.
            val seen = StringBuilder()
            val buf = ByteArray(256)
            val input = adopted.pty.inputStream
            while ("ready" !in seen) {
                val n = input.read(buf, 0, buf.size)
                assertTrue(n > 0)
                seen.append(String(buf, 0, n))
            }
            adopted.pty.outputStream.write("hello\n".toByteArray())
            while ("got:hello" !in seen) {
                val n = input.read(buf, 0, buf.size)
                assertTrue(n > 0)
                seen.append(String(buf, 0, n))
            }
            assertEquals(100, adopted.pty.winSize.columns)
            proc.destroyForcibly()
            Unit
        }

    @Test
    fun `a manifest written for another process is not taken up`() {
        val proc = child("sleep 5")
        val out = Handover.Outgoing()
        out.agents += record("a3") to PtyHandoff(fd = proc.pty.masterFD, pid = proc.pid(), columns = 80, rows = 24, replay = ByteArray(0))
        val manifest = Handover.write(out)
        // The same handover, claimed by some other process.
        Files.writeString(manifest, Files.readString(manifest).replace("\"nopPid\":${ProcessHandle.current().pid()}", "\"nopPid\":1"))
        // Nothing inherited: this JVM's own masters belong to pty4j, and closing one would break it.
        assertFalse(Handover.adopt(manifest, inherited = emptyList()), "not this process's: start as any launch does")
        assertNull(Handover.claimAgent("a3"))
        assertFalse(Files.exists(manifest.parent))
        proc.destroyForcibly()
    }

    @Test
    fun `a manifest anywhere but this nop's own handover directory is left alone, with what is beside it`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        Files.writeString(manifest, "not a manifest")
        val unrelated = Files.writeString(tmp.resolve("keep.txt"), "the user's")
        assertFalse(Handover.adopt(manifest, inherited = emptyList()))
        assertTrue(Files.exists(manifest) && Files.exists(unrelated), "nothing outside the handover directory is deleted")
        // The same for a path that reaches the right name by way of somewhere else.
        Handover.adopt(tmp.resolve("../${tmp.fileName}/manifest.json"), inherited = emptyList())
        assertTrue(Files.exists(unrelated))
    }

    @Test
    fun `a descriptor that is not a PTY master is refused`() {
        val proc = child("sleep 5")
        // A live child, paired with a descriptor that is not a ptmx: the test JVM's own stdin.
        val out = Handover.Outgoing()
        out.agents += record("a2") to PtyHandoff(fd = 0, pid = proc.pid(), columns = 80, rows = 24, replay = ByteArray(0))
        Handover.adopt(Handover.write(out), inherited = emptyList())
        assertNull(Handover.claimAgent("a2"))
        proc.destroyForcibly()
    }
}
