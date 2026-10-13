package iondrive.nop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.ipc.Handover
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a nop installed from a release up to date. Every few hours it asks GitHub for the latest
 * release, and when that is newer than this build it downloads, checks and stages it beside the
 * install ([Updater]). A staged update goes in when nop next restarts ([installForRestart]), and the
 * restart hands the running agents and terminals over to it as any restart does.
 *
 * Only a release install is touched: a build from a checkout, or a copy of one (see [Installation]),
 * is updated by rebuilding, and nop asks GitHub nothing on its behalf.
 */
object SelfUpdate {

    sealed interface Status {
        /** Not looked yet. */
        data object Idle : Status

        /** Not updated from releases at all, for [reason]. */
        data class Off(val reason: String) : Status

        data object Checking : Status

        /** This build is the latest release or newer. */
        data object Current : Status

        data class Downloading(val version: Version, val done: Long, val total: Long) : Status

        /** [version] is staged and goes in at the next restart. */
        data class Ready(val version: Version) : Status

        /** The last attempt failed, for [reason]; the next check tries again. */
        data class Failed(val reason: String) : Status
    }

    /** What the updater is doing, for the UI to show. Compose state; written from its own thread. */
    var status: Status by mutableStateOf(Status.Idle)
        private set

    private val updater by lazy { Updater() }
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nop-update").apply { isDaemon = true }
    }

    /** Starts the checks. Called once, by the nop that owns the windows. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val installation = Installation.current()
        if (installation !is Installation.Release) {
            status = Status.Off((installation as Installation.NotUpdatable).reason)
            return
        }
        val root = rootOf(installation.launcher)
        if (!Files.isWritable(root.parent)) {
            status = Status.Off("nop cannot write beside $root, so it cannot stage an update there")
            return
        }
        executor.scheduleWithFixedDelay(
            { check(installation, root) },
            FIRST_CHECK_MINUTES,
            CHECK_EVERY_HOURS * 60,
            TimeUnit.MINUTES,
        )
    }

    /** Checks now, off the calling thread, when [start] found a release install to keep current. */
    fun checkNow() {
        val installation = Installation.current() as? Installation.Release ?: return
        if (started.get()) executor.execute { check(installation, rootOf(installation.launcher)) }
    }

    private fun check(installation: Installation.Release, root: Path) {
        status = Status.Checking
        status = runCatching {
            val latest = updater.latest()
            if (latest.version <= installation.version) {
                // Something staged earlier is no newer than what runs now: it would only go backwards.
                updater.discard(root)
                return@runCatching Status.Current
            }
            updater.staged(root)?.takeIf { it.version == latest.version }?.let { return@runCatching Status.Ready(it.version) }
            Log.info("update: downloading ${latest.version} to stage beside $root")
            val staged = updater.stage(root, latest) { done, total -> status = Status.Downloading(latest.version, done, total) }
            Log.info("update: ${staged.version} is staged beside $root and goes in at the next restart")
            Status.Ready(staged.version)
        }.getOrElse { failure ->
            val reason = (failure as? UpdateException)?.message ?: failure.toString()
            Log.warn("update: $reason")
            Status.Failed(reason)
        }
    }

    /**
     * Puts a staged update in place of the install [launcher] belongs to, as a restart is about to
     * exec that launcher, so what it execs is the update. Returns the version installed, or null when
     * there was none to install — nothing staged, this is not a release install, or the update's
     * build would not take over this nop's handover, which would hang up every terminal.
     */
    fun installForRestart(launcher: Path): Version? {
        val installation = Installation.of(launcher, BuildInfo.version) as? Installation.Release ?: return null
        val root = rootOf(launcher)
        val staged = updater.staged(root) ?: return null
        if (staged.version <= installation.version) return null
        if (staged.handover != Handover.VERSION) {
            Log.warn(
                "update: not installing ${staged.version} at this restart: its build adopts handover " +
                    "${staged.handover}, and this nop hands over version ${Handover.VERSION}",
            )
            return null
        }
        return runCatching { updater.install(root)?.version }
            .onSuccess { if (it != null) Log.info("update: installed $it at $root; the old image is beside it") }
            .onFailure { Log.error("update: could not install ${staged.version} at $root", it) }
            .getOrNull()
    }

    /** The image a launcher at `<root>/bin/nop` belongs to. */
    private fun rootOf(launcher: Path): Path = launcher.toAbsolutePath().normalize().parent.parent

    private const val FIRST_CHECK_MINUTES = 2L
    private const val CHECK_EVERY_HOURS = 6L
}
