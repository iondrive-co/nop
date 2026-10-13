package iondrive.nop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.ipc.Handover
import iondrive.nop.ipc.Restart
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a nop installed from a release up to date.
 *
 * Every few hours it asks GitHub for the latest release, and when that is newer than this build it
 * stages it beside the install as a version of its own ([Updater]). The next restart starts that
 * version, handing the running agents and terminals over to it as any restart does — or, when its
 * build cannot take those over, the next start does ([redirectAtStart]). The install's home moves to
 * the new version only once it has come up ([markHealthy]); one that never does is marked failed by
 * the next start, which is the old version again because the home never moved.
 *
 * Only a release install is updated: a build from a checkout, or a copy of one (see [Installation]),
 * is updated by rebuilding, and nop asks GitHub nothing on its behalf. A release installed somewhere
 * the user cannot write, such as a package's `/opt/nop`, gets its versions under
 * `~/.local/opt/nop` instead, and a start of the old one goes on to the newest of those.
 */
object SelfUpdate {

    sealed interface Status {
        /** Not looked yet. */
        data object Idle : Status

        /** This nop is not updated from releases, for [reason]. */
        data class Off(val reason: String) : Status

        /** The user turned the checks off. */
        data object Disabled : Status

        data object Checking : Status

        /** This build is the latest release, or newer. */
        data object Current : Status

        data class Downloading(val version: Version, val done: Long, val total: Long) : Status

        /**
         * [version] is staged. [atRestart] says whether the next restart starts it with every
         * terminal carried over; when not, it starts at nop's next start instead.
         */
        data class Ready(val version: Version, val atRestart: Boolean) : Status

        /** The last check failed, for [reason]; the next one tries again. */
        data class Failed(val reason: String) : Status
    }

    /** What the updater is doing, for the UI. Compose state; written from the updater's own thread. */
    var status: Status by mutableStateOf(Status.Idle)
        internal set

    /** A version this start found had been tried and never came up, so nop went back from it. */
    var rolledBack: Version? by mutableStateOf(null)
        private set

    /** Whether the checks run (see [Settings.loadAutoUpdate]). Compose state. */
    var automatic: Boolean by mutableStateOf(true)
        private set

    /** This build as nop shows it: its version, marked `-dev` when it is someone's own build. */
    val label: String by lazy {
        val version = BuildInfo.version?.toString() ?: return@lazy "dev"
        if (Installation.isOwnBuild(Restart.launcher())) "$version-dev" else version
    }

    /** Where this nop runs from, and where its versions go. */
    internal data class Place(
        /** The image this nop started from: the directory holding `bin/nop`. */
        val image: Path,
        /** The path launchers name, which is (or becomes) a link to the version that runs. */
        val home: Path,
        val version: Version,
        /** A home of the user's own standing in for an install they cannot write to. */
        val system: Boolean,
    )

    internal sealed interface Located {
        data class At(val place: Place) : Located
        data class Nowhere(val reason: String) : Located
    }

    private val located: Located by lazy {
        locate(Restart.launcher(), BuildInfo.version, Path.of(System.getProperty("user.home")))
    }

    private val updater by lazy { Updater() }
    private val started = AtomicBoolean(false)
    private var scheduled: ScheduledFuture<*>? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nop-update").apply { isDaemon = true }
    }

    /**
     * Where an install whose launcher is [launcher] keeps its versions, or why it is not updated.
     * The image is the launcher's real directory; its home is the link beside it that the image's
     * name says it is a version of (`nop-1.2.6` is a version of `nop`), else the image itself.
     */
    internal fun locate(launcher: Path?, version: Version?, userHome: Path): Located {
        val installation = Installation.of(launcher, version)
        if (installation !is Installation.Release) return Located.Nowhere((installation as Installation.NotUpdatable).reason)
        val real = runCatching { installation.launcher.toRealPath() }.getOrElse { installation.launcher.toAbsolutePath().normalize() }
        val image = real.parent?.parent ?: return Located.Nowhere("nop's launcher is not in an app image")
        val parent = image.parent ?: return Located.Nowhere("nop's image has no directory to put versions beside")
        val home = if (Files.isWritable(parent)) {
            val named = VERSIONED.matchEntire(image.fileName.toString())
            if (named != null && Version.parse(named.groupValues[2]) != null) image.resolveSibling(named.groupValues[1]) else image
        } else {
            userHome.resolve(".local/opt/nop")
        }
        if (home != image && Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) && Files.exists(home.resolve(Installation.BUILT_FROM))) {
            return Located.Nowhere("$home holds a build from a checkout, which a release must not replace")
        }
        return Located.At(Place(image, home, installation.version, system = !Files.isWritable(parent)))
    }

    /** Starts the checks. Called once, by the nop that owns the windows. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        automatic = Settings.loadAutoUpdate()
        val place = when (val at = located) {
            is Located.Nowhere -> {
                status = Status.Off(at.reason)
                return
            }
            is Located.At -> at.place
        }
        noteFailedStarts(place, updater)?.let { rolledBack = it }
        if (!automatic) {
            status = Status.Disabled
            return
        }
        schedule(place)
    }

    /** Turns the checks on or off, and remembers it. Off throws away an update staged but not started. */
    fun changeAutomatic(on: Boolean) {
        Settings.saveAutoUpdate(on)
        automatic = on
        val place = (located as? Located.At)?.place ?: return
        if (on) {
            status = Status.Idle
            schedule(place)
            checkNow()
        } else {
            synchronized(this) {
                scheduled?.cancel(false)
                scheduled = null
            }
            executor.execute { runCatching { discardStaged(place, updater) } }
            status = Status.Disabled
        }
    }

    /** Checks now, off the calling thread, for a release install. */
    fun checkNow() {
        val place = (located as? Located.At)?.place ?: return
        executor.execute { check(place, updater) }
    }

    private fun schedule(place: Place) = synchronized(this) {
        if (scheduled == null) {
            scheduled = executor.scheduleWithFixedDelay({ check(place, updater) }, FIRST_CHECK_MINUTES, CHECK_EVERY_HOURS * 60, TimeUnit.MINUTES)
        }
    }

    internal fun check(place: Place, updater: Updater) {
        status = Status.Checking
        status = runCatching {
            val latest = updater.latest()
            val images = updater.images(place.home)
            // Staged and never started, but newer than any release there is now: a release taken back,
            // which is no longer something to install.
            val withdrawn = images.filter { it.version > latest.version && !it.tried && !it.failed }.map { it.dir }.toSet()
            if (withdrawn.isNotEmpty()) updater.prune(place.home, images.map { it.dir }.toSet() - withdrawn)
            if (latest.version <= place.version) return@runCatching Status.Current
            if (images.any { it.version == latest.version && it.failed }) {
                return@runCatching Status.Failed("${latest.version} did not start here before, so nop stays on ${place.version}")
            }
            candidate(place, updater)?.takeIf { it.version >= latest.version }?.let { return@runCatching Status.Ready(it.version, carries(it)) }
            Log.info("update: downloading ${latest.version} to stage beside ${place.home}")
            val reuse = listOf(place.image) + images.filter { it.healthy && it.dir != place.image }.map { it.dir }
            val staged = updater.stage(place.home, latest, reuse) { done, total -> status = Status.Downloading(latest.version, done, total) }
            // An older version staged and never started is superseded by this one.
            updater.prune(place.home, keep(place, updater) + staged.dir)
            Log.info("update: ${staged.version} is staged at ${staged.dir}")
            Status.Ready(staged.version, carries(staged))
        }.getOrElse { failure ->
            val reason = (failure as? UpdateException)?.message ?: failure.toString()
            Log.warn("update: $reason")
            Status.Failed(reason)
        }
    }

    /**
     * The launcher a restart should exec instead of this nop's own: a newer version staged beside the
     * install, when its build takes over this nop's handover. Marks it tried. Null for none.
     */
    fun restartTarget(): Path? = (located as? Located.At)?.place?.let { restartTarget(it, updater) }

    internal fun restartTarget(place: Place, updater: Updater): Path? {
        val image = runCatching { candidate(place, updater) }.getOrNull() ?: return null
        if (!carries(image)) {
            Log.info(
                "update: ${image.version} starts at nop's next start, not this restart: its build adopts handover " +
                    "${image.adopts.sorted()}, and this nop writes ${Handover.VERSION}",
            )
            return null
        }
        return runCatching { start(place, image, "restarting into", updater) }
            .onFailure { Log.error("update: could not start ${image.version}", it) }
            .getOrNull()
    }

    /**
     * The launcher a starting nop should exec in place of itself: a newer version staged beside the
     * install, or one already installed there that this older launcher does not know of. Null to
     * carry on. Only for a start with nothing running yet, since nothing is carried over.
     */
    fun redirectAtStart(): Path? =
        (located as? Located.At)?.place?.let { redirectAtStart(it, updater, Settings.loadAutoUpdate()) }

    internal fun redirectAtStart(place: Place, updater: Updater, automatic: Boolean): Path? {
        val image = runCatching { candidate(place, updater) }.getOrNull() ?: return null
        if (!image.tried && !automatic) return null
        return runCatching { start(place, image, "starting", updater) }
            .onFailure { Log.error("update: could not start ${image.version}", it) }
            .getOrNull()
    }

    /**
     * Called once this nop has come up — its windows open, the terminals it was handed taken up.
     * When it is a version that was just started for the first time, the install's home moves to it,
     * the versions no longer needed are deleted, and a stand-in for a system install is put in the
     * desktop's menu.
     */
    fun markHealthy() {
        (located as? Located.At)?.place?.let { markHealthy(it, updater) }
    }

    internal fun markHealthy(place: Place, updater: Updater, applications: Path? = null) {
        runCatching {
            val own = updater.images(place.home).firstOrNull { same(it.dir, place.image) } ?: return
            if (own.healthy || !own.tried) return
            updater.mark(own.dir, Updater.HEALTHY)
            val from = updater.triedFrom(own.dir)
            updater.activate(place.home, own.dir, aside(place, from?.second, updater))
            updater.prune(place.home, keep(place, updater) + listOfNotNull(from?.first))
            if (place.system) {
                if (applications != null) DesktopEntry.write(place.home, applications) else DesktopEntry.write(place.home)
            }
            Log.info("update: ${own.version} came up; ${place.home} now starts it")
        }.onFailure { Log.error("update: could not finish installing ${place.version}", it) }
    }

    /**
     * The newest version beside the install that is newer than this one and may be started. Never the
     * directory this nop runs from, and newer than both the version this build says it is and the
     * version its directory is named for: a start that went to a version must not come back from it.
     */
    private fun candidate(place: Place, updater: Updater): Updater.Image? {
        val images = updater.images(place.home)
        val own = images.firstOrNull { same(it.dir, place.image) }?.version?.let { maxOf(it, place.version) } ?: place.version
        return images
            .filter { !same(it.dir, place.image) && it.version > own && !it.failed && (!it.tried || it.healthy) }
            .maxByOrNull { it.version }
    }

    private fun start(place: Place, image: Updater.Image, doing: String, updater: Updater): Path {
        if (!image.tried) {
            updater.mark(image.dir, Updater.TRIED, "from=${place.image}\nfromVersion=${place.version}\n")
        }
        Log.info("update: $doing ${image.version} in place of ${place.version}")
        return image.launcher
    }

    private fun carries(image: Updater.Image): Boolean = Handover.VERSION in image.adopts

    /**
     * A version that was started and never came up — a start that is not that version is the old one
     * again, so it did not — is marked failed and not tried again.
     */
    internal fun noteFailedStarts(place: Place, updater: Updater): Version? = runCatching {
        var failed: Version? = null
        for (image in updater.images(place.home)) {
            if (!image.tried || image.healthy || image.failed || same(image.dir, place.image)) continue
            updater.fail(image.dir)
            failed = image.version
            Log.warn("update: ${image.version} was started and never came up; this is ${place.version} again, and ${image.version} is not tried again")
        }
        failed
    }.getOrNull()

    /** Versions staged and never started are thrown away. */
    private fun discardStaged(place: Place, updater: Updater) {
        val unstarted = updater.images(place.home).filter { it.version > place.version && !it.tried && !it.failed }.map { it.dir }.toSet()
        if (unstarted.isEmpty()) return
        updater.prune(place.home, updater.images(place.home).map { it.dir }.toSet() - unstarted)
    }

    /**
     * What stays when versions are pruned: this one, the newest healthy one older than it (what going
     * back would mean), and anything newer, which may be staged or starting.
     */
    private fun keep(place: Place, updater: Updater): Set<Path> {
        val images = updater.images(place.home)
        val previous = images.filter { it.healthy && it.version < place.version }.maxByOrNull { it.version }
        return setOf(place.image) + listOfNotNull(previous?.dir) + images.filter { it.version > place.version }.map { it.dir }
    }

    /**
     * Where a home that is still a directory of its own goes when it becomes a link: beside it as the
     * version it holds, when that name is free.
     */
    private fun aside(place: Place, version: Version?, updater: Updater): Path {
        val named = version?.let { updater.imageDir(place.home, it) }
        if (named != null && !Files.exists(named, LinkOption.NOFOLLOW_LINKS)) return named
        val previous = Updater.sibling(place.home, "previous")
        if (Files.isDirectory(place.home, LinkOption.NOFOLLOW_LINKS)) Updater.deleteTree(previous)
        return previous
    }

    private fun same(a: Path, b: Path): Boolean =
        runCatching { Files.isSameFile(a, b) }.getOrElse { a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize() }

    /** `nop-1.2.6`: a version of the home `nop`. */
    private val VERSIONED = Regex("""(.+)-(\d+\.\d+\.\d+)""")

    private const val FIRST_CHECK_MINUTES = 2L
    private const val CHECK_EVERY_HOURS = 6L
}
