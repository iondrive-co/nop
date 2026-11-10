package iondrive.nop.worker

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import iondrive.nop.index.FileIndex
import iondrive.nop.index.Indexer
import iondrive.nop.index.SymbolIndex
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * The work on a project's whole tree that nop does away from its windows: git status, the dirty
 * check behind a project tab's dot, and the project index.
 *
 * Each of these walks the entire working tree, and what they cost is set by the tree's size, which
 * the agents working in it decide. Done in the process that draws nop and holds every terminal, one
 * tree grown past what the heap holds took all of it down. [ProjectWorker] runs them in a process
 * of their own, where running out of memory ends only that process; this is the work itself, which
 * that process runs, and which nop runs in place when it has no launcher to start one with.
 */
internal object ProjectWork {
    /** A status walk's result: the changes, and HEAD as of the walk. */
    data class Status(val status: GitStatus, val head: String?)

    sealed interface Indexed {
        /** The cached index was still current; nothing was written. */
        data object Fresh : Indexed

        /** A new index was written to the project's data directory, listing [files] files. */
        data class Built(val files: Int) : Indexed
    }

    fun status(root: Path): Status {
        val repo = GitRepo.discover(root) ?: error("$root is no longer a git repository")
        return repo.use {
            val status = it.loadStatus()
            val head = it.headSha()
            it.releaseObjectCaches()
            Status(status, head)
        }
    }

    fun dirty(root: Path): Boolean {
        val repo = GitRepo.discover(root) ?: error("$root is no longer a git repository")
        return repo.use {
            val dirty = it.hasChanges()
            it.releaseObjectCaches()
            dirty
        }
    }

    /**
     * Brings [root]'s index in [dataDir] (`index.tsv`, `files.txt`) up to date.
     *
     * [checkFresh]: the caller holds a cached index of [cachedFiles] files as of [since], so a
     * stat-only probe can say nothing changed. [reusePrevious]: the cached symbols are this version's,
     * so Java files untouched since [since] keep their entries (see [Indexer.build]). The file list is
     * stamped with when the build started: a file saved during it may have been read before the save.
     */
    fun index(
        root: Path,
        dataDir: Path,
        checkFresh: Boolean,
        reusePrevious: Boolean,
        since: Long,
        cachedFiles: Int,
        stop: () -> Boolean = { false },
    ): Indexed {
        if (checkFresh && since > 0L && !Indexer.isStale(root, since, cachedFiles, stop)) return Indexed.Fresh
        val indexFile = dataDir.resolve(INDEX_FILE)
        val filesFile = dataDir.resolve(FILES_FILE)
        val started = System.currentTimeMillis()
        val previous = if (reusePrevious) SymbolIndex.load(indexFile) else null
        val walked = ArrayList<String>()
        val symbols = Indexer.build(root, previous, since, walked, stop)
        SymbolIndex.save(indexFile, symbols)
        FileIndex.save(filesFile, FileIndex.of(walked))
        runCatching { Files.setLastModifiedTime(filesFile, FileTime.fromMillis(started)) }
        return Indexed.Built(walked.size)
    }

    const val INDEX_FILE = "index.tsv"
    const val FILES_FILE = "files.txt"
}

/** One piece of work for the worker, as a line of JSON on its input. */
@Serializable
internal data class WorkRequest(
    val id: Long,
    val op: String,
    val root: String,
    val dataDir: String? = null,
    val checkFresh: Boolean = false,
    val reusePrevious: Boolean = false,
    val since: Long = 0L,
    val cachedFiles: Int = 0,
) {
    companion object {
        const val STATUS = "status"
        const val DIRTY = "dirty"
        const val INDEX = "index"
    }
}

/** The worker's answer to the request with the same [id], as a line of JSON on its output. */
@Serializable
internal data class WorkReply(
    val id: Long,
    val error: String? = null,
    val outOfMemory: Boolean = false,
    val branch: String? = null,
    val changes: List<WireChange>? = null,
    val head: String? = null,
    val dirty: Boolean? = null,
    val fresh: Boolean? = null,
    val files: Int? = null,
)

@Serializable
internal data class WireChange(val path: String, val kind: String)

internal fun ProjectWork.Status.toReply(id: Long) = WorkReply(
    id = id,
    branch = status.branch,
    changes = status.changes.map { WireChange(it.path, it.kind.name) },
    head = head,
)

internal fun WorkReply.toStatus() = ProjectWork.Status(
    GitStatus(branch, changes.orEmpty().map { FileChange(it.path, ChangeKind.valueOf(it.kind)) }),
    head,
)
