package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

internal val IGNORED_DIR_NAMES = setOf(
    ".git", ".idea", ".gradle", ".vscode",
    "node_modules", "build", "out", "target", "dist", ".next", "__pycache__",
)

/**
 * One entry of a directory as the project tree shows it.
 *
 * What it is was read once, when the directory was listed, and without following a symlink: a
 * symlink's target can be a mount that takes seconds to answer or never does, and the tree must not
 * be the thing that waits for it. See [DirectoryListings].
 */
data class TreeChild(
    val file: File,
    val isDirectory: Boolean,
    val link: Link = Link.None,
) {
    enum class Link {
        /** Not a symlink. */
        None,

        /** A symlink nobody has looked behind yet. Shown as a plain row until somebody has. */
        Pending,

        /** A symlink whose target answered: [isDirectory] says what that target is. */
        Resolved,

        /** A symlink whose target could not be read — dangling, or on a mount that is not there. */
        Broken,
    }
}

/**
 * Lists [dir] without following a symlink. Each entry's attributes are read once, from the entry
 * itself, so a link into a dead mount costs no more than a link anywhere else.
 *
 * A link [previous] already knew about keeps what was learned of it — a link into a directory goes
 * on being a directory while it is looked at again, rather than flickering into a plain row on
 * every refresh.
 */
internal fun listEntries(dir: File, previous: List<TreeChild>? = null): List<TreeChild> {
    val before = previous.orEmpty().associateBy { it.file.name }
    return Files.newDirectoryStream(dir.toPath()).use { stream ->
        stream.mapNotNull { path ->
            val name = path.fileName.toString()
            if (name in IGNORED_DIR_NAMES) return@mapNotNull null
            // Gone between the listing and the look: not an entry any more.
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return@mapNotNull null
            }
            if (!attrs.isSymbolicLink) {
                TreeChild(path.toFile(), attrs.isDirectory)
            } else {
                before[name]?.takeIf { it.link != TreeChild.Link.None }
                    ?: TreeChild(path.toFile(), isDirectory = false, link = TreeChild.Link.Pending)
            }
        }
    }
}

/** Whether [link]'s target is a directory, or null when it cannot be read. Follows the link, so it can block. */
internal fun linkTargetIsDirectory(link: File): Boolean? = try {
    Files.readAttributes(link.toPath(), BasicFileAttributes::class.java).isDirectory
} catch (_: IOException) {
    null
}

/**
 * The project tree's copy of the directories it shows, read off the UI thread.
 *
 * The tree used to list a directory, and stat every entry in it, while it was being drawn. That is
 * fine on a local disk and a disaster on anything else: a project with a few dozen symlinks into a
 * network share that had not come back after a reboot spent minutes per redraw waiting on the
 * automounter, and since every window draws on the same thread, all of nop froze with it. Here the
 * drawing only ever reads what is already in memory. A directory nobody has listed yet is listed on
 * a worker, and the tree redraws when it arrives ([version]); one that is slow to answer says so
 * ([Listing.Pending]) instead of taking the window down with it.
 *
 * Symlinks are listed without being followed and then looked behind one at a time, each on its own
 * worker, so a link that hangs holds up nothing but its own row.
 *
 * A thread stuck in the kernel on a dead mount cannot be interrupted, so the one guard against
 * piling them up is that a path is never asked twice at once: a second request for a directory
 * still being listed is remembered and run once the first is done, and a link already being looked
 * at is not looked at again.
 *
 * All state is changed on the UI thread ([onUi]); the workers only read the disk.
 */
internal class DirectoryListings(
    private val read: (File, List<TreeChild>?) -> List<TreeChild> = ::listEntries,
    private val resolve: (File) -> Boolean? = ::linkTargetIsDirectory,
    private val onUi: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val loadingAfterMs: Long = 300,
    private val notRespondingAfterMs: Long = 2_000,
) {
    sealed interface Listing {
        /** Asked for and not back yet, for long enough to say so. */
        data class Pending(val notResponding: Boolean) : Listing

        data class Ready(val children: List<TreeChild>) : Listing

        data class Failed(val reason: String) : Listing
    }

    private val listings = mutableStateMapOf<String, Listing>()

    /** Bumped on every change, so a tree built from these listings knows to build itself again. */
    var version by mutableIntStateOf(0)
        private set

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val askedAgain = ConcurrentHashMap.newKeySet<String>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    /**
     * What is known of [dir], asking for it if nothing is. Null means it has been asked for and is
     * not back yet: show nothing, since on a local disk it will be back before anyone could read a
     * "loading" row. Never touches the disk, so it is safe to call while drawing.
     */
    fun childrenOf(dir: File): Listing? {
        val listing = listings[dir.absolutePath]
        if (listing == null) request(dir)
        return listing
    }

    /** [dir]'s entries if they are in, without asking for them or subscribing to them. */
    fun peek(dir: File): List<TreeChild>? =
        (Snapshot.withoutReadObservation { listings[dir.absolutePath] } as? Listing.Ready)?.children

    /** Suspends until [dir] has been listed, or has failed to be. */
    suspend fun awaitListed(dir: File) {
        childrenOf(dir)
        snapshotFlow { listings[dir.absolutePath] }.first { it is Listing.Ready || it is Listing.Failed }
    }

    /**
     * Reads the directories in [keep] again — the files on disk may have changed — and forgets the
     * rest. What is on screen stays there until the new listing replaces it. UI thread only.
     */
    fun refresh(keep: Set<String>) {
        listings.keys.filter { it !in keep }.forEach { listings.remove(it) }
        keep.forEach { key ->
            when (listings[key]) {
                is Listing.Ready, is Listing.Failed -> request(File(key))
                else -> Unit
            }
        }
        version += 1
    }

    private fun request(dir: File) {
        val key = dir.absolutePath
        if (!inFlight.add(key)) {
            askedAgain.add(key)
            return
        }
        if (listings[key] == null) {
            timers.schedule({ onUi { markPending(key, notResponding = false) } }, loadingAfterMs, TimeUnit.MILLISECONDS)
            timers.schedule({ onUi { markPending(key, notResponding = true) } }, notRespondingAfterMs, TimeUnit.MILLISECONDS)
        }
        val previous = (listings[key] as? Listing.Ready)?.children
        workers.execute {
            val result = try {
                Listing.Ready(read(dir, previous))
            } catch (e: IOException) {
                Listing.Failed(reasonFor(e))
            } catch (e: DirectoryIteratorException) {
                Listing.Failed(reasonFor(e.cause))
            } catch (e: SecurityException) {
                Listing.Failed(e.message ?: "not allowed")
            }
            onUi {
                inFlight.remove(key)
                publish(key, result)
                if (result is Listing.Ready) {
                    result.children
                        .filter { it.link == TreeChild.Link.Pending || it.link == TreeChild.Link.Broken }
                        .forEach { lookBehind(key, it.file) }
                }
                if (askedAgain.remove(key)) request(dir)
            }
        }
    }

    private fun lookBehind(dirKey: String, link: File) {
        val linkKey = link.absolutePath
        if (!resolving.add(linkKey)) return
        workers.execute {
            val isDirectory = resolve(link)
            onUi {
                resolving.remove(linkKey)
                val current = listings[dirKey] as? Listing.Ready ?: return@onUi
                val updated = current.children.map { child ->
                    if (child.file != link) {
                        child
                    } else if (isDirectory == null) {
                        child.copy(isDirectory = false, link = TreeChild.Link.Broken)
                    } else {
                        child.copy(isDirectory = isDirectory, link = TreeChild.Link.Resolved)
                    }
                }
                if (updated != current.children) publish(dirKey, Listing.Ready(updated))
            }
        }
    }

    private fun markPending(key: String, notResponding: Boolean) {
        if (key !in inFlight) return
        when (val current = listings[key]) {
            is Listing.Ready, is Listing.Failed -> return
            is Listing.Pending -> if (current.notResponding || !notResponding) return
            null -> Unit
        }
        publish(key, Listing.Pending(notResponding))
    }

    private fun publish(key: String, listing: Listing) {
        listings[key] = listing
        version += 1
    }

    companion object {
        // Unbounded, because a worker stuck on a dead mount stays stuck: a fixed pool would fill up
        // with them and leave every other directory waiting. The number stuck at once is bounded by
        // the paths, since none is ever asked twice at a time.
        private val workers = Executors.newCachedThreadPool { r ->
            Thread(r, "project-tree-listing").apply { isDaemon = true }
        }
        private val timers = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "project-tree-timers").apply { isDaemon = true }
        }

        private val byProject = ConcurrentHashMap<Path, DirectoryListings>()

        /**
         * The listings for the project at [path]. Kept for as long as nop runs, so coming back to a
         * project draws the tree it left rather than starting from nothing.
         */
        fun forProject(path: Path): DirectoryListings =
            byProject.getOrPut(path.toAbsolutePath().normalize()) { DirectoryListings() }

        private fun reasonFor(e: Throwable?): String = when (e) {
            is FileSystemException -> e.reason ?: e.javaClass.simpleName
            null -> "unknown error"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
