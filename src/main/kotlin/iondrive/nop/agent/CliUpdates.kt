package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    sealed interface Status {
        data object Idle : Status
        data class Updating(
            val provider: Provider? = null,
            val refreshingModels: Boolean = false,
        ) : Status
        data class Done(val results: List<ProviderResult>) : Status
    }

    data class Outcome(val success: Boolean, val detail: String)
    data class ProviderResult(val provider: Provider, val outcome: Outcome)

    /** Latest update state for the UI. Compose state; written from the updater's own thread. */
    var status: Status by mutableStateOf(Status.Idle)
        internal set

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nop-cli-updates").apply { isDaemon = true }
    }

    /** Called only by the nop that owns the windows; checks again while long-running sessions stay open. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({
            runUpdates(configuredOnly = true)
        }, 30, TimeUnit.HOURS.toSeconds(6), TimeUnit.SECONDS)
    }

    /** Checks for and applies updates for installed agent CLIs and refreshes model catalogs off the calling thread. */
    fun updateNow() {
        if (status is Status.Updating) return
        executor.execute { runUpdates(configuredOnly = false) }
    }

    internal fun runUpdates(
        configuredOnly: Boolean,
        accountsProvider: () -> List<Account> = { Accounts.load().accounts },
        locateBinary: (Provider) -> Path? = { CliTools.locate(it) },
        runUpdate: (Path) -> Outcome = { update(it) },
        refreshModels: (List<Account>) -> Unit = { Usage.refreshModels(it) },
    ) {
        if (!running.compareAndSet(false, true)) return
        try {
            status = Status.Updating()
            val accounts = accountsProvider()
            val configured = if (configuredOnly) accounts.map { it.provider }.toSet() else null
            val results = mutableListOf<ProviderResult>()
            for (provider in UPDATABLE_PROVIDERS) {
                if (configured != null && provider !in configured) continue
                val binary = locateBinary(provider) ?: continue
                status = Status.Updating(provider)
                val outcome = runCatching { runUpdate(binary) }
                    .getOrElse { Outcome(false, it.toString()) }
                val message = "CLI update for ${provider.label}: ${outcome.detail}"
                if (outcome.success) Log.info(message) else Log.warn(message)
                results += ProviderResult(provider, outcome)
            }
            status = Status.Updating(refreshingModels = true)
            runCatching { refreshModels(accounts) }
                .onFailure { Log.warn("could not refresh agent models: $it") }
            status = Status.Done(results)
        } catch (t: Throwable) {
            Log.warn("could not check CLI updates: $t")
            status = Status.Done(emptyList())
        } finally {
            running.set(false)
        }
    }

    private val UPDATABLE_PROVIDERS = listOf(Provider.OpenAI, Provider.Anthropic)

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
