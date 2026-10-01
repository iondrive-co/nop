package iondrive.nop.terminal

import com.pty4j.PtyProcess
import com.pty4j.WinSize
import iondrive.nop.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

/**
 * A terminal program the previous nop started, picked up by this one: the PTY master it left open
 * across the exec, and the child it left behind. See [iondrive.nop.ipc.Handover].
 *
 * The exec kept this process's pid, so the child is still *this* process's child, and is reaped here
 * like any other — which is what keeps its exit code, the one thing that tells a CLI quitting from a
 * CLI dying. Everything else pty4j would do for a process it started is done straight on the
 * descriptor: reads, writes, the window size.
 */
class AdoptedPty(
    /** The PTY master, open in this process since before it was this program. */
    val fd: Int,
    private val childPid: Long,
) : PtyProcess() {

    @Volatile private var exitCode: Int? = null
    private val exited = CompletableFuture<Int>()

    @Volatile private var closed = false

    /** Set by [interruptReads]: every read from here on returns end of stream. */
    @Volatile private var interrupted = false

    init {
        // It was left open across the exec on purpose; from here it is an ordinary descriptor again,
        // and no program this nop starts should inherit another terminal's master.
        Posix.closeOnExec(fd)
        // waitpid blocks in native code, so it gets a thread of its own, as pty4j's reaper does.
        thread(isDaemon = true, name = "adopted-pty-reaper-$childPid") {
            val code = Posix.waitFor(childPid.toInt())
            if (code == null) Log.warn("adopted terminal $childPid is not a child of this nop; its exit code is lost")
            exitCode = code ?: -1
            exited.complete(code ?: -1)
        }
    }

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val buf = ByteArray(len)
            while (true) {
                if (closed || interrupted) return -1
                // A bounded wait, so [interruptReads] and [close] are noticed without a wake-up pipe.
                if (!Posix.awaitReadable(fd, POLL_MS)) continue
                val n = Posix.read(fd, buf, len)
                if (n > 0) {
                    System.arraycopy(buf, 0, b, off, n.toInt())
                    return n.toInt()
                }
                if (n == 0L) return -1
                when (Posix.errno()) {
                    Posix.EINTR, Posix.EAGAIN -> continue
                    // EIO is what a master reads once every process holding the slave has gone.
                    else -> return -1
                }
            }
        }

        override fun close() = closeFd()
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("terminal closed")
            if (!Posix.writeFully(fd, b.copyOfRange(off, off + len))) throw IOException("write to terminal failed: errno ${Posix.errno()}")
        }

        override fun close() = closeFd()
    }

    /** Makes every read from now on return end of stream. See [PtyRecorder.freeze]. */
    fun interruptReads() {
        interrupted = true
    }

    @Synchronized
    private fun closeFd() {
        if (closed) return
        closed = true
        Posix.close(fd)
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exited.get()

    override fun exitValue(): Int = exitCode ?: throw IllegalThreadStateException("process hasn't exited")

    override fun isAlive(): Boolean = exitCode == null

    override fun pid(): Long = childPid

    override fun onExit(): CompletableFuture<Process> = exited.thenApply { this }

    override fun toHandle(): ProcessHandle = ProcessHandle.of(childPid).orElseThrow { UnsupportedOperationException("process $childPid is gone") }

    /** SIGTERM to the program's process group, as pty4j's own destroy does. */
    override fun destroy() {
        if (isAlive) Posix.signalGroup(childPid.toInt(), Posix.SIGTERM)
    }

    override fun destroyForcibly(): Process {
        if (isAlive) Posix.signalGroup(childPid.toInt(), Posix.SIGKILL)
        return this
    }

    override fun setWinSize(winSize: WinSize) {
        if (!closed) Posix.setWindowSize(fd, winSize.columns, winSize.rows)
    }

    override fun getWinSize(): WinSize {
        val (cols, rows) = Posix.windowSize(fd) ?: throw IOException("no window size on terminal $fd")
        return WinSize(cols, rows)
    }

    private companion object {
        const val POLL_MS = 200
    }
}
