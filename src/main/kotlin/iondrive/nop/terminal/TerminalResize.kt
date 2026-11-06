package iondrive.nop.terminal

import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.JediTerminal
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Apply a drag's final size to the buffer and PTY together, limiting full transcript redraws. */
internal class TerminalResize(
    private val terminalModel: JediTerminal,
    connector: TtyConnector,
    typeAhead: TerminalTypeAheadManager,
    private val executors: TerminalExecutorServiceManager,
    private val delayMs: Long = 150,
) : TerminalStarter(
    terminalModel, connector, TtyBasedArrayDataStream(connector, typeAhead::onTerminalStateChanged), typeAhead, executors,
) {
    private val resizeLock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var initial = true
    private var stopped = false
    private var applied: TermSize? = null
    private var revision = 0L

    override fun postResize(size: TermSize, origin: RequestOrigin) {
        synchronized(resizeLock) {
            if (stopped) return
            val request = ++revision
            pending?.cancel(false)
            pending = null
            // The first layout establishes the CLI's startup geometry without waiting for a drag.
            if (initial || origin != RequestOrigin.User) {
                initial = false
                applyResize(size, origin)
            } else {
                pending = executors.singleThreadScheduledExecutor.schedule({
                    synchronized(resizeLock) {
                        if (!stopped && request == revision) {
                            applyResize(size, origin)
                            pending = null
                        }
                    }
                }, delayMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun applyResize(size: TermSize, origin: RequestOrigin) {
        if (size == applied && size.columns == terminalModel.terminalWidth && size.rows == terminalModel.terminalHeight) return
        applied = size
        super.postResize(size, origin)
    }

    override fun requestEmulatorStop() {
        cancelResize()
        super.requestEmulatorStop()
    }

    override fun close() {
        cancelResize()
        super.close()
    }

    private fun cancelResize() {
        synchronized(resizeLock) {
            stopped = true
            revision++
            pending?.cancel(false)
            pending = null
        }
    }
}
