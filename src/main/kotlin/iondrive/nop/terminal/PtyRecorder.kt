package iondrive.nop.terminal

import java.io.InputStream

/**
 * Everything a terminal's program has written lately, kept so the next nop can put it back on screen
 * when this one hands the program over (see [TerminalSession.handOff]).
 *
 * Two things are kept, because the screen is made of both. The bytes themselves, as many as
 * [capacity] holds — the scrollback and the frame the program last drew. And the terminal modes the
 * program has switched on or off (`ESC [ ? n h` / `l`), however long ago: bracketed paste, the
 * alternate screen, a hidden cursor and application cursor keys are each set once at startup and
 * never again, so a replay of the last megabyte alone would hand the new terminal a program that
 * believes in modes it never heard about.
 *
 * It also owns the moment reading stops. [freeze] guarantees that no byte is read from the PTY after
 * it returns, and that every byte read before it is in [replay] — which is what lets the next nop
 * pick the PTY up exactly where this one let go, with nothing drawn twice or lost in between.
 */
class PtyRecorder(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Object()

    /** Made on the first byte: most terminals nop builds are never started, and cost nothing. */
    private var ring: ByteArray? = null
    private var head = 0
    private var size = 0
    private var wrapped = false
    private val modes = DecModes()

    private var frozen = false
    private var reading = false

    /** [inner] as the terminal should read it: everything it returns is recorded on the way through. */
    fun wrap(inner: InputStream): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            synchronized(lock) {
                if (frozen) return -1
                reading = true
            }
            var n = -1
            try {
                n = inner.read(b, off, len)
                return n
            } finally {
                synchronized(lock) {
                    if (n > 0) record(b, off, n)
                    reading = false
                    lock.notifyAll()
                }
            }
        }

        override fun available(): Int = if (synchronized(lock) { frozen }) 0 else runCatching { inner.available() }.getOrDefault(0)

        override fun close() = inner.close()
    }

    /** Records [bytes] as though the program had just written them: what an adopted terminal starts from. */
    fun seed(bytes: ByteArray) = synchronized(lock) { record(bytes, 0, bytes.size) }

    /**
     * Stops all reading through [wrap]. [wake] is called once the stop is in place, to unblock a read
     * that is waiting on the PTY; a read already under way is let finish (and recorded), for up to
     * [timeoutMs]. Every read after this returns end of stream.
     */
    fun freeze(timeoutMs: Long = FREEZE_TIMEOUT_MS, wake: () -> Unit) {
        synchronized(lock) { frozen = true }
        runCatching(wake)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (reading) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
    }

    /**
     * What to feed a fresh terminal so it shows what this one showed: the modes in force, then the
     * recorded output. A ring that has wrapped starts after its first line break, so the replay does
     * not open in the middle of an escape sequence or a character.
     */
    fun replay(): ByteArray = synchronized(lock) {
        val body = ByteArray(size)
        val start = if (wrapped) head else 0
        ring?.let { r -> for (i in 0 until size) body[i] = r[(start + i) % capacity] }
        val from = if (wrapped) cleanStart(body) else 0
        modes.prefix() + body.copyOfRange(from, body.size)
    }

    private fun record(b: ByteArray, off: Int, n: Int) {
        modes.feed(b, off, n)
        // Only the tail of an oversized write can fit, and only the tail is wanted. What is dropped
        // is lost the same way an overwrite loses it, so the replay starts on a clean line either way.
        val skip = (n - capacity).coerceAtLeast(0)
        if (skip > 0) wrapped = true
        val ring = ring ?: ByteArray(capacity).also { ring = it }
        for (i in off + skip until off + n) {
            ring[head] = b[i]
            head = (head + 1) % capacity
            if (size < capacity) size++ else wrapped = true
        }
    }

    private fun cleanStart(body: ByteArray): Int {
        val limit = minOf(body.size, CLEAN_START_SEARCH)
        for (i in 0 until limit) if (body[i] == '\n'.code.toByte()) return i + 1
        return 0
    }

    companion object {
        /** A megabyte per terminal: a long scrollback of agent output, and a small cost beside a JVM. */
        const val DEFAULT_CAPACITY = 1 shl 20
        private const val FREEZE_TIMEOUT_MS = 2_000L
        private const val CLEAN_START_SEARCH = 64 * 1024
    }
}

/**
 * The DEC private modes a program has set or reset (`ESC [ ? n ; m h` and `l`), read off its output a
 * byte at a time so a sequence split across two reads is still seen. A full reset (`ESC c`) forgets
 * them all. Nothing else is parsed.
 *
 * Only the modes in [TRACKED] are kept — the ones a TUI switches once and depends on after — so what
 * this holds is bounded however much a program writes. Any other number is skipped: a program, or the
 * output of something it runs, can name millions of distinct ones, and each would otherwise stay here
 * and in every replay for the life of the terminal.
 */
internal class DecModes {
    private val state = LinkedHashMap<Int, Boolean>()

    private var phase = 0 // 0 text, 1 after ESC, 2 after ESC [, 3 in ESC [ ? params
    private val params = StringBuilder()

    fun feed(b: ByteArray, off: Int, n: Int) {
        for (i in off until off + n) step(b[i].toInt() and 0xff)
    }

    private fun step(ch: Int) {
        when (phase) {
            0 -> if (ch == ESC) phase = 1
            1 -> phase = when (ch) {
                '['.code -> 2
                'c'.code -> { state.clear(); 0 }
                ESC -> 1
                else -> 0
            }
            2 -> phase = if (ch == '?'.code) { params.setLength(0); 3 } else if (ch == ESC) 1 else 0
            3 -> when {
                ch in '0'.code..'9'.code || ch == ';'.code -> {
                    params.append(ch.toChar())
                    if (params.length > MAX_PARAMS) phase = 0
                }
                ch == 'h'.code || ch == 'l'.code -> {
                    for (p in params.split(';')) p.toIntOrNull()?.takeIf { it in TRACKED }?.let { mode ->
                        state.remove(mode)
                        state[mode] = ch == 'h'.code
                    }
                    phase = 0
                }
                ch == ESC -> phase = 1
                else -> phase = 0
            }
        }
    }

    /** The sequences that put a fresh terminal into the same modes: screen switches first. */
    fun prefix(): ByteArray {
        val out = StringBuilder()
        val ordered = state.entries.sortedBy { if (it.key in SCREENS) 0 else 1 }
        for ((mode, on) in ordered) {
            // A mode the terminal starts in only needs saying when it has been turned off, and the
            // rest only when they have been turned on.
            val needed = if (mode in ON_BY_DEFAULT) !on else on
            if (needed) out.append("\u001b[?").append(mode).append(if (on) 'h' else 'l')
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val ESC = 0x1b
        const val MAX_PARAMS = 64
        val SCREENS = setOf(47, 1047, 1049)
        /** Autowrap and a visible cursor. */
        val ON_BY_DEFAULT = setOf(7, 25)

        /**
         * Application cursor keys, autowrap, a blinking or hidden cursor, the alternate screens, the
         * mouse reporting modes and their encodings, focus reporting, alternate scroll, bracketed
         * paste and synchronised output.
         */
        val TRACKED: Set<Int> = setOf(1, 7, 12, 25, 1000, 1002, 1003, 1004, 1005, 1006, 1007, 1015, 2004, 2026) + SCREENS
    }
}
