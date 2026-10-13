package iondrive.nop.update

import iondrive.nop.agent.arr
import iondrive.nop.agent.obj
import iondrive.nop.agent.str
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Duration
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Fetches a release and puts it where the next restart runs it.
 *
 * All of it happens beside the installed image — `root`, the directory holding `bin/nop` — and none
 * of it in it: the running nop's files stay as they are until [install] swaps a complete, checked
 * image in by renaming. The siblings it uses are named after `root`:
 *
 * - `<root>.download`, the tarball as it downloads;
 * - `<root>.unpacking`, the tarball unpacked, while every file is checked against the manifest;
 * - `<root>.staged`, an image every file of which matched the signed manifest, with [STAMP] in it;
 * - `<root>.previous`, the image [install] replaced, to go back to.
 */
class Updater(
    private val latestUrl: URI = LATEST,
    private val key: PublicKey = UpdateManifest.releaseKey,
    private val platform: String? = UpdateManifest.currentPlatform(),
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    /** A published release: its version, and its assets by file name. */
    data class Release(val version: Version, val assets: Map<String, URI>)

    /** An update staged beside an install, and the handover its build adopts ([BuildInfo.handover]). */
    data class Staged(val version: Version, val handover: Int)

    /** Held while `<root>.staged` is replaced or swapped in, so neither sees the other half done. */
    private val lock = Any()

    /** The latest release, as GitHub names it. */
    fun latest(): Release {
        val body = get(latestUrl, "application/vnd.github+json").use { it.readBytes() }
        val release = runCatching { Json.parseToJsonElement(body.decodeToString()).obj() }.getOrNull()
            ?: throw UpdateException("GitHub's answer about the latest release does not parse")
        val tag = release["tag_name"].str() ?: throw UpdateException("the latest release has no tag")
        val version = Version.parse(tag) ?: throw UpdateException("the latest release, $tag, is not a version")
        val assets = release["assets"].arr().orEmpty().mapNotNull { asset ->
            val o = asset.obj() ?: return@mapNotNull null
            val name = o["name"].str() ?: return@mapNotNull null
            val url = o["browser_download_url"].str() ?: return@mapNotNull null
            name to runCatching { URI.create(url) }.getOrElse { return@mapNotNull null }
        }.toMap()
        return Release(version, assets)
    }

    /** What is staged beside [root], or null for nothing (or something unreadable). */
    fun staged(root: Path): Staged? {
        val stamp = sibling(root, STAGED).resolve(STAMP)
        val props = runCatching { Files.newInputStream(stamp).use { s -> Properties().apply { load(s) } } }.getOrNull()
            ?: return null
        val version = props.getProperty("version")?.let(Version::parse) ?: return null
        return Staged(version, BuildInfo.handoverOf(props))
    }

    /**
     * Downloads [release], checks it against its signed manifest, and leaves it staged beside [root]
     * for [install]. [progress] hears the bytes of the tarball so far, and its size. Throws
     * [UpdateException] for anything that stops it, having cleared away whatever it had written.
     */
    fun stage(root: Path, release: Release, progress: (Long, Long) -> Unit = { _, _ -> }): Staged {
        val platform = platform ?: throw UpdateException("nop does not update itself on this platform")
        val manifest = UpdateManifest.verified(
            asset(release, MANIFEST).use { it.readNBytes(MAX_MANIFEST_BYTES) },
            asset(release, SIGNATURE).use { it.readNBytes(MAX_MANIFEST_BYTES) },
            key,
        )
        if (Version.parse(manifest.version) != release.version) {
            throw UpdateException("release ${release.version}'s update.json is for ${manifest.version}")
        }
        if (manifest.platform != platform) {
            throw UpdateException("release ${release.version} has no update for $platform")
        }

        val download = sibling(root, DOWNLOAD)
        val unpacking = sibling(root, UNPACKING)
        try {
            fetchArchive(release, manifest.archive, download, progress)
            deleteTree(unpacking)
            Files.createDirectories(unpacking)
            untar(download, unpacking)
            val image = unpacking.resolve(manifest.archive.root)
            check(image, manifest)
            val handover = handoverOf(image, release.version)
            Files.writeString(image.resolve(STAMP), "version=${release.version}\nhandover=$handover\n")
            // The staged image is only readable by whoever may read the install.
            runCatching { Files.setPosixFilePermissions(image, Files.getPosixFilePermissions(root)) }
            synchronized(lock) {
                val staged = sibling(root, STAGED)
                deleteTree(staged)
                Files.move(image, staged, StandardCopyOption.ATOMIC_MOVE)
            }
            return Staged(release.version, handover)
        } finally {
            Files.deleteIfExists(download)
            deleteTree(unpacking)
        }
    }

    /**
     * Swaps the image staged beside [root] in for it, keeping the one it replaces as
     * `<root>.previous`. Returns what it installed, or null when nothing was staged. Run only as the
     * running nop is about to exec its launcher: the files it started from are no longer there after.
     */
    fun install(root: Path): Staged? = synchronized(lock) {
        val staged = staged(root) ?: return null
        val previous = sibling(root, PREVIOUS)
        deleteTree(previous)
        Files.move(root, previous, StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(sibling(root, STAGED), root, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(previous, root, StandardCopyOption.ATOMIC_MOVE)
            throw e
        }
        staged
    }

    /** Throws away whatever is staged beside [root]. */
    fun discard(root: Path) = synchronized(lock) { deleteTree(sibling(root, STAGED)) }

    private fun fetchArchive(release: Release, archive: UpdateManifest.Archive, into: Path, progress: (Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        asset(release, archive.name).use { input ->
            Files.newOutputStream(into).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    done += n
                    // The size is signed, so a longer download is not the release's.
                    if (done > archive.size) throw UpdateException("${archive.name} is longer than the manifest says")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    progress(done, archive.size)
                }
            }
        }
        if (done != archive.size) throw UpdateException("${archive.name} stopped at $done of ${archive.size} bytes")
        if (HexFormat.of().formatHex(digest.digest()) != archive.sha256.lowercase()) {
            throw UpdateException("${archive.name} does not match the hash in the signed manifest")
        }
    }

    /** `tar`, which every Linux has, rather than a tar reader of nop's own. */
    private fun untar(archive: Path, into: Path) {
        val process = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", into.toString(), "--no-same-owner")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw UpdateException("unpacking the update took over ten minutes")
        }
        if (process.exitValue() != 0) throw UpdateException("could not unpack the update: ${output.trim()}")
    }

    /**
     * That [image] holds exactly the manifest's files: each one present with its size and hash, each
     * link with its target, and nothing the manifest does not list. The executable bit is set from
     * the manifest rather than taken from the tarball.
     */
    private fun check(image: Path, manifest: UpdateManifest) {
        if (!Files.isDirectory(image, LinkOption.NOFOLLOW_LINKS)) {
            throw UpdateException("the tarball has no ${manifest.archive.root}/ directory")
        }
        val expected = manifest.files.associateBy { it.path }
        val seen = HashSet<String>()
        Files.walk(image).use { paths ->
            for (path in paths) {
                if (path == image) continue
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isDirectory) continue
                val rel = image.relativize(path).joinToString("/")
                val entry = expected[rel] ?: throw UpdateException("$rel is in the tarball but not in the signed manifest")
                when {
                    entry.link != null -> {
                        if (!attrs.isSymbolicLink || Files.readSymbolicLink(path).toString() != entry.link) {
                            throw UpdateException("$rel is not the link the manifest lists")
                        }
                    }
                    attrs.isRegularFile -> {
                        if (attrs.size() != entry.size || sha256(path) != entry.sha256?.lowercase()) {
                            throw UpdateException("$rel does not match its hash in the signed manifest")
                        }
                        Files.setPosixFilePermissions(path, if (entry.executable) EXECUTABLE else PLAIN)
                    }
                    else -> throw UpdateException("$rel is not the file the manifest lists")
                }
                seen += rel
            }
        }
        val missing = expected.keys - seen
        if (missing.isNotEmpty()) {
            throw UpdateException("the tarball lacks ${missing.first()}" + if (missing.size > 1) " and ${missing.size - 1} more" else "")
        }
    }

    /**
     * The handover the staged build adopts, from the stamp in its own jar — the one `lib/app` jar
     * that has one. Its version has to be the release's, or the image is not what it says it is.
     */
    private fun handoverOf(image: Path, version: Version): Int {
        val jars = Files.list(image.resolve("lib/app")).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.toList() }
        for (jar in jars) {
            val stamp = ZipFile(jar.toFile()).use { zip ->
                zip.getEntry(BuildInfo.RESOURCE_PATH)?.let { e -> zip.getInputStream(e).use { s -> Properties().apply { load(s) } } }
            } ?: continue
            if (stamp.getProperty("version")?.let(Version::parse) != version) {
                throw UpdateException("the update's build says it is ${stamp.getProperty("version")}, not $version")
            }
            return BuildInfo.handoverOf(stamp)
        }
        throw UpdateException("the update's build carries no version stamp")
    }

    private fun asset(release: Release, name: String): InputStream {
        val url = release.assets[name] ?: throw UpdateException("release ${release.version} has no $name")
        return get(url, "application/octet-stream")
    }

    private fun get(url: URI, accept: String): InputStream {
        val request = HttpRequest.newBuilder(url)
            .setHeader("Accept", accept)
            .setHeader("User-Agent", "nop/${BuildInfo.version ?: "unknown"}")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
            .getOrElse { throw UpdateException("could not reach ${url.host}: ${it.message ?: it}") }
        if (response.statusCode() != 200) {
            response.body().close()
            throw UpdateException("${url.host} answered ${response.statusCode()} for ${url.path.substringAfterLast('/')}")
        }
        return response.body()
    }

    companion object {
        val LATEST: URI = URI.create("https://api.github.com/repos/iondrive-co/nop/releases/latest")

        const val MANIFEST = "update.json"
        const val SIGNATURE = "update.json.sig"

        /** In a staged image: the version staged and the handover its build adopts. */
        const val STAMP = ".update-staged"

        const val DOWNLOAD = "download"
        const val UNPACKING = "unpacking"
        const val STAGED = "staged"
        const val PREVIOUS = "previous"

        /** Far above any real manifest (one line per file in the image), and a cap on a hostile one. */
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

        private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")
        private val PLAIN = PosixFilePermissions.fromString("rw-r--r--")

        /** `<root>.<what>`, beside [root]. */
        fun sibling(root: Path, what: String): Path = root.resolveSibling("${root.fileName}.$what")

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun deleteTree(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
