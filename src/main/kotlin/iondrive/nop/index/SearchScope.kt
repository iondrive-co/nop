package iondrive.nop.index

import iondrive.nop.Log
import java.nio.file.Path

/**
 * The part of a project a find-in-files is worth reading: the files git would show you, less the
 * ones it keeps in Git LFS.
 *
 * The file index lists everything on disk outside build directories and the directories git ignores
 * whole, which is right for opening a file by name and wrong for reading every one of them. In a
 * data-heavy repository much of it is nothing a search is for: ignored files outside those
 * directories, and data kept in Git LFS. git lists what is left in well under a second.
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
    internal fun gitListed(projectRoot: Path): Set<String>? =
        GitLsFiles.run(
            projectRoot,
            listOf("--cached", "--others", "--exclude-standard", "--", ".", ":(exclude,attr:filter=lfs)"),
        ) { Log.warn("find in files: $it, searching every indexed file") }?.toHashSet()
}
