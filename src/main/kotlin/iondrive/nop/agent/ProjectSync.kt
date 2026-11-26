package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.Duration as JavaDuration
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Where this machine stands with a synced project. */
internal enum class SyncRole {
    /** Not known yet: the remote has not answered. Nothing runs and nothing is shown. */
    Starting,

    /** This machine holds the project: its tabs run here and are sent to the remote. */
    Owner,

    /** Another machine holds the project: its tabs are shown here, read-only, until this one takes over. */
    Viewer,
}

/**
 * What the UI shows of a project's sync. [lease] is the holder as last seen, who checked in [age]
 * seconds before [checked], when the remote last answered. [rows] are the holder's tabs as a viewer
 * last fetched them, and [pulled] counts the fetches that changed something, so a view of one of
 * them knows to read it again. [takenOverBy] names the machine that took the project from this one;
 * [busy] is a takeover under way, and [pending] an owner whose claim the remote has not seen yet.
 * [notice] is what the last takeover left behind, for the user to read once.
 */
internal data class SyncView(
    val role: SyncRole = SyncRole.Starting,
    val lease: SyncLease? = null,
    val age: Long? = null,
    val checked: Instant? = null,
    val error: String? = null,
    val rows: List<Settings.OpenAgent> = emptyList(),
    val pulled: Int = 0,
    val takenOverBy: String? = null,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val notice: String? = null,
)

/** A project's sync once it has been looked up: [sync] is null when the project is not synced. */
internal class SyncLookup(val sync: ProjectSync?)

/**
 * One project's agent tabs kept on the remote: who holds the project, and the holder's tabs sent
 * there as they change, every [interval].
 *
 * The holder checks in each time; a check-in that is turned down means another machine has taken
 * the project, and this one stops its agents and becomes a viewer of the other's. A viewer fetches
 * the holder's tabs each time, and takes the project over only when the user asks it to, which
 * fetches the holder's latest and puts them in place here before this machine's agents start.
 *
 * What the remote said is kept in plain fields under [mutex], which every exchange with it holds;
 * [view] is the copy the UI reads, handed over through [post].
 */
internal class ProjectSync(
    val root: Path,
    private val link: SyncLink,
    private val key: String,
    val seat: String,
    private val host: String,
    val files: SyncFiles,
    /** The remote, as the user named it. */
    val target: String,
    private val head: () -> String,
    private val dirty: () -> Boolean,
    private val stopSessions: () -> Unit,
    private val post: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val interval: Duration = 30.seconds,
) {
    var view by mutableStateOf(SyncView())
        private set

    /** Whether this machine's agents may run and its tab strip be saved. */
    @Volatile
    var owning = false
        private set

    /** Whether the remote has answered since this machine last became the holder, so the tabs here are its to send. */
    @Volatile
    private var contacted = false

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    private var gen = 0L
    private var pendingClaim: Long? = null
    private var pushedPrint: String? = null
    private var lastPulledPrint: String? = null
    private var state = SyncView()

    fun localHead(): String = head()

    fun localDirty(): Boolean = dirty()

    /** Asks the remote where the project stands, then keeps in step with it until [finish]. */
    @Synchronized
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            mutex.withLock { guarded { begin() } }
            while (isActive) {
                delay(interval)
                mutex.withLock { guarded { if (owning) ownerTick() else viewerTick() } }
            }
        }
    }

    /**
     * Makes this machine the holder, taking the project from whichever machine holds it now, which
     * is told at its next check-in. The holder's latest tabs are fetched and put in place first;
     * [onto] is the local account the user chose for each account this machine has none by the
     * name of, by [SyncFiles.accountKey], and a tab of an account with none chosen stays behind.
     */
    fun takeOver(onto: Map<String, String> = emptyMap()): Job = scope.launch {
        mutex.withLock {
            if (owning) return@withLock
            update { it.copy(busy = true, error = null) }
            try {
                guarded { claimFromHolder(onto) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Stops keeping in step and, when this machine holds the project, sends what has changed since
     * the last copy, giving up at [deadline].
     */
    fun finish(deadline: Instant) {
        loop?.cancel()
        if (owning && contacted) {
            runBlocking {
                withTimeoutOrNull(left(deadline)) {
                    mutex.withLock {
                        val wait = left(deadline)
                        if (!owning || !wait.isPositive()) return@withLock
                        try {
                            val print = files.stage()
                            if (print != pushedPrint) {
                                link.push(key, seat, files.outDir, wait)
                                pushedPrint = print
                            }
                        } catch (e: SyncFailure) {
                            Log.warn("sync: could not send the agent tabs of $root on the way out: ${e.message}")
                        }
                    }
                }
            }
        }
        scope.cancel()
    }

    /**
     * Stops at once and sends nothing more, not even what has changed since the last copy: the
     * remote this was kept in step with is no longer where the user wants these tabs kept.
     */
    fun abandon() {
        loop?.cancel()
        link.close()
        scope.cancel()
    }

    private fun left(deadline: Instant): Duration =
        JavaDuration.between(Instant.now(), deadline).toMillis().coerceAtLeast(0).milliseconds

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncFailure) {
            update { it.copy(error = e.message) }
        } catch (e: Exception) {
            Log.error("sync: ${root.fileName} failed", e)
            update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    @Synchronized
    private fun update(change: (SyncView) -> SyncView) {
        val next = change(state)
        state = next
        post { view = next }
    }

    private fun stop() = post(stopSessions)

    private fun readRetrying(): SyncReply = retrying { link.read(key) }

    private fun retrying(call: () -> SyncReply): SyncReply {
        repeat(BUSY_TRIES - 1) {
            val reply = call()
            if (reply.result != "busy") return reply
        }
        val reply = call()
        if (reply.result == "busy") throw SyncFailure("another machine kept the project locked on $target for too long")
        return reply
    }

    private fun claim(expected: Long): SyncReply =
        link.claim(key, expected, seat, host, root.toString(), head(), dirty())

    // ---- first contact -------------------------------------------------------------------

    private fun begin() {
        val cached = files.cachedLease()
        val reply = try {
            readRetrying()
        } catch (e: SyncFailure) {
            beginOffline(cached, e.message)
            return
        }
        val lease = reply.lease
        when {
            lease == null && (cached == null || cached.seat == seat) -> {
                val claimed = retrying { claim(0) }
                val now = claimed.lease
                if (claimed.ok && now != null) becomeOwner(now, claimed) else becomeViewer(claimed)
            }
            lease == null -> becomeViewer(reply, shown = cached)
            lease.seat == seat -> becomeOwner(lease, reply)
            else -> becomeViewer(reply)
        }
    }

    /**
     * The remote cannot be reached: go on from where this machine last stood. A project it has never
     * synced, or held when it last heard, is held, and claimed once the remote answers; one another
     * machine held stays that machine's.
     */
    private fun beginOffline(cached: SyncLease?, error: String?) {
        Log.warn("sync: could not reach $target for ${root.fileName}: $error")
        when {
            cached == null -> {
                pendingClaim = 0
                owning = true
                update { it.copy(role = SyncRole.Owner, error = error, pending = true) }
            }
            cached.seat == seat -> {
                gen = cached.gen
                owning = true
                update { it.copy(role = SyncRole.Owner, lease = cached, error = error) }
            }
            else -> {
                owning = false
                update {
                    it.copy(role = SyncRole.Viewer, lease = cached, error = error, rows = shownRows(cached), pulled = it.pulled + 1)
                }
                stop()
            }
        }
    }

    // ---- holder ------------------------------------------------------------------------------

    private fun becomeOwner(lease: SyncLease, reply: SyncReply) {
        gen = lease.gen
        pendingClaim = null
        contacted = true
        owning = true
        files.cacheLease(lease)
        update {
            it.copy(
                role = SyncRole.Owner, lease = lease, age = reply.age, checked = Instant.now(),
                error = null, takenOverBy = null, pending = false,
            )
        }
        send(reply)
    }

    private fun ownerTick() {
        val claim = pendingClaim
        val reply = try {
            if (claim != null) claim(claim) else link.beat(key, gen, seat, head(), dirty())
        } catch (e: SyncFailure) {
            contacted = false
            update { it.copy(error = e.message) }
            return
        }
        ownerReply(reply, reclaim = true)
    }

    private fun ownerReply(reply: SyncReply, reclaim: Boolean) {
        val lease = reply.lease
        when {
            reply.result == "busy" -> return
            lease == null && reclaim -> {
                // The remote has lost its record of the project (a new folder, or one cleared): it is
                // still this machine's, so it is claimed again rather than given up.
                Log.warn("sync: $target has no lease for ${root.fileName}; claiming it again")
                val again = try {
                    claim(0)
                } catch (e: SyncFailure) {
                    contacted = false
                    update { it.copy(error = e.message) }
                    return
                }
                ownerReply(again, reclaim = false)
            }
            lease == null -> update { it.copy(error = "$target did not keep the project's lease") }
            reply.ok || lease.seat == seat -> becomeOwner(lease, reply)
            else -> deposed(reply)
        }
    }

    /** Sends the tabs when they have changed since they were last sent, or the remote has none of this machine's. */
    private fun send(reply: SyncReply) {
        val print = files.stage()
        if (print == pushedPrint && reply.pushed) return
        try {
            link.push(key, seat, files.outDir)
            pushedPrint = print
        } catch (e: SyncFailure) {
            update { it.copy(error = e.message) }
        }
    }

    /** Another machine has taken the project: this one's agents stop and it shows the other's. */
    private fun deposed(reply: SyncReply) {
        val lease = reply.lease ?: return
        Log.info("sync: ${lease.host} has taken over ${root.fileName}; stopping its agents here")
        owning = false
        contacted = false
        pendingClaim = null
        files.cacheLease(lease)
        update {
            it.copy(
                role = SyncRole.Viewer, lease = lease, age = reply.age, checked = Instant.now(),
                error = null, takenOverBy = lease.host.ifEmpty { "another machine" }, pending = false,
            )
        }
        stop()
        fetch(reply)
        showFetched(reply)
    }

    // ---- viewer ------------------------------------------------------------------------------

    private fun becomeViewer(reply: SyncReply, shown: SyncLease? = reply.lease) {
        owning = false
        contacted = false
        shown?.let(files::cacheLease)
        update { it.copy(role = SyncRole.Viewer, lease = shown, age = reply.age, checked = Instant.now(), error = null) }
        stop()
        if (reply.lease != null) fetch(reply)
        showFetched(reply, shown)
    }

    private fun viewerTick() {
        val reply = try {
            link.read(key)
        } catch (e: SyncFailure) {
            update { it.copy(error = e.message) }
            return
        }
        if (reply.result == "busy") return
        val lease = reply.lease
        if (lease != null && lease.seat == seat) {
            // Only this machine claims as itself: a takeover whose answer never arrived.
            becomeOwner(lease, reply)
            return
        }
        lease?.let(files::cacheLease)
        update { it.copy(lease = lease ?: it.lease, age = reply.age, checked = Instant.now(), error = null) }
        fetch(reply)
        showFetched(reply)
    }

    /** Fetches the holder's tabs, when it has sent any. */
    private fun fetch(reply: SyncReply) {
        val lease = reply.lease ?: return
        if (!reply.pushed || lease.seat == seat) return
        try {
            pull(lease.seat)
        } catch (e: SyncFailure) {
            update { it.copy(error = e.message) }
        }
    }

    /**
     * Fetches [holder]'s copy. A fetch from another machine than the last replaces what is here, and
     * until it has all come what is here is nobody's. However the fetch ends, what it brought is
     * cleared of anything but plain files before anything reads it.
     */
    private fun pull(holder: String) {
        if (files.pulledSeat() != holder) files.setPulledSeat("")
        try {
            link.pull(key, holder, files.inDir)
        } finally {
            files.dropPulledLinks()
        }
        files.setPulledSeat(holder)
    }

    private fun showFetched(reply: SyncReply, shown: SyncLease? = reply.lease) {
        val print = files.pulledPrint() + "\n" + files.pulledSeat()
        if (print == lastPulledPrint && state.rows.isNotEmpty()) return
        lastPulledPrint = print
        val rows = shownRows(shown ?: reply.lease)
        update { it.copy(rows = rows, pulled = it.pulled + 1) }
    }

    /** The tabs fetched from [lease]'s holder, or none when what is here came from another machine. */
    private fun shownRows(lease: SyncLease?): List<Settings.OpenAgent> =
        if (lease != null && files.pulledSeat() == lease.seat) files.viewedRows() else emptyList()

    // ---- taking over -------------------------------------------------------------------------

    private fun claimFromHolder(onto: Map<String, String>) {
        val cached = files.cachedLease()
        val fresh = try {
            readRetrying()
        } catch (e: SyncFailure) {
            // Taken over without the remote: what was last fetched becomes this machine's, and the
            // claim is made when the remote answers. Another machine claiming first wins.
            Log.warn("sync: taking over ${root.fileName} without reaching $target: ${e.message}")
            pendingClaim = cached?.gen ?: 0
            val placed = if (cached != null && files.pulledSeat() == cached.seat) files.place(onto) else null
            owning = true
            update { it.copy(role = SyncRole.Owner, error = e.message, takenOverBy = null, pending = true, rows = emptyList()) }
            report(placed, null)
            return
        }
        val old = fresh.lease
        if (old != null && old.seat == seat) {
            becomeOwner(old, fresh)
            return
        }
        val reply = retrying { claim(old?.gen ?: 0) }
        val now = reply.lease
        if (!reply.ok || now == null) {
            now?.let(files::cacheLease)
            update { it.copy(lease = now ?: it.lease, error = "${now?.host?.ifEmpty { null } ?: "Another machine"} took the project over first") }
            return
        }
        var problem: String? = null
        if (old != null && fresh.pushed) {
            try {
                pull(old.seat)
            } catch (e: SyncFailure) {
                problem = "Took over with the tabs last fetched: ${e.message}"
            }
        }
        val placed = if (old != null && files.pulledSeat() == old.seat) files.place(onto) else null
        Log.info("sync: took over ${root.fileName} from ${old?.host ?: "nobody"}")
        becomeOwner(now, reply.copy(pushed = false))
        report(placed, problem)
    }

    /** Says, in [SyncView.notice], what a takeover did not bring across. */
    private fun report(placed: SyncFiles.Placed?, problem: String?) {
        if (placed != null) {
            Log.info("sync: placed ${placed.rows} tab(s) of ${root.fileName}, left out ${placed.dropped}, ${placed.failed} file(s) failed")
        }
        val said = buildList {
            problem?.let(::add)
            if (placed != null && placed.dropped > 0) add("Left behind: " + placed.reasons.joinToString("; "))
            if (placed != null && placed.failed > 0) add("${placed.failed} file(s) could not be put in place (see the log)")
        }
        update { it.copy(notice = said.joinToString(". ").ifEmpty { null }) }
    }

    /** Takes down what [report] said, once the user has read it. */
    fun dismissNotice() = update { it.copy(notice = null) }

    private companion object {
        const val BUSY_TRIES = 3
    }
}

/**
 * Each open project's [ProjectSync], or the fact that it has none, by repository root, kept for as
 * long as a window has a tab on the project — the same life as its agents in [AgentSessionStore].
 */
internal object ProjectSyncStore {
    private class Entry(val sync: ProjectSync?, val remote: SyncRemote?, val projects: MutableSet<Path>)

    private val byRoot = LinkedHashMap<Path, Entry>()

    /**
     * Counts the times the remote has been changed under open projects. A project's sync is another
     * object after each, so whatever holds one reads this to know to ask [of] again.
     */
    var revision by mutableStateOf(0)
        private set

    init {
        // Off the caller's thread: a save can come from the UI, and setting a sync up reads the repository.
        Accounts.addChangeListener { config -> thread(isDaemon = true, name = "nop-sync-remote") { reconfigure(config.sync) } }
    }

    /**
     * The sync for the repository at [root], made and started the first time a project in it asks,
     * or null when the project is not synced: there is no remote set, or the project has no origin
     * to tell its clones by. Reads files; call it off the UI thread.
     */
    @Synchronized
    fun of(root: Path, project: Path): ProjectSync? {
        val key = norm(root)
        byRoot[key]?.let {
            it.projects.add(norm(project))
            return it.sync
        }
        val remote = Accounts.load().sync
        val made = runCatching { make(key, remote) }.onFailure { Log.error("sync: could not set up $key", it) }.getOrNull()
        byRoot[key] = Entry(made, remote, mutableSetOf(norm(project)))
        made?.start()
        return made
    }

    /** The lookup already made for [root], without making one: null when no project in it has asked yet. */
    @Synchronized
    fun peek(root: Path): SyncLookup? = byRoot[norm(root)]?.let { SyncLookup(it.sync) }

    /** Whether [root]'s agents may run and its tabs be saved: always, for a project that is not synced. */
    @Synchronized
    fun owning(root: Path): Boolean = byRoot[norm(root)]?.sync?.owning ?: true

    /**
     * The user has set, changed or turned off the remote: every open project stops with the one it
     * had, at once and without a last copy to it, and starts with [remote] when there is one. A
     * project that was only viewing another machine's tabs goes back to its own.
     */
    @Synchronized
    fun reconfigure(remote: SyncRemote?) {
        val stale = byRoot.filterValues { it.remote != remote }
        if (stale.isEmpty()) return
        for ((root, entry) in stale) {
            entry.sync?.abandon()
            val made = runCatching { make(root, remote) }.onFailure { Log.error("sync: could not set up $root", it) }.getOrNull()
            byRoot[root] = Entry(made, remote, entry.projects)
            made?.start()
        }
        Log.info("sync: the remote is now ${remote?.target ?: "off"}; ${stale.size} open project(s) follow it")
        SwingUtilities.invokeLater { revision++ }
    }

    /** Stops the syncs of projects no window has a tab on any more, sending what each holder has left. */
    @Synchronized
    fun retain(projects: Collection<Path>) {
        val open = projects.map(::norm).toSet()
        val closed = byRoot.filterValues { entry -> entry.projects.none { it in open } }
        closed.keys.forEach { byRoot.remove(it) }
        val syncs = closed.values.mapNotNull { it.sync }
        if (syncs.isEmpty()) return
        thread(isDaemon = true, name = "nop-sync-close") {
            val deadline = Instant.now().plusSeconds(60)
            syncs.forEach { it.finish(deadline) }
        }
    }

    /** Stops every sync, each holder sending what it has left, for at most [limit] in all. nop exiting. */
    fun disposeAll(limit: Duration = 20.seconds) {
        val syncs = synchronized(this) {
            byRoot.values.mapNotNull { it.sync }.also { byRoot.clear() }
        }
        if (syncs.isEmpty()) return
        val deadline = Instant.now().plusMillis(limit.inWholeMilliseconds)
        val threads = syncs.map { sync -> thread(name = "nop-sync-finish") { sync.finish(deadline) } }
        threads.forEach { it.join(JavaDuration.between(Instant.now(), deadline).toMillis().coerceAtLeast(1)) }
    }

    private fun make(root: Path, remote: SyncRemote?): ProjectSync? {
        if (remote == null) return null
        SyncLink.problem(remote)?.let {
            Log.warn("sync is off for $root: $it")
            return null
        }
        val origin = GitRepo.discover(root)?.use { it.originUrl() }
        if (origin == null) {
            Log.info("sync: $root has no origin remote, so its agents stay on this machine")
            return null
        }
        val dataRoot = Accounts.dataRoot()
        val controlDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "nop-ssh") }
            ?: dataRoot.resolve("ssh")
        val head = { runCatching { GitRepo.discover(root)?.use { it.headSha() } }.getOrNull().orEmpty() }
        val dirty = DirtyCheck(root, head)
        return ProjectSync(
            root = root,
            link = SyncLink(remote, controlDir),
            key = SyncIdentity.projectKey(origin, root, dataRoot),
            seat = SyncIdentity.seat(root, dataRoot),
            host = SyncIdentity.hostName(),
            files = SyncFiles(root, dataRoot, remote = remote.target + "\n" + remote.base),
            target = remote.target,
            head = head,
            dirty = dirty::get,
            stopSessions = { AgentSessionStore.byRoot().firstOrNull { it.first == root }?.third?.disposeAll() },
        )
    }

    /** Whether the checkout has uncommitted changes, looked at again when HEAD moves or every two minutes. */
    private class DirtyCheck(private val root: Path, private val head: () -> String) {
        private var at = 0L
        private var seenHead: String? = null
        private var value = false

        @Synchronized
        fun get(): Boolean {
            val h = head()
            val now = System.currentTimeMillis()
            if (h != seenHead || now - at > 120_000) {
                value = runCatching { GitRepo.discover(root)?.use { it.hasChanges() } ?: false }.getOrDefault(false)
                seenHead = h
                at = now
            }
            return value
        }
    }

    private fun norm(path: Path): Path = path.toAbsolutePath().normalize()
}
