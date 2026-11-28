package iondrive.nop.agent

import iondrive.nop.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Sync's side of an S3-compatible store (Cloudflare R2, MinIO, Backblaze B2, AWS S3): the lease that
 * says which machine holds each project, and the copies of its tabs. nop only ever calls out to the
 * store, so no machine (a laptop on the road least of all) has to accept a connection.
 *
 * Everything is kept under [SyncRemote.prefix] in the bucket:
 *
 * - `projects/<key>/lease`, the project's [SyncLease];
 * - `projects/<key>/machines/<seat>/manifest`, the files a machine last sent, each as the list of
 *   its chunks, and `…/blobs/<name>`, the chunks themselves;
 * - `key-check`, a known text sealed with the sync key, so a machine with another key is told so
 *   before it writes anything.
 *
 * Every object is sealed by [SyncCrypto], bound to its own name, so the store sees neither the tabs
 * nor the lease, and an object it changes or moves does not open.
 *
 * The lease is changed only by a conditional write naming the version just read, which the store
 * turns down when another machine wrote in between: that is what keeps two claims from both
 * landing, with no lock to hold. Its age is the store's own clock (the reply's `Date` against the
 * lease's `Last-Modified`), so a machine whose clock is off still reads it right.
 *
 * Each machine sends into its own folder, so a holder never overwrites what a viewer is fetching.
 * Files are cut into [CHUNK]-sized pieces named by their HMAC, and only pieces the store does not
 * have yet are sent, so a log that grew costs its new tail, not the whole file. The manifest is
 * written last, so a machine fetching it never sees a send that is half done; chunks no manifest
 * names any more are removed after it. A fetch that loses a chunk to that cleanup starts again
 * from the new manifest. Files a fetch places are never links (see [SyncFiles.dropPulledLinks]).
 */
internal class SyncLink(private val remote: SyncRemote) : AutoCloseable {

    init {
        problem(remote)?.let { throw SyncFailure(it) }
    }

    private val crypto: SyncCrypto = SyncCrypto.parse(remote.syncKey)!!
    private val prefix = remote.prefix.trim('/')
    private val s3 = S3Client(
        remote.endpoint, remote.region, remote.bucket, remote.accessKey, remote.secretKey, remote.rateLimit, remote.target,
    )
    private val random = SecureRandom()

    @Volatile
    private var verified = false

    /** Checks that the store can be reached, takes this machine's keys, and does what sync needs. */
    fun ping(): SyncReply {
        checkKey()
        val date = probe()
        verified = true
        return SyncReply("ok", null, date, false)
    }

    fun read(key: String): SyncReply {
        ids(key)
        val now = current(key)
        val lease = now.lease
        return SyncReply("ok", lease, now.date, lease != null && pushed(key, lease.seat))
    }

    fun claim(key: String, expected: Long, seat: String, host: String, root: String, head: String, dirty: Boolean): SyncReply {
        ids(key, seat)
        ensure()
        repeat(TRIES) {
            val now = current(key)
            val lease = now.lease
            if ((lease?.gen ?: 0) != expected) return SyncReply("lost", lease, now.date, lease != null && pushed(key, lease.seat))
            val next = SyncLease(expected + 1, seat, clean(host), clean(root), clean(head), dirty, 0)
            val put = write(key, next, now) ?: return@repeat
            return SyncReply("ok", next.copy(at = put.date ?: 0), put.date, pushed(key, seat))
        }
        return SyncReply("busy", null, null, false)
    }

    fun beat(key: String, gen: Long, seat: String, head: String, dirty: Boolean): SyncReply {
        ids(key, seat)
        repeat(TRIES) {
            val now = current(key)
            val lease = now.lease
            if (lease == null || lease.gen != gen || lease.seat != seat) {
                return SyncReply("lost", lease, now.date, lease != null && pushed(key, lease.seat))
            }
            val next = lease.copy(head = clean(head), dirty = dirty, at = 0)
            val put = write(key, next, now) ?: return@repeat
            return SyncReply("ok", next.copy(at = put.date ?: 0), put.date, pushed(key, seat))
        }
        return SyncReply("busy", null, null, false)
    }

    /**
     * Sends what [from] holds as [seat]'s copy of the project's tabs, leaving the store with those
     * files and no others. Credentials and files that only make sense beside a live process are
     * never sent.
     */
    fun push(key: String, seat: String, from: Path, timeout: Duration = COPY_TIMEOUT) {
        ids(key, seat)
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) throw SyncFailure("there are no tabs to send in $from")
        ensure()
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        val base = machineRel(key, seat)
        val blobs = "$base/blobs/"
        try {
            val old = try {
                manifest("$base/manifest")
            } catch (e: SyncFailure) {
                Log.warn("sync: sending every file again, as the last list sent to ${remote.target} could not be read: ${e.message}")
                null
            }
            val stored = s3.list(full(blobs)).mapNotNullTo(HashSet()) { it.key.substringAfterLast('/').takeIf(BLOB::matches) }
            val before = old?.files?.associateBy { it.path }.orEmpty()
            val entries = ArrayList<Entry>()
            for ((rel, file) in sendable(from)) {
                callTime(deadline, timeout)
                val attrs = try {
                    Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    continue
                }
                if (!attrs.isRegularFile) continue
                val mtime = attrs.lastModifiedTime().toMillis()
                val prior = before[rel]
                if (prior != null && prior.size == attrs.size() && prior.mtime == mtime && prior.chunks.all { it in stored }) {
                    entries += prior
                    continue
                }
                entries += send(rel, file, mtime, blobs, stored, deadline, timeout) ?: continue
            }
            val manifest = json.encodeToString(Manifest.serializer(), Manifest(files = entries)).toByteArray()
            val manifestRel = "$base/manifest"
            s3.put(full(manifestRel), sealed(manifestRel, manifest), paced = true, timeout = callTime(deadline, timeout))

            val named = entries.flatMapTo(HashSet()) { it.chunks }
            for (name in stored) {
                if (name in named) continue
                if (System.nanoTime() > deadline) break
                try {
                    s3.delete(full(blobs + name))
                } catch (e: SyncFailure) {
                    Log.warn("sync: could not remove chunks ${remote.target} no longer needs: ${e.message}")
                    break
                }
            }
        } catch (e: IOException) {
            throw SyncFailure("could not send the tabs in $from: ${e.message ?: e.toString()}")
        }
    }

    /**
     * Makes [into] hold [seat]'s copy of the project's tabs and nothing else. Files already there
     * with the same size and time are kept, and so are the pieces of changed files that did not
     * change. Each file is written beside its place and moved in once whole.
     */
    fun pull(key: String, seat: String, into: Path, timeout: Duration = COPY_TIMEOUT) {
        ids(key, seat)
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        val base = machineRel(key, seat)
        try {
            OwnerOnly.directory(into)
            repeat(TRIES) {
                val manifest = manifest("$base/manifest", callTime(deadline, timeout))
                    ?: throw SyncFailure("the other machine has not sent its tabs to ${remote.target} yet")
                if (manifest.version != 1) {
                    throw SyncFailure("the other machine sent its tabs with a newer nop; update nop on this machine")
                }
                try {
                    mirror(manifest, "$base/blobs/", into, deadline, timeout)
                    return
                } catch (_: Gone) {
                    // The holder sent again and removed a chunk the old list named: start from the new one.
                }
            }
        } catch (e: IOException) {
            throw SyncFailure("could not fetch the tabs into $into: ${e.message ?: e.toString()}")
        }
        throw SyncFailure("the other machine kept changing its tabs while they were fetched from ${remote.target}")
    }

    override fun close() {
        s3.close()
    }

    // ---- the lease ---------------------------------------------------------------------------

    private class Current(val lease: SyncLease?, val etag: String?, val date: Long?)

    private fun current(key: String): Current {
        val rel = leaseRel(key)
        val reply = s3.get(full(rel), LEASE_LIMIT) ?: return Current(null, null, null)
        val text = try {
            opened(rel, reply.body, LEASE_LIMIT).toString(Charsets.UTF_8)
        } catch (_: SyncFailure) {
            throw SyncFailure("the project's lease on ${remote.target} was not sealed with this machine's sync key; every machine must use the same one")
        }
        val lease = SyncLease.parse(text) ?: throw SyncFailure("${remote.target} holds a project lease nop could not read")
        return Current(lease.copy(at = reply.modified ?: 0), reply.etag, reply.date)
    }

    /** Writes [next] only while the lease is still the one [seen]; null when another write came first. */
    private fun write(key: String, next: SyncLease, seen: Current): S3Client.Reply? {
        val rel = leaseRel(key)
        val etag = if (seen.lease == null) null else seen.etag ?: throw SyncFailure("${remote.target} did not say which version of the lease it holds")
        return s3.putIf(full(rel), sealed(rel, next.text().toByteArray()), etag)
    }

    private fun pushed(key: String, seat: String): Boolean =
        HEX.matches(seat) && s3.head(full("${machineRel(key, seat)}/manifest")) != null

    // ---- checks ------------------------------------------------------------------------------

    private fun ensure() {
        if (verified) return
        checkKey()
        probe()
        verified = true
    }

    /** Makes sure this machine's sync key is the one the store was first used with. */
    private fun checkKey() {
        val reply = s3.get(full(KEY_CHECK), CHECK_LIMIT)
            ?: if (s3.putIf(full(KEY_CHECK), sealed(KEY_CHECK, MARKER.toByteArray()), null) != null) return
            else s3.get(full(KEY_CHECK), CHECK_LIMIT)
        val same = reply != null && runCatching { opened(KEY_CHECK, reply.body, CHECK_LIMIT).toString(Charsets.UTF_8) }.getOrNull() == MARKER
        if (!same) {
            throw SyncFailure("the sync key is not the one the other machines use with ${remote.target}; copy it from one of them")
        }
    }

    /** Makes sure the store turns down a conditional write that should not land; returns its clock. */
    private fun probe(): Long? {
        val rel = "probe/" + SyncCrypto.hex(ByteArray(16).also { random.nextBytes(it) })
        val body = sealed(rel, MARKER.toByteArray())
        val refused = "${remote.target} does not honour conditional writes, which the sync needs"
        try {
            val first = s3.putIf(full(rel), body, null) ?: throw SyncFailure(refused)
            val etag = first.etag ?: throw SyncFailure(refused)
            if (s3.putIf(full(rel), body, null) != null) throw SyncFailure(refused)
            if (s3.putIf(full(rel), body, "\"0123456789abcdef0123456789abcdef\"") != null) throw SyncFailure(refused)
            return (s3.putIf(full(rel), body, etag) ?: throw SyncFailure(refused)).date ?: first.date
        } finally {
            runCatching { s3.delete(full(rel)) }
        }
    }

    // ---- sending -----------------------------------------------------------------------------

    /** The files under [from] that are sent, by their path below it. */
    private fun sendable(from: Path): List<Pair<String, Path>> {
        val out = ArrayList<Pair<String, Path>>()
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (attrs.isRegularFile && !excluded(name)) out += from.relativize(file).joinToString("/") to file
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return out.sortedBy { it.first }
    }

    /** Sends the chunks of [file] the store does not have; null when the file went away meanwhile. */
    private fun send(
        rel: String,
        file: Path,
        mtime: Long,
        blobs: String,
        stored: MutableSet<String>,
        deadline: Long,
        timeout: Duration,
    ): Entry? {
        val chunks = ArrayList<String>()
        var size = 0L
        val buffer = ByteArray(CHUNK)
        try {
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                while (true) {
                    val n = fill(channel, buffer)
                    if (n == 0) break
                    size += n
                    val name = crypto.name(buffer, 0, n)
                    if (name !in stored) {
                        val blobRel = blobs + name
                        val sealed = crypto.seal(SyncCrypto.pack(buffer, 0, n), blobRel)
                        s3.put(full(blobRel), sealed, paced = true, timeout = callTime(deadline, timeout))
                        stored += name
                    }
                    chunks += name
                    if (n < CHUNK) break
                }
            }
        } catch (_: NoSuchFileException) {
            return null
        }
        return Entry(rel, size, mtime, chunks)
    }

    // ---- fetching ----------------------------------------------------------------------------

    private class Gone : Exception()

    private fun mirror(manifest: Manifest, blobs: String, into: Path, deadline: Long, timeout: Duration) {
        val wanted = wanted(manifest, into)
        val dirs = wanted.keys.flatMapTo(HashSet()) { ancestors(it) }
        clear(into, wanted.keys, dirs)
        for (entry in wanted.values) {
            callTime(deadline, timeout)
            place(entry, blobs, into, deadline, timeout)
        }
    }

    /** The entries of [manifest] that are safe to place under [into], by path. */
    private fun wanted(manifest: Manifest, into: Path): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        val root = into.toAbsolutePath().normalize()
        for (entry in manifest.files) {
            val bad = badEntry(entry, root)
            if (bad != null) {
                Log.warn("sync: left out ${entry.path.take(200)} from ${remote.target}: $bad")
                continue
            }
            if (out.putIfAbsent(entry.path, entry) != null) Log.warn("sync: ${remote.target} listed ${entry.path.take(200)} twice")
        }
        val dirs = out.keys.flatMapTo(HashSet()) { ancestors(it) }
        for (path in out.keys.filter { it in dirs }) {
            Log.warn("sync: left out ${path.take(200)} from ${remote.target}: it is also a folder")
            out.remove(path)
        }
        return out
    }

    private fun badEntry(entry: Entry, root: Path): String? {
        val segments = entry.path.split('/')
        return when {
            entry.path.length > MAX_PATH -> "its name is too long"
            segments.any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it || '\\' in it } -> "its name is not a plain path"
            excluded(segments.last()) -> "nop never places such a file"
            !root.resolve(entry.path).normalize().startsWith(root) -> "it is outside the folder"
            entry.size < 0 -> "its size is wrong"
            entry.chunks.size.toLong() != (entry.size + CHUNK - 1) / CHUNK -> "its chunks do not match its size"
            entry.chunks.any { !BLOB.matches(it) } -> "a chunk has a wrong name"
            else -> null
        }
    }

    /** Removes from [into] everything that is not a wanted file or a folder one sits in. */
    private fun clear(into: Path, files: Set<String>, dirs: Set<String>) {
        Files.walkFileTree(into, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = into.relativize(file).joinToString("/")
                if (!attrs.isRegularFile || rel !in files) Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (dir == into) return FileVisitResult.CONTINUE
                val rel = into.relativize(dir).joinToString("/")
                if (rel !in dirs) {
                    try {
                        Files.deleteIfExists(dir)
                    } catch (_: DirectoryNotEmptyException) {
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun place(entry: Entry, blobs: String, into: Path, deadline: Long, timeout: Duration) {
        for (dir in ancestors(entry.path)) {
            val at = into.resolve(dir)
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) Files.delete(at)
        }
        val target = into.resolve(entry.path)
        OwnerOnly.directory(target.parent)
        val existing = runCatching {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()?.takeIf { it.isRegularFile }
        if (existing != null && existing.size() == entry.size && existing.lastModifiedTime().toMillis() == entry.mtime) return
        if (existing == null) Files.deleteIfExists(target)

        val temp = target.resolveSibling(".nop-sync-" + SyncCrypto.hex(ByteArray(8).also { random.nextBytes(it) }) + ".part")
        var moved = false
        try {
            create(temp).use { out ->
                val local = existing?.let { runCatching { FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS) }.getOrNull() }
                local.use {
                    val buffer = ByteArray(CHUNK)
                    entry.chunks.forEachIndexed { i, name ->
                        val offset = i.toLong() * CHUNK
                        val length = minOf(CHUNK.toLong(), entry.size - offset).toInt()
                        val plain = reuse(local, offset, length, name, buffer) ?: fetch(blobs + name, name, length, deadline, timeout)
                        val bb = ByteBuffer.wrap(plain, 0, length)
                        while (bb.hasRemaining()) out.write(bb)
                    }
                }
            }
            Files.setLastModifiedTime(temp, FileTime.fromMillis(entry.mtime))
            OwnerOnly.tighten(temp)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            moved = true
        } finally {
            if (!moved) runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun create(file: Path): FileChannel {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        return try {
            FileChannel.open(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            FileChannel.open(file, options)
        }
    }

    /** The piece of the local file at [offset] when it is already the chunk [name], or null. */
    private fun reuse(local: FileChannel?, offset: Long, length: Int, name: String, buffer: ByteArray): ByteArray? {
        if (local == null) return null
        val bb = ByteBuffer.wrap(buffer, 0, length)
        while (bb.hasRemaining()) {
            if (local.read(bb, offset + bb.position()) < 0) return null
        }
        return if (crypto.name(buffer, 0, length) == name) buffer else null
    }

    private fun fetch(rel: String, name: String, length: Int, deadline: Long, timeout: Duration): ByteArray {
        val reply = s3.get(full(rel), CHUNK + SEAL_OVERHEAD, paced = true, timeout = callTime(deadline, timeout)) ?: throw Gone()
        val plain = opened(rel, reply.body, CHUNK)
        if (plain.size != length || crypto.name(plain) != name) throw SyncFailure(SyncCrypto.NOT_OURS)
        return plain
    }

    // ---- objects -----------------------------------------------------------------------------

    @Serializable
    private data class Manifest(val version: Int = 1, val files: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val path: String, val size: Long, val mtime: Long, val chunks: List<String>)

    /** The manifest at [rel], or null when there is none. */
    private fun manifest(rel: String, timeout: Duration = S3Client.CALL_TIMEOUT): Manifest? {
        val reply = s3.get(full(rel), MANIFEST_SEALED_LIMIT, paced = true, timeout = timeout) ?: return null
        val text = opened(rel, reply.body, MANIFEST_LIMIT).toString(Charsets.UTF_8)
        return try {
            json.decodeFromString(Manifest.serializer(), text)
        } catch (_: IllegalArgumentException) {
            throw SyncFailure("${remote.target} holds a list of tabs nop could not read")
        }
    }

    private fun sealed(rel: String, plain: ByteArray): ByteArray = crypto.seal(SyncCrypto.pack(plain), rel)

    private fun opened(rel: String, body: ByteArray, limit: Int): ByteArray = SyncCrypto.unpack(crypto.open(body, rel), limit)

    private fun full(rel: String): String = if (prefix.isEmpty()) rel else "$prefix/$rel"

    private fun leaseRel(key: String) = "projects/$key/lease"

    private fun machineRel(key: String, seat: String) = "projects/$key/machines/$seat"

    private fun ids(key: String, seat: String? = null) {
        if (!HEX.matches(key)) throw SyncFailure("the project key $key is not one nop makes")
        if (seat != null && !HEX.matches(seat)) throw SyncFailure("the machine id $seat is not one nop makes")
    }

    /** How long the next call may take: what is left of the copy's time, up to a call's own limit. */
    private fun callTime(deadline: Long, timeout: Duration): Duration {
        val left = deadline - System.nanoTime()
        if (left <= 0) throw SyncFailure("copying agent tabs with ${remote.target} took longer than $timeout")
        return minOf(S3Client.CALL_TIMEOUT, left.nanoseconds)
    }

    companion object {
        /**
         * The longest a send or fetch may take in all. A call that stalls ends on its own time limit
         * well before; chunks already sent stay in the store and are not sent again.
         */
        val COPY_TIMEOUT = 60.minutes

        /** The size files are cut into. */
        const val CHUNK = 1 shl 20

        private val HEX = Regex("[0-9a-f]{1,64}")
        private val BLOB = Regex("[0-9a-f]{64}")
        private val BUCKET = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        private val SEGMENT = Regex("[A-Za-z0-9._-]+")
        private val REGION = Regex("[a-z0-9-]{1,32}")
        private const val TRIES = 3
        private const val MAX_PATH = 4096
        private const val SEAL_OVERHEAD = 64
        private const val LEASE_LIMIT = 64 * 1024
        private const val CHECK_LIMIT = 4096
        private const val MANIFEST_SEALED_LIMIT = 32 * 1024 * 1024
        private const val MANIFEST_LIMIT = 64 * 1024 * 1024
        private const val KEY_CHECK = "key-check"
        private const val MARKER = "nop sync key check 1"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** What is wrong with [remote] as somewhere to sync, in words for the user, or null when nothing is. */
        fun problem(remote: SyncRemote): String? {
            val endpoint = remote.endpoint.trim()
            if (endpoint.isEmpty()) return "Give the store's S3 endpoint, such as https://<account>.r2.cloudflarestorage.com"
            val uri = runCatching { java.net.URI(endpoint) }.getOrNull()
                ?: return "The endpoint $endpoint is not a web address"
            val host = uri.host
            return when {
                uri.scheme == null || host.isNullOrEmpty() -> "The endpoint $endpoint is not a web address"
                uri.scheme != "https" && !(uri.scheme == "http" && host in LOCAL_HOSTS) ->
                    "The endpoint must start with https:// (plain http is only for a store on this machine)"
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                    "The endpoint is the store's address alone, with no name, password, query or #"
                !(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") ->
                    "The endpoint is the store's address alone; the bucket goes in its own field"
                !BUCKET.matches(remote.bucket) -> "The bucket is 3 to 63 lower-case letters, digits, dots and dashes"
                remote.prefix.trim('/').split('/').let { parts ->
                    remote.prefix.trim('/').isNotEmpty() && parts.any { !SEGMENT.matches(it) || it == "." || it == ".." }
                } -> "The folder in the bucket is made of letters, digits, dots, dashes and underscores, split by /"
                !REGION.matches(remote.region) -> "The region is lower-case letters, digits and dashes, such as auto or us-east-1"
                !credential(remote.accessKey) -> "Give the access key the store issued"
                !credential(remote.secretKey) -> "Give the secret key the store issued with the access key"
                SyncCrypto.parse(remote.syncKey) == null ->
                    "Give the sync key: generate one on the first machine and copy it to the others"
                remote.rateLimit < 0 -> "The speed cap is a whole number of KB a second, or 0 for none"
                else -> null
            }
        }

        private fun credential(value: String): Boolean =
            value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() }

        private fun excluded(name: String): Boolean =
            name in Backup.CREDENTIALS || Backup.transient(name) || name.startsWith(".nop-sync-")

        /** The folders [path] sits in, outermost first: `a/b/c` gives `a` and `a/b`. */
        private fun ancestors(path: String): List<String> {
            val out = ArrayList<String>()
            var i = path.indexOf('/')
            while (i > 0) {
                out += path.substring(0, i)
                i = path.indexOf('/', i + 1)
            }
            return out
        }

        private fun clean(value: String): String = value.replace("\n", " ").replace("\r", " ").take(500)

        private fun fill(channel: FileChannel, buffer: ByteArray): Int {
            val bb = ByteBuffer.wrap(buffer)
            while (bb.hasRemaining()) {
                if (channel.read(bb) < 0) break
            }
            return bb.position()
        }
    }
}
