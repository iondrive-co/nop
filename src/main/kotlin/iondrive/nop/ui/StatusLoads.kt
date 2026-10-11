package iondrive.nop.ui

import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import iondrive.nop.git.RepoWatcher
import iondrive.nop.git.WalkPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Status walks that outlive the project view that asked for them, and the last status each one found.
 *
 * A project's [App] is composed only while its tab is in front, so everything it loaded went with it
 * on a tab switch, and the view that came back started from an empty status. On a large checkout that
 * is a long wait, and a walk started by opening or refreshing a project should complete in the background:
 * the walk runs here on a supervisor scope, records its result on completion, and lets a returning view
 * open directly on the loaded file list rather than starting a new walk.
 *
 * The walk runs against a [GitRepo] of its own (the view closes its own on the way out); a view arriving
 * while one is running joins it rather than starting a second; and the result is kept per repository.
 */
internal object StatusLoads {
    /** A status walk's answer, with the HEAD it was read against and the watcher generation when it was taken. */
    data class Loaded(
        val status: GitStatus,
        val headSha: String?,
        val generation: Long = RepoWatcher.UNKNOWN,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<Path, Deferred<GitStatus>>()
    private val last = ConcurrentHashMap<Path, Loaded>()
    private val pacers = ConcurrentHashMap<Path, WalkPacer>()

    private fun key(root: Path): Path = root.toAbsolutePath().normalize()

    /** The pacer spacing repeated status walks for [root]. */
    fun pacer(root: Path): WalkPacer = pacers.computeIfAbsent(key(root)) { WalkPacer() }

    /** Whether a status walk is currently running for [root]. */
    fun isInFlight(root: Path): Boolean {
        val k = key(root)
        return synchronized(inFlight) { inFlight.containsKey(k) }
    }

    /** What [root] looked like at its last load, or null if nop has not loaded it since starting. */
    fun last(root: Path): Loaded? = last[key(root)]

    /** Records a status the view loaded or polled, for the next view of [root] to open on. */
    fun remember(root: Path, status: GitStatus, headSha: String?, generation: Long = RepoWatcher.UNKNOWN) {
        last[key(root)] = Loaded(status, headSha, generation)
    }

    /**
     * Walks [root]'s status, or waits for the walk already running there. Joining means the answer
     * can predate an edit made while the walk ran; the view's poll walks again on its next change.
     */
    suspend fun load(root: Path, watcher: RepoWatcher? = null): GitStatus {
        val k = key(root)
        val pacer = pacer(k)
        val load = synchronized(inFlight) {
            inFlight[k] ?: scope.async(start = CoroutineStart.LAZY) {
                val repo = GitRepo.discover(k) ?: error("$k is no longer a git repository")
                val (status, headSha) = pacer.walk {
                    repo.use {
                        val s = it.loadStatus()
                        val h = it.headSha()
                        it.releaseObjectCaches()
                        s to h
                    }
                }
                val gen = watcher?.generation(k) ?: RepoWatcher.UNKNOWN
                remember(k, status, headSha, gen)
                status
            }.also { walk ->
                inFlight[k] = walk
                walk.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(k, walk) } }
                walk.start()
            }
        }
        return load.await()
    }
}
