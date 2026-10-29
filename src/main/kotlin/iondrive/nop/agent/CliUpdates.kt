package iondrive.nop.agent

import iondrive.nop.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps installed vendor CLIs current through their own installers, independently of account homes. */
object CliUpdates {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nop-cli-updates").apply { isDaemon = true }
    }

    /** Called only by the nop that owns the windows; checks again while long-running sessions stay open. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({
            runCatching {
                val providers = Accounts.load().accounts.map { it.provider }.toSet()
                for (provider in listOf(Provider.OpenAI, Provider.Anthropic)) {
                    if (provider !in providers) continue
                    val binary = CliTools.locate(provider) ?: continue
                    runCatching { update(binary) }
                        .onSuccess { result ->
                            val message = "CLI update for ${provider.label}: ${result.detail}"
                            if (result.success) Log.info(message) else Log.warn(message)
                        }
                        .onFailure { Log.warn("CLI update for ${provider.label}: $it") }
                }
            }.onFailure { Log.warn("could not check CLI updates: $it") }
        }, 30, TimeUnit.HOURS.toSeconds(6), TimeUnit.SECONDS)
    }

    internal data class Outcome(val success: Boolean, val detail: String)

    /** Closed stdin prevents prompts; output goes to disk so a full pipe cannot stall the installer. */
    internal fun update(binary: Path, timeout: Duration = Duration.ofMinutes(5)): Outcome {
        val output = Files.createTempFile("nop-cli-update-", ".log")
        try {
            val builder = ProcessBuilder(binary.toString(), "update")
                .directory(File(System.getProperty("user.home")))
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
            // Desktop launches may lack the install directory in PATH. Native installers use it
            // to find their launcher and otherwise try to repair the user's shell configuration.
            builder.environment()["PATH"] = binary.toAbsolutePath().parent.toString() +
                File.pathSeparator + builder.environment()["PATH"].orEmpty()
            val process = builder.start()
            try {
                process.outputStream.close()
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    return Outcome(false, "timed out after ${timeout.seconds}s")
                }
                val tail = Files.newInputStream(output).use { stream ->
                    stream.skipNBytes((Files.size(output) - 4096).coerceAtLeast(0))
                    String(stream.readNBytes(4096), Charsets.UTF_8).trim()
                }
                return Outcome(process.exitValue() == 0, "exit ${process.exitValue()}" +
                    tail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
            } finally {
                if (process.isAlive) {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
