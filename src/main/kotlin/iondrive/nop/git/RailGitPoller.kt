package iondrive.nop.git

import iondrive.nop.Log
import java.nio.file.Path

/**
 * Keeps the "has uncommitted changes" flag current for every project open in the rail — the dot on
 * each rail tab.
 *
 * The obvious way to do this is the reason this class exists: opening each repository, walking it,
 * and closing it again every few seconds. Measured on a 23-project rail that held one core at ~7%
 * around the clock with nop idle and untouched — ~19k syscalls and ~7.3k file stats a second. A JGit
 * status costs the size of the working tree rather than the number of changes in it, and rebuilding
 * the repository each tick re-read every level of git config and rescanned the pack directory on top
 * of that.
 *
 * So none of those three things happen per tick any more. Each project's [GitRepo] is opened once
 * and kept (see [retain]); a [RepoWatcher] reports which trees have moved, so an unchanged project
 * costs a counter comparison; and a project nobody is looking at re-walks at most once per
 * [backgroundIntervalMs] however busy it gets — which matters on a machine where agents write to
 * several checkouts at once. An idle rail costs a map lookup per project per tick.
 *
 * The active project is skipped entirely: its own panel already holds a fresh status, and that is
 * what feeds its dot. Nothing here should walk a tree twice.
 *
 * The [RepoWatcher] is borrowed, not owned — the active project's panel consults the same one, so
 * closing it belongs to whoever created it. [close] releases the repositories.
 */
class RailGitPoller(
    private val watcher: RepoWatcher,
    private val backgroundIntervalMs: Long = BACKGROUND_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val projects = LinkedHashMap<Path, Project>()

    private class Project(val path: Path) {
        var repo: GitRepo? = null

        /** When we last tried to open a repository here; 0 for never. See [RailGitPoller.repoFor]. */
        var openAttemptedMs = 0L

        /** False until the first status walk, which happens regardless of what the watcher says. */
        var walked = false
        var lastWalkMs = 0L
        var lastGeneration = RepoWatcher.UNKNOWN
    }

    /**
     * Adopt [paths] as the set of open projects. Repositories for paths that have left the rail are
     * closed and unwatched here and now — a rail tab closed should not leave a repository handle and
     * a tree of watches behind it.
     */
    @Synchronized
    fun retain(paths: Collection<Path>) {
        val keep = paths.toSet()
        for (path in projects.keys.toList()) {
            if (path in keep) continue
            val repo = projects.remove(path)?.repo ?: continue
            // Two rail tabs can sit inside one repository (a project and a subdirectory of it).
            // Only drop the watches once the last of them is gone.
            if (projects.values.none { it.repo?.rootDir == repo.rootDir }) watcher.unwatch(repo.rootDir)
            runCatching { repo.close() }
                .onFailure { Log.error("rail git poll: closing $path failed", it) }
        }
        for (path in keep) projects.getOrPut(path) { Project(path) }
    }

    /**
     * One tick. Walks only the projects whose tree has moved since the last look and whose turn it
     * is, and returns a flag for each one actually walked — so an idle rail returns an empty map.
     * [active] is skipped; its panel owns its status.
     */
    @Synchronized
    fun sweep(active: Path?): Map<Path, Boolean> {
        val dirty = mutableMapOf<Path, Boolean>()
        val now = nowMs()
        for (project in projects.values) {
            val repo = repoFor(project, now) ?: continue
            if (project.path == active) {
                // The project on screen: keep its watches current every tick, because its panel
                // reads the same watcher and wants an edit to show up in seconds. Its status is that
                // panel's to load, so there is nothing else to do for it here.
                watcher.sync(repo)
                continue
            }
            if (project.walked && now - project.lastWalkMs < backgroundIntervalMs) continue
            // Behind the interval, not in front of it: re-registering is itself a tree walk whenever
            // directories have appeared, so a background checkout busily creating them would defeat
            // the limit if this ran every tick.
            watcher.sync(repo)
            val generation = watcher.generation(repo.rootDir)
            // Read the generation *before* walking and store that: a change landing during the walk
            // leaves a higher count behind and so is picked up next tick rather than being taken as
            // already covered.
            val quiet = generation != RepoWatcher.UNKNOWN && generation == project.lastGeneration
            if (project.walked && quiet) continue
            project.walked = true
            project.lastWalkMs = now
            project.lastGeneration = generation
            dirty[project.path] = runCatching { !repo.loadStatus().isClean }
                .onFailure { Log.error("rail git poll: status for ${project.path} failed", it) }
                .getOrDefault(false)
        }
        return dirty
    }

    /** Closes every open repository. The borrowed [RepoWatcher] is the caller's to close. */
    @Synchronized
    override fun close() {
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
            .onFailure { Log.error("rail git poll: opening ${project.path} failed", it) }
            .getOrNull()
        return project.repo
    }

    companion object {
        /**
         * How often a project nobody is looking at may re-walk its tree. Its dot only has to be
         * roughly right — the panel for the project actually on screen refreshes on its own, far
         * quicker cadence — and this is the ceiling that keeps a busy background checkout (an agent
         * writing to it, a build running in it) from costing what the whole rail used to.
         */
        const val BACKGROUND_INTERVAL_MS = 30_000L
    }
}
