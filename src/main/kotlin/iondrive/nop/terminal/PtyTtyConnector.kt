package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.nio.charset.StandardCharsets

/**
 * Adapts a pty4j [PtyProcess] to JediTerm's `TtyConnector`. JediTerm doesn't publish a PTY
 * connector (the one in its source tree lives only in the standalone app), but the abstract
 * [ProcessTtyConnector] it does ship already implements charset-decoded read/write against any
 * [Process] — and [PtyProcess] is a [Process]. We only add the name and the resize bridge.
 *
 * The resize → [PtyProcess.setWinSize] hop is the important part: it's what delivers `SIGWINCH`
 * to the child so full-screen apps (vim, htop, less) reflow when the tab is resized.
 */
class PtyTtyConnector(
    private val process: PtyProcess,
    /**
     * Sees raw bytes typed or pasted into the PTY.
     */
    private val inputTap: ((ByteArray) -> Unit)? = null,
    /**
     * Keeps what the program writes, below the decoding, so the next nop can be handed it — see
     * [PtyRecorder]. Null for a terminal that is never handed over.
     */
    recorder: PtyRecorder? = null,
    /**
     * Output to draw before anything the program writes from here on: what an adopted terminal was
     * showing in the nop that handed it over. It goes to [replayTap] and not to [tap], because it is
     * history — a quota wall in it is one the last nop has already dealt with.
     */
    replay: ByteArray? = null,
    private val replayTap: ((String) -> Unit)? = null,
    /**
     * Sees every character on its way to the terminal, and changes none of them.
     *
     * The agent launcher uses it for two things the CLI never tells nop directly: noticing the
     * moment a vendor announces a quota wall, and keeping a little plain text to build a handoff
     * out of when that provider's transcript can't be read. Both are read-only by construction —
     * the tap gets a copy after the decode and cannot alter what the terminal draws.
     *
     * It runs on the thread feeding the terminal, so an expensive one would show up as lag in the
     * TUI. Anything a tap wants to do beyond a regex belongs on another thread.
     */
    private val tap: ((String) -> Unit)? = null,
) : ProcessTtyConnector(recorder?.let { RecordedProcess(process, it) } ?: process, StandardCharsets.UTF_8) {

    /**
     * Set once the program has been handed to the next nop. From then on closing this connector —
     * which JediTerm does by itself when reading stops — must neither signal the program nor close
     * the PTY master, both of which are the next nop's now.
     */
    @Volatile
    var handedOver: Boolean = false

    private var replayReader: Reader? =
        replay?.takeIf { it.isNotEmpty() }?.let { InputStreamReader(ByteArrayInputStream(it), StandardCharsets.UTF_8) }

    override fun getName(): String = "pty"

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        replayReader?.let { r ->
            val n = r.read(buf, offset, length)
            if (n > 0) {
                replayTap?.let { listener -> runCatching { listener(String(buf, offset, n)) } }
                return n
            }
            replayReader = null
        }
        val read = super.read(buf, offset, length)
        val listener = tap
        if (read > 0 && listener != null) {
            // Failures are swallowed: a broken tap must never cost the user their terminal.
            runCatching { listener(String(buf, offset, read)) }
        }
        return read
    }

    override fun write(buf: ByteArray) {
        val listener = inputTap
        if (listener != null) {
            runCatching { listener(buf) }
        }
        super.write(buf)
    }

    override fun write(string: String) {
        val listener = inputTap
        if (listener != null) {
            runCatching { listener(string.toByteArray(StandardCharsets.UTF_8)) }
        }
        super.write(string)
    }

    override fun isConnected(): Boolean = process.isAlive

    override fun close() {
        if (handedOver) return
        super.close()
    }

    override fun resize(termSize: TermSize) {
        if (process.isAlive && !handedOver) {
            process.setWinSize(WinSize(termSize.columns, termSize.rows))
        }
    }
}

/** [inner] with its output read through [recorder]. Everything else goes straight to [inner]. */
private class RecordedProcess(private val inner: Process, recorder: PtyRecorder) : Process() {
    private val input = recorder.wrap(inner.inputStream)
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = inner.outputStream
    override fun getErrorStream(): InputStream = inner.errorStream
    override fun waitFor(): Int = inner.waitFor()
    override fun exitValue(): Int = inner.exitValue()
    override fun destroy() = inner.destroy()
    override fun destroyForcibly(): Process = inner.destroyForcibly()
    override fun isAlive(): Boolean = inner.isAlive
    override fun pid(): Long = inner.pid()
}
