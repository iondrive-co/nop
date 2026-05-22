package iondrive.nop.launchers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Streaming state for a single launcher invocation. Output is appended to a Compose-observable
 * string buffer so an output tab can render it live.
 */
class LauncherRun(val launcher: Launcher, val workingDir: File) {
    var output: String by mutableStateOf("")
        private set
    var exitCode: Int? by mutableStateOf(null)
        private set
    var running: Boolean by mutableStateOf(false)
        private set

    private val outputBuilder = StringBuilder()
    private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        outputBuilder.clear()
        output = ""
        exitCode = null

        // Use a shell so the user can write pipes / chained commands naturally.
        val pb = ProcessBuilder("sh", "-c", launcher.command)
            .directory(workingDir)
            .redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            append("Failed to start: ${t.message}\n")
            running = false
            exitCode = -1
            return
        }
        process = proc
        thread(isDaemon = true, name = "launcher-${launcher.name}") {
            BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                val buf = CharArray(2048)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    append(String(buf, 0, n))
                }
            }
            val code = proc.waitFor()
            exitCode = code
            running = false
            append("\n[exit $code]\n")
        }
    }

    fun stop() {
        val p = process ?: return
        // The Process handle points at the shell wrapper; without taking down the descendants
        // too, killing `sh` leaves `sleep`/`gradle`/whatever as an orphan that keeps writing.
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    private fun append(text: String) {
        synchronized(outputBuilder) {
            outputBuilder.append(text)
            output = outputBuilder.toString()
        }
    }
}
