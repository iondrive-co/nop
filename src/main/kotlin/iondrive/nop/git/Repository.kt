package iondrive.nop.git

import iondrive.nop.PathOrder
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.StashApplyFailureException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.IndexDiffFilter
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.treewalk.filter.SkipWorkTreeFilter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.eclipse.jgit.dircache.Checkout
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.CoreConfig
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.merge.ResolveMerger
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
     *
     * Pass [partial] when [changes] deliberately leaves some of the working tree's pending changes
     * out, which makes the commit path-limited (`git commit -- <paths>`) instead of a commit of the
     * index as it stands. It matters because the index is not always nop's alone: a path staged
     * from a terminal shows up as a pending change like any other, and unticking it has to keep it
     * out of the commit rather than merely decline to re-stage it. The whole-working-tree case
     * skips the pathspec because it cannot arise there — every index entry that differs from HEAD
     * is a pending change, so a commit of them all is a commit of the index — and because a
     * pathspec makes JGit rebuild the commit's tree from disk, re-reading every file the staging
     * above has just read.
     *
     * [onProgress] is called as the commit advances, for the commit button's readout. It is
     * invoked from the staging worker threads as well as this one, so it must be cheap and
     * thread-safe; snapshots arrive in order and never go backwards. [startedAtMillis] is the
     * clock the reported elapsed time and ETA run from — pass the moment the *user* asked for the
     * commit, which is earlier than this call by however long the pre-commit status check took.
     */
    fun stageAndCommit(
        message: String,
        changes: Collection<FileChange>,
        partial: Boolean = false,
        startedAtMillis: Long = System.currentTimeMillis(),
        onProgress: (CommitProgress) -> Unit = {},
    ): String {
        val (removed, staged) = changes.partition {
            it.kind == ChangeKind.REMOVED || it.kind == ChangeKind.MISSING
        }
        val progress = ProgressEmitter(onProgress, startedAtMillis, filesTotal = staged.size)
        progress.enter(CommitProgress.Phase.STAGING)
        if (staged.isNotEmpty() && !stageBlobsInParallel(staged, progress)) {
            // AddCommand reports nothing as it walks, so this path stays on elapsed time only:
            // the emitter's byte total is still 0 and the button shows "Staging… 1m 20s".
            val add = git.add()
            staged.forEach { add.addFilepattern(it.path) }
            add.call()
        }
        if (removed.isNotEmpty()) {
            val rm = git.rm().setCached(true)
            removed.forEach { rm.addFilepattern(it.path) }
            rm.call()
        }
        progress.enter(CommitProgress.Phase.COMMITTING)
        val commit = git.commit().setMessage(message).apply {
            if (partial) {
                changes.forEach { setOnly(it.path) }
                // JGit refuses an empty commit once a pathspec is set, where a commit of the whole
                // index allows one. Keep the looser rule: a change set that races to a no-op is not
                // worth an error dialog, and never was on the other path.
                setAllowEmpty(true)
            }
        }.call()
        return commit.name
    }

    /**
     * Collects the byte and file counts a commit reports back through
     * [stageAndCommit]'s `onProgress`, and delivers snapshots of them.
     *
     * Every worker in [stageBlobsInParallel] calls [advance] as it finishes a blob, so the counters
     * are atomics; delivery is throttled to [MIN_EMIT_INTERVAL_NANOS] because a change set of
     * thousands of small files would otherwise emit thousands of times a second for a readout that
     * a user reads once a second. Phase changes bypass the throttle — they are rare and each one
     * changes what the button says.
     *
     * Reading the counters and calling the sink happens under a lock so the delivered snapshot is
     * self-consistent (a fraction can't be built from one worker's byte count and another's file
     * count) and monotone, which is what lets the UI trust the numbers to only ever go up.
     */
    private class ProgressEmitter(
        private val sink: (CommitProgress) -> Unit,
        private val startedAtMillis: Long,
        private val filesTotal: Int,
    ) {
        private val bytesDone = AtomicLong()
        private val bytesTotal = AtomicLong()
        private val filesDone = AtomicInteger()
        private val lastEmitNanos = AtomicLong(Long.MIN_VALUE)
        private val lock = Any()

        @Volatile
        private var phase = CommitProgress.Phase.STAGING

        /** Switches to [next] and reports it immediately. */
        fun enter(next: CommitProgress.Phase) {
            phase = next
            emit(force = true)
        }

        /** Declares how many bytes the whole staging pass covers, once the plan is known. */
        fun expect(bytes: Long) {
            bytesTotal.set(bytes)
            emit(force = true)
        }

        /** Records [files] files totalling [bytes] as staged. */
        fun advance(bytes: Long, files: Int) {
            bytesDone.addAndGet(bytes)
            filesDone.addAndGet(files)
            emit(force = false)
        }

        private fun emit(force: Boolean) {
            val now = System.nanoTime()
            if (!force) {
                val last = lastEmitNanos.get()
                if (now - last < MIN_EMIT_INTERVAL_NANOS) return
                // A worker that loses this race just skips its emit; the next one covers it, and
                // the phase change at the end of the pass reports the final counts regardless.
                if (!lastEmitNanos.compareAndSet(last, now)) return
            } else {
                lastEmitNanos.set(now)
            }
            // The sink runs under the lock too: reading the counters and delivering them has to be
            // one step, or a worker holding an older snapshot could deliver it after a newer one.
            synchronized(lock) {
                sink(
                    CommitProgress(
                        phase = phase,
                        bytesDone = bytesDone.get(),
                        bytesTotal = bytesTotal.get(),
                        filesDone = filesDone.get(),
                        filesTotal = filesTotal,
                        startedAtMillis = startedAtMillis,
                    )
                )
            }
        }

        private companion object {
            /** ~8 snapshots a second: finer than anyone reads, coarser than a per-file storm. */
            const val MIN_EMIT_INTERVAL_NANOS = 120_000_000L
        }
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
    private fun stageBlobsInParallel(staged: List<FileChange>, progress: ProgressEmitter): Boolean {
        if (staged.size < PARALLEL_STAGE_MIN_FILES) return false
        val plan = planStaging(staged)
        if (plan.fast.size < PARALLEL_STAGE_MIN_FILES) return false
        val fastBytes = plan.fast.sumOf { it.file.length() }
        if (fastBytes < PARALLEL_STAGE_MIN_BYTES && plan.fast.size < PARALLEL_STAGE_ALWAYS_FILES) return false

        // The plan is what makes progress measurable, and this is the earliest point it exists: now
        // the total is known, the button can switch from an elapsed clock to a percentage and ETA.
        // The filtered half counts towards the total even though AddCommand won't report as it
        // goes — it lands as one step below — so the percentage covers the whole change set rather
        // than jumping backwards when the parallel half starts.
        val slowBytes = plan.slow.sumOf { File(rootDir.toFile(), it.path).length() }
        progress.expect(fastBytes + slowBytes)

        // Anything needing conversion goes through AddCommand first. It takes and releases the
        // index lock itself, so it has to finish before the fast half locks the index in turn.
        if (plan.slow.isNotEmpty()) {
            val add = git.add()
            plan.slow.forEach { add.addFilepattern(it.path) }
            add.call()
            progress.advance(slowBytes, plan.slow.size)
        }
        progress.enter(CommitProgress.Phase.WRITING)

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
                            progress.advance(length, 1)
                        }
                        inserter.flush()
                    }
                }
            }
            // invokeAll waits for every worker, so no insert is still running when we unwind.
            pool.invokeAll(tasks).forEach { it.get() }

            progress.enter(CommitProgress.Phase.COMMITTING)
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
     * Undoes what [sha] changed, in the working tree and the index, leaving the reversal as
     * uncommitted changes — `git revert --no-commit <sha>`. HEAD does not move and no commit is
     * made, so the reversal arrives as ordinary pending changes the user can inspect, amend or
     * throw away like any other. This is the gesture IntelliJ's log calls "Revert Commit", and it
     * is not the same as rolling files back to how the commit left them: reverting the middle of
     * three commits keeps the third one's work.
     *
     * Reverting is a three-way merge — base is the commit's own tree, "ours" is HEAD, "theirs" is
     * what the commit started from — which is why later work on the same files survives, and why
     * the reversal can fail to apply. It fails as a unit: a commit whose changes can't be reversed
     * cleanly, or whose paths carry uncommitted edits that would be overwritten, throws with those
     * paths named and leaves the working tree exactly as it was. Nothing is half-applied.
     *
     * A merge commit is reversed against its first parent (git's `-m 1`): the changes the merge
     * brought in go away, and the mainline is what stays. Returns which paths the reversal
     * rewrote and which it removed, so the caller can reconcile open editors and tabs.
     */
    fun revertCommit(sha: String): RevertCommitOutcome {
        val commitId = repository.resolve(sha) ?: throw IOException("No such revision: $sha")
        val headId = repository.resolve(Constants.HEAD)
            ?: throw IOException("This branch has no commits yet, so there is nothing to revert onto.")
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(commitId)
            val head = walk.parseCommit(headId)
            // "Theirs" is the state the commit started from. A root commit started from nothing, so
            // its reversal is measured against the empty tree — inserted rather than assumed
            // present, since a repository need never have stored it.
            val before: ObjectId = if (commit.parentCount > 0) {
                walk.parseCommit(commit.getParent(0)).id
            } else {
                emptyTreeId()
            }

            // In-core deliberately: a working-tree merger writes conflict markers into the files as
            // it goes and only then reports failure, which would leave the user holding a corrupted
            // tree *and* an error. Computing the result tree in memory means a reversal that can't
            // be applied changes nothing at all, and the checkout below is the only thing that ever
            // touches disk.
            val merger = MergeStrategy.RECURSIVE.newMerger(repository, true) as ResolveMerger
            merger.setBase(commit.tree)
            merger.setCommitNames(arrayOf("BASE", "HEAD", sha))
            if (!merger.merge(head, before)) {
                // Nothing has been written, so the message is the whole of the outcome: naming the
                // paths is what tells the user which files to look at.
                // getFailingPaths() is null unless the merge hit a hard failure, so it can only be
                // read defensively; the unmerged paths are the usual answer.
                val paths = (merger.unmergedPaths.orEmpty() + merger.failingPaths?.keys.orEmpty()).distinct()
                throw IOException(conflictMessage(sha, paths))
            }
            // Already reversed — by an earlier revert, or by later work that happened to undo it.
            // Checking out an unchanged tree would be a no-op that still reported success.
            if (head.tree.id == merger.resultTreeId) return RevertCommitOutcome(emptyList(), emptyList())

            val checkout = DirCacheCheckout(repository, head.tree.id, repository.lockDirCache(), merger.resultTreeId)
            // Refuse rather than overwrite: uncommitted edits to a path the reversal touches are
            // work git has no copy of, and losing them is the one outcome this must never produce.
            checkout.setFailOnConflict(true)
            try {
                checkout.checkout()
            } catch (e: CheckoutConflictException) {
                throw IOException(conflictMessage(sha, checkout.conflicts.ifEmpty { listOf(e.message.orEmpty()) }))
            }
            // What changed is read off the two trees rather than out of the checkout: the merger
            // writes most of the working-tree updates itself before DirCacheCheckout runs, so the
            // checkout's own lists see only the remainder. The trees are the whole answer.
            return changedPaths(head.tree.id, merger.resultTreeId)
        }
    }

    /**
     * How [to] differs from [from], split the way a caller reconciling open files needs it: paths
     * the new tree drops are [RevertCommitOutcome.removed], everything else it touches is
     * [RevertCommitOutcome.updated].
     */
    private fun changedPaths(from: ObjectId, to: ObjectId): RevertCommitOutcome {
        val reader = repository.newObjectReader()
        val oldTree = CanonicalTreeParser().apply { reset(reader, from) }
        val newTree = CanonicalTreeParser().apply { reset(reader, to) }
        val formatter = DiffFormatter(DisabledOutputStream.INSTANCE)
        formatter.setRepository(repository)
        val updated = ArrayList<String>()
        val removed = ArrayList<String>()
        for (entry in formatter.scan(oldTree, newTree)) {
            if (entry.changeType == DiffEntry.ChangeType.DELETE) removed.add(entry.oldPath)
            else updated.add(entry.newPath)
        }
        return RevertCommitOutcome(updated = updated, removed = removed)
    }

    /** The empty tree, stored if this repository has never had cause to hold it. */
    private fun emptyTreeId(): ObjectId = repository.newObjectInserter().use { inserter ->
        val id = inserter.insert(TreeFormatter())
        inserter.flush()
        id
    }

    /** Why a revert couldn't be applied, with the paths that stopped it — capped so it stays readable. */
    private fun conflictMessage(sha: String, paths: List<String>): String {
        val shown = paths.filter { it.isNotEmpty() }.take(10)
        val more = paths.size - shown.size
        val list = shown.joinToString("\n") + if (more > 0) "\n…and $more more" else ""
        return "Reverting ${sha.take(7)} would conflict with what is on disk. " +
            "Commit or revert your changes to these paths first, then try again:\n$list"
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
     * Moves [changes] onto the shelf and restores just those paths to their committed state —
     * `git stash push -- <paths>`. Pending changes the caller left out stay in the working tree,
     * and stay out of the shelf entry. Returns the new stash SHA, or null when nothing selected
     * turned out to have anything to stash.
     *
     * JGit's StashCreateCommand takes no pathspec, so this is its algorithm — a HEAD / index /
     * working-tree walk building the two or three commits a shelf entry is made of — with the two
     * changes a partial stash needs. A path the caller didn't select is pinned to its HEAD state in
     * both trees the entry carries, so applying it later can neither resurrect that path's change
     * nor revert it; and the whole-tree hard reset the command finishes with narrows to
     * [revertFiles] over the selected paths, the only part of the working tree allowed to move.
     *
     * The index is locked for the walk but never written: the edits below exist only to shape the
     * trees the stash commits point at, and the real index is put back by [revertFiles] once the
     * entry is safely on the shelf.
     */
    fun stashCreate(message: String? = null, changes: Collection<FileChange>): String? {
        if (changes.isEmpty()) return null
        val wanted = changes.mapTo(HashSet()) { it.path }
        val head = repository.exactRef(Constants.HEAD)?.takeIf { it.objectId != null }
            ?: throw IOException("This branch has no commits yet, so there is nothing to stash against.")
        val person = PersonIdent(repository)
        val stashId: ObjectId
        val shelved = HashSet<String>()
        repository.newObjectReader().use { reader ->
            val headCommit = RevWalk(reader).use { it.parseCommit(head.objectId) }
            val cache = repository.lockDirCache()
            try {
                repository.newObjectInserter().use { inserter ->
                    // Paths the caller didn't select, to be pinned back to HEAD before either tree
                    // is written; the selected paths' working-tree state, as edits over the index;
                    // and the selected untracked files, which a stash carries in a third parent.
                    val heldOut = ArrayList<DirCacheEditor.PathEdit>()
                    val wtEdits = ArrayList<DirCacheEditor.PathEdit>()
                    val wtDeletes = ArrayList<String>()
                    val untracked = ArrayList<DirCacheEntry>()
                    // [shelved] collects the selected paths this walk actually accounted for,
                    // which is what gets reverted at the end. Not simply [changes]: a path the walk
                    // never yielded is not in the entry — it is clean by now, or ignored — and
                    // reverting it would throw away a change nothing has a copy of.
                    TreeWalk(repository, reader).use { walk ->
                        walk.isRecursive = true
                        walk.addTree(headCommit.tree)
                        walk.addTree(DirCacheIterator(cache))
                        walk.addTree(FileTreeIterator(repository))
                        walk.getTree(2, FileTreeIterator::class.java).setDirCacheIterator(walk, 1)
                        walk.filter = AndTreeFilter.create(SkipWorkTreeFilter(1), IndexDiffFilter(1, 2))
                        while (walk.next()) {
                            val headIter = walk.getTree(0, AbstractTreeIterator::class.java)
                            val indexIter = walk.getTree(1, DirCacheIterator::class.java)
                            val wtIter = walk.getTree(2, WorkingTreeIterator::class.java)
                            val path = walk.pathString
                            // A tree can't hold a conflicted entry, so nothing can be stashed while
                            // a merge is unresolved anywhere — the refusal JGit's own stash makes.
                            if (indexIter != null && !indexIter.dirCacheEntry.isMerged) {
                                throw IOException(
                                    "$path has unresolved merge conflicts, so nothing can be " +
                                        "stashed until they are dealt with."
                                )
                            }
                            if (path !in wanted) {
                                heldOut.add(
                                    if (headIter == null) DirCacheEditor.DeletePath(path)
                                    else SetBlob(path, headIter.entryFileMode, walk.getObjectId(0))
                                )
                                continue
                            }
                            if (wtIter != null) {
                                shelved.add(path)
                                // Where the index already holds these bytes the cache needs no edit.
                                if (indexIter != null && wtIter.idEqual(indexIter)) continue
                                if (headIter != null && wtIter.idEqual(headIter)) continue
                                val entry = workingTreeEntry(walk, wtIter, inserter)
                                if (indexIter == null && headIter == null) {
                                    // Untracked, so it belongs in the third parent and nowhere near
                                    // the working tree's own tree — where a tracked addition would
                                    // put it, and where applying the entry would stage it.
                                    untracked.add(entry)
                                } else {
                                    wtEdits.add(object : DirCacheEditor.PathEdit(entry) {
                                        override fun apply(ent: DirCacheEntry) = ent.copyMetaData(entry)
                                    })
                                }
                                continue
                            }
                            shelved.add(path)
                            if (headIter != null) wtDeletes.add(path)
                        }
                    }
                    // Nothing selected was actually dirty — no entry, and the working tree is left
                    // alone. The cache edits above are discarded with the lock.
                    if (shelved.isEmpty()) return null

                    val branch = Repository.shortenRefName(head.target.name)
                    val onHead = "$branch: ${headCommit.abbreviate(Constants.OBJECT_ID_ABBREV_STRING_LENGTH).name()} " +
                        headCommit.shortMessage
                    val builder = CommitBuilder()
                    builder.author = person
                    builder.committer = person

                    if (heldOut.isNotEmpty()) {
                        val editor = cache.editor()
                        heldOut.forEach { editor.add(it) }
                        editor.finish()
                    }
                    builder.setParentId(headCommit)
                    builder.setTreeId(cache.writeTree(inserter))
                    builder.message = "index on $onHead"
                    val indexCommit = inserter.insert(builder)

                    var untrackedCommit: ObjectId? = null
                    if (untracked.isNotEmpty()) {
                        val untrackedCache = DirCache.newInCore()
                        val untrackedBuilder = untrackedCache.builder()
                        untracked.forEach { untrackedBuilder.add(it) }
                        untrackedBuilder.finish()
                        builder.setParentIds(emptyList<ObjectId>())
                        builder.setTreeId(untrackedCache.writeTree(inserter))
                        builder.message = "untracked files on $onHead"
                        untrackedCommit = inserter.insert(builder)
                    }

                    if (wtEdits.isNotEmpty() || wtDeletes.isNotEmpty()) {
                        val editor = cache.editor()
                        wtEdits.forEach { editor.add(it) }
                        wtDeletes.forEach { editor.add(DirCacheEditor.DeletePath(it)) }
                        editor.finish()
                    }
                    builder.setParentId(headCommit)
                    builder.addParentId(indexCommit)
                    untrackedCommit?.let { builder.addParentId(it) }
                    val stashMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: "WIP on $onHead"
                    builder.message = stashMessage
                    builder.setTreeId(cache.writeTree(inserter))
                    stashId = inserter.insert(builder)
                    inserter.flush()
                    updateStashRef(stashId, person, stashMessage)
                }
            } finally {
                cache.unlock()
            }
        }
        // Only now that the entry is on the shelf does the working tree move, and only over the
        // paths that went onto it.
        revertFiles(changes.filter { it.path in shelved })
        return stashId.name
    }

    /**
     * The [DirCacheEntry] for a path's current on-disk content, with the blob inserted. Content is
     * read through [WorkingTreeIterator.openEntryStream], so a check-in filter (autocrlf, git-lfs)
     * applies here exactly as it would to a commit of the same file.
     */
    private fun workingTreeEntry(
        walk: TreeWalk,
        wtIter: WorkingTreeIterator,
        inserter: ObjectInserter,
    ): DirCacheEntry {
        val entry = DirCacheEntry(walk.rawPath)
        entry.setLength(wtIter.entryLength)
        entry.setLastModified(wtIter.entryLastModifiedInstant)
        entry.fileMode = wtIter.entryFileMode
        wtIter.openEntryStream().use { content ->
            entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, wtIter.entryContentLength, content))
        }
        return entry
    }

    /**
     * Points one index entry at a given blob. Mode and object id are all it sets: the caches
     * [stashCreate] edits are written out as trees and then dropped with the lock, so the stat data
     * an existing entry carries is never read back and doesn't need correcting.
     */
    private class SetBlob(
        path: String,
        private val mode: FileMode,
        private val blob: ObjectId,
    ) : DirCacheEditor.PathEdit(path) {
        override fun apply(ent: DirCacheEntry) {
            ent.fileMode = mode
            ent.setObjectId(blob)
        }
    }

    /**
     * Moves `refs/stash` to a newly written stash commit, pushing the previous tip down the
     * reflog — which is the shelf: `git stash list` and [stashList] both read the entries out of
     * `refs/stash`'s reflog rather than off a ref apiece.
     */
    private fun updateStashRef(commitId: ObjectId, refLogIdent: PersonIdent, refLogMessage: String) {
        val current = repository.findRef(Constants.R_STASH)
        val update = repository.updateRef(Constants.R_STASH)
        update.setNewObjectId(commitId)
        update.setRefLogIdent(refLogIdent)
        update.setRefLogMessage(refLogMessage, false)
        update.setForceRefLog(true)
        update.setExpectedOldObjectId(current?.objectId ?: ObjectId.zeroId())
        update.forceUpdate()
    }

    fun stashList(): List<StashEntry> =
        git.stashList().call().mapIndexed { idx, c ->
            StashEntry(index = idx, sha = c.name, message = c.shortMessage ?: "(no message)")
        }

    /** Apply a stash without removing it from the shelf. */
    fun stashApply(entry: StashEntry) {
        applyRestoringUntracked(entry)
    }

    /** Apply a stash then drop it. */
    fun stashPop(entry: StashEntry) {
        // Apply first; if this throws (e.g., conflicts), keep the stash on the shelf
        applyRestoringUntracked(entry)
        git.stashDrop().setStashRef(entry.index).call()
    }

    /**
     * JGit restores an entry's untracked files only after the tracked merge has succeeded, so a
     * conflicting apply writes the conflict markers and leaves every untracked file where it was —
     * on the shelf, with nothing on screen to say so. The entry survives either way, so the files
     * are never lost, but a working tree quietly short of files reads as loss. Put them back, and
     * name them in the failure.
     */
    private fun applyRestoringUntracked(entry: StashEntry) {
        try {
            git.stashApply().setStashRef(entry.sha).call()
        } catch (failure: StashApplyFailureException) {
            val restored = runCatching { restoreUntracked(entry.sha) }.getOrDefault(emptyList())
            if (restored.isEmpty()) throw failure
            throw StashApplyFailureException(
                "${failure.message} The stash was kept, and the untracked files it carries were " +
                    "put back: ${restored.joinToString(", ")}.",
                failure,
            )
        }
    }

    /**
     * Writes the untracked files a stash carries in its third parent into the working tree,
     * returning the paths written. A path already on disk is skipped rather than overwritten: the
     * entry stays on the shelf, so a skip is recoverable where a clobber is not.
     *
     * The smudge command has to come off the walk and be passed on. JGit's own untracked restore
     * hardcodes it to null, which writes a git-lfs pointer to disk in place of the file it stands
     * for — the second tree is a [FileTreeIterator] partly so `.gitattributes` resolves here.
     */
    private fun restoreUntracked(stashSha: String): List<String> {
        val written = ArrayList<String>()
        RevWalk(repository).use { revWalk ->
            val stashCommit = revWalk.parseCommit(ObjectId.fromString(stashSha))
            if (stashCommit.parentCount < 3) return emptyList()
            val untrackedTree = revWalk.parseTree(stashCommit.getParent(2))
            val checkout = Checkout(repository).setRecursiveDeletion(true)
            TreeWalk(repository).use { walk ->
                walk.setOperationType(TreeWalk.OperationType.CHECKOUT_OP)
                walk.addTree(untrackedTree)
                walk.addTree(FileTreeIterator(repository))
                walk.isRecursive = true
                while (walk.next()) {
                    if (walk.getTree(0, AbstractTreeIterator::class.java) == null) continue
                    if (walk.getTree(1, FileTreeIterator::class.java) != null) continue
                    val path = walk.pathString
                    val cacheEntry = DirCacheEntry(walk.rawPath)
                    cacheEntry.setFileMode(walk.getFileMode(0))
                    cacheEntry.setObjectId(walk.getObjectId(0))
                    checkout.checkout(
                        cacheEntry,
                        DirCacheCheckout.CheckoutMetadata(
                            walk.getEolStreamType(TreeWalk.OperationType.CHECKOUT_OP),
                            walk.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE),
                        ),
                        walk.objectReader,
                        path,
                    )
                    written.add(path)
                }
            }
        }
        return written
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
     * Pruning ignored directories is what makes watching every open project affordable: on
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
