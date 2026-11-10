package iondrive.nop.index

import iondrive.nop.git.WalkPacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the index refreshes of every open project one at a time, each project's spaced by what its
 * last refresh cost.
 *
 * A refresh walks the whole project tree, and the walk's price is set by the tree's size, not by how
 * much changed: a project with millions of files on disk (run output, caches, data the project's own
 * tools write) takes tens of seconds and holds a list of every path while it runs. Requests arrive
 * from the git poll every few seconds while anything writes into the tree. Started one per request,
 * refreshes run side by side, each with its own copy of that list, until the heap is full and every
 * core is collecting it. Taking turns holds nop to one walk's memory; spacing each project's walks by
 * [WalkPacer]'s multiple of the last one's duration holds a large tree to a fixed share of a core,
 * and leaves a small project, whose refresh takes milliseconds, on the poll's own cadence.
 *
 * A caller that collapses requests while a refresh runs (a conflated flow) then gets one refresh
 * for any number of requests made during the last one.
 */
class IndexBuilds(private val pacerFor: (Path) -> WalkPacer = { WalkPacer() }) {
    private val lock = Mutex()
    private val pacers = ConcurrentHashMap<Path, WalkPacer>()

    /**
     * Runs [body] once no other refresh is running and [root]'s pacing allows. Waiting for either is
     * cancellable; [body] itself stops only where it checks for cancellation.
     */
    suspend fun <T> inTurn(root: Path, body: suspend () -> T): T {
        val pacer = pacers.computeIfAbsent(root.toAbsolutePath().normalize(), pacerFor)
        while (true) {
            val wait = pacer.waitMs()
            if (wait <= 0L) break
            delay(wait)
        }
        return lock.withLock {
            val start = pacer.now()
            try {
                body()
            } finally {
                pacer.record(start, pacer.now())
            }
        }
    }

    companion object {
        /** The one every project window shares, so refreshes take turns across all of them. */
        val shared = IndexBuilds()
    }
}
