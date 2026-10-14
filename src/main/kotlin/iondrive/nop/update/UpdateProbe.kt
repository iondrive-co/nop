package iondrive.nop.update

import iondrive.nop.ipc.Handover
import iondrive.nop.ipc.Restart
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `nop --update-probe`: one line saying which build this is and which handovers it takes up, printed
 * before nop does anything else, then exit.
 *
 * A nop that has staged an update runs the update's launcher with it. An answer proves the update's
 * runtime and jars start at all, which nothing short of running it can, and says whether a restart
 * into it would carry the terminals across ([Handover.ADOPTS]).
 */
object UpdateProbe {

    const val FLAG = "--update-probe"

    private const val PREFIX = "nop-update-probe"

    /** What a build says about itself. */
    data class Answer(val version: Version?, val handover: Int, val adopts: Set<Int>)

    /** What running a launcher with [FLAG] came to. */
    sealed interface Outcome {
        data class Answered(val answer: Answer) : Outcome

        /** A build older than the flag, which refuses it as an unknown option and starts nothing. */
        data object TooOld : Outcome

        /** [why] in a phrase (`it crashed with signal 11 (SIGSEGV)`), and all it printed. */
        data class Failed(val why: String, val output: String = "") : Outcome
    }

    /** This build's answer. */
    fun answer(): String =
        "$PREFIX version=${BuildInfo.version ?: "unknown"} handover=${Handover.VERSION} " +
            "adopts=${Handover.ADOPTS.sorted().joinToString(",")}"

    internal fun parse(output: String): Answer? {
        val line = output.lineSequence().firstOrNull { it.startsWith("$PREFIX ") } ?: return null
        val fields = line.removePrefix("$PREFIX ").split(' ').mapNotNull { field ->
            field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val handover = fields["handover"]?.toIntOrNull() ?: return null
        val adopts = fields["adopts"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: setOf(handover)
        return Answer(fields["version"]?.let(Version::parse), handover, adopts)
    }

    /**
     * Runs [launcher] with [FLAG] in the environment a restart would exec it in ([Restart.successorEnvironment]),
     * so an answer says the restart would start too — but with config, data and runtime directories
     * that are empty, so a build that took the flag for something else would still find no nop to reach.
     */
    fun run(launcher: Path): Outcome {
        val scratch = Files.createTempDirectory("nop-update-probe")
        try {
            val env = Restart.successorEnvironment(System.getenv()).toMutableMap()
            for (dir in listOf("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_RUNTIME_DIR")) {
                env[dir] = Files.createDirectory(scratch.resolve(dir.lowercase())).toString()
            }
            val process = ProcessBuilder(launcher.toString(), FLAG)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .apply { environment().clear(); environment().putAll(env) }
                .start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching { process.inputStream.bufferedReader().forEachLine { if (output.length < 8192) output.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Outcome.Failed("it did not answer within $PROBE_SECONDS seconds", output.toString())
            }
            reader.join(1000)
            val text = output.toString()
            parse(text)?.let { return Outcome.Answered(it) }
            if (process.exitValue() == 2 && "unknown option" in text) return Outcome.TooOld
            val said = text.trim().lines().lastOrNull()?.takeIf { it.isNotBlank() }?.let { ", saying: ${it.take(200)}" } ?: " without printing anything"
            return Outcome.Failed("it ${ended(process.exitValue())}$said", text)
        } catch (e: Exception) {
            return Outcome.Failed("it could not be run: ${e.message ?: e}")
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    /** How a process that exited with [status] ended, in words: a status over 128 is a signal. */
    internal fun ended(status: Int): String {
        if (status <= 128) return "exited $status"
        val signal = status - 128
        val name = SIGNALS[signal]?.let { " ($it)" }.orEmpty()
        return "crashed with signal $signal$name"
    }

    private val SIGNALS = mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV", 15 to "SIGTERM")

    private const val PROBE_SECONDS = 60L
}
