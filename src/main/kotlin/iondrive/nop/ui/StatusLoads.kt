package iondrive.nop.ui

import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
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
 * on a tab switch, and the view that came back started from an empty status. On a big checkout that
 * is a long wait — ifingr (2026-10-10: 300k files, a 40 MB index) takes JGit about a minute — and it
 * never finished if the user looked away first: the walk is blocking JGit code that cancellation
 * cannot stop, so it ran to the end and its answer was thrown away, and the next visit began another.
 * Meanwhile the commit panel said "Not a git repository".
 *
 * So the walk runs here, on a scope nop holds for its whole life and against a [GitRepo] of its own
 * (the view closes its own on the way out); a view arriving while one is running joins it rather
 * than starting a second; and the result is kept per repository, so a returning view opens on the
 * status it last had while it reloads.
 */
internal object StatusLoads {
    /** A status walk's answer, with the HEAD it was read against. */
    data class Loaded(val status: GitStatus, val headSha: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<Path, Deferred<GitStatus>>()
    private val last = ConcurrentHashMap<Path, Loaded>()

    private fun key(root: Path): Path = root.toAbsolutePath().normalize()

    /** What [root] looked like at its last load, or null if nop has not loaded it since starting. */
    fun last(root: Path): Loaded? = last[key(root)]

    /** Records a status the view loaded or polled, for the next view of [root] to open on. */
    fun remember(root: Path, status: GitStatus, headSha: String?) {
        last[key(root)] = Loaded(status, headSha)
    }

    /**
     * Walks [root]'s status, or waits for the walk already running there. Joining means the answer
     * can predate an edit made while the walk ran; the view's poll walks again on its next change.
     */
    suspend fun load(root: Path): GitStatus {
        val k = key(root)
        val load = synchronized(inFlight) {
            inFlight[k] ?: scope.async(start = CoroutineStart.LAZY) {
                val repo = GitRepo.discover(k) ?: error("$k is no longer a git repository")
                repo.use { it.loadStatus() }
            }.also { walk ->
                inFlight[k] = walk
                walk.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(k, walk) } }
                walk.start()
            }
        }
        return load.await()
    }
}
