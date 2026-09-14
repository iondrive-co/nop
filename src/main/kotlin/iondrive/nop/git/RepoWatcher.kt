package iondrive.nop.git

import iondrive.nop.Log
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Filesystem watches over git working trees, so a poller can ask whether a tree has changed
 * instead of walking it to find out. One [WatchService] — a single inotify instance on Linux —
 * covers every repository passed to [sync].
 *
 * A watch here is an optimisation and never the source of truth. Every answer that isn't a definite
 * "nothing has changed" reports change: a tree never synced, one too large to watch, a registration
 * that threw, a kernel queue that overflowed. The caller then does the walk it would have done
 * anyway, so the failure mode is the cost we started from — not a dirty flag stuck on stale.
 *
 * Thread-safe. Events are drained on a private daemon thread; callers only read counters.
 *
 * One gap worth naming: JGit reports a nested repository as a gitlink rather than a tree, so
 * [GitRepo.workingTreeDirs] never descends into one and nothing here watches inside it. For a plain
 * nested clone that costs nothing — the parent reports the whole directory as untracked whatever
 * happens inside it. For a true submodule, whose content changes do show as a parent modification,
 * the parent's flag would wait for the next change it can see. No repository open here has one.
 */
class RepoWatcher(private val maxDirs: Int = MAX_WATCHED_DIRS) : AutoCloseable {
    private val service: WatchService = FileSystems.getDefault().newWatchService()

    /** Working-tree root -> its watch state. */
    private val watched = ConcurrentHashMap<Path, Watched>()

    /** Every live key back to the root it belongs to, so the drain thread can attribute events. */
    private val keyOwner = ConcurrentHashMap<WatchKey, Path>()

    @Volatile private var closed = false

    private val drainThread =
        Thread(::drain, "nop:repo-watch").apply { isDaemon = true; start() }

    private class Watched {
        /**
         * Bumped once per event batch. Callers compare against the value they last acted on, rather
         * than consuming a flag, so several callers can watch one tree without racing to clear it.
         */
        val generation = AtomicLong(0)

        /** The registered directories no longer cover the tree: one appeared, or events were dropped. */
        @Volatile var needsRegister = true

        /** Cleared for good once the tree proves too large to watch; [generation] then never applies. */
        @Volatile var watchable = true

        /** Registered directory -> key. Guarded by `this`. */
        val keys = HashMap<Path, WatchKey>()
    }

    /**
     * Register — or re-register — watches over [repo]'s working tree and the parts of its git
     * directory that move status. Cheap enough to call on every tick: it re-walks the tree only
     * when the last registration went stale, and re-registering a directory already watched is a
     * no-op in the JDK.
     */
    fun sync(repo: GitRepo) {
        if (closed) return
        val state = watched.computeIfAbsent(repo.rootDir) { Watched() }
        if (!state.watchable || !state.needsRegister) return
        // Cleared before the walk rather than after. An event for a directory the walk has already
        // passed sets the flag again while we are still walking, and we want that to survive — a
        // clear afterwards would drop it and leave us watching a tree we already know we've stopped
        // covering. Re-walking one extra time is the cheaper mistake.
        state.needsRegister = false
        val dirs = runCatching { repo.workingTreeDirs(maxDirs) }
            .onFailure { Log.error("repo watch: listing ${repo.rootDir} failed", it) }
            .getOrNull()
        if (dirs == null) {
            state.watchable = false
            Log.info("repo watch: ${repo.rootDir} has more than $maxDirs directories — polling it instead")
            return
        }
        register(repo.rootDir, state, dirs + gitDirWatchDirs(repo))
        // The tree was unwatched while it was being walked, so treat it as moved: one status walk
        // now is what makes the first watched answer trustworthy.
        state.generation.incrementAndGet()
    }

    /**
     * How many changes [root] has been seen to take, or [UNKNOWN] when this watcher cannot say.
     * Callers keep the value they last acted on and walk again when it differs; [UNKNOWN] must
     * always be read as "walk again", which is why it is compared explicitly rather than by
     * inequality — two consecutive [UNKNOWN]s are not evidence of a quiet tree.
     */
    fun generation(root: Path): Long {
        val state = watched[root] ?: return UNKNOWN
        if (!state.watchable || state.needsRegister) return UNKNOWN
        return state.generation.get()
    }

    /** Stop watching [root], releasing its watches. */
    fun unwatch(root: Path) {
        val state = watched.remove(root) ?: return
        synchronized(state) {
            for (key in state.keys.values) {
                keyOwner.remove(key)
                key.cancel()
            }
            state.keys.clear()
        }
    }

    /** How many directories are currently watched under [root]. For tests and diagnostics. */
    fun watchCount(root: Path): Int {
        val state = watched[root] ?: return 0
        return synchronized(state) { state.keys.size }
    }

    override fun close() {
        closed = true
        for (root in watched.keys.toList()) unwatch(root)
        // Closing the service is what wakes the drain thread out of take(); the interrupt only
        // covers the window before it gets there.
        runCatching { service.close() }
        drainThread.interrupt()
    }

    private fun register(root: Path, state: Watched, dirs: List<Path>) {
        val wanted = dirs.toSet()
        synchronized(state) {
            for (dir in wanted) {
                if (state.keys.containsKey(dir)) continue
                // A directory can go away between the walk and here; that is not worth logging.
                val key = runCatching { dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
                    .getOrNull() ?: continue
                state.keys[dir] = key
                keyOwner[key] = root
            }
            for (dir in state.keys.keys - wanted) {
                val key = state.keys.remove(dir) ?: continue
                keyOwner.remove(key)
                key.cancel()
            }
        }
    }

    /**
     * The parts of a git directory worth watching: the directory itself, where `index`, `HEAD` and
     * `MERGE_HEAD` live — so staging, committing and merging all surface — plus `refs/`, so a branch
     * rename or a new stash does too. `objects/` is deliberately left out. It is ~260 directories per
     * repository that a single fetch floods with events, and nothing in it changes what status says.
     */
    private fun gitDirWatchDirs(repo: GitRepo): List<Path> {
        val refs = repo.gitDir.resolve("refs")
        val refDirs = runCatching {
            Files.walk(refs).use { paths ->
                paths.filter { Files.isDirectory(it) }.limit(maxDirs.toLong()).toList()
            }
        }.getOrDefault(emptyList())
        return listOf(repo.gitDir) + refDirs
    }

    private fun drain() {
        while (!closed) {
            val key = try {
                service.take()
            } catch (interrupted: InterruptedException) {
                return
            } catch (serviceClosed: ClosedWatchServiceException) {
                return
            }
            val dir = key.watchable() as? Path
            val events = key.pollEvents()
            // reset() before anything can throw, or a watch is lost for the life of the process.
            val stillValid = key.reset()
            val state = keyOwner[key]?.let { watched[it] }
            if (state != null) {
                state.generation.incrementAndGet()
                for (event in events) {
                    val created = event.kind() == ENTRY_CREATE
                    if (event.kind() == OVERFLOW) {
                        // Events were dropped, so the tree may have grown in ways we can't see.
                        state.needsRegister = true
                    } else if (created && dir != null) {
                        val child = (event.context() as? Path)?.let(dir::resolve)
                        // A new directory needs a watch of its own, and only a re-walk can say
                        // whether the repository's ignore rules want it.
                        if (child != null && Files.isDirectory(child)) state.needsRegister = true
                    }
                }
            }
            if (!stillValid) {
                keyOwner.remove(key)
                if (state != null) {
                    synchronized(state) { if (dir != null) state.keys.remove(dir) }
                    state.needsRegister = true
                }
            }
        }
    }

    companion object {
        /** [generation]'s answer when this watcher cannot vouch for a tree. Never a real count. */
        const val UNKNOWN = -1L

        /**
         * Per-repository directory ceiling. All 23 projects on this machine need 1.5k, so the
         * ceiling is not there to be reached in normal use — it is the point past which watching a
         * tree costs more than the walks it saves, and where falling back to polling is the right
         * answer. Linux's default watch limit (~500k) is far above either number.
         */
        const val MAX_WATCHED_DIRS = 20_000
    }
}
