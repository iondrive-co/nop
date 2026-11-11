package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.EventLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What a follower turns appended transcript text into, wherever the CLI's writes happen to fall.
 */
class TranscriptFollowerTest {

    private val logs = mutableListOf<EventLog>()

    @AfterEach
    fun closeLogs() {
        logs.forEach {
            it.close()
            Files.deleteIfExists(it.file)
        }
    }

    private fun follower(file: Path, tmp: Path, offset: Long? = null): Pair<TranscriptFollower, MutableList<String>> {
        val seen = mutableListOf<String>()
        val tailer = object : Tailer {
            override fun locate(run: RunContext): Path = file
            override fun parse(line: String): List<AgentEvent> {
                seen += line
                return emptyList()
            }
        }
        val run = RunContext(tmp, tmp, "s", 0L, joinAt = offset?.let { file to it })
        val log = EventLog.open("follower-test-${System.nanoTime()}").also { logs += it }
        return TranscriptFollower(tailer, run, log) to seen
    }

    private fun Path.append(bytes: ByteArray) = Files.write(this, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

    @Test
    fun `a character cut in two by a write comes through whole`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.jsonl")
        val line = "emoji 🎉 and a dash — end"
        val bytes = line.toByteArray()
        val cut = line.indexOf("🎉").let { line.substring(0, it).toByteArray().size } + 2
        val (follower, seen) = follower(file, tmp)
        file.append(bytes.copyOfRange(0, cut))
        follower.stop()
        assertEquals(emptyList<String>(), seen, "half a line is not a line yet")
        file.append(bytes.copyOfRange(cut, bytes.size) + "\n".toByteArray())
        follower.stop()
        assertEquals(listOf(line), seen)
    }

    @Test
    fun `every byte up to the last line break is read once, and no more than once`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.jsonl")
        val (follower, seen) = follower(file, tmp)
        file.append("one\ntwo\nthr".toByteArray())
        follower.stop()
        assertEquals(listOf("one", "two"), seen)
        follower.stop()
        follower.stop()
        file.append("ee\n".toByteArray())
        follower.stop()
        assertEquals(listOf("one", "two", "three"), seen)
    }

    @Test
    fun `a position taken mid-line points back at where the line began`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.jsonl")
        val (follower, _) = follower(file, tmp)
        val first = "first\n".toByteArray()
        file.append(first + "secö".toByteArray())
        follower.stop()
        assertEquals(file to first.size.toLong(), follower.position())
    }

    @Test
    fun `a transcript rewritten from the top is read again from there`(@TempDir tmp: Path) {
        val file = tmp.resolve("t.jsonl")
        val (follower, seen) = follower(file, tmp)
        file.append("alpha\nbravo\n".toByteArray())
        follower.stop()
        Files.writeString(file, "x\n")
        follower.stop()
        assertEquals(listOf("alpha", "bravo", "x"), seen)
    }
}
