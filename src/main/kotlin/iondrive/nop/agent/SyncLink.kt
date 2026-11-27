package iondrive.nop.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Who holds a project, as the remote keeps it: which machine ([seat], shown as [host] with its
 * checkout at [root]), on which commit, and when it last checked in ([at], in the remote's clock).
 *
 * [gen] counts the times the project has changed hands. A claim names the generation it saw, and
 * the remote turns it down when somebody else claimed in between, so two machines taking over at
 * once cannot both win. A holder's check-in names it too, and is turned down once the project has
 * been taken: that is how a machine learns it was taken over without having given anything up.
 */
internal data class SyncLease(
    val gen: Long,
    val seat: String,
    val host: String,
    val root: String,
    val head: String,
    val dirty: Boolean,
    val at: Long,
) {
    /** The lease in the `name=value` lines the remote writes, which is also how it is kept locally. */
    fun text(): String = "gen=$gen\nseat=$seat\nhost=$host\nroot=$root\nhead=$head\ndirty=${if (dirty) 1 else 0}\nat=$at\n"

    companion object {
        fun parse(fields: Map<String, String>): SyncLease? {
            val gen = fields["gen"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val seat = fields["seat"]?.takeIf { it.isNotBlank() } ?: return null
            return SyncLease(
                gen = gen,
                seat = seat,
                host = fields["host"].orEmpty(),
                root = fields["root"].orEmpty(),
                head = fields["head"].orEmpty(),
                dirty = fields["dirty"] == "1",
                at = fields["at"]?.toLongOrNull() ?: 0,
            )
        }

        fun parse(text: String): SyncLease? = parse(fields(text))

        /** The first value given for each name in [text]'s `name=value` lines. */
        fun fields(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) out.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trimEnd('\r'))
            }
            return out
        }
    }
}

/**
 * What the remote answered: [result] is `ok`, `lost` (somebody else holds the project), or `busy`
 * (another machine had the project's lock for too long to wait). [now] is the remote's clock, so a
 * lease's age is worked out in one clock. [pushed] says the holder has sent its tabs at least once.
 */
internal data class SyncReply(val result: String, val lease: SyncLease?, val now: Long?, val pushed: Boolean) {
    val ok: Boolean get() = result == "ok"

    /** How long ago the holder checked in, in seconds, or null when the remote did not say. */
    val age: Long? get() = lease?.let { l -> now?.let { (it - l.at).coerceAtLeast(0) } }
}

/** A sync that could not be done, with a message written for the user. */
internal class SyncFailure(message: String) : Exception(message)

/**
 * The output of a program that was run to the end, or killed: at its time limit ([exit] -1), or for
 * printing more than it is given room for ([exit] -2).
 */
internal data class Ran(val exit: Int, val out: String, val err: String)

/**
 * The remote that keeps each project's agent tabs, reached over ssh: a lease saying which machine
 * holds the project, and one folder per machine with that machine's copy of its tabs, logs and
 * conversations.
 *
 * Everything is under `<base>/projects/<key>/` in the remote user's home: `lease`, `lock`, and
 * `machines/<seat>/`. Every change to the lease is made by a short script run under `flock` on
 * `lock`, and so is every copy in or out, so a reader never sees a lease being written or a folder
 * half sent. The remote needs nothing but `sh`, `flock` and `rsync`.
 *
 * ssh is run in batch mode, so a key that needs a passphrase must be in an agent, and with strict
 * host key checking whatever the user's ssh config says, so the remote's host key must already be
 * known: nop never accepts a host key on the user's behalf. [controlDir] is where one shared
 * connection per remote is kept between calls; null opens a new one each time.
 *
 * Nothing the remote sends back is taken on trust: only plain files and folders are copied either
 * way, and an answer longer than any real one ends the call.
 */
internal class SyncLink(private val remote: SyncRemote, private val controlDir: Path?) {

    init {
        problem(remote)?.let { throw SyncFailure(it) }
    }

    private val ssh: String = remote.ssh?.takeIf { it.isNotBlank() } ?: "ssh"

    @Volatile
    private var closed = false
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()

    /**
     * Ends whatever this link is running and has every later call fail: for a remote the user has
     * turned off or changed, which must be sent nothing more.
     */
    fun close() {
        closed = true
        running.forEach { it.destroyForcibly() }
    }

    private fun run(argv: List<String>, stdin: String?, timeout: Duration): Ran {
        if (closed) throw SyncFailure("syncing with ${remote.target} was stopped")
        val ran = run(argv, stdin, timeout, onStart = {
            running += it
            if (closed) it.destroyForcibly()
        }, onEnd = { running -= it })
        if (closed) throw SyncFailure("syncing with ${remote.target} was stopped")
        return ran
    }

    /** A check that the remote can be reached and has what the sync needs. */
    fun ping(): SyncReply = call("ping", "0")

    fun read(key: String): SyncReply = call("read", key)

    /** Takes [key] for [seat], provided the lease is still at generation [expected] (0: none yet). */
    fun claim(key: String, expected: Long, seat: String, host: String, root: String, head: String, dirty: Boolean): SyncReply =
        call("claim", key, expected.toString(), seat, host, root, head, if (dirty) "1" else "0")

    /** [seat] checking in on [key], which it holds at generation [gen]. */
    fun beat(key: String, gen: Long, seat: String, head: String, dirty: Boolean): SyncReply =
        call("beat", key, gen.toString(), seat, head, if (dirty) "1" else "0")

    /**
     * Sends the plain files in the tree at [from] as [seat]'s copy of [key]. Only after a claim or a
     * check-in has made the project's folders.
     */
    fun push(key: String, seat: String, from: Path, timeout: Duration = COPY_TIMEOUT) {
        require(HEX.matches(seat))
        rsync(listOf("$from/", "${remote.target}:${projectDir(key)}/machines/$seat/"), key, timeout)
    }

    /**
     * Fetches [seat]'s copy of [key] into [into], which it replaces. What arrives is the remote's to
     * choose; see [SyncFiles.dropPulledLinks] for what is done about that before any of it is read.
     */
    fun pull(key: String, seat: String, into: Path, timeout: Duration = COPY_TIMEOUT) {
        require(HEX.matches(seat))
        OwnerOnly.directory(into)
        rsync(listOf("${remote.target}:${projectDir(key)}/machines/$seat/", "$into/"), key, timeout)
    }

    private fun projectDir(key: String): String {
        require(HEX.matches(key))
        return "${remote.base.trimEnd('/')}/projects/$key"
    }

    private fun sshOptions(): List<String> {
        val options = mutableListOf(
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=15",
            "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=2",
        )
        controlPath()?.let { options += listOf("-o", "ControlMaster=auto", "-o", "ControlPath=$it", "-o", "ControlPersist=120") }
        remote.identity?.takeIf { it.isNotBlank() }?.let { options += listOf("-i", expand(it), "-o", "IdentitiesOnly=yes") }
        return options
    }

    /**
     * Where the shared connection's socket goes, or null for none. ssh puts the socket at a hash of
     * the connection under this folder, and a socket's path has a hard limit not much over a hundred
     * bytes, so a folder too deep to fit is no shared connection rather than a failure.
     */
    private fun controlPath(): String? {
        val dir = controlDir ?: return null
        if (dir.toString().length + 1 + 40 + 20 > 104 || '\'' in dir.toString()) return null
        return runCatching {
            OwnerOnly.directory(dir)
            OwnerOnly.tightenDirectory(dir)
            "$dir/%C"
        }.getOrNull()
    }

    private fun call(command: String, key: String, vararg args: String): SyncReply {
        val values = listOf(remote.base.trimEnd('/'), command, key) + args.map(::clean)
        val remoteCommand = "sh -s -- " + values.joinToString(" ") { "'$it'" }
        val ran = run(listOf(ssh) + sshOptions() + listOf(remote.target, remoteCommand), SCRIPT, CALL_TIMEOUT)
        if (ran.exit != 0) throw SyncFailure(sshProblem(ran))
        val fields = SyncLease.fields(ran.out)
        return when (val result = fields["result"] ?: if (command == "read") "ok" else "unknown") {
            "ok", "lost", "busy" -> SyncReply(result, SyncLease.parse(fields), fields["now"]?.toLongOrNull(), fields["pushed"] == "1")
            "noflock" -> throw SyncFailure("${remote.target} has no flock, which the sync needs (it is in util-linux)")
            "norsync" -> throw SyncFailure("${remote.target} has no rsync, which the sync needs")
            else -> throw SyncFailure("${remote.target} gave an answer nop does not understand: ${ran.out.trim().take(200)}")
        }
    }

    private fun rsync(paths: List<String>, key: String, timeout: Duration) {
        val shell = (listOf(ssh) + sshOptions()).joinToString(" ") { "'$it'" }
        val excludes = (Backup.CREDENTIALS + Backup.TRANSIENT_PATTERNS).map { "--exclude=$it" }
        // Folders, files, their modes and times, and nothing else: no link is made or followed, and
        // no device or owner is taken from the other side. Compressed, since conversations are text
        // and shrink several times over, and capped at the remote's rate, which rsync hands to the
        // far side too, so that a pull is held to it as well as a push.
        val argv = listOf("rsync", "-rptz", "--no-links", "--delete", "--chmod=go=", "--timeout=60") +
            listOfNotNull(remote.rateLimit.takeIf { it > 0 }?.let { "--bwlimit=$it" }) +
            listOf("-e", shell, "--rsync-path=flock -w 60 ${projectDir(key)}/lock rsync") + excludes + paths
        val ran = run(argv, null, timeout)
        // 24: files vanished while the list was being made, which is a log rotated or a tab closed
        // mid-copy; the next copy picks up where it is then.
        if (ran.exit == 0 || ran.exit == 24) return
        if (ran.exit == -1) throw SyncFailure("copying agent tabs with ${remote.target} took longer than $timeout")
        throw SyncFailure(sshProblem(ran))
    }

    /** What went wrong with an ssh or rsync run, for the user. */
    private fun sshProblem(ran: Ran): String {
        val err = ran.err.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val said = err.lastOrNull().orEmpty()
        return when {
            ran.exit == -1 -> "${remote.target} did not answer in time"
            ran.exit == -2 -> "${remote.target} sent more than a sync answer holds, so it was cut off"
            err.any { "Host key verification failed" in it } ->
                "${remote.target}'s host key is not known yet: connect once with ssh ${remote.target} from a terminal to check and record it"
            err.any { "REMOTE HOST IDENTIFICATION HAS CHANGED" in it } ->
                "${remote.target}'s host key has changed since it was recorded; check why before syncing with it"
            err.any { "Permission denied" in it } -> "${remote.target} refused the key: $said"
            said.isNotEmpty() -> "could not sync with ${remote.target}: $said"
            else -> "could not sync with ${remote.target} (exit ${ran.exit})"
        }
    }

    companion object {
        private val CALL_TIMEOUT = 60.seconds
        // Long enough for a busy project's first copy at the rate cap. A copy that stalls is ended
        // well before by rsync's own --timeout, and one ended keeps every file it finished, so the
        // next copy carries on from there.
        val COPY_TIMEOUT = 60.minutes

        private val HEX = Regex("[0-9a-f]{1,64}")
        private val TARGET = Regex("[A-Za-z0-9._@:\\[\\]-]+")
        private val BASE = Regex("[A-Za-z0-9._/-]+")

        /** Why [remote] cannot be used, for the settings dialog, or null when it can. */
        fun problem(remote: SyncRemote): String? = when {
            remote.target.isBlank() -> "No remote is set"
            !TARGET.matches(remote.target) || remote.target.startsWith("-") ->
                "The remote is user@host or a host name from the ssh config"
            !BASE.matches(remote.base) || remote.base.startsWith("/") || remote.base.split('/').any { it == ".." } ->
                "The remote folder is a path under the remote user's home, such as ${SyncRemote.DEFAULT_BASE}"
            remote.identity != null && '\'' in remote.identity -> "The key file's path cannot contain a quote"
            remote.identity != null && remote.identity.isNotBlank() && !Files.isRegularFile(Path.of(expand(remote.identity))) ->
                "There is no key file at ${remote.identity}"
            remote.ssh != null && '\'' in remote.ssh -> "The ssh program's path cannot contain a quote"
            remote.rateLimit < 0 -> "The speed cap is a whole number of KB a second, or 0 for none"
            else -> null
        }

        private fun expand(path: String): String =
            if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path

        /** A value as it can go between single quotes and on one line of the lease. */
        private fun clean(value: String): String =
            value.filter { it != '\'' && it != '\n' && it != '\r' }.take(500)

        /**
         * Runs [argv] with [stdin], for at most [timeout], and returns what it printed.
         *
         * Both outputs are read on their own threads and joined for a short time only: ssh's shared
         * connection lives on in the background with the first caller's output still open, so
         * waiting for the end of it would wait for that connection to close. Each output is kept up
         * to [OUTPUT_LIMIT]; a program that prints more is killed, since no answer here is that long
         * and the other end decides how much it sends.
         */
        fun run(
            argv: List<String>,
            stdin: String?,
            timeout: Duration,
            onStart: (Process) -> Unit = {},
            onEnd: (Process) -> Unit = {},
        ): Ran {
            val process = try {
                ProcessBuilder(argv).start()
            } catch (e: java.io.IOException) {
                throw SyncFailure("could not run ${argv.first()}: ${e.message}")
            }
            onStart(process)
            try {
                val flooded = AtomicBoolean()
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                val readers = listOf(process.inputStream to out, process.errorStream to err).map { (stream, into) ->
                    drain(stream, into) {
                        flooded.set(true)
                        process.destroyForcibly()
                    }
                }
                runCatching { process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) } }
                val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                readers.forEach { it.join(2_000) }
                val exit = when {
                    flooded.get() -> -2
                    finished -> process.exitValue()
                    else -> -1
                }
                return Ran(exit, out.text(), err.text())
            } finally {
                onEnd(process)
            }
        }

        /** Reads [stream] into [into] until it ends, or until it has given [OUTPUT_LIMIT] bytes, when [onFlood] is called. */
        private fun drain(stream: InputStream, into: ByteArrayOutputStream, onFlood: () -> Unit): Thread =
            thread(isDaemon = true, name = "nop-sync-output") {
                runCatching {
                    val buffer = ByteArray(8192)
                    var kept = 0
                    stream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (kept + n > OUTPUT_LIMIT) {
                                onFlood()
                                break
                            }
                            kept += n
                            synchronized(into) { into.write(buffer, 0, n) }
                        }
                    }
                }
            }

        /** More than a lease, a tool's version or an rsync error ever is. */
        private const val OUTPUT_LIMIT = 256 * 1024

        private fun ByteArrayOutputStream.text(): String = synchronized(this) { toString(Charsets.UTF_8) }

        /**
         * The remote side, fed to `sh -s` on stdin with its arguments on the command line: the base
         * folder, the command, the project's key, then the command's own. Prints `name=value` lines.
         */
        val SCRIPT = """
            set -eu
            base=${'$'}1; cmd=${'$'}2; key=${'$'}3; shift 3
            command -v flock >/dev/null 2>&1 || { echo result=noflock; exit 0; }
            command -v rsync >/dev/null 2>&1 || { echo result=norsync; exit 0; }
            if [ "${'$'}cmd" = ping ]; then echo result=ok; echo "now=${'$'}(date +%s)"; exit 0; fi
            dir="${'$'}base/projects/${'$'}key"
            mkdir -p "${'$'}dir/machines"
            chmod 700 "${'$'}base" 2>/dev/null || true
            exec 9>>"${'$'}dir/lock"
            flock -w 20 9 || { echo result=busy; exit 0; }
            lease="${'$'}dir/lease"
            field() { if [ -f "${'$'}lease" ]; then sed -n "s/^${'$'}1=//p" "${'$'}lease" | head -n 1; fi; }
            show() {
              if [ -f "${'$'}lease" ]; then cat "${'$'}lease"; fi
              s=${'$'}(field seat)
              if [ -n "${'$'}s" ] && [ -d "${'$'}dir/machines/${'$'}s" ]; then echo pushed=1; fi
              echo "now=${'$'}(date +%s)"
            }
            write() {
              tmp="${'$'}lease.tmp.${'$'}${'$'}"
              printf 'gen=%s\nseat=%s\nhost=%s\nroot=%s\nhead=%s\ndirty=%s\nat=%s\n' "${'$'}@" "${'$'}(date +%s)" > "${'$'}tmp"
              mv "${'$'}tmp" "${'$'}lease"
            }
            case "${'$'}cmd" in
            read) show ;;
            claim)
              gen=${'$'}(field gen); gen=${'$'}{gen:-0}
              if [ "${'$'}gen" = "${'$'}1" ]; then write ${'$'}(( gen + 1 )) "${'$'}2" "${'$'}3" "${'$'}4" "${'$'}5" "${'$'}6"; echo result=ok; else echo result=lost; fi
              show ;;
            beat)
              gen=${'$'}(field gen); seat=${'$'}(field seat)
              if [ "${'$'}gen" = "${'$'}1" ] && [ "${'$'}seat" = "${'$'}2" ]; then
                write "${'$'}gen" "${'$'}seat" "${'$'}(field host)" "${'$'}(field root)" "${'$'}3" "${'$'}4"; echo result=ok
              else echo result=lost; fi
              show ;;
            *) echo result=unknown ;;
            esac
        """.trimIndent() + "\n"
    }
}
