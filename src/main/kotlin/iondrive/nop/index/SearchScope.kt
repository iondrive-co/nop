package iondrive.nop.index

import iondrive.nop.Log
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The part of a project a find-in-files is worth reading: the files git would show you, less the
 * ones it keeps in Git LFS.
 *
 * The file index lists everything on disk outside a few build directories, which is right for
 * opening a file by name and wrong for reading every one of them. In a data-heavy repository much
 * of it is nothing a search is for: virtualenvs, logs and run output, git-ignored data, and data
 * kept in Git LFS. git lists what is left in well under a second.
 *
 * git itself answers rather than nop, because only git applies every rule that decides this: each
 * nested `.gitignore`, `.git/info/exclude`, the global excludes file, and the `filter=lfs`
 * attributes wherever a `.gitattributes` declares them. The rest of nop reads the repository through
 * JGit, but JGit has no one call for "listed and not in LFS", and getting it right path by path is
 * the kind of thing that fails silently (see Repository.planStaging).
 *
 * Anything that stops git answering — no git on the PATH, a directory that is not a repository, a
 * listing that takes too long — leaves the list as it was. A search that is slower than it could be
 * is recoverable; one that quietly skips files is not.
 */
object SearchScope {
    /** Past this, git is stuck on something, and a search of every file beats waiting on it. */
    private const val TIMEOUT_SECONDS = 30L

    /** [files] (project-relative, `/`-separated) cut down to the ones git lists outside Git LFS. */
    fun narrow(projectRoot: Path, files: List<String>): List<String> {
        val listed = gitListed(projectRoot) ?: return files
        return files.filter { keeps(listed, it) }
    }

    /**
     * Whether [rel] is in [listed] — itself, or under a directory git lists as one entry.
     *
     * git names a submodule, and an untracked repository nested inside this one, by its directory
     * alone rather than by the files in it. Everything in the index under such a directory is kept:
     * git has not said it is ignored, only that it is somebody else's to describe.
     */
    internal fun keeps(listed: Set<String>, rel: String): Boolean {
        if (rel in listed) return true
        var slash = rel.lastIndexOf('/')
        while (slash > 0) {
            val dir = rel.substring(0, slash)
            if (dir in listed || "$dir/" in listed) return true
            slash = rel.lastIndexOf('/', slash - 1)
        }
        return false
    }

    /**
     * The paths `git ls-files` gives for [projectRoot], relative to it, or null when git could not
     * answer.
     *
     * Tracked files and untracked ones that are not ignored (`--cached --others --exclude-standard`),
     * with anything carrying `filter=lfs` left out by the pathspec. Paths come back relative to the
     * directory git is run in, which is what the index holds even when [projectRoot] is a
     * subdirectory of the repository.
     */
    internal fun gitListed(projectRoot: Path): Set<String>? {
        val command = listOf(
            "git", "-C", projectRoot.toAbsolutePath().normalize().toString(),
            "ls-files", "-z", "--cached", "--others", "--exclude-standard",
            "--", ".", ":(exclude,attr:filter=lfs)",
        )
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            Log.warn("find in files: could not run git, searching every indexed file: $e")
            return null
        }
        try {
            process.outputStream.close()
            // Read on a thread of its own so the timeout below can still fire while git is writing:
            // a pipe nobody drains stops git, and waitFor would then wait for as long as it liked.
            var output: ByteArray? = null
            val reader = thread(isDaemon = true, name = "search-scope-git") {
                output = runCatching { process.inputStream.readAllBytes() }.getOrNull()
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.warn("find in files: git ls-files took over ${TIMEOUT_SECONDS}s, searching every indexed file")
                return null
            }
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            // Not a repository (128), or a git too old for the attr pathspec: either way, no answer.
            if (process.exitValue() != 0) return null
            val bytes = output ?: return null
            return String(bytes, Charsets.UTF_8).split('\u0000').filterTo(HashSet()) { it.isNotEmpty() }
        } finally {
            process.destroyForcibly()
        }
    }
}
