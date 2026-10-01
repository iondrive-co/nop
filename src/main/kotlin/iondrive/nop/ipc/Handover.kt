package iondrive.nop.ipc

import iondrive.nop.Log
import iondrive.nop.agent.ActivityTracker
import iondrive.nop.agent.AgentSocket
import iondrive.nop.agent.OwnerOnly
import iondrive.nop.terminal.Adopted
import iondrive.nop.terminal.AdoptedPty
import iondrive.nop.terminal.Posix
import iondrive.nop.terminal.PtyHandoff
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Restarting nop without stopping what runs in its terminals.
 *
 * Every agent CLI and shell nop runs is its child, under a PTY whose master only nop holds, so a nop
 * that exits takes them all with it: the master closes, the kernel hangs the terminal up, and the
 * programs get SIGHUP. Nothing about the programs needs restarting — only nop does. So a restart
 * doesn't exit. It replaces the program running in this process with the build now on disk
 * (`execve`), keeping the pid, which keeps every child a child of this process; and it marks each
 * PTY master to stay open across the exec while everything else closes. The new build finds the
 * masters already open, a manifest saying which tab each belongs to, and what each was showing, and
 * carries on reading them. A program writing while the two builds change places just waits on a full
 * PTY until the new one reads.
 *
 * Laid out in [dir] beside the agent socket (owner-only): `manifest.json`, and one `<n>.replay` per
 * terminal holding what it had drawn (see [iondrive.nop.terminal.PtyRecorder.replay]). The new build
 * reads them once and deletes them.
 *
 * What does not survive is anything nop itself held only in memory and no tab could be rebuilt from:
 * a message waiting in an inbox, a launcher run (which comes back waiting to be run, as it always
 * has), and anything else this nop had started outside a PTY.
 */
object Handover {

    /**
     * Whether this system can restart nop in place. The exec, `/proc` and the terminal ioctls it
     * relies on are Linux's, and [Posix] maps them for a 64-bit libc; anywhere else a restart starts
     * the next nop the ordinary way and every terminal ends with this one.
     */
    val supported: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Linux") && System.getProperty("os.arch").orEmpty().contains("64")

    /** One terminal's program, as written down. [replay] names its file in [dir]. */
    @Serializable
    data class PtyRecord(val fd: Int, val pid: Long, val columns: Int, val rows: Int, val replay: String)

    /** An agent tab carried across, with what its session needs to go on as though nothing happened. */
    @Serializable
    data class AgentRecord(
        val sessionId: String,
        /** The repo root its sessions are filed under, and the project tabs that lead there. */
        val root: String,
        val projects: List<String>,
        val provider: String,
        val account: String,
        val nativeSessionId: String? = null,
        val argv: List<String>,
        val isResume: Boolean,
        val seededFromHandoff: Boolean,
        /** Epoch millis the run was started — in the nop that started it, not this one. */
        val startedAt: Long,
        val userPromptSubmitted: Boolean,
        val waitedOutWallAt: Long? = null,
        val transcriptPath: String? = null,
        val transcriptOffset: Long? = null,
        val tracker: ActivityTracker.Snapshot = ActivityTracker.Snapshot(),
        val title: String,
        val titleIsUsers: Boolean,
        val baselineSha: String? = null,
        val ticket: String,
        /** Whether it was the tab its project was showing. */
        val selected: Boolean = false,
        val pty: PtyRecord? = null,
    )

    /** A Term tab carried across. */
    @Serializable
    data class ShellRecord(
        val root: String,
        val projects: List<String>,
        val dir: String,
        val title: String,
        val selected: Boolean = false,
        val pty: PtyRecord? = null,
    )

    @Serializable
    data class Manifest(
        val version: Int = VERSION,
        /** The process this was written for. A manifest any other process finds is not its own. */
        val nopPid: Long,
        val agents: List<AgentRecord> = emptyList(),
        val shells: List<ShellRecord> = emptyList(),
        /** Which of Term, Agent or Run each repo's session pane was showing, by repo root. */
        val views: Map<String, String> = emptyMap(),
        /** This process's other children at the exec, which the new build reaps as they finish. */
        val strays: List<Long> = emptyList(),
    )

    /** What this nop is handing over: each tab's record, and its live PTY. */
    class Outgoing {
        val agents = mutableListOf<Pair<AgentRecord, PtyHandoff>>()
        val shells = mutableListOf<Pair<ShellRecord, PtyHandoff>>()
        val views = mutableMapOf<String, String>()
        val isEmpty: Boolean get() = agents.isEmpty() && shells.isEmpty()
        val fds: List<Int> get() = agents.map { it.second.fd } + shells.map { it.second.fd }
        val pids: Set<Long> get() = (agents.map { it.second.pid } + shells.map { it.second.pid }).toSet()
    }

    /** What the last nop handed this one, ready to be put back into tabs. */
    class Incoming(
        val agents: MutableList<Pair<AgentRecord, Adopted>>,
        val shells: MutableList<Pair<ShellRecord, Adopted>>,
        val views: Map<String, String>,
        val strays: List<Long>,
    )

    /**
     * Set once by [adopt], early in a start that [exec] began, and drained as each tab claims its
     * terminal. What is left once the windows are up has no tab to go to; see [hangUpUnclaimed].
     */
    @Volatile
    var incoming: Incoming? = null
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The directory this process's handover is written to. */
    fun dir(pid: Long = ProcessHandle.current().pid()): Path? =
        AgentSocket.socketDir()?.resolve("handover-$pid")

    /**
     * Writes [out] down for the next build, and returns the manifest's path. Throws when it cannot,
     * in which case there is no handover to make.
     */
    fun write(out: Outgoing, strays: List<Long> = emptyList()): Path {
        val dir = dir() ?: error("no private directory to write the handover to")
        clear(dir)
        OwnerOnly.directory(dir)
        check(AgentSocket.isPrivate(dir)) { "$dir is not a private directory of this user's" }
        var n = 0
        fun store(h: PtyHandoff): PtyRecord {
            val name = "${n++}.replay"
            OwnerOnly.file(dir.resolve(name))
            Files.write(dir.resolve(name), h.replay)
            return PtyRecord(fd = h.fd, pid = h.pid, columns = h.columns, rows = h.rows, replay = name)
        }
        val manifest = Manifest(
            nopPid = ProcessHandle.current().pid(),
            agents = out.agents.map { (rec, h) -> rec.copy(pty = store(h)) },
            shells = out.shells.map { (rec, h) -> rec.copy(pty = store(h)) },
            views = out.views,
            strays = strays,
        )
        val path = dir.resolve(MANIFEST)
        OwnerOnly.file(path)
        Files.writeString(path, json.encodeToString(Manifest.serializer(), manifest))
        return path
    }

    /**
     * Replaces this process with [launcher], passing it [manifest], with [fds] kept open across the
     * exec and everything else closed. Returns only if the exec failed, with the errno; this process
     * is then still the old nop, every descriptor still open, and the caller restarts it the ordinary
     * way.
     */
    fun exec(launcher: Path, manifest: Path, fds: Collection<Int>, env: Map<String, String>): Int {
        Posix.keepOnlyAcrossExec(fds)
        val errno = Posix.exec(launcher.toString(), listOf(launcher.toString(), ADOPT_FLAG, manifest.toString()), env)
        Log.error("restart: exec of $launcher failed with errno $errno", null)
        return errno
    }

    /**
     * Takes up what the nop before this one in this process left at [manifest], and sets [incoming].
     *
     * Each terminal is checked before it is believed: its descriptor has to be open here and be a
     * PTY master, and its pid has to be a child of this process — which is what an exec preserves and
     * nothing else could arrange. One that fails is dropped, and its descriptor (if it is one) closed.
     * The manifest and the replays are deleted once read, whatever came of them.
     *
     * Only from the one place this process's handover is written ([dir]): a manifest anywhere else is
     * refused and left exactly as it is, along with everything beside it, since the flag that names it
     * is on a command line anyone can type.
     *
     * True when the manifest was this process's own — when this process really is the nop before,
     * replaced in place — whatever came of its terminals. False otherwise, and the caller then starts
     * the way any launch does.
     */
    fun adopt(
        manifest: Path,
        /**
         * The masters that came across the exec: every one open this early, since this nop has opened
         * none of its own yet. One that ends up taken up by nothing is closed, which hangs its program
         * up as exiting would have — never left open with nobody reading it, which would stall the
         * program behind a full PTY for as long as nop runs.
         */
        inherited: List<Int> = Posix.openMasters(),
    ): Boolean {
        val taken = mutableSetOf<Int>()
        try {
            return read(manifest, taken)
        } finally {
            for (fd in inherited) if (fd !in taken) {
                Log.warn("restart: hanging up terminal fd $fd, which nothing took up")
                Posix.close(fd)
            }
        }
    }

    private fun read(manifest: Path, taken: MutableSet<Int>): Boolean {
        val me = ProcessHandle.current().pid()
        val dir = dir(me)
        val expected = dir?.resolve(MANIFEST)?.toAbsolutePath()?.normalize()
        if (dir == null || manifest.toAbsolutePath().normalize() != expected || !AgentSocket.isPrivate(dir)) {
            Log.warn("restart: not taking up $manifest: this nop writes its handover to $expected, and only reads it from there")
            return false
        }
        val read = runCatching { json.decodeFromString(Manifest.serializer(), Files.readString(manifest)) }
            .onFailure { Log.warn("restart: could not read the handover at $manifest: $it") }
            .getOrNull()
        if (read == null || read.nopPid != me || read.version != VERSION) {
            if (read != null) Log.warn("restart: the handover at $manifest is not this process's (pid ${read.nopPid}, version ${read.version})")
            clear(dir)
            return false
        }
        fun take(rec: PtyRecord?, what: String): Adopted? {
            rec ?: return null
            val replay = rec.replay.takeIf { REPLAY.matches(it) }
                ?.let { runCatching { Files.readAllBytes(dir.resolve(it)) }.getOrNull() }
                ?: ByteArray(0)
            val isMaster = Posix.isMaster(rec.fd)
            val isChild = Posix.parentOf(rec.pid) == me
            if (!isMaster || !isChild || rec.fd in taken) {
                Log.warn("restart: not taking up $what: fd ${rec.fd} is ${Posix.describe(rec.fd)}, pid ${rec.pid} ${if (isChild) "is" else "is not"} a child")
                return null
            }
            taken += rec.fd
            return Adopted(AdoptedPty(rec.fd, rec.pid), rec.columns, rec.rows, replay)
        }
        val agents = read.agents.mapNotNull { r -> take(r.pty, "agent tab ${r.title}")?.let { r to it } }
        val shells = read.shells.mapNotNull { r -> take(r.pty, "terminal in ${r.dir}")?.let { r to it } }
        clear(dir)
        Log.info("restart: took up ${agents.size} agent tab(s) and ${shells.size} terminal(s) from the nop before")
        incoming = Incoming(agents.toMutableList(), shells.toMutableList(), read.views, read.strays)
        return true
    }

    /** The agent tab [sessionId]'s terminal, if the last nop handed it over; each is given out once. */
    @Synchronized
    fun claimAgent(sessionId: String): Pair<AgentRecord, Adopted>? {
        val list = incoming?.agents ?: return null
        val i = list.indexOfFirst { it.first.sessionId == sessionId }
        return if (i < 0) null else list.removeAt(i)
    }

    /** The Term tabs handed over for the repo at [root], in strip order; each is given out once. */
    @Synchronized
    fun claimShells(root: Path): List<Pair<ShellRecord, Adopted>> {
        val list = incoming?.shells ?: return emptyList()
        val key = root.toAbsolutePath().normalize().toString()
        val mine = list.filter { it.first.root == key }
        list.removeAll(mine)
        return mine
    }

    /**
     * Hangs up every terminal the last nop handed over that no tab has claimed, which is what exiting
     * would have done to it: a program nothing on screen can reach is one the user has no way back to.
     */
    @Synchronized
    fun hangUpUnclaimed() {
        val left = incoming ?: return
        incoming = null
        val all = left.agents.map { it.second to "agent tab ${it.first.title}" } + left.shells.map { it.second to "terminal in ${it.first.dir}" }
        for ((adopted, what) in all) {
            Log.info("restart: no tab took up $what (pid ${adopted.pty.pid()}); hanging it up")
            adopted.pty.outputStream.close()
        }
    }

    /**
     * Deletes what [write] puts in [dir] — the manifest and the replays, each a plain file — and then
     * [dir] itself if that has left it empty. Nothing else in it is touched, and no link is followed.
     */
    private fun clear(dir: Path) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.list(dir).use { it.toList() }.forEach { f ->
                val name = f.fileName.toString()
                if ((name == MANIFEST || REPLAY.matches(name)) && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(f)
                }
            }
            Files.deleteIfExists(dir)
        }.onFailure { Log.warn("restart: could not clear the handover at $dir: $it") }
    }

    /** Passed to the new build, with the manifest's path. Not in the usage: nobody types it. */
    const val ADOPT_FLAG = "--adopt-handover"

    private const val MANIFEST = "manifest.json"
    private val REPLAY = Regex("""\d+\.replay""")
    private const val VERSION = 1
}
