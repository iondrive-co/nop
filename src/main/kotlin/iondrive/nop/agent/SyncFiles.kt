package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.agent.transcript.ClaudeTailer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Who this machine, and a project on it, are to the other machines that share its agents. */
internal object SyncIdentity {

    /**
     * nop's own id for this machine, made the first time it is asked for and kept beside the rest of
     * nop's data. The system's machine id goes into [seat] as well, so a data folder copied onto
     * another machine does not pass that machine off as this one.
     */
    fun machineId(dataRoot: Path = Accounts.dataRoot()): String {
        val file = dataRoot.resolve("sync-machine-id")
        runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val made = UUID.randomUUID().toString()
        runCatching {
            OwnerOnly.directory(dataRoot)
            writeAtomically(file, made + "\n")
        }.onFailure { Log.warn("could not keep the sync machine id in $file: $it") }
        return made
    }

    /**
     * The seat [root] holds on this machine: the same checkout on the same machine is always the same
     * seat, and anything else is another one.
     */
    fun seat(root: Path, dataRoot: Path = Accounts.dataRoot(), systemId: String = systemMachineId()): String =
        sha1(systemId + "\n" + machineId(dataRoot) + "\n" + root.toAbsolutePath().normalize()).take(16)

    /**
     * The key a project's agents are kept under on the remote: every clone of one origin shares it.
     *
     * An origin that is a path on this machine names a repository only here, where the same path on
     * another machine may be any repository at all, so it is keyed with this machine's id and found
     * from [root] when it is relative: only checkouts on this machine share it.
     */
    fun projectKey(origin: String, root: Path? = null, dataRoot: Path = Accounts.dataRoot()): String {
        val named = normalizeOrigin(origin)
        if (!isLocal(origin)) return sha1(named).take(16)
        val path = runCatching { (root?.toAbsolutePath()?.resolve(named) ?: Path.of(named)).normalize() }.getOrNull()
        val real = path?.let { runCatching { it.toRealPath() }.getOrDefault(it) }
        return sha1("local\n" + machineId(dataRoot) + "\n" + (real ?: named)).take(16)
    }

    /** Whether [origin] is a path on this machine rather than a repository on a host. */
    fun isLocal(origin: String): Boolean = split(origin).first.isNullOrEmpty()

    /**
     * [url] with what differs between clones of one repository taken out: the scheme, the user, the
     * scheme's own port, a trailing `.git` or `/`, and the case of the host. `git@host:Org/Repo.git`,
     * `https://host/Org/Repo` and `ssh://git@host:22/Org/Repo.git` all come out as `host/Org/Repo`.
     * Any other port stays, since it is another service on that host with repositories of its own.
     * A local path is kept as it is, less the same trailing parts.
     */
    fun normalizeOrigin(url: String): String {
        val (host, path) = split(url)
        val trimmed = trimRepository(if (host == null) path else path.trimStart('/'))
        return if (host.isNullOrEmpty()) trimmed else "$host/$trimmed"
    }

    /** The host of [url], lower-cased and with any port that is not its scheme's own, and the path; a null host is a bare path. */
    private fun split(url: String): Pair<String?, String> {
        val s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme < 0) return SCP.matchEntire(s)?.let { it.groupValues[1].lowercase() to it.groupValues[2] } ?: (null to s)
        val rest = s.substring(scheme + 3)
        val slash = rest.indexOf('/').takeIf { it >= 0 } ?: rest.length
        val authority = rest.substring(0, slash).substringAfterLast('@')
        val port = PORT.find(authority)?.groupValues?.get(1).orEmpty()
        val name = authority.replace(PORT, "").lowercase()
        val own = DEFAULT_PORTS[s.substring(0, scheme).lowercase()]
        val host = if (port.isEmpty() || port.toIntOrNull() == own) name else "$name:$port"
        return host to rest.substring(slash)
    }

    /** The name this machine goes by, as other machines are shown it. */
    fun hostName(): String {
        val named = runCatching { Files.readString(Path.of("/etc/hostname")) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("HOSTNAME")?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown"
        return named.replace(Regex("[^A-Za-z0-9._-]"), "-").take(64)
    }

    private fun systemMachineId(): String =
        runCatching { Files.readString(Path.of("/etc/machine-id")).trim() }.getOrDefault("")

    private fun trimRepository(path: String): String {
        var p = path.trimEnd('/')
        if (p.endsWith(".git")) p = p.removeSuffix(".git").trimEnd('/')
        return p
    }

    /** `[user@]host:path`, which git takes as ssh; a host never holds a `/`, which is how a path is told apart. */
    private val SCP = Regex("(?:[^@/:]+@)?([^@/:]+):(.+)")
    private val PORT = Regex(":(\\d*)$")
    private val DEFAULT_PORTS = mapOf("ssh" to 22, "git+ssh" to 22, "ssh+git" to 22, "git" to 9418, "http" to 80, "https" to 443)
}

/**
 * The files a project's agent tabs live in, gathered to be sent to the remote and put back in place
 * when they come from it.
 *
 * What goes is the tab strip, each tab's own log, inbox and handoffs, and the conversation its CLI
 * keeps — never an account's credentials or a live database's journals. [stage] copies them into one
 * folder for the copy to read: regular files only, named one by one, so a link among them never
 * takes anything else along, and nop's own logs without the tokens a launch command can hold.
 *
 * What comes back is another machine's word, not this one's. [place] takes from it only the files
 * that belong to each tab's own conversation, never a link, never over a conversation this project
 * does not already own, and nothing that would have an agent act without the user: a prompt waiting
 * to be sent is left behind and a message waiting in an inbox goes back to waiting for Deliver.
 * Copies go through [Backup.mirrorFile], so a log that has only grown takes only its new bytes and
 * anything replaced is kept in [asideDir].
 *
 * [remote] names where the copies are kept: what was fetched from one remote says nothing about
 * another, so a change of remote forgets it.
 */
internal class SyncFiles(
    val root: Path,
    private val dataRoot: Path = Accounts.dataRoot(),
    private val accounts: () -> List<Account> = { Accounts.load().accounts },
    private val ownConversation: (String, String) -> String? = { sid, saved -> EventLog.ownConversation(sid, saved) },
    remote: String = "",
) {
    private val dataDir: Path = Settings.projectDataDir(root)
    private val syncDir: Path = dataDir.resolve("sync")

    /** Where [stage] gathers what is sent. */
    val outDir: Path = syncDir.resolve("out")

    /** Where what came from another machine is kept, as it came. */
    val inDir: Path = syncDir.resolve("in")

    /** Where the files [place] replaced are kept. */
    val asideDir: Path = syncDir.resolve("aside")

    private val leaseFile = syncDir.resolve("lease")
    private val pulledFile = syncDir.resolve("pulled")
    private val remoteFile = syncDir.resolve("remote")
    private val placedFile = syncDir.resolve("placed")

    private data class Seen(val saved: String, val logSize: Long, val own: String?)

    /**
     * A file as [stage] last copied it: the source's size and time then, how far into it the copy
     * has read and how long that made the copy, and the source's last bytes before that point, which
     * is what tells a log that has grown from another file under its name.
     */
    private class Staged(val size: Long, val modified: Long, val read: Long, val wrote: Long, val tail: ByteArray)

    private val conversations = ConcurrentHashMap<String, Seen>()
    private val rollouts = ConcurrentHashMap<Pair<String, String>, Path>()
    private val staged = ConcurrentHashMap<Path, Staged>()

    init {
        val noted = runCatching { Files.readString(remoteFile) }.getOrNull().orEmpty()
        if (noted != remote) {
            runCatching {
                Files.deleteIfExists(leaseFile)
                Files.deleteIfExists(pulledFile)
                clear(inDir)
                writeAtomically(remoteFile, remote)
            }.onFailure { Log.warn("sync: could not forget what $syncDir held of another remote: $it") }
        }
    }

    /**
     * What [place] did: the tabs put in place, the ones left out, the files that could not be placed,
     * and why each tab left out was, for the user.
     */
    data class Placed(val rows: Int, val dropped: Int, val failed: Int, val reasons: List<String> = emptyList())

    /**
     * Copies every file the open tabs need into [outDir], leaving nothing else there, and returns a
     * fingerprint of their names, sizes and times: the same fingerprint means nothing needs sending.
     * A file that has not changed is not copied again, and a log that has grown takes its new lines.
     */
    fun stage(): String {
        OwnerOnly.directory(outDir)
        dropLinks(outDir)
        val wanted = HashSet<Path>()
        fun put(at: Path, source: Path, redact: Boolean = false) {
            if (stageFile(source, at, redact)) wanted.add(at)
        }
        fun putTree(at: Path, source: Path) {
            for (file in regularFiles(source)) put(at.resolve(source.relativize(file).toString()), file)
        }
        val agentsFile = dataDir.resolve("agents")
        put(outDir.resolve("agents"), agentsFile)
        val local = accounts()
        for (row in Settings.readOpenAgents(agentsFile)) {
            val sid = row.sessionId
            if (!ID.matches(sid)) continue
            val log = dataRoot.resolve("sessions").resolve("$sid.jsonl")
            put(outDir.resolve("logs").resolve("$sid.jsonl"), log, redact = true)
            put(outDir.resolve("inbox").resolve("$sid.json"), dataRoot.resolve("inboxes").resolve("$sid.json"))
            putTree(outDir.resolve("handoffs").resolve(sid), dataRoot.resolve("handoffs").resolve(sid))
            val saved = row.nativeSessionId
            if (saved.isEmpty()) continue
            val account = local.firstOrNull { it.name == row.account } ?: continue
            val ids = listOfNotNull(saved, conversation(sid, saved, log)).filter { ID.matches(it) }.distinct()
            val native = outDir.resolve("native").resolve(sid)
            val home = account.homePath
            for (id in ids) {
                when {
                    account.provider.usesClaude -> {
                        val transcript = Transcripts.find(account.provider, home, root.toFile(), id) ?: continue
                        put(native.resolve("claude").resolve("$id.jsonl"), transcript)
                        putTree(native.resolve("claude").resolve(id), transcript.resolveSibling(id))
                    }
                    account.provider == Provider.OpenAI -> {
                        val sessions = home.resolve(".codex").resolve("sessions")
                        val rollout = rollout(sid, id, account) ?: continue
                        if (!rollout.startsWith(sessions)) continue
                        put(native.resolve("codex").resolve(sessions.relativize(rollout).toString()), rollout)
                    }
                    account.provider == Provider.Antigravity -> {
                        val cli = Antigravity.cliDir(home)
                        put(native.resolve("antigravity/conversations").resolve("$id.db"), cli.resolve("conversations").resolve("$id.db"))
                        putTree(native.resolve("antigravity/brain").resolve(id), cli.resolve("brain").resolve(id))
                    }
                }
            }
        }
        prune(wanted)
        return fingerprint(outDir)
    }

    /** A fingerprint of what the last pull brought, by the same measure as [stage]'s. */
    fun pulledPrint(): String = fingerprint(inDir)

    /** Removes from what a pull brought everything that is not a plain file or folder; returns how many. */
    fun dropPulledLinks(): Int = dropLinks(inDir).also {
        if (it > 0) Log.warn("sync: removed $it link(s) or special file(s) the remote sent for ${root.fileName}")
    }

    /**
     * Puts what came from another machine in place on this one and makes its tabs this project's
     * tabs. A row runs on the local account of its own name and kind, or on the one [onto] gives for
     * it under [accountKey]; a row with neither is left out, since which account's quota a
     * conversation spends is the user's to say.
     */
    fun place(onto: Map<String, String> = emptyMap(), now: Instant = Instant.now()): Placed {
        // A log put in place below is another file under the same name, not the one staged grown.
        staged.clear()
        val local = accounts()
        val placedBefore = placed()
        val mapped = ArrayList<Settings.OpenAgent>()
        val reasons = ArrayList<String>()
        var failed = 0
        for (row in viewedRows()) {
            val sid = row.sessionId
            val name = row.title.ifBlank { "Untitled" }.take(40)
            fun leaveOut(why: String) {
                Log.warn("sync: tab $sid ($name) left out: $why")
                reasons += "$name: $why"
            }
            val provider = Provider.byId(row.provider)
            val account = provider?.let { p ->
                local.firstOrNull { it.name == row.account && it.provider == p }
                    ?: onto[accountKey(row)]?.let { chosen -> local.firstOrNull { it.name == chosen && it.provider == p } }
            }
            val incomingLog = inDir.resolve("logs").resolve("$sid.jsonl")
            val localLog = dataRoot.resolve("sessions").resolve("$sid.jsonl")
            val incoming = if (ID.matches(sid)) runIds(incomingLog) else null
            when {
                !ID.matches(sid) -> leaveOut("its id is not one nop gives a tab")
                row.nativeSessionId.isEmpty() -> leaveOut("it had not started its conversation, and a prompt is not sent on another machine's word")
                !ID.matches(row.nativeSessionId) || incoming == null -> leaveOut("it names a conversation id no CLI gives")
                account == null -> leaveOut("no account here was chosen to run ${row.account}'s tabs on")
                regular(localLog) != null && sid !in placedBefore && projectOf(localLog) != root.toAbsolutePath().normalize().toString() ->
                    leaveOut("a tab with its id belongs to another project on this machine")
                else -> {
                    // What this machine's own record of the tab says it has been in: those are the
                    // conversations the incoming copy may replace, where any other may only be added to.
                    val known = runIds(localLog).orEmpty()
                    failed += copyFile(incomingLog, localLog, asideDir.resolve("logs"), now)
                    failed += holdInbox(sid, now)
                    failed += copyTree(inDir.resolve("handoffs").resolve(sid), dataRoot.resolve("handoffs").resolve(sid), asideDir.resolve("handoffs").resolve(sid), now)
                    failed += copyNative(sid, account, incoming + row.nativeSessionId, known, now)
                    mapped += row.copy(
                        account = account.name,
                        provider = account.provider.id,
                        authorizedModel = row.authorizedModel.takeIf { MODEL.matches(it) }.orEmpty(),
                        authorizedReasoning = row.authorizedReasoning.takeIf { MODEL.matches(it) }.orEmpty(),
                        pendingStart = null,
                    )
                }
            }
        }
        runCatching { writeAtomically(placedFile, (placedBefore + mapped.map { it.sessionId }).joinToString("\n")) }
            .onFailure { Log.warn("sync: could not note the tabs placed in $placedFile: $it") }
        Settings.saveOpenAgents(root, mapped)
        return Placed(mapped.size, reasons.size, failed, reasons)
    }

    /** The tabs another machine sent, as it sent them. */
    fun viewedRows(): List<Settings.OpenAgent> = Settings.readOpenAgents(inDir.resolve("agents"))

    /**
     * The accounts the fetched tabs ran on that this machine has none of by that name and kind, as
     * [accountKey] to what to call each: the ones [place] needs an account chosen for.
     */
    fun unmatchedAccounts(rows: List<Settings.OpenAgent> = viewedRows()): Map<String, Settings.OpenAgent> {
        val local = accounts()
        return rows.filter { row -> local.none { it.name == row.account && it.provider.id == row.provider } }
            .associateBy(::accountKey)
    }

    /** The local accounts a tab that ran on [row]'s account could run on instead: the ones of its kind. */
    fun accountsFor(row: Settings.OpenAgent): List<String> =
        accounts().filter { it.provider.id == row.provider }.map { it.name }

    /** The log another machine sent for the tab [sessionId], or null when it sent none. */
    fun viewedLog(sessionId: String): Path? =
        if (!ID.matches(sessionId)) null
        else inDir.resolve("logs").resolve("$sessionId.jsonl").takeIf { regular(it) != null }

    /** The lease as last seen, kept so a machine that cannot reach the remote still knows where it stood. */
    fun cachedLease(): SyncLease? = runCatching { SyncLease.parse(Files.readString(leaseFile)) }.getOrNull()

    fun cacheLease(lease: SyncLease) {
        runCatching { writeAtomically(leaseFile, lease.text()) }
            .onFailure { Log.warn("sync: could not keep the lease in $leaseFile: $it") }
    }

    /** The seat whose files [inDir] holds, or null when it holds none yet. */
    fun pulledSeat(): String? = runCatching { Files.readString(pulledFile).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun setPulledSeat(seat: String) {
        runCatching { writeAtomically(pulledFile, seat + "\n") }
            .onFailure { Log.warn("sync: could not note the pulled seat in $pulledFile: $it") }
    }

    /** The tabs [place] has made this project's before: a log of one of them is this project's whatever path it names. */
    private fun placed(): Set<String> =
        runCatching { Files.readAllLines(placedFile) }.getOrDefault(emptyList()).filter { ID.matches(it) }.toSet()

    /** The conversation tab [sid] resumes, read from its log again only when the log has changed. */
    private fun conversation(sid: String, saved: String, log: Path): String? {
        val size = runCatching { Files.size(log) }.getOrDefault(-1L)
        conversations[sid]?.takeIf { it.saved == saved && it.logSize == size }?.let { return it.own }
        val own = runCatching { ownConversation(sid, saved) }.getOrNull()
        conversations[sid] = Seen(saved, size, own)
        return own
    }

    /** A Codex rollout, found once and remembered while it is still there: finding one walks every day's folder. */
    private fun rollout(sid: String, id: String, account: Account): Path? {
        val key = sid to id
        rollouts[key]?.takeIf { Files.isRegularFile(it) }?.let { return it }
        val found = Transcripts.find(account.provider, account.homePath, root.toFile(), id) ?: return null
        rollouts[key] = found
        return found
    }

    // ---- sending ---------------------------------------------------------------------------------

    /**
     * Brings the copy at [at] up to date with [source], and says whether there is a copy: none of a
     * file that is not there, is not a plain file, or is one never sent. [redact] is for nop's own
     * logs, copied a line at a time through [McpServers.redactLogLine].
     */
    private fun stageFile(source: Path, at: Path, redact: Boolean): Boolean {
        val attrs = regular(source) ?: return false
        val name = source.fileName.toString()
        if (name in Backup.CREDENTIALS || Backup.transient(name)) return false
        val size = attrs.size()
        val modified = attrs.lastModifiedTime().toMillis()
        val had = staged[at]?.takeIf { regular(at)?.size() == it.wrote }
        if (had != null && had.size == size && had.modified == modified) return true
        return try {
            val grown = had != null && name.endsWith(".jsonl") && size > had.size &&
                bytes(source, had.read - had.tail.size, had.tail.size).contentEquals(had.tail)
            val from = if (grown) had!!.read else 0L
            if (!grown) {
                Files.deleteIfExists(at)
                OwnerOnly.file(at)
            }
            val (read, wrote) = copyRegion(source, at, from, size, redact)
            Files.setLastModifiedTime(at, FileTime.fromMillis(modified))
            val end = from + read
            val span = minOf(end, TAIL_BYTES.toLong()).toInt()
            staged[at] = Staged(size, modified, end, (if (grown) had!!.wrote else 0L) + wrote, bytes(source, end - span, span))
            true
        } catch (e: IOException) {
            Log.warn("sync: could not copy $source to send: $e")
            staged.remove(at)
            runCatching { Files.deleteIfExists(at) }
            false
        }
    }

    /**
     * Adds [source]'s bytes from [from] up to [upTo] to the end of [at], and returns how many were
     * read and how many written. With [redact] only whole lines are taken, so a line still being
     * written waits for the next copy.
     */
    private fun copyRegion(source: Path, at: Path, from: Long, upTo: Long, redact: Boolean): Pair<Long, Long> {
        FileChannel.open(source, StandardOpenOption.READ).use { input ->
            FileChannel.open(at, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { output ->
                if (!redact) {
                    var pos = from
                    while (pos < upTo) {
                        val moved = input.transferTo(pos, upTo - pos, output)
                        if (moved <= 0) break
                        pos += moved
                    }
                    return (pos - from) to (pos - from)
                }
                input.position(from)
                val lines = Channels.newInputStream(input).buffered(1 shl 16)
                val out = Channels.newOutputStream(output).buffered(1 shl 16)
                val line = ByteArrayOutputStream()
                var read = 0L
                var taken = 0L
                var wrote = 0L
                while (from + read < upTo) {
                    val b = lines.read()
                    if (b < 0) break
                    read++
                    if (b != '\n'.code) {
                        line.write(b)
                        continue
                    }
                    val text = line.toString(Charsets.UTF_8)
                    val safe = McpServers.redactLogLine(text)
                    if (safe === text) {
                        line.writeTo(out)
                        wrote += line.size()
                    } else if (safe != null) {
                        val bytes = safe.toByteArray()
                        out.write(bytes)
                        wrote += bytes.size
                    }
                    if (safe != null) {
                        out.write('\n'.code)
                        wrote++
                    }
                    line.reset()
                    taken = read
                }
                out.flush()
                return taken to wrote
            }
        }
    }

    /** Removes everything under [outDir] that is not in [wanted], and the folders that leaves empty. */
    private fun prune(wanted: Set<Path>) {
        staged.keys.retainAll(wanted)
        runCatching {
            Files.walkFileTree(outDir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file !in wanted) Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    if (d != outDir && Files.newDirectoryStream(d).use { !it.iterator().hasNext() }) Files.deleteIfExists(d)
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { Log.warn("sync: could not tidy $outDir: $it") }
    }

    // ---- receiving -------------------------------------------------------------------------------

    /**
     * The conversation ids [log]'s runs name, each one an id a CLI could have given; null when one of
     * them is not, since that log is not one nop wrote. A resume hands these to a CLI as arguments.
     */
    private fun runIds(log: Path): Set<String>? = try {
        EventLog.readEvents(log, "run_started") { events ->
            val ids = HashSet<String>()
            for (run in events.filterIsInstance<AgentEvent.RunStarted>()) {
                for (id in listOfNotNull(run.nativeSessionId?.takeIf { it.isNotEmpty() }, EventLog.askedFor(run))) {
                    if (!ID.matches(id)) return@readEvents null
                    ids += id
                }
            }
            ids
        }
    } catch (_: IOException) {
        null
    }

    /** The project [log] was last in, as it names it, or null when it names none. */
    private fun projectOf(log: Path): String? = try {
        EventLog.readEvents(log, "session_started") { events ->
            events.filterIsInstance<AgentEvent.SessionStarted>().lastOrNull()?.projectPath
        }
    } catch (_: IOException) {
        null
    }

    /**
     * Puts the messages that were waiting in tab [sid]'s inbox into its inbox here, every one of them
     * waiting for the user again: that another machine's copy says Deliver was pressed is not the
     * user pressing it here. Returns 1 when that failed.
     */
    private fun holdInbox(sid: String, now: Instant): Int {
        val from = inDir.resolve("inbox").resolve("$sid.json")
        val size = regular(from)?.size() ?: return 0
        val inboxes = dataRoot.resolve("inboxes")
        return try {
            if (size > INBOX_LIMIT) throw IOException("it is $size bytes")
            val held = AgentInboxStore.load(sid, from.parent).orEmpty().map { it.copy(approved = false, attemptedAt = 0, attempts = 0) }
            val into = inboxes.resolve("$sid.json")
            if (regular(into) != null && runCatching { AgentInboxStore.load(sid, inboxes) }.getOrNull() != held) {
                val aside = OwnerOnly.directory(asideDir.resolve("inbox"))
                Files.copy(into, aside.resolve("$sid.before-${now.toEpochMilli()}.json"), StandardCopyOption.REPLACE_EXISTING)
            }
            AgentInboxStore.save(sid, held, inboxes)
            0
        } catch (e: Exception) {
            Log.warn("sync: could not place the inbox of tab $sid: $e")
            1
        }
    }

    /**
     * Puts in place the conversations [ids] that came for tab [sid], and nothing else that came with
     * them: each CLI keeps every project's conversations together, so only the files that are one of
     * these conversations by name are taken. One of [known] may be replaced; any other only added to.
     */
    private fun copyNative(sid: String, account: Account, ids: Set<String>, known: Set<String>, now: Instant): Int {
        val native = inDir.resolve("native").resolve(sid)
        val home = account.homePath
        var failed = 0
        when {
            account.provider.usesClaude -> {
                val from = native.resolve("claude")
                val into = home.resolve("projects").resolve(ClaudeTailer.slug(root))
                val aside = asideDir.resolve("claude")
                for (id in ids) {
                    failed += copyFile(from.resolve("$id.jsonl"), into.resolve("$id.jsonl"), aside, now, replace = id in known)
                    failed += copyTree(from.resolve(id), into.resolve(id), aside.resolve(id), now, replace = id in known)
                }
            }
            account.provider == Provider.OpenAI -> {
                val from = native.resolve("codex")
                val sessions = home.resolve(".codex").resolve("sessions")
                for (file in regularFiles(from)) {
                    val rel = from.relativize(file).toString()
                    val id = ids.firstOrNull { rel.endsWith("-$it.jsonl") }?.takeIf { ROLLOUT.matches(rel) }
                    if (id == null) {
                        Log.warn("sync: not placing $rel of tab $sid: it is not one of its conversations")
                        failed++
                        continue
                    }
                    // Where this machine already keeps that conversation, when it does: one id is one file.
                    val into = Transcripts.find(account.provider, home, root.toFile(), id) ?: sessions.resolve(rel)
                    failed += copyFile(file, into, asideDir.resolve("codex"), now, replace = id in known)
                }
            }
            account.provider == Provider.Antigravity -> {
                val from = native.resolve("antigravity")
                val cli = Antigravity.cliDir(home)
                val aside = asideDir.resolve("antigravity")
                for (id in ids) {
                    failed += copyFile(
                        from.resolve("conversations").resolve("$id.db"), cli.resolve("conversations").resolve("$id.db"),
                        aside.resolve("conversations"), now, replace = id in known,
                    )
                    failed += copyTree(from.resolve("brain").resolve(id), cli.resolve("brain").resolve(id), aside.resolve("brain").resolve(id), now, replace = id in known)
                }
            }
        }
        return failed
    }

    /** Every regular file under [from] copied to the same place under [into]; returns how many failed. */
    private fun copyTree(from: Path, into: Path, aside: Path, now: Instant, replace: Boolean = true): Int {
        var failed = 0
        for (file in regularFiles(from)) {
            val rel = from.relativize(file)
            val parent = rel.parent?.toString()
            failed += copyFile(file, into.resolve(rel.toString()), if (parent == null) aside else aside.resolve(parent), now, replace)
        }
        return failed
    }

    /**
     * [from] mirrored onto [into], with what it replaces kept in [aside]; 1 when it failed, else 0.
     * Without [replace] a file already at [into] is only ever added to: anything but the same log
     * further on, or the same file, is left as it is and counted as failed.
     */
    private fun copyFile(from: Path, into: Path, aside: Path, now: Instant, replace: Boolean = true): Int {
        val name = from.fileName.toString()
        if (regular(from) == null || name in Backup.CREDENTIALS || Backup.transient(name)) return 0
        return try {
            if (!replace && Files.exists(into, LinkOption.NOFOLLOW_LINKS) && !Backup.continues(from, into)) {
                Log.warn("sync: left $into as it is: what came for it is another conversation, not more of this one")
                return 1
            }
            if (name.endsWith(".db") && changes(from, into)) {
                // A journal left beside the database it replaces would be replayed into the new one.
                for (suffix in listOf("-wal", "-shm")) {
                    val journal = into.resolveSibling(name + suffix)
                    if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                        OwnerOnly.directory(aside)
                        Files.move(journal, aside.resolve("${name + suffix}.before-${now.toEpochMilli()}"))
                    }
                }
            }
            Backup.mirrorFile(from, into, aside, now)
            0
        } catch (e: Exception) {
            Log.warn("sync: could not place $into: $e")
            1
        }
    }

    /** Whether copying [from] would change [into], by the same test the copy itself makes. */
    private fun changes(from: Path, into: Path): Boolean {
        if (!Files.exists(into)) return true
        val a = Files.readAttributes(from, BasicFileAttributes::class.java)
        val b = Files.readAttributes(into, BasicFileAttributes::class.java)
        return a.size() != b.size() ||
            abs(a.lastModifiedTime().toMillis() - b.lastModifiedTime().toMillis()) > MTIME_SLACK_MS
    }

    /** Names, sizes and times of the regular files under [dir], hashed. A link is never followed. */
    private fun fingerprint(dir: Path): String {
        val lines = ArrayList<String>()
        for (file in regularFiles(dir)) {
            val attrs = regular(file) ?: continue
            lines += "${dir.relativize(file)}\t${attrs.size()}\t${attrs.lastModifiedTime().toMillis()}"
        }
        lines.sort()
        return sha1(lines.joinToString("\n"))
    }

    companion object {
        /** An id that names one file and nothing above it, and that no program takes for an option. */
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        /** A model or reasoning setting as a CLI is given one. */
        private val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._:/\\[\\]-]{0,99}")

        /** Where Codex files a rollout, under its sessions folder. */
        private val ROLLOUT = Regex("""\d{4}/\d{2}/\d{2}/rollout-[A-Za-z0-9T:._-]+\.jsonl""")

        /** How far apart two times can be and still be the same file's, as [Backup] judges it. */
        private const val MTIME_SLACK_MS = 2000L

        /** How much of a staged log's end is kept to tell its growth from its replacement. */
        private const val TAIL_BYTES = 64

        /** Larger than any inbox nop writes; one beyond it is not read. */
        private const val INBOX_LIMIT = 8L * 1024 * 1024

        /** How a tab's account is named in what [place] is told to run it on. */
        fun accountKey(row: Settings.OpenAgent): String = row.provider + "/" + row.account

        /** [file]'s attributes when it is a regular file itself, not a link to one; else null. */
        private fun regular(file: Path): BasicFileAttributes? = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).takeIf { it.isRegularFile }
        } catch (_: IOException) {
            null
        }

        /** The regular files under [dir], no link followed or counted; none when [dir] is not a folder itself. */
        private fun regularFiles(dir: Path): List<Path> {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
            val files = ArrayList<Path>()
            runCatching {
                Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName.toString()
                        if (attrs.isRegularFile && name !in Backup.CREDENTIALS && !Backup.transient(name)) files.add(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }.onFailure { Log.warn("sync: could not list $dir: $it") }
            return files
        }

        /** [span] bytes of [file] from [start], or fewer when it ends first. */
        private fun bytes(file: Path, start: Long, span: Int): ByteArray {
            if (span <= 0 || start < 0) return ByteArray(0)
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(span)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, start + buffer.position()) < 0) break
                }
                return buffer.array().copyOf(buffer.position())
            }
        }

        /** Removes every link and special file under [dir], leaving plain files and folders; returns how many. */
        internal fun dropLinks(dir: Path): Int {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return 0
            var dropped = 0
            runCatching {
                Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) {
                            Files.deleteIfExists(file)
                            dropped++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }.onFailure { Log.warn("sync: could not check $dir for links: $it") }
            return dropped
        }

        /** Removes [dir] and everything in it, links included but never what they point at. */
        internal fun clear(dir: Path) {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(d)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}

internal fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** [text] written to [file] owner-only, replacing it in one step. */
internal fun writeAtomically(file: Path, text: String) {
    val dir = file.toAbsolutePath().parent
    OwnerOnly.directory(dir)
    val temp = Files.createTempFile(dir, ".nop-sync-", ".tmp")
    try {
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}
