package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What a handed-over terminal comes back showing, and the guarantee the handover rests on: once
 * [PtyRecorder.freeze] returns, nothing more is read, and everything that was read is in the replay.
 */
class PtyRecorderTest {

    private fun String.bytes() = toByteArray(Charsets.UTF_8)

    private fun PtyRecorder.through(text: String) {
        val input = wrap(text.bytes().inputStream())
        val buf = ByteArray(64)
        while (input.read(buf, 0, buf.size) > 0) Unit
    }

    @Test
    fun `the replay is what was read, in order`() {
        val recorder = PtyRecorder()
        recorder.through("hello\r\nworld")
        assertEquals("hello\r\nworld", String(recorder.replay()))
    }

    @Test
    fun `a full ring keeps the newest bytes and starts after a line break`() {
        val recorder = PtyRecorder(capacity = 16)
        recorder.through("aaaa\nbbbb\ncccc\ndddd\neeee")
        // The newest 16 bytes are "b\ncccc\ndddd\neeee"; the replay opens on the first whole line.
        assertEquals("cccc\ndddd\neeee", String(recorder.replay()))
    }

    @Test
    fun `modes set long ago are put back ahead of the output`() {
        val recorder = PtyRecorder(capacity = 8)
        recorder.through("\u001b[?2004h\u001b[?1049h\u001b[?25l" + "x".repeat(40) + "\nend")
        val replay = String(recorder.replay())
        assertTrue(replay.startsWith("\u001b[?1049h"), "the screen switch first: $replay")
        assertTrue("\u001b[?2004h" in replay && "\u001b[?25l" in replay, replay)
        assertTrue(replay.endsWith("end"), replay)
    }

    @Test
    fun `a mode switched off again is not put back, and a split sequence is still read`() {
        val modes = DecModes()
        val on = "\u001b[?20".bytes()
        val rest = "04h then \u001b[?1;2004l".bytes()
        modes.feed(on, 0, on.size)
        modes.feed(rest, 0, rest.size)
        assertEquals("", String(modes.prefix()))
    }

    @Test
    fun `modes nop does not put back are not kept, however many are named`() {
        val recorder = PtyRecorder(capacity = 1024)
        val noise = StringBuilder()
        for (mode in 3000 until 23_000) noise.append("\u001b[?").append(mode).append('h')
        recorder.through(noise.toString() + "\u001b[?2004h\nend")
        // Twenty thousand modes named, and the replay is the one nop tracks and the last line.
        assertEquals("\u001b[?2004hend", String(recorder.replay()))
    }

    @Test
    fun `a full reset forgets every mode`() {
        val modes = DecModes()
        val b = "\u001b[?1h\u001bc".bytes()
        modes.feed(b, 0, b.size)
        assertEquals("", String(modes.prefix()))
    }

    @Test
    fun `nothing is read after a freeze, and a read under way finishes into the replay`() =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val chunks = LinkedBlockingQueue<ByteArray>()
            // Counted down as each read reaches the PTY: the second is the one the freeze lands on.
            val inside = CountDownLatch(2)
            // A PTY stand-in whose read blocks until it is handed a chunk, as a master does.
            val pty = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    inside.countDown()
                    val chunk = chunks.take()
                    if (chunk.isEmpty()) return -1
                    System.arraycopy(chunk, 0, b, off, chunk.size)
                    return chunk.size
                }
            }
            val recorder = PtyRecorder()
            val input = recorder.wrap(pty)
            val results = LinkedBlockingQueue<Int>()
            thread {
                val buf = ByteArray(64)
                while (true) {
                    val n = input.read(buf, 0, buf.size)
                    results.put(n)
                    if (n < 0) break
                }
            }
            chunks.put("before ".bytes())
            assertEquals(7, results.take())
            inside.await()
            // The reader is now blocked in the PTY. The wake hands it one last chunk, as a read that
            // was already returning when the freeze landed would.
            recorder.freeze { chunks.put("during".bytes()) }
            chunks.put("after".bytes())
            assertEquals(6, results.poll(5, TimeUnit.SECONDS))
            assertEquals(-1, results.poll(5, TimeUnit.SECONDS))
            assertEquals("before during", String(recorder.replay()))
        }
}
