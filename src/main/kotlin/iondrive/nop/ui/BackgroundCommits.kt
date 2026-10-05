package iondrive.nop.ui

import androidx.compose.runtime.mutableStateMapOf
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.git.CommitProgress
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Commits that outlive the project view that started them.
 *
 * A project's [App] is composed only while its tab is in front: switching tabs disposes it, and with
 * it every coroutine launched from its rememberCoroutineScope, and it closes its [GitRepo]. A commit
 * launched there was cancelled and rolled back the moment the user looked at another project — on
 * a big change set (hermes, 2026-10-05: 6,422 paths, minutes to stage) that read as a commit that
 * gave up by itself. So the commit runs here instead, on a scope nop holds for its whole life and
 * against a [GitRepo] of its own, and the view that comes back finds it still running, or finds how
 * it ended and finishes up (see App's handling of [Run.outcome]).
 *
 * [start], [runFor] and [finish] are called from the UI thread; the map is snapshot state, so a view
 * recomposes as a run appears and goes.
 */
internal object BackgroundCommits {
    sealed interface Outcome {
        data class Committed(val commitAs: CommitAs) : Outcome
        data class Failed(val error: GitOpError) : Outcome
        data object Cancelled : Outcome
    }

    class Run internal constructor(val root: Path, val message: String) {
        internal lateinit var job: Job
        internal val progressFlow = MutableStateFlow<CommitProgress?>(null)
        internal val outcomeFlow = MutableStateFlow<Outcome?>(null)
        val progress: StateFlow<CommitProgress?> get() = progressFlow
        /** Null while the commit runs; how it ended once it has, until a view [finish]es it. */
        val outcome: StateFlow<Outcome?> get() = outcomeFlow

        /** Stops the commit, unless it has already committed and is only waiting for a view. */
        fun cancel() {
            if (progressFlow.value?.phase != CommitProgress.Phase.REFRESHING) job.cancel()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = mutableStateMapOf<Path, Run>()

    private fun key(root: Path): Path = root.toAbsolutePath().normalize()

    fun runFor(root: Path): Run? = runs[key(root)]

    /** Starts committing [included] in [root], or returns null when a commit there is already running. */
    fun start(root: Path, message: String, included: List<FileChange>, partial: Boolean, commitAs: CommitAs): Run? {
        val k = key(root)
        if (runs[k] != null) return null
        val run = Run(k, message)
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            val startedAt = System.currentTimeMillis()
            run.progressFlow.value = CommitProgress(CommitProgress.Phase.STAGING, startedAtMillis = startedAt)
            val outcome = try {
                val error = runGitOp("Commit failed") {
                    val repo = GitRepo.discover(k) ?: error("$k is no longer a git repository")
                    repo.use {
                        // Saved first, so that a config nop cannot write stops the commit rather
                        // than letting it land as the user asked while the saving silently fails.
                        if (commitAs.save && commitAs.identity != null) it.saveIdentity(commitAs.identity)
                        it.stageAndCommit(
                            message,
                            included,
                            partial = partial,
                            startedAtMillis = startedAt,
                            identity = commitAs.identity,
                            onProgress = { p -> run.progressFlow.value = p },
                            isCancelled = { !run.job.isActive },
                        )
                    }
                    recordCommitted(k, message)
                }
                if (error == null) Outcome.Committed(commitAs) else Outcome.Failed(error)
            } catch (_: CancellationException) {
                Outcome.Cancelled
            }
            Log.info("commit in $k: ${outcome::class.simpleName}, ${included.size} paths, " +
                "${(System.currentTimeMillis() - startedAt) / 1000}s" +
                ((outcome as? Outcome.Failed)?.let { " — ${it.error.detail}" } ?: ""))
            // The view that picks this up reloads status before calling [finish], and the button
            // keeps reporting through that walk.
            run.progressFlow.value = CommitProgress(CommitProgress.Phase.REFRESHING, startedAtMillis = startedAt)
            run.outcomeFlow.value = outcome
        }
        runs[k] = run
        run.job.start()
        return run
    }

    /** Called by the view once it has dealt with [run]'s outcome: the button is free again. */
    fun finish(run: Run) {
        if (runs[run.root] === run) runs.remove(run.root)
    }

    // What a landed commit does to the message history and draft, done here because no view may be
    // open to do it, and a draft left holding the committed message would come back as if unsent.
    private fun recordCommitted(root: Path, message: String) {
        val recent = (listOf(message) + Settings.loadRecentCommitMessages(root)).distinct()
            .take(COMMIT_MESSAGE_HISTORY_CAP)
        Settings.saveRecentCommitMessages(root, recent)
        if (Settings.loadCommitMessageDraft(root).trim() == message) Settings.saveCommitMessageDraft(root, "")
    }
}
