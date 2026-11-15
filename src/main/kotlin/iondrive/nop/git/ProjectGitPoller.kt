package iondrive.nop.git

import iondrive.nop.Log
import iondrive.nop.worker.ProjectWorker
import java.nio.file.Path

/**
 * Keeps the "has uncommitted changes" flag current for every project open in any window's bar — the
 * dot on each project tab.
 *
 * The obvious way to do this is the reason this class exists: opening each repository, walking it,
 * and closing it again every few seconds keeps a core busy around the clock with nop idle and
 * untouched. A JGit status costs the size of the working tree rather than the number of changes in
 * it, and rebuilding the repository each tick re-reads every level of git config and rescans the
 * pack directory on top of that.
 *
 * So none of those three things happen per tick. Each project's [GitRepo] is opened once
 * and kept (see [retain]) — sharing its repository with the window showing it, if one is — with
 * only its pack indexes let go between walks; a [RepoWatcher] reports which trees have moved, so an unchanged project
 * costs a counter comparison; and a project nobody is looking at re-walks at most once per
 * [backgroundIntervalMs] however busy it gets — which matters on a machine where agents write to
 * several checkouts at once. An idle bar costs a map lookup per project per tick.
 *
 * The project in front of each window is skipped entirely: its own panel already holds a fresh
 * status, and that is what feeds its dot. Nothing here should walk a tree twice.
 *
 * The [RepoWatcher] is borrowed, not owned — the active project's panel consults the same one, so
 * closing it belongs to whoever created it. [close] releases the repositories.
 */
class ProjectGitPoller(
    private val watcher: RepoWatcher,
    private val backgroundIntervalMs: Long = BACKGROUND_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val readDirty: (Path) -> Boolean = ProjectWorker::dirty,
) : AutoCloseable {
    private val projects = LinkedHashMap<Path, Project>()
    private val sweepLock = Any()
    private var closed = false

    private class Project(val path: Path) {
        var repo: GitRepo? = null

        /** When we last tried to open a repository here; 0 for never. See [ProjectGitPoller.repoFor]. */
        var openAttemptedMs = 0L

        /** False until the first status walk, which happens regardless of what the watcher says. */
        var walked = false
        var lastWalkMs = 0L
        var lastGeneration = RepoWatcher.UNKNOWN
    }

    /**
     * Adopt [paths] as the set of open projects. Repositories for paths that have left the bar are
     * closed and unwatched here and now — a project tab closed should not leave a repository handle and
     * a tree of watches behind it.
     */
    @Synchronized
    fun retain(paths: Collection<Path>) {
        if (closed) return
        val keep = paths.toSet()
        for (path in projects.keys.toList()) {
            if (path in keep) continue
            val repo = projects.remove(path)?.repo ?: continue
            // Two project tabs can sit inside one repository (a project and a subdirectory of it).
            // Only drop the watches once the last of them is gone.
            if (projects.values.none { it.repo?.rootDir == repo.rootDir }) watcher.unwatch(repo.rootDir)
            runCatching { repo.close() }
                .onFailure { Log.error("project git poll: closing $path failed", it) }
        }
        for (path in keep) projects.getOrPut(path) { Project(path) }
    }

    /**
     * One tick. Walks only the projects whose tree has moved since the last look and whose turn it
     * is, and returns a flag for each one actually walked — so an idle bar returns an empty map.
     * The projects in [active] are skipped: each is the tab in front of one of nop's windows, and
     * that window's own panel owns its status.
     */
    fun sweep(active: Set<Path>): Map<Path, Boolean> = synchronized(sweepLock) {
        val dirty = mutableMapOf<Path, Boolean>()
        val now = nowMs()
        val snapshot = synchronized(this) { projects.values.toList() }
        for (project in snapshot) {
            val root = synchronized(this) prepare@{
                if (closed || projects[project.path] !== project) return@prepare null
                val repo = repoFor(project, now) ?: return@prepare null
                if (project.path in active) {
                    // A project on screen: its panel owns status, but still needs fresh watches.
                    watcher.sync(repo)
                    return@prepare null
                }
                if (project.walked && now - project.lastWalkMs < backgroundIntervalMs) return@prepare null
                // Re-registering is a tree walk too, so background projects do it at the interval.
                watcher.sync(repo)
                val generation = watcher.generation(repo.rootDir)
                // A change during the walk must leave a newer generation for the next sweep.
                val quiet = generation != RepoWatcher.UNKNOWN && generation == project.lastGeneration
                if (project.walked && quiet) return@prepare null
                project.walked = true
                project.lastWalkMs = now
                project.lastGeneration = generation
                repo.rootDir
            } ?: continue
            // In the project worker: the walk's cost is set by the size of the tree, and a tree
            // too big for the heap should cost that process, not this one (see ProjectWorker).
            // Never hold the map lock while waiting for it: retain/close run on the UI thread.
            val changed = try {
                readDirty(root)
            } catch (e: ProjectWorker.Unavailable) {
                // No worker to ask just now: say nothing about this project, and look again next time.
                synchronized(this) { project.lastGeneration = RepoWatcher.UNKNOWN }
                continue
            } catch (e: Exception) {
                Log.error("project git poll: status for ${project.path} failed", e)
                false
            }
            synchronized(this) {
                // A tab closed (or closed and reopened) while the worker was answering.
                if (!closed && projects[project.path] === project) dirty[project.path] = changed
            }
        }
        dirty
    }

    /** Closes every open repository. The borrowed [RepoWatcher] is the caller's to close. */
    @Synchronized
    override fun close() {
        closed = true
        for (project in projects.values) {
            project.repo?.let { repo -> runCatching { repo.close() } }
        }
        projects.clear()
    }

    private fun repoFor(project: Project, now: Long): GitRepo? {
        project.repo?.let { return it }
        // A directory that is not a repository can become one (git init in a terminal), so keep
        // retrying — but at the background interval, not every tick: each miss climbs the directory
        // tree to the filesystem root looking for a .git that isn't there.
        if (project.openAttemptedMs != 0L && now - project.openAttemptedMs < backgroundIntervalMs) return null
        project.openAttemptedMs = now
        project.repo = runCatching { GitRepo.discover(project.path) }
            .onFailure { Log.error("project git poll: opening ${project.path} failed", it) }
            .getOrNull()
        return project.repo
    }

    companion object {
        /**
         * How often a project nobody is looking at may re-walk its tree. Its dot only has to be
         * roughly right — the panel for the project actually on screen refreshes on its own, far
         * quicker cadence — and this is the ceiling that keeps a busy background checkout (an agent
         * writing to it, a build running in it) cheap.
         */
        const val BACKGROUND_INTERVAL_MS = 30_000L
    }
}
