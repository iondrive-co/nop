package iondrive.nop.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.nio.file.Path

class GitRepo(val rootDir: Path, private val repository: Repository) : AutoCloseable {
    private val git: Git = Git.wrap(repository)

    /**
     * Stages the given changes and creates a commit. ADDED/MODIFIED/UNTRACKED paths are added
     * via `git add`; REMOVED/MISSING paths are staged for removal via `git rm --cached`.
     * Returns the new commit SHA, or throws if no changes were ultimately staged.
     */
    fun stageAndCommit(message: String, changes: Collection<FileChange>): String {
        for (change in changes) {
            when (change.kind) {
                ChangeKind.REMOVED, ChangeKind.MISSING ->
                    git.rm().setCached(true).addFilepattern(change.path).call()
                else ->
                    git.add().addFilepattern(change.path).call()
            }
        }
        val commit = git.commit().setMessage(message).call()
        return commit.name
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

    /** File content from the working tree, or null if the file is absent. */
    fun readWorkingTreeContent(relPath: String): String? {
        val file = File(rootDir.toFile(), relPath)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun close() {
        repository.close()
    }

    companion object {
        fun discover(path: Path): GitRepo? {
            val gitDir = findGitDir(path.toFile()) ?: return null
            val repository = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()
            return GitRepo(rootDir = gitDir.parentFile.toPath(), repository = repository)
        }

        private fun findGitDir(start: File): File? {
            var cur: File? = start.absoluteFile
            while (cur != null) {
                val candidate = File(cur, ".git")
                if (candidate.exists()) return candidate
                cur = cur.parentFile
            }
            return null
        }
    }
}
