package iondrive.nop.terminal

import iondrive.nop.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Command copying's old messages: loaded once the terminal exists, one tab at a time. */
internal class CommandHistory(private val load: () -> Unit) : AutoCloseable {
    private var started = false
    private var closed = false
    private var task: Future<*>? = null

    @Synchronized
    fun start() {
        if (started || closed) return
        started = true
        task = reader.submit {
            try {
                load()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.warn("could not restore command copying history: $e")
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        task?.cancel(true)
    }

    private companion object {
        // A thread per tab used to parse every conversation at once, competing with the first
        // window for CPU and heap. Hidden tabs also no longer read before their widget is attached.
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "agent-command-history").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}
