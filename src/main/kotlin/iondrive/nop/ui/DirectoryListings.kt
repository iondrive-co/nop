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
    internal val name = file.name
    internal val sortName = name.lowercase()
    internal val row by lazy(LazyThreadSafetyMode.PUBLICATION) { TreeEntry.Node(this) }

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

/** Immutable display order and rows, prepared before a listing reaches the UI. */
internal class DirectoryChildren private constructor(
    private val ordered: List<TreeChild>,
    val directories: List<TreeChild>,
    val files: List<TreeChild>,
) : AbstractList<TreeChild>() {
    override val size: Int get() = ordered.size
    override fun get(index: Int): TreeChild = ordered[index]
    val byName = ordered.associateBy { it.name }
    val rows = ordered.map { it.row }

    companion object {
        fun prepare(children: List<TreeChild>, previous: DirectoryChildren? = null): DirectoryChildren {
            if (children is DirectoryChildren) return children
            if (previous != null && children.size == previous.size &&
                children.all { previous.byName[it.name] == it }
            ) return previous
            val (dirs, files) = children.partition { it.isDirectory }
            val byName = compareBy<TreeChild> { it.sortName }.thenBy { it.name }
            val sortedDirs = dirs.sortedWith(byName)
            val sortedFiles = files.sortedWith(byName)
            return DirectoryChildren(sortedDirs + sortedFiles, sortedDirs, sortedFiles)
        }
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
    val before = (previous as? DirectoryChildren)?.byName ?: previous.orEmpty().associateBy { it.name }
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
            val old = before[name]
            if (!attrs.isSymbolicLink) {
                old?.takeIf { it.link == TreeChild.Link.None && it.isDirectory == attrs.isDirectory }
                    ?: TreeChild(path.toFile(), attrs.isDirectory)
            } else {
                old?.takeIf { it.link != TreeChild.Link.None }
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
 * Shared state changes on the UI thread ([onUi]); workers read and prepare immutable listings.
 */
internal class DirectoryListings(
    private val read: (File, List<TreeChild>?) -> List<TreeChild> = ::listEntries,
    private val resolve: (File) -> Boolean? = ::linkTargetIsDirectory,
    private val onUi: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val loadingAfterMs: Long = 300,
    private val notRespondingAfterMs: Long = 2_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxInactiveChildren: Int = 10_000,
    private val maxInactiveDirectories: Int = 32,
    private val scheduleLinkBatch: (() -> Unit) -> Unit = { batch ->
        timers.schedule({ onUi(batch) }, 25, TimeUnit.MILLISECONDS)
    },
    private val onIdle: () -> Unit = {},
) {
    sealed interface Listing {
        /** Asked for and not back yet, for long enough to say so. */
        data class Pending(val notResponding: Boolean) : Listing

        data class Ready(val children: DirectoryChildren) : Listing

        data class Failed(val reason: String) : Listing
    }

    private val listings = mutableStateMapOf<String, Listing>()

    /** Bumped on every change, so a tree built from these listings knows to build itself again. */
    var version by mutableIntStateOf(0)
        private set

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val askedAgain = ConcurrentHashMap.newKeySet<String>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val nextPollAt = mutableMapOf<String, Long>()
    private val retained = mutableMapOf<String, Int>()
    private val inactive = LinkedHashMap<String, Int>()
    private var inactiveChildren = 0
    private val views = mutableSetOf<View>()
    private var retired = false
    internal val hasViews: Boolean get() = views.isNotEmpty()

    private data class LinkResult(val child: TreeChild, val isDirectory: Boolean?)
    private val pendingLinks = mutableMapOf<String, MutableMap<String, LinkResult>>()
    private val scheduledLinks = mutableSetOf<String>()
    private val applyingLinks = mutableSetOf<String>()

    /** One displayed tree. Cached listings may be shared, but each view owns its polling lifetime. */
    inner class View : AutoCloseable {
        @Volatile private var visible = emptySet<String>()
        @Volatile private var closed = false
        private var kept = emptySet<String>()
        private val awaiting = mutableMapOf<String, Int>()

        fun show(directories: Set<String>, keep: Set<String> = directories) {
            if (closed) return
            retain(keep + awaiting.keys)
            for (key in directories - visible) nextPollAt.remove(key)
            visible = directories
        }

        private fun retain(keys: Set<String>) {
            for (key in keys - kept) pin(key)
            val released = kept - keys
            kept = keys
            for (key in released) unpin(key)
        }

        /** Read cached state only; drawing never starts disk work. */
        fun childrenOf(dir: File): Listing? {
            touch(dir.absolutePath)
            return listings[dir.absolutePath]
        }

        suspend fun awaitListed(dir: File) {
            // Revealing an open file may need ancestors beyond the current viewport. This is a
            // one-off navigation read, owned by the view just like its background reads.
            if (closed) return
            val key = dir.absolutePath
            awaiting[key] = (awaiting[key] ?: 0) + 1
            retain(kept + key)
            try {
                if (key !in inFlight && listings[key] !is Listing.Ready && listings[key] !is Listing.Failed) {
                    request(dir, wanted = { !closed })
                }
                snapshotFlow { listings[key] }.first { it is Listing.Ready || it is Listing.Failed }
            } finally {
                val count = awaiting.getValue(key)
                if (count == 1) awaiting.remove(key) else awaiting[key] = count - 1
            }
        }

        fun poll(force: Boolean = false) {
            if (closed) return
            val now = nowMs()
            for (key in visible) {
                if (!force && (key in inFlight || now < (nextPollAt[key] ?: Long.MIN_VALUE))) continue
                request(File(key), wanted = { key in visible })
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            visible = emptySet()
            retain(emptySet())
            views.remove(this)
            if (views.isEmpty()) onIdle()
        }
    }

    fun view(): View = View().also { views.add(it) }

    private fun touch(key: String) {
        val weight = inactive.remove(key) ?: return
        inactive[key] = weight
    }

    private fun pin(key: String) {
        retained[key] = (retained[key] ?: 0) + 1
        inactiveChildren -= inactive.remove(key) ?: 0
    }

    private fun unpin(key: String) {
        val count = retained[key] ?: return
        if (count > 1) retained[key] = count - 1 else {
            retained.remove(key)
            rememberInactive(key)
        }
    }

    private fun rememberInactive(key: String) {
        if (key in retained || key !in listings) return
        inactiveChildren -= inactive.remove(key) ?: 0
        val weight = (listings[key] as? Listing.Ready)?.children?.size ?: 0
        inactive[key] = weight
        inactiveChildren += weight
        while (inactive.size > maxInactiveDirectories || inactiveChildren > maxInactiveChildren) {
            val oldest = inactive.entries.first()
            val path = oldest.key
            inactiveChildren -= oldest.value
            inactive.remove(path)
            listings.remove(path)
            nextPollAt.remove(path)
        }
    }

    internal fun retire() {
        check(!hasViews)
        retired = true
        listings.clear()
        inactive.clear()
        inactiveChildren = 0
        nextPollAt.clear()
        pendingLinks.clear()
    }

    /**
     * What is known of [dir], asking for it if nothing is. Null means it has been asked for and is
     * not back yet: show nothing, since on a local disk it will be back before anyone could read a
     * "loading" row. Never touches the disk, so it is safe to call while drawing.
     */
    fun childrenOf(dir: File): Listing? {
        touch(dir.absolutePath)
        val listing = listings[dir.absolutePath]
        if (listing == null) request(dir)
        return listing
    }

    /** [dir]'s entries if they are in, without asking for them or subscribing to them. */
    fun peek(dir: File): List<TreeChild>? {
        touch(dir.absolutePath)
        return (Snapshot.withoutReadObservation { listings[dir.absolutePath] } as? Listing.Ready)?.children
    }

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
        val removed = listings.keys.filter { it !in keep }
        removed.forEach {
            listings.remove(it)
            inactiveChildren -= inactive.remove(it) ?: 0
            nextPollAt.remove(it)
        }
        keep.forEach { key ->
            when (listings[key]) {
                is Listing.Ready, is Listing.Failed -> request(File(key))
                else -> Unit
            }
        }
        if (removed.isNotEmpty()) version += 1
    }

    private fun request(dir: File, wanted: () -> Boolean = { true }) {
        if (retired) return
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
            if (!wanted()) {
                onUi { inFlight.remove(key) }
                return@execute
            }
            val started = nowMs()
            val result = try {
                Listing.Ready(DirectoryChildren.prepare(read(dir, previous), previous))
            } catch (e: IOException) {
                Listing.Failed(reasonFor(e))
            } catch (e: DirectoryIteratorException) {
                Listing.Failed(reasonFor(e.cause))
            } catch (e: SecurityException) {
                Listing.Failed(e.message ?: "not allowed")
            }
            val costMs = (nowMs() - started).coerceAtLeast(0)
            onUi {
                inFlight.remove(key)
                if (retired) return@onUi
                // At least three seconds, three milliseconds per entry, and a hundred times the
                // read's duration. Large local directories and slow shares each get their own pace.
                val entries = (result as? Listing.Ready)?.children?.size ?: 0
                nextPollAt[key] = nowMs() + maxOf(3_000L, entries.toLong() * 3, costMs * 100)
                publish(key, result)
                if (wanted() && result is Listing.Ready) {
                    result.children
                        .filter { it.link == TreeChild.Link.Pending || it.link == TreeChild.Link.Broken }
                        .forEach { lookBehind(key, it, wanted) }
                }
                if (askedAgain.remove(key) && wanted()) request(dir, wanted)
            }
        }
    }

    private fun lookBehind(dirKey: String, child: TreeChild, wanted: () -> Boolean = { true }) {
        val linkKey = child.file.absolutePath
        if (!resolving.add(linkKey)) return
        workers.execute {
            if (!wanted()) {
                onUi { resolving.remove(linkKey) }
                return@execute
            }
            val isDirectory = resolve(child.file)
            onUi {
                if (retired) {
                    resolving.remove(linkKey)
                    return@onUi
                }
                pendingLinks.getOrPut(dirKey) { mutableMapOf() }[linkKey] = LinkResult(child, isDirectory)
                scheduleLinks(dirKey)
            }
        }
    }

    private fun scheduleLinks(dirKey: String) {
        if (dirKey in applyingLinks || !scheduledLinks.add(dirKey)) return
        scheduleLinkBatch {
            scheduledLinks.remove(dirKey)
            val updates = pendingLinks.remove(dirKey) ?: return@scheduleLinkBatch
            val current = listings[dirKey] as? Listing.Ready
            if (current == null || retired) {
                updates.keys.forEach { resolving.remove(it) }
                return@scheduleLinkBatch
            }
            applyingLinks.add(dirKey)
            workers.execute {
                val children = current.children.map { child ->
                    val result = updates[child.file.absolutePath]?.takeIf { it.child === child }
                    when {
                        result == null -> child
                        result.isDirectory == null -> child.copy(isDirectory = false, link = TreeChild.Link.Broken)
                        else -> child.copy(isDirectory = result.isDirectory, link = TreeChild.Link.Resolved)
                    }
                }
                val prepared = DirectoryChildren.prepare(children, current.children)
                onUi {
                    applyingLinks.remove(dirKey)
                    if (!retired && listings[dirKey] === current) {
                        publish(dirKey, Listing.Ready(prepared))
                        updates.keys.forEach { resolving.remove(it) }
                    } else if (!retired && listings[dirKey] is Listing.Ready) {
                        pendingLinks.getOrPut(dirKey) { mutableMapOf() }.putAll(updates)
                    } else {
                        updates.keys.forEach { resolving.remove(it) }
                    }
                    if (dirKey in pendingLinks) scheduleLinks(dirKey)
                }
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
        if (listings[key] == listing) return
        listings[key] = listing
        version += 1
        rememberInactive(key)
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

        private val byProject = ProjectDirectoryListings()

        /**
         * Recent project listings. Active views are retained; inactive projects share a bounded LRU.
         */
        fun forProject(path: Path): DirectoryListings =
            byProject.forProject(path)

        private fun reasonFor(e: Throwable?): String = when (e) {
            is FileSystemException -> e.reason ?: e.javaClass.simpleName
            null -> "unknown error"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

/** UI-thread cache: open projects are pinned, with a small working set of inactive projects. */
internal class ProjectDirectoryListings(private val maxInactiveProjects: Int = 8) {
    private val projects = LinkedHashMap<Path, DirectoryListings>(16, .75f, true)

    fun forProject(path: Path): DirectoryListings {
        val key = path.toAbsolutePath().normalize()
        val listings = projects.getOrPut(key) { DirectoryListings(onIdle = { becameInactive(key) }) }
        trim()
        return listings
    }

    private fun becameInactive(key: Path) {
        // Closing an active tree makes it the most recently used inactive project.
        projects[key]
        trim()
    }

    private fun trim() {
        var inactive = projects.values.count { !it.hasViews }
        val iterator = projects.entries.iterator()
        while (inactive > maxInactiveProjects && iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.hasViews) {
                iterator.remove()
                entry.value.retire()
                inactive--
            }
        }
    }
}
