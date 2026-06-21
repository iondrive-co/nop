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
class PtyTtyConnector(private val process: PtyProcess) :
    ProcessTtyConnector(process, StandardCharsets.UTF_8) {

    override fun getName(): String = "pty"

    override fun isConnected(): Boolean = process.isAlive

    override fun resize(termSize: TermSize) {
        if (process.isAlive) {
            process.setWinSize(WinSize(termSize.columns, termSize.rows))
        }
    }
}
