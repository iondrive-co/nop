package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A copy of every agent session in a folder the user chose, kept current while nop runs. The folder
 * may be on an SMB share, named `smb://host/share/folder`; [NetworkShare] finds or makes its mount.
 *
 * What it copies is the work, not the logins: nop's own session logs, handoff summaries and shared
 * memory; `agent.json`; and from each account's home the vendor's transcripts — what a resume reads
 * — and the CLI's own settings. Credential files are never copied, wherever they turn up: a backup
 * is a second place the data sits, and a login in it would be one more copy of a key to keep safe.
 *
 * It only adds. A file new since the last run is copied, a log that has grown has its new bytes
 * appended, and nothing is ever removed from the destination — so deleting the source, which is
 * what a backup is for, cannot reach the copy. A file that has *shrunk* is the one case where
 * copying over it would lose something, so the backed-up version is kept aside under a dated name
 * before the new one takes its place, and so is a log that was rewritten rather than added to.
 *
 * Runs on a thread of its own: every [INTERVAL_MINUTES], shortly after a session's run ends, and when
 * asked from the accounts dialog, which shows [status]. Only a nop that has called [start] backs up
 * or restores anything, which keeps a test JVM away from the user's real folders.
 */
object Backup {

    /** What the last run did, for the accounts dialog. */
    data class Status(
        val running: Boolean = false,
        val lastSuccessAt: Long? = null,
        val lastError: String? = null,
        val copiedFiles: Int = 0,
        val copiedBytes: Long = 0,
    )

    /** How much one run copied. */
    data class Copied(val files: Int, val bytes: Long)

    /** Compose state, written from the backup thread the way a tailer writes a run's session id. */
    var status: Status by mutableStateOf(Status())
        private set

    private const val INTERVAL_MINUTES = 10L
    private const val AFTER_RUN_SECONDS = 30L
    private const val FIRST_RUN_MINUTES = 1L

    /** How far apart two timestamps may be and still be the same file's. FAT and SMB keep two seconds. */
    private const val TIME_SLACK_MS = 2_000L

    /** Enough of a log's tail to tell that it grew rather than was rewritten. */
    private const val TAIL_CHECK_BYTES = 4096

    /** Never copied, wherever they turn up. */
    private val CREDENTIALS = setOf(".credentials.json", "auth.json", "antigravity-oauth-token", "credential.json")

    private const val TEMP_PREFIX = ".nop-backup-"

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nop-backup").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val soonPending = AtomicBoolean(false)

    /** Starts the timer. Called once, by the nop that owns the windows. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({ runConfigured() }, FIRST_RUN_MINUTES, INTERVAL_MINUTES * 60, TimeUnit.SECONDS)
    }

    /**
     * Asks for a run shortly — after a session's run ends, which is when a transcript has just had
     * its last lines written. Requests while one is already waiting fold into it.
     */
    fun requestSoon() {
        if (!started.get() || !soonPending.compareAndSet(false, true)) return
        executor.schedule({
            soonPending.set(false)
            runConfigured()
        }, AFTER_RUN_SECONDS, TimeUnit.SECONDS)
    }

    /** Runs now, off the calling thread. */
    fun runNow() {
        if (started.get()) executor.execute { runConfigured() }
    }

    /**
     * Puts [id]'s transcript back into [account]'s home from the backup, when the home has lost it
     * and the backup has it. True when it did.
     */
    fun restore(account: Account, projectDir: File, id: String): Boolean =
        started.get() && restore(Accounts.load(), account, projectDir, id)

    internal fun restore(config: AgentConfig, account: Account, projectDir: File, id: String): Boolean {
        // A share that is not mounted is not mounted here: this runs as a session starts.
        val named = config.backupDir?.takeIf { it.isNotBlank() } ?: return false
        val dest = runCatching { NetworkShare.resolve(named, mount = false) }.getOrNull() ?: return false
        val saved = dest.resolve("homes").resolve(account.name)
        val found = Transcripts.find(account.provider, saved, projectDir, id) ?: return false
        val target = account.homePath.resolve(saved.relativize(found).toString())
        if (Files.exists(target)) return false
        return runCatching {
            OwnerOnly.directory(target.parent)
            copyWhole(found, target)
            Log.info("restored conversation $id for ${account.name} from the backup in $dest")
            true
        }.onFailure { Log.warn("could not restore conversation $id for ${account.name} from $dest: $it") }
            .getOrDefault(false)
    }

    /**
     * Why [dest] cannot be the backup folder, or null when it can. A folder inside what it backs up
     * would copy itself into itself, and one inside nop's own data or an account's home would go
     * with it the day that folder is deleted, which is the day it is needed.
     */
    fun refusal(dest: Path, config: AgentConfig, dataRoot: Path = Accounts.dataRoot()): String? {
        val target = dest.toAbsolutePath().normalize()
        if (target.startsWith(dataRoot.toAbsolutePath().normalize())) {
            return "It is inside nop's own data folder, so deleting that would delete the backup too."
        }
        config.accounts.firstOrNull { target.startsWith(it.homePath.toAbsolutePath().normalize()) }?.let {
            return "It is inside ${it.name}'s home, so deleting that would delete the backup too."
        }
        return null
    }

    private fun runConfigured() {
        val config = Accounts.load()
        val named = config.backupDir?.takeIf { it.isNotBlank() } ?: return
        status = status.copy(running = true)
        val result = runCatching { run(config, NetworkShare.resolve(named), Accounts.dataRoot(), Accounts.configFile) }
        result.onSuccess { copied ->
            status = Status(lastSuccessAt = System.currentTimeMillis(), copiedFiles = copied.files, copiedBytes = copied.bytes)
            if (copied.files > 0) Log.info("backup: ${copied.files} file(s), ${copied.bytes} bytes to $named")
        }
        result.onFailure { e ->
            status = status.copy(running = false, lastError = e.message ?: e.toString())
            Log.warn("backup to $named failed: $e")
        }
    }

    /**
     * What is copied, and where under the destination it goes: nop's own folders under `nop/`, the
     * account list as `agent.json`, and each account's share of its home under `homes/<name>/`.
     */
    internal fun sources(config: AgentConfig, dataRoot: Path, configFile: Path): List<Pair<Path, String>> = buildList {
        for (dir in listOf("sessions", "handoffs", "memory")) add(dataRoot.resolve(dir) to "nop/$dir")
        add(configFile to "agent.json")
        for (account in config.accounts) {
            for (part in keptIn(account.provider)) {
                add(account.homePath.resolve(part) to "homes/${account.name}/$part")
            }
        }
    }

    /**
     * The parts of a home that are the work rather than the login: the conversations, and the
     * settings the CLI reads — which are otherwise lost with the home, hooks and all.
     */
    internal fun keptIn(provider: Provider): List<String> = when (provider) {
        Provider.Anthropic -> listOf("projects", "settings.json")
        Provider.OpenAI -> listOf(
            ".codex/sessions",
            ".codex/session_index.jsonl",
            ".codex/config.toml",
            ".codex/hooks.json",
        )
        Provider.Antigravity -> listOf(
            ".gemini/antigravity-cli/conversations",
            ".gemini/antigravity-cli/brain",
            ".gemini/antigravity-cli/history.jsonl",
        )
    }

    /** One pass over every source into [dest]. */
    internal fun run(
        config: AgentConfig,
        dest: Path,
        dataRoot: Path,
        configFile: Path,
        now: Instant = Instant.now(),
    ): Copied {
        refusal(dest, config, dataRoot)?.let { throw IOException(it) }
        val target = dest.toAbsolutePath().normalize()
        OwnerOnly.directory(target)
        var files = 0
        var bytes = 0L
        for ((source, under) in sources(config, dataRoot, configFile)) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue
            val base = target.resolve(under)
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    if (!attrs.isRegularFile || name in CREDENTIALS || transient(name)) return FileVisitResult.CONTINUE
                    val relative = source.relativize(file).toString()
                    val into = if (relative.isEmpty()) base else base.resolve(relative)
                    runCatching { mirror(file, into, now) }
                        .onSuccess { copied ->
                            if (copied > 0) {
                                files++
                                bytes += copied
                            }
                        }
                        .onFailure { Log.warn("backup: could not copy $file: $it") }
                    return FileVisitResult.CONTINUE
                }

                // A file the CLI deleted between listing and reading is simply not there to copy, and a
                // folder that cannot be read is one folder missed rather than the whole run lost.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) Log.warn("backup: could not read $dir: $exc")
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return Copied(files, bytes)
    }

    /**
     * Brings [into] up to date with [from], returning how many bytes that took (0 when it already
     * was). Everything nop and the vendors log is append-only JSONL, so a grown log takes only its
     * new bytes — after checking that its tail still matches, which is what tells growth from a
     * rewrite that happens to be longer.
     */
    private fun mirror(from: Path, into: Path, now: Instant): Long {
        val size = Files.size(from)
        val modified = Files.getLastModifiedTime(from)
        val isLog = from.fileName.toString().endsWith(".jsonl")
        if (Files.exists(into)) {
            val had = Files.size(into)
            // A log only grows, so the same size is the same log. Anything else is compared by time
            // as well, loosely: a share can keep coarser timestamps than the disk it was copied from,
            // and an exact comparison would copy every file again on every run.
            if (had == size && (isLog || sameTime(Files.getLastModifiedTime(into).toMillis(), modified.toMillis()))) return 0
            if (isLog && size > had && tailMatches(from, into, had)) {
                val added = append(from, into, had, size)
                runCatching { Files.setLastModifiedTime(into, modified) }
                return added
            }
            // Past this point the copy is replaced. That loses what was backed up when the file has
            // shrunk, and when a log — which only ever grows — has been rewritten, so either way the
            // old copy is kept aside first.
            if (size < had || isLog) keepAside(into, now)
        } else {
            OwnerOnly.directory(into.parent)
        }
        copyWhole(from, into)
        runCatching { Files.setLastModifiedTime(into, modified) }
        return size
    }

    private fun sameTime(a: Long, b: Long): Boolean = kotlin.math.abs(a - b) <= TIME_SLACK_MS

    private fun tailMatches(from: Path, into: Path, length: Long): Boolean {
        val span = minOf(length, TAIL_CHECK_BYTES.toLong()).toInt()
        if (span == 0) return true
        val start = length - span
        return read(from, start, span).contentEquals(read(into, start, span))
    }

    private fun read(file: Path, start: Long, span: Int): ByteArray =
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(span)
            var at = start
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, at) < 0) break
                at = start + buffer.position()
            }
            buffer.array().copyOf(buffer.position())
        }

    /** Appends [from]'s bytes between [had] and [size] to [into]. A log still being written is copied as far as [size]. */
    private fun append(from: Path, into: Path, had: Long, size: Long): Long {
        FileChannel.open(from, StandardOpenOption.READ).use { source ->
            FileChannel.open(into, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { sink ->
                var at = had
                while (at < size) {
                    val moved = source.transferTo(at, size - at, sink)
                    if (moved <= 0) break
                    at += moved
                }
                return at - had
            }
        }
    }

    /** Moves the backed-up [file] aside, to `name.before-<time>.ext`, before a smaller version replaces it. */
    private fun keepAside(file: Path, now: Instant) {
        val name = file.fileName.toString()
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val aside = file.resolveSibling("${name.substring(0, dot)}.before-${STAMP.format(now)}${name.substring(dot)}")
        Files.move(file, aside)
    }

    /** Copies through a temporary name and a rename, so no reader ever finds half a file. */
    private fun copyWhole(from: Path, into: Path) {
        val temp = into.resolveSibling("$TEMP_PREFIX${into.fileName}")
        Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING)
        OwnerOnly.tighten(temp)
        try {
            Files.move(temp, into, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, into, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Files that only make sense beside a live process — SQLite's journals, lock files — and this
     * backup's own temporaries. A copy of a journal taken mid-write would be worse than none.
     */
    private fun transient(name: String): Boolean =
        name.startsWith(TEMP_PREFIX) || name.endsWith("-wal") || name.endsWith("-shm") ||
            name.endsWith("-journal") || name.endsWith(".lock")
}
