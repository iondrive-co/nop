package iondrive.nop.terminal

import com.jediterm.terminal.TerminalDataStream
import com.jediterm.terminal.ArrayTerminalDataStream
import java.util.ArrayDeque

/** Holds a program's synchronized redraw until its final screen has reached the emulator. */
internal class SynchronizedOutput(
    private val changed: (Boolean) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile var active: Boolean = false
        private set
    @Volatile private var startedAt = 0L
    @Volatile private var lastOutputAt = 0L
    private var modeEnabled = false
    private var replaying = false

    val showProgress: Boolean get() = active && clock() - startedAt >= 150
    val elapsed: Long get() = clock() - startedAt

    @Synchronized fun begin() {
        modeEnabled = true
        update()
    }

    fun touch() { if (active) lastOutputAt = clock() }

    @Synchronized fun end() {
        modeEnabled = false
        update()
    }

    @Synchronized fun replay(enabled: Boolean) { replaying = enabled; update() }
    @Synchronized fun reset() { modeEnabled = false; replaying = false; update() }

    /** A crashed program or a lost reset sequence must not leave the terminal hidden. */
    @Synchronized fun expire() { if (active && clock() - lastOutputAt >= 2_000) reset() }

    private fun update() {
        val enabled = modeEnabled || replaying
        if (enabled) lastOutputAt = clock()
        if (enabled == active) return
        if (enabled) startedAt = clock()
        active = enabled
        changed(enabled)
    }
}

/**
 * Applies DEC mode 2026 to painting rather than JediTerm's size-limited input buffer. All text and
 * other controls reach the emulator normally, so a long redraw can populate history incrementally.
 */
internal class SynchronizedOutputStream(
    private val source: TerminalDataStream,
    private val output: SynchronizedOutput,
) : TerminalDataStream {
    private val pending = ArrayDeque<Char>()
    private var state = 0
    private var endPending = false

    override fun getChar(): Char {
        finishPending()
        if (pending.isNotEmpty()) return pending.removeFirst()
        while (true) {
            val ch = read()
            if (state != 0) {
                state = when (state) {
                    1 -> when (ch) { '\u0007', '\u009c' -> 0; '\u001b' -> 3; else -> 1 }
                    2 -> when (ch) { '\u009c' -> 0; '\u001b' -> 4; else -> 2 }
                    else -> when (ch) { '\\', '\u009c' -> 0; '\u001b' -> state; else -> state - 2 }
                }
                return ch
            }
            if (ch != '\u001b' && ch != '\u009b') return ch
            val next = if (ch == '\u001b') read() else '['
            if (next == '[') {
                val control = StringBuilder()
                while (control.length < 64) {
                    val part = read()
                    control.append(part)
                    if (part in '@'..'~' || part == '\u001b' || part == '\u0018' || part == '\u001a') break
                }
                val last = control.last()
                val parameters = control.dropLast(1).toString()
                if ((last == 'h' || last == 'l') && parameters.startsWith('?')) {
                    val modes = parameters.substring(1).split(';')
                    if ("2026" in modes) {
                        val remaining = modes.filter { it != "2026" }
                        if (last == 'h') output.begin()
                        else if (remaining.isEmpty()) output.end() else endPending = true
                        if (remaining.isEmpty()) continue
                        control.setLength(0)
                        control.append('?').append(remaining.joinToString(";")).append(last)
                    }
                }
                if (ch == '\u001b') pending.addLast('[')
                control.forEach(pending::addLast)
            } else {
                pending.addLast(next)
                state = when (next) { ']' -> 1; 'P', 'X', '^', '_' -> 2; else -> 0 }
            }
            return ch
        }
    }

    override fun readNonControlCharacters(maxChars: Int): String {
        finishPending()
        output.touch()
        if (pending.isEmpty()) return source.readNonControlCharacters(maxChars)
        val text = ArrayTerminalDataStream(pending.toList().toCharArray()).readNonControlCharacters(maxChars)
        repeat(text.length) { pending.removeFirst() }
        return text
    }

    override fun pushChar(ch: Char) { pending.addFirst(ch) }
    override fun pushBackBuffer(chars: CharArray, length: Int) {
        for (i in length - 1 downTo 0) pending.addFirst(chars[i])
    }
    override fun isEmpty(): Boolean = pending.isEmpty() && source.isEmpty

    private fun read(): Char = source.getChar().also { output.touch() }
    private fun finishPending() {
        if (endPending && pending.isEmpty()) {
            endPending = false
            output.end()
        }
    }
}
