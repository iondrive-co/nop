package iondrive.nop.terminal

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The few libc calls that handing a terminal from one nop to the next needs, and that neither the JDK
 * nor pty4j exposes: reading and writing a PTY master nop did not open itself, reaping a child it did
 * not start, and replacing the running program with another while keeping chosen descriptors open.
 *
 * Linux only, and only 64-bit: `size_t` is mapped as a Kotlin `Long`. That is every machine nop's
 * handover runs on; anything else never gets as far as calling these (see [Handover][iondrive.nop.ipc.Handover]).
 */
internal object Posix {
    private interface C : Library {
        fun read(fd: Int, buf: ByteArray, count: Long): Long
        fun write(fd: Int, buf: ByteArray, count: Long): Long
        fun close(fd: Int): Int
        fun fcntl(fd: Int, cmd: Int, arg: Int): Int
        fun poll(fds: Pointer, nfds: Long, timeout: Int): Int
        fun ioctl(fd: Int, request: NativeLong, arg: Pointer): Int
        fun waitpid(pid: Int, status: IntArray, options: Int): Int
        fun kill(pid: Int, sig: Int): Int
        fun close_range(first: Int, last: Int, flags: Int): Int
        fun sigprocmask(how: Int, set: Pointer, old: Pointer?): Int
        fun execve(path: String, argv: Array<String>, envp: Array<String>): Int
    }

    private val c: C by lazy { Native.load("c", C::class.java) }

    const val EINTR = 4
    const val EIO = 5
    const val EAGAIN = 11
    const val ECHILD = 10

    const val SIGHUP = 1
    const val SIGKILL = 9
    const val SIGTERM = 15

    private const val F_GETFD = 1
    private const val F_SETFD = 2
    private const val FD_CLOEXEC = 1
    private const val CLOSE_RANGE_CLOEXEC = 4
    private const val SIG_SETMASK = 2
    private const val POLLIN: Short = 0x001
    private const val POLLERR: Short = 0x008
    private const val POLLHUP: Short = 0x010
    private const val TIOCGWINSZ = 0x5413L
    private const val TIOCSWINSZ = 0x5414L

    /** The errno of the last call on this thread. */
    fun errno(): Int = Native.getLastError()

    fun read(fd: Int, buf: ByteArray, len: Int): Long = c.read(fd, buf, len.toLong())

    /** Writes all of [bytes], retrying short writes and interruptions. False when the fd refused. */
    fun writeFully(fd: Int, bytes: ByteArray): Boolean {
        var off = 0
        while (off < bytes.size) {
            val chunk = if (off == 0) bytes else bytes.copyOfRange(off, bytes.size)
            val n = c.write(fd, chunk, chunk.size.toLong())
            if (n < 0) {
                val e = errno()
                if (e == EINTR || e == EAGAIN) continue
                return false
            }
            off += n.toInt()
        }
        return true
    }

    fun close(fd: Int): Int = c.close(fd)

    /** Marks [fd] to close when this process next execs, as every descriptor nop opens should. */
    fun closeOnExec(fd: Int) {
        c.fcntl(fd, F_SETFD, FD_CLOEXEC)
    }

    /** Whether [fd] is open in this process. */
    fun isOpen(fd: Int): Boolean = c.fcntl(fd, F_GETFD, 0) != -1

    /**
     * Waits up to [timeoutMs] for [fd] to have something to read — or to have hung up, which a read
     * then reports. False on a timeout.
     */
    fun awaitReadable(fd: Int, timeoutMs: Int): Boolean {
        val pfd = Memory(8)
        pfd.setInt(0, fd)
        pfd.setShort(4, POLLIN)
        pfd.setShort(6, 0)
        val n = c.poll(pfd, 1, timeoutMs)
        if (n <= 0) return false
        val revents = pfd.getShort(6).toInt()
        return revents and (POLLIN.toInt() or POLLHUP.toInt() or POLLERR.toInt()) != 0
    }

    /** The window size of the terminal behind [fd], as columns to rows, or null if it has none. */
    fun windowSize(fd: Int): Pair<Int, Int>? {
        val ws = Memory(8).apply { clear() }
        if (c.ioctl(fd, NativeLong(TIOCGWINSZ), ws) != 0) return null
        val rows = ws.getShort(0).toInt() and 0xffff
        val cols = ws.getShort(2).toInt() and 0xffff
        return if (cols > 0 && rows > 0) cols to rows else null
    }

    /** Sets the window size of the terminal behind [fd], which sends its foreground group SIGWINCH. */
    fun setWindowSize(fd: Int, columns: Int, rows: Int): Boolean {
        val ws = Memory(8).apply { clear() }
        ws.setShort(0, rows.toShort())
        ws.setShort(2, columns.toShort())
        return c.ioctl(fd, NativeLong(TIOCSWINSZ), ws) == 0
    }

    /**
     * Blocks until the child [pid] exits and reaps it. Its exit code, 128 + the signal for one that
     * was killed, or null when [pid] is not a child of this process.
     */
    fun waitFor(pid: Int): Int? {
        val status = IntArray(1)
        while (true) {
            val r = c.waitpid(pid, status, 0)
            if (r == pid) break
            if (r == -1 && errno() == EINTR) continue
            return null
        }
        val s = status[0]
        val signal = s and 0x7f
        return if (signal == 0) (s shr 8) and 0xff else 128 + signal
    }

    /** Signals the process group [pid] leads, falling back to the process alone. */
    fun signalGroup(pid: Int, signal: Int): Int {
        val r = c.kill(-pid, signal)
        return if (r == 0) r else c.kill(pid, signal)
    }

    /**
     * Makes every descriptor from 3 up close when this process execs, except [keep], which stay open
     * across it. Without the first half, the next nop would start holding the X connection, the
     * sockets and every file this one had open, for the rest of its life.
     */
    fun keepOnlyAcrossExec(keep: Collection<Int>) {
        val ranged = runCatching { c.close_range(3, -1, CLOSE_RANGE_CLOEXEC) == 0 }.getOrDefault(false)
        if (!ranged) {
            // A kernel or libc without close_range: mark them one at a time instead.
            val open = runCatching {
                Files.list(Path.of("/proc/self/fd")).use { s -> s.toList().mapNotNull { it.fileName.toString().toIntOrNull() } }
            }.getOrDefault(emptyList())
            for (fd in open) if (fd >= 3) c.fcntl(fd, F_SETFD, FD_CLOEXEC)
        }
        for (fd in keep) c.fcntl(fd, F_SETFD, 0)
    }

    /**
     * Replaces this process with [path], keeping its pid, its children and whatever
     * [keepOnlyAcrossExec] left open. Returns only when the exec failed, with the errno.
     *
     * The signal mask is cleared first because it survives an exec, and the JVM's threads do not all
     * run with an empty one; the program that starts in this process's place should begin with every
     * signal deliverable, as it would from a shell.
     */
    fun exec(path: String, argv: List<String>, env: Map<String, String>): Int {
        val empty = Memory(128).apply { clear() }
        c.sigprocmask(SIG_SETMASK, empty, null)
        c.execve(path, argv.toTypedArray(), env.map { (k, v) -> "$k=$v" }.toTypedArray())
        return errno()
    }

    /** The parent pid of [pid], from `/proc`, or null when it cannot be read. */
    fun parentOf(pid: Long): Long? = runCatching {
        // The command name is in parentheses and may itself hold spaces or parentheses, so the fields
        // are counted from the last closing one.
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[1].toLong()
    }.getOrNull()

    /** The PTY masters open in this process, by descriptor. */
    fun openMasters(): List<Int> = runCatching {
        Files.list(Path.of("/proc/self/fd")).use { s -> s.toList().mapNotNull { it.fileName.toString().toIntOrNull() } }
            .filter { isMaster(it) }
    }.getOrDefault(emptyList())

    /** Whether [fd] is a PTY master. */
    fun isMaster(fd: Int): Boolean = describe(fd).let { it == "/dev/ptmx" || it == "/dev/pts/ptmx" }

    /** What [fd] in this process refers to, from `/proc/self/fd`. */
    fun describe(fd: Int): String? = runCatching { Files.readSymbolicLink(Path.of("/proc/self/fd/$fd")).toString() }.getOrNull()
}
