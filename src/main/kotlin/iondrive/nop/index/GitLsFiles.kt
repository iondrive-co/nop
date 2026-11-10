package iondrive.nop.index

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs `git ls-files` in a project and returns the paths it names, relative to the project.
 *
 * Anything that stops git answering — no git on the PATH, a directory that is not a repository, a
 * listing that takes too long — returns null, and [onTrouble] hears the reasons worth logging, so
 * each caller can fall back to what it did without git.
 */
internal object GitLsFiles {
    /** Past this, git is stuck on something, and the caller's fallback beats waiting on it. */
    private const val TIMEOUT_SECONDS = 30L

    /** `git ls-files -z [args]` in [projectRoot], split at the NULs; null when git could not answer. */
    fun run(projectRoot: Path, args: List<String>, onTrouble: (String) -> Unit): List<String>? {
        val command = listOf("git", "-C", projectRoot.toAbsolutePath().normalize().toString(), "ls-files", "-z") + args
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            onTrouble("could not run git: $e")
            return null
        }
        try {
            process.outputStream.close()
            // Read on a thread of its own so the timeout below can still fire while git is writing:
            // a pipe nobody drains stops git, and waitFor would then wait for as long as it liked.
            var output: ByteArray? = null
            val reader = thread(isDaemon = true, name = "git-ls-files") {
                output = runCatching { process.inputStream.readAllBytes() }.getOrNull()
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                onTrouble("git ls-files took over ${TIMEOUT_SECONDS}s")
                return null
            }
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            // Not a repository (128), or a git too old for an argument: either way, no answer.
            if (process.exitValue() != 0) return null
            val bytes = output ?: return null
            return String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        } finally {
            process.destroyForcibly()
        }
    }
}
