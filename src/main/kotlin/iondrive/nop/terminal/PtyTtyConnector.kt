package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
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
) : ProcessTtyConnector(process, StandardCharsets.UTF_8) {

    override fun getName(): String = "pty"

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val read = super.read(buf, offset, length)
        val listener = tap
        if (read > 0 && listener != null) {
            // Failures are swallowed: a broken tap must never cost the user their terminal.
            runCatching { listener(String(buf, offset, read)) }
        }
        return read
    }

    override fun isConnected(): Boolean = process.isAlive

    override fun resize(termSize: TermSize) {
        if (process.isAlive) {
            process.setWinSize(WinSize(termSize.columns, termSize.rows))
        }
    }
}
