package iondrive.nop.git

import iondrive.nop.PathOrder
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.CoreConfig
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class GitRepo(val rootDir: Path, private val repository: Repository) : AutoCloseable {
    private val git: Git = Git.wrap(repository)

    /**
     * Stages the given changes and creates a commit. ADDED/MODIFIED/UNTRACKED paths are added
     * via `git add`; REMOVED/MISSING paths are staged for removal via `git rm --cached`.
     * Returns the new commit SHA, or throws if no changes were ultimately staged.
     *
     * Both halves go in as one command apiece rather than one per file, for the same reason
     * [revertFiles] batches. Every AddCommand parses the whole index, walks, then writes the whole
     * index back and fsyncs it, so a per-file loop costs O(files x index size): staging 2.4k paths
     * against an 19k-entry index measured at 59s that way against 5s batched. It also makes staging
     * atomic — JGit holds `index.lock` for the length of a command and unlocks without publishing if
     * it throws, so a file that vanishes mid-walk (a build or download still writing into the tree)
     * now leaves the index untouched instead of stranding the paths that happened to be staged
     * before the failure, half-committed and needing an unstage by hand.
     */
    fun stageAndCommit(message: String, changes: Collection<FileChange>): String {
        val (removed, staged) = changes.partition {
            it.kind == ChangeKind.REMOVED || it.kind == ChangeKind.MISSING
        }
        if (staged.isNotEmpty() && !stageBlobsInParallel(staged)) {
            val add = git.add()
            staged.forEach { add.addFilepattern(it.path) }
            add.call()
        }
        if (removed.isNotEmpty()) {
            val rm = git.rm().setCached(true)
            removed.forEach { rm.addFilepattern(it.path) }
            rm.call()
        }
        val commit = git.commit().setMessage(message).call()
        return commit.name
    }

    /**
     * Stages [staged] by deflating its blobs across every core, returning false if it declines the
     * job so [stageAndCommit] falls back to JGit's [org.eclipse.jgit.api.AddCommand].
     *
     * AddCommand walks and inserts on one thread, and writing a blob is zlib deflate — pure CPU.
     * On a change set of already-compressed files that is the entire cost of a commit: 400 real
     * .npz totalling 1.9 GB measured 63.5s of a 63.7s commit, at user=59.4s against real=61.8s,
     * i.e. exactly one core of sixteen while deflate bought nothing (the object store came back
     * byte-identical with compression off). Files are independent, so they are handed to a worker
     * apiece, each with its own [ObjectInserter] — inserters are not thread-safe, but a
     * FileRepository's loose objects are written to per-insert temp files and renamed, so parallel
     * inserters do not contend.
     *
     * The index is still built on one thread once every blob is in, and only then published, so
     * this keeps the atomicity [stageAndCommit] relies on: a file that vanishes mid-run makes its
     * own insert throw, and the DirCache is unlocked without ever being written.
     *
     * It declines whenever the bytes on disk might not be the bytes git should record, because it
     * reads files directly instead of through a [org.eclipse.jgit.treewalk.WorkingTreeIterator] and
     * so applies none of git's content filters. That means CRLF conversion, clean filters (Git LFS
     * among them), any .gitattributes that could turn one into play, and symlinks all go back to
     * AddCommand, as does a change set too small for the threads to pay for themselves.
     */
    private fun stageBlobsInParallel(staged: List<FileChange>): Boolean {
        if (staged.size < PARALLEL_STAGE_MIN_FILES) return false
        val plan = planStaging(staged)
        if (plan.fast.size < PARALLEL_STAGE_MIN_FILES) return false
        if (plan.fast.sumOf { it.file.length() } < PARALLEL_STAGE_MIN_BYTES &&
            plan.fast.size < PARALLEL_STAGE_ALWAYS_FILES
        ) return false

        // Anything needing conversion goes through AddCommand first. It takes and releases the
        // index lock itself, so it has to finish before the fast half locks the index in turn.
        if (plan.slow.isNotEmpty()) {
            val add = git.add()
            plan.slow.forEach { add.addFilepattern(it.path) }
            add.call()
        }

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
        val queue = ConcurrentLinkedQueue(plan.fast)
        val done = ConcurrentLinkedQueue<StagedBlob>()
        val pool = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "nop-stage").apply { isDaemon = true }
        }
        val dirCache = repository.lockDirCache()
        try {
            val tasks = (1..workers).map {
                Callable {
                    repository.newObjectInserter().use { inserter ->
                        while (true) {
                            val entry = queue.poll() ?: break
                            // If the file is being rewritten underneath us the stream runs short and
                            // insert throws — better a failed commit than one recording a length
                            // that never existed.
                            val length = entry.file.length()
                            val id = FileInputStream(entry.file).use { input ->
                                inserter.insert(Constants.OBJ_BLOB, length, input)
                            }
                            done.add(
                                StagedBlob(
                                    path = entry.path,
                                    id = id,
                                    length = length,
                                    lastModified = Files.getLastModifiedTime(entry.file.toPath()).toInstant(),
                                    executable = entry.executable,
                                )
                            )
                        }
                        inserter.flush()
                    }
                }
            }
            // invokeAll waits for every worker, so no insert is still running when we unwind.
            pool.invokeAll(tasks).forEach { it.get() }

            val editor = dirCache.editor()
            for (blob in done) {
                editor.add(object : DirCacheEditor.PathEdit(blob.path) {
                    override fun apply(ent: DirCacheEntry) {
                        ent.fileMode = if (blob.executable) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE
                        ent.setLength(blob.length)
                        ent.setLastModified(blob.lastModified)
                        ent.setObjectId(blob.id)
                    }
                })
            }
            if (!editor.commit()) throw IOException("could not write $gitDir/index")
            return true
        } catch (t: Throwable) {
            dirCache.unlock()
            throw (t as? ExecutionException)?.cause ?: t
        } finally {
            pool.shutdownNow()
        }
    }

    /** One file's blob, inserted by a worker and waiting to go into the index. */
    private data class StagedBlob(
        val path: String,
        val id: ObjectId,
        val length: Long,
        val lastModified: Instant,
        val executable: Boolean,
    )

    /** A file whose bytes on disk are exactly the bytes git should record. */
    private class FastEntry(val path: String, val file: File, val executable: Boolean)

    /** [fast] can be read straight off disk in parallel; [slow] must go through AddCommand. */
    private class StagePlan(val fast: List<FastEntry>, val slow: List<FileChange>)

    /**
     * Sorts [staged] into the paths that can be read raw and the paths that cannot.
     *
     * The decision is per file, not per repository, and that distinction is the whole point: this
     * machine has `core.autocrlf=input` set globally and git-lfs registered in ~/.gitconfig, and
     * hermes carries a .gitattributes — so a repo-wide "might a filter apply?" test says yes
     * everywhere and the fast path never runs. Asking JGit per path instead is what lets a commit
     * of binary data go fast in a repo that also holds filtered text.
     *
     * The walk itself is stat-only and costs about what one status pass costs; it is the content
     * reads it *avoids* doing sequentially that matter. For each entry it takes JGit's own answers
     * — [WorkingTreeIterator.getCleanFilterCommand] and [WorkingTreeIterator.getEolStreamType] —
     * rather than re-deriving them, so a clean filter (Git LFS) or an unconditional text=/eol=
     * conversion sends that path back to AddCommand. Under autocrlf the type comes back AUTO_LF,
     * which converts text but passes binary through untouched, so binary content is still safe to
     * read raw; that is decided with git's own rule, a NUL byte in the first 8k
     * ([org.eclipse.jgit.diff.RawText.isBinary]).
     */
    private fun planStaging(staged: List<FileChange>): StagePlan {
        val wanted = staged.associateBy { it.path }
        val fast = ArrayList<FastEntry>()
        val slow = ArrayList<FileChange>()
        val seen = HashSet<String>()
        runCatching {
            TreeWalk(repository).use { walk ->
                walk.addTree(FileTreeIterator(repository))
                walk.isRecursive = true
                walk.filter = PathFilterGroup.createFromStrings(wanted.keys)
                while (walk.next()) {
                    val change = wanted[walk.pathString] ?: continue
                    seen.add(change.path)
                    val f = walk.getTree(0, WorkingTreeIterator::class.java)
                    val mode = f?.entryFileMode
                    val plain = mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE
                    if (f == null || !plain || f.cleanFilterCommand != null || !passesThrough(f, change)) {
                        slow.add(change)
                    } else {
                        fast.add(
                            FastEntry(
                                path = change.path,
                                file = File(rootDir.toFile(), change.path),
                                executable = mode == FileMode.EXECUTABLE_FILE,
                            )
                        )
                    }
                }
            }
        }.onFailure {
            // A walk that cannot complete tells us nothing about any path, so nothing is fast.
            return StagePlan(emptyList(), staged)
        }
        // Paths the walk never yielded are gone from disk (or ignored); AddCommand knows what to
        // do with those, and it is the same thing it does today.
        staged.filterTo(slow) { it.path !in seen }
        return StagePlan(fast, slow)
    }

    /** True when check-in would copy this file's bytes through unchanged. */
    private fun passesThrough(f: WorkingTreeIterator, change: FileChange): Boolean =
        when (f.eolStreamType) {
            CoreConfig.EolStreamType.DIRECT -> true
            CoreConfig.EolStreamType.AUTO_LF, CoreConfig.EolStreamType.AUTO_CRLF ->
                runCatching {
                    FileInputStream(File(rootDir.toFile(), change.path)).use { RawText.isBinary(it) }
                }.getOrDefault(false)
            else -> false
        }

    /**
     * Soft-resets the current branch back one commit (`git reset --soft HEAD~1`). The last commit
     * is removed from the branch tip, but its changes stay staged in the index and working tree, so
     * they reappear as pending changes ready to be amended and re-committed. Returns false (and does
     * nothing) when HEAD has no parent — a single root commit or an unborn branch can't go back a
     * revision.
     */
    fun softResetHead(): Boolean {
        val parent = repository.resolve("HEAD~1") ?: return false
        git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(parent.name).call()
        return true
    }

    /** True when HEAD has a parent commit, i.e. [softResetHead] can move back a revision. */
    fun canSoftResetHead(): Boolean = repository.resolve("HEAD~1") != null

    /**
     * SHA of the commit HEAD points at, or null on an unborn branch with no commits yet. A ref
     * read rather than a walk, so it is cheap enough for the poll to take on every tick: it is how
     * a commit, merge, pull or reset — each of which moves what an open diff's left-hand side
     * should say — is noticed even when the set of dirty files comes back identical.
     */
    fun headSha(): String? = repository.resolve("HEAD")?.name

    /**
     * Discards local changes to a single file, restoring it to its last committed state — the
     * per-file counterpart to a rollback:
     *  - a modified, deleted, or conflicted tracked file is reset to its HEAD content, overwriting
     *    both the staged index entry and the working-tree copy (`git checkout HEAD -- path`);
     *  - a newly added file (staged but not yet committed) is unstaged and removed from disk;
     *  - an untracked file is simply deleted.
     * Destructive: any uncommitted edits to the file are lost and cannot be recovered.
     */
    fun revertFile(change: FileChange) {
        when (change.kind) {
            ChangeKind.UNTRACKED ->
                File(rootDir.toFile(), change.path).delete()
            ChangeKind.ADDED -> {
                // Unstage the addition (rm --cached mirrors how stageAndCommit unstages and works
                // even on an unborn branch with no HEAD), then delete the working-tree file.
                runCatching { git.rm().setCached(true).addFilepattern(change.path).call() }
                File(rootDir.toFile(), change.path).delete()
            }
            ChangeKind.MODIFIED, ChangeKind.REMOVED, ChangeKind.MISSING, ChangeKind.CONFLICT -> {
                // Reset the index entry back to HEAD (this also clears any conflict stages), then
                // write HEAD's content into the working tree, recreating a deleted file if needed.
                git.reset().setRef("HEAD").addPath(change.path).call()
                git.checkout().addPath(change.path).call()
            }
        }
    }

    /**
     * Discards local changes to every one of [changes] at once — the whole-working-tree
     * counterpart to [revertFile], as raised by the commit panel's "Revert all".
     *
     * Tracked paths are reset and checked out in one command apiece rather than a pair per file,
     * so reverting a large change set costs a couple of git operations instead of hundreds; new
     * files are unstaged (if staged) and deleted. Destructive in exactly the same way as
     * [revertFile]: nothing discarded here can be recovered.
     */
    fun revertFiles(changes: Collection<FileChange>) {
        val (fresh, tracked) = changes.partition {
            it.kind == ChangeKind.UNTRACKED || it.kind == ChangeKind.ADDED
        }
        if (tracked.isNotEmpty()) {
            val reset = git.reset().setRef("HEAD")
            tracked.forEach { reset.addPath(it.path) }
            reset.call()
            val checkout = git.checkout()
            tracked.forEach { checkout.addPath(it.path) }
            checkout.call()
        }
        // Staged additions must leave the index before their file goes; untracked ones only exist
        // on disk. rm --cached mirrors revertFile and works on an unborn branch with no HEAD.
        val staged = fresh.filter { it.kind == ChangeKind.ADDED }
        if (staged.isNotEmpty()) {
            val rm = git.rm().setCached(true)
            staged.forEach { rm.addFilepattern(it.path) }
            runCatching { rm.call() }
        }
        fresh.forEach { File(rootDir.toFile(), it.path).delete() }
    }

    /**
     * The most recent commit messages (full bodies, trimmed), newest first and de-duplicated, for
     * offering as reusable commit messages. Walks at most [limit] commits. Returns empty for an
     * unborn branch with no commits yet.
     */
    fun recentCommitMessages(limit: Int = 20): List<String> =
        runCatching {
            git.log().setMaxCount(limit).call()
                .map { it.fullMessage.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }.getOrDefault(emptyList())

    /**
     * Stash all uncommitted changes (including untracked) to the shelf.
     * Returns the new stash SHA, or null if the working tree had nothing to stash.
     */
    fun stashCreate(message: String? = null): String? {
        val cmd = git.stashCreate().setIncludeUntracked(true)
        if (!message.isNullOrBlank()) cmd.setWorkingDirectoryMessage(message)
        val commit = cmd.call() ?: return null
        return commit.name
    }

    fun stashList(): List<StashEntry> =
        git.stashList().call().mapIndexed { idx, c ->
            StashEntry(index = idx, sha = c.name, message = c.shortMessage ?: "(no message)")
        }

    /** Apply a stash without removing it from the shelf. */
    fun stashApply(entry: StashEntry) {
        git.stashApply().setStashRef(entry.sha).call()
    }

    /** Apply a stash then drop it. */
    fun stashPop(entry: StashEntry) {
        // Apply first; if this throws (e.g., conflicts), keep the stash on the shelf
        git.stashApply().setStashRef(entry.sha).call()
        git.stashDrop().setStashRef(entry.index).call()
    }

    /** Drop a stash without applying. */
    fun stashDrop(entry: StashEntry) {
        git.stashDrop().setStashRef(entry.index).call()
    }

    fun loadStatus(): GitStatus {
        val status = git.status().call()
        val changes = buildList {
            status.modified.forEach { add(FileChange(it, ChangeKind.MODIFIED)) }
            status.changed.forEach { add(FileChange(it, ChangeKind.MODIFIED)) }
            status.added.forEach { add(FileChange(it, ChangeKind.ADDED)) }
            status.untracked.forEach { add(FileChange(it, ChangeKind.UNTRACKED)) }
            status.removed.forEach { add(FileChange(it, ChangeKind.REMOVED)) }
            status.missing.forEach { add(FileChange(it, ChangeKind.MISSING)) }
            status.conflicting.forEach { add(FileChange(it, ChangeKind.CONFLICT)) }
        }.distinctBy { it.path }
            // JGit returns each bucket as a hash set, so the order it hands paths back in is
            // arbitrary and — worse — shifts as the sets are rebuilt: editing one file could send
            // an unrelated one to the top of the change list. Sort so a file stays where the user
            // last saw it, and so files in a folder stay together. distinctBy runs first: it keeps
            // the earliest entry for a path, which is how modified/changed wins over the rest.
            .sortedWith(compareBy(PathOrder) { it.path })
        val branch = repository.branch
        return GitStatus(branch = branch, changes = changes)
    }

    /** File content at HEAD for the given repo-relative path, or null if the path is absent from HEAD. */
    fun readHeadContent(relPath: String): String? = readContentAt("HEAD", relPath)

    /** File content at an arbitrary revision for the given repo-relative path. */
    fun readContentAt(rev: String, relPath: String): String? {
        val objectId = repository.resolve(rev) ?: return null
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(objectId)
            TreeWalk.forPath(repository, relPath, commit.tree)?.use { tw ->
                val blobId = tw.getObjectId(0)
                val loader = repository.open(blobId)
                return String(loader.bytes, Charsets.UTF_8)
            }
        }
        return null
    }

    /**
     * Commits touching [relPath], newest first. Pass null/empty to log the whole repo.
     * Capped at [limit] entries to keep the UI snappy on long-lived files.
     */
    fun history(relPath: String?, limit: Int = 200): List<CommitInfo> {
        val cmd = git.log().setMaxCount(limit)
        if (!relPath.isNullOrEmpty()) cmd.addPath(relPath)
        return cmd.call().map { c ->
            CommitInfo(
                sha = c.name,
                author = c.authorIdent?.name ?: "(unknown)",
                whenEpochSeconds = c.commitTime.toLong(),
                shortMessage = c.shortMessage ?: "",
            )
        }
    }

    /** Files changed in a single commit, compared against its first parent (or the empty tree for root commits). */
    fun commitFiles(sha: String): List<CommitFile> {
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(repository.resolve(sha))
            val reader = repository.newObjectReader()
            val newTree = CanonicalTreeParser().apply { reset(reader, commit.tree) }
            val oldTree = if (commit.parentCount > 0) {
                val parent = walk.parseCommit(commit.getParent(0))
                CanonicalTreeParser().apply { reset(reader, parent.tree) }
            } else null

            val formatter = DiffFormatter(DisabledOutputStream.INSTANCE)
            formatter.setRepository(repository)
            val diffs = if (oldTree != null) {
                formatter.scan(oldTree, newTree)
            } else {
                formatter.scan(EmptyTreeIterator(), newTree)
            }
            return diffs.map { d ->
                CommitFile(
                    path = (d.newPath.takeIf { it != DiffEntry.DEV_NULL } ?: d.oldPath),
                    changeType = when (d.changeType) {
                        DiffEntry.ChangeType.ADD -> CommitFileChange.ADDED
                        DiffEntry.ChangeType.DELETE -> CommitFileChange.DELETED
                        DiffEntry.ChangeType.MODIFY -> CommitFileChange.MODIFIED
                        DiffEntry.ChangeType.RENAME -> CommitFileChange.RENAMED
                        DiffEntry.ChangeType.COPY -> CommitFileChange.COPIED
                        else -> CommitFileChange.MODIFIED
                    },
                )
            }
        }
    }

    /**
     * Per-line git blame for [relPath], following renames. Each entry attributes one line of the
     * *current working-tree* file to the commit that last touched it, so the result lines up with
     * what the editor shows: lines whose content matches HEAD carry that commit's sha/author/date,
     * while lines edited locally but not yet committed come back with a null sha (see [BlameLine]).
     * Returns null when the path can't be blamed — absent from history, binary, or an unborn branch.
     */
    fun blame(relPath: String): List<BlameLine>? = runCatching {
        val result = git.blame()
            .setFilePath(relPath)
            // Ignore whitespace-only churn so reflows/reindents don't reassign a line to the commit
            // that merely re-spaced it — matches what `git blame -w` and IntelliJ's annotate show.
            .setTextComparator(org.eclipse.jgit.diff.RawTextComparator.WS_IGNORE_ALL)
            .setFollowFileRenames(true)
            .call() ?: return null
        val lineCount = result.resultContents.size()
        (0 until lineCount).map { i ->
            val commit = result.getSourceCommit(i)
            val author = result.getSourceAuthor(i)
            BlameLine(
                sha = commit?.name,
                author = author?.name ?: commit?.authorIdent?.name ?: "",
                whenEpochSeconds = commit?.commitTime?.toLong() ?: 0L,
                summary = commit?.shortMessage ?: "Uncommitted changes",
            )
        }
    }.getOrNull()

    /** File content from the working tree, or null if the file is absent. */
    fun readWorkingTreeContent(relPath: String): String? {
        val file = File(rootDir.toFile(), relPath)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** The repository's git directory — `.git` in an ordinary clone. */
    val gitDir: Path get() = repository.directory.toPath()

    /**
     * Every working-tree directory a status walk looks inside: [rootDir] plus each subdirectory
     * that isn't ignored. Meant for registering filesystem watches over the same ground
     * [loadStatus] covers, so a caller can be *told* when to re-run it instead of re-running it to
     * find out.
     *
     * Pruning ignored directories is what makes watching a whole rail of projects affordable: on
     * this machine's 23, the 61.8k directories on disk came to 1.5k once virtualenvs and build
     * output were dropped. Directories the index already holds an entry under are kept even when an
     * ignore rule matches them — git goes on tracking what it already tracks, so changes there do
     * move status, and pruning them would lose changes silently.
     *
     * Returns null as soon as the walk passes [limit] directories, which is the caller's signal
     * that this tree is too big to watch and has to be polled instead. Walking the rest only to say
     * how far over it went would be effort spent on a tree we have already given up on.
     */
    fun workingTreeDirs(limit: Int): List<Path>? {
        val indexDirs = indexDirs()
        val dirs = mutableListOf(rootDir)
        TreeWalk(repository).use { walk ->
            walk.addTree(FileTreeIterator(repository))
            // Non-recursive plus an explicit enterSubtree() is what lets a directory be inspected
            // *before* its contents are: an ignored tree is skipped whole, never descended into.
            walk.isRecursive = false
            while (walk.next()) {
                if (!walk.isSubtree) continue
                val entry = walk.getTree(0, WorkingTreeIterator::class.java) ?: continue
                val path = walk.pathString
                if (entry.isEntryIgnored && path !in indexDirs) continue
                if (dirs.size >= limit) return null
                dirs.add(rootDir.resolve(path))
                walk.enterSubtree()
            }
        }
        return dirs
    }

    /**
     * Every directory the index holds an entry under, ancestors included, as repo-relative paths.
     * Read straight from the index rather than walked: it is the cheap half of the question
     * [workingTreeDirs] asks, and the answer is needed before the walk starts.
     */
    private fun indexDirs(): Set<String> {
        val cache = runCatching { repository.readDirCache() }.getOrNull() ?: return emptySet()
        val dirs = HashSet<String>()
        for (i in 0 until cache.entryCount) {
            val path = cache.getEntry(i).pathString
            var slash = path.lastIndexOf('/')
            // Walk the ancestors from the deepest up, stopping at the first one already recorded —
            // everything above it went in with whichever entry recorded it.
            while (slash > 0) {
                if (!dirs.add(path.substring(0, slash))) break
                slash = path.lastIndexOf('/', slash - 1)
            }
        }
        return dirs
    }

    override fun close() {
        repository.close()
    }

    companion object {
        /** Below this many files the worker threads cost more than the deflate they save. */
        private const val PARALLEL_STAGE_MIN_FILES = 8
        /** ...unless the change set is small in count but heavy in bytes, where they still pay. */
        private const val PARALLEL_STAGE_MIN_BYTES = 8L * 1024 * 1024
        /** ...or large in count, where per-file overhead dominates however small the files are. */
        private const val PARALLEL_STAGE_ALWAYS_FILES = 64

        fun discover(path: Path, ceiling: Path? = null): GitRepo? {
            val gitDir = findGitDir(path.toFile(), ceiling?.toFile()) ?: return null
            val repository = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()
            return GitRepo(rootDir = gitDir.parentFile.toPath(), repository = repository)
        }

        // Nearest `.git` at or above [start]. The search climbs parent directories
        // until it finds one or has inspected [ceiling] (inclusive); a null ceiling
        // climbs to the filesystem root, matching git's default behaviour. The
        // ceiling (cf. git's GIT_CEILING_DIRECTORIES) lets callers bound the walk —
        // useful in tests, where a scratch dir often sits inside another working tree.
        private fun findGitDir(start: File, ceiling: File? = null): File? {
            var cur: File? = start.absoluteFile
            val stop = ceiling?.absoluteFile
            while (cur != null) {
                val candidate = File(cur, ".git")
                if (candidate.exists()) return candidate
                if (cur == stop) return null
                cur = cur.parentFile
            }
            return null
        }
    }
}
