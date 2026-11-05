package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import iondrive.nop.Log
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

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

    /**
     * Whoever holds this terminal is ending it, so closing should end the program as well. Without
     * it a close comes from JediTerm alone, whose session ending is no reason to end the program.
     */
    @Volatile
    var releasing: Boolean = false

    /**
     * Called when JediTerm's session has ended with the program still running and still writing,
     * for the holder to read it again. See [close].
     */
    @Volatile
    var onReaderLost: (() -> Unit)? = null

    /** The session reading this has ended and none has replaced it yet. */
    @Volatile
    var readerLost: Boolean = false
        private set

    /** The program's output has run out, which a session ending over is the ordinary way to end. */
    @Volatile
    private var outputEnded = false

    /** Thrown by the next [read], as an error anywhere in the session would end it. For tests. */
    @Volatile
    internal var failNextRead: Throwable? = null

    private var replayReader: Reader? =
        replay?.takeIf { it.isNotEmpty() }?.let { InputStreamReader(ByteArrayInputStream(it), StandardCharsets.UTF_8) }

    override fun getName(): String = "pty"

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        readerLost = false
        failNextRead?.let {
            failNextRead = null
            throw it
        }
        replayReader?.let { r ->
            val n = r.read(buf, offset, length)
            if (n > 0) {
                replayTap?.let { listener -> runCatching { listener(String(buf, offset, n)) } }
                return n
            }
            replayReader = null
        }
        val read = try {
            super.read(buf, offset, length)
        } catch (e: IOException) {
            outputEnded = true
            throw e
        }
        if (read < 0) outputEnded = true
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

    /**
     * JediTerm closes this whenever its session ends, and closing sends the program SIGTERM. Most
     * sessions end because the program did, but one also ends on any error drawing its output, and an
     * OutOfMemoryError in nop is not the program's fault: that was agents dying mid-work because
     * nop's heap filled. So a close with the program alive, its output still flowing and nobody ending
     * it leaves the program running, for [onReaderLost] to start reading it again.
     */
    override fun close() {
        if (handedOver) return
        if (!releasing && !outputEnded && process.isAlive) {
            readerLost = true
            runCatching { onReaderLost?.invoke() }
            return
        }
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

/**
 * Starts [widget] reading [connector] again after the session reading it died, trying every [retryMs]
 * until one takes or [wanted] says the terminal is no longer worth it. It does not give up while the
 * program runs: a program nothing reads blocks once the PTY fills, which it recovers from when the
 * heap does, where ending it would lose whatever it was doing.
 *
 * What the session was drawing when it died is lost from the screen, not from the program. A TUI
 * draws over it on its next redraw.
 */
internal fun resumeReading(
    widget: JediTermWidget,
    connector: PtyTtyConnector,
    retryMs: Long = RESUME_RETRY_MS,
    wanted: () -> Boolean,
) {
    fun lost() = connector.readerLost && connector.isConnected && !connector.releasing && wanted()
    thread(isDaemon = true, name = "terminal-resume") {
        runCatching { Log.warn("terminal: reading stopped with its program still running; reading it again") }
        while (lost()) {
            Thread.sleep(retryMs)
            // Starting one before the last has finished ending is refused, so the next pass retries.
            SwingUtilities.invokeLater { if (lost()) runCatching { widget.start() } }
        }
    }
}

private const val RESUME_RETRY_MS = 1_000L
