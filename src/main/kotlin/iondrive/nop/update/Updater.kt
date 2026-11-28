package iondrive.nop.update

import iondrive.nop.agent.arr
import iondrive.nop.agent.bool
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
 * Fetches a release and stages it as a version of its own beside the install.
 *
 * An install is a home — the path every launcher names, such as `~/.local/opt/nop` — and versions
 * beside it, one directory each: `<home>-1.2.6`. The home is a link to the version that runs. A new
 * version is unpacked and checked in a directory of its own and started from there; the home moves to
 * it only once it has come up ([activate]). Nothing a running nop started from is moved or changed,
 * and a version that never comes up leaves the home where it was.
 *
 * A version directory carries markers: [STAMP] once every file in it matched the signed manifest and
 * its launcher answered [UpdateProbe], [TRIED] once a nop started it, then [HEALTHY] when that start
 * came up or [FAILED] when it did not.
 *
 * On macOS the image is an app bundle and so is each version: a home `nop.app` has versions
 * `nop-1.2.6.app` beside it, each with its launcher at `Contents/MacOS/nop` ([launcherIn]).
 */
class Updater(
    private val releasesUrl: URI = RELEASES,
    private val key: PublicKey = UpdateManifest.releaseKey,
    private val platform: String? = UpdateManifest.currentPlatform(),
    /** Whether [install] tells LaunchServices about the bundle it replaced, as only a real install should. */
    private val registerBundles: Boolean = true,
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    /** A published release: its version, and its assets by file name. */
    data class Release(val version: Version, val assets: Map<String, URI>)

    /** One version beside a home, as its markers describe it. */
    data class Image(
        val dir: Path,
        val version: Version,
        /** The handover versions its build takes up ([UpdateProbe.Answer.adopts]). */
        val adopts: Set<Int>,
        val tried: Boolean,
        val healthy: Boolean,
        val failed: Boolean,
    ) {
        val launcher: Path get() = launcherIn(dir)
    }

    /** Held while a version directory is created, swapped in or removed. */
    private val lock = Any()

    /**
     * The newest published release that carries a signed update for this platform. A release whose
     * build for this platform failed has none, and is passed over for the newest one that does, rather
     * than leaving every install on this platform where it is until the next release.
     */
    fun latest(): Release {
        val body = get(releasesUrl, "application/vnd.github+json").use { it.readBytes() }
        val releases = runCatching { Json.parseToJsonElement(body.decodeToString()).arr() }.getOrNull()
            ?: throw UpdateException("GitHub's answer about the releases does not parse")
        val published = releases.mapNotNull { element ->
            val release = element.obj() ?: return@mapNotNull null
            if (release["draft"].bool() || release["prerelease"].bool()) return@mapNotNull null
            val version = release["tag_name"].str()?.let(Version::parse) ?: return@mapNotNull null
            val assets = release["assets"].arr().orEmpty().mapNotNull { asset ->
                val o = asset.obj() ?: return@mapNotNull null
                val name = o["name"].str() ?: return@mapNotNull null
                val url = o["browser_download_url"].str() ?: return@mapNotNull null
                name to runCatching { URI.create(url) }.getOrElse { return@mapNotNull null }
            }.toMap()
            Release(version, assets)
        }.sortedByDescending { it.version }
        if (published.isEmpty()) throw UpdateException("GitHub lists no published release")
        val platform = platform ?: return published.first()
        return published.firstOrNull { manifestName(it, platform) != null }
            ?: throw UpdateException("no published release has a signed update for $platform")
    }

    /** Where [version] goes beside [home]: `nop-1.2.6` beside `nop`, `nop-1.2.6.app` beside `nop.app`. */
    fun imageDir(home: Path, version: Version): Path {
        val (stem, suffix) = nameParts(home)
        return home.resolveSibling("$stem-$version$suffix")
    }

    /** The versions staged beside [home], oldest first. Only those whose every file was checked. */
    fun images(home: Path): List<Image> {
        val parent = home.toAbsolutePath().parent ?: return emptyList()
        val (stem, suffix) = nameParts(home)
        val prefix = "$stem-"
        val dirs = runCatching {
            Files.list(parent).use { s ->
                s.filter { it.fileName.toString().let { n -> n.startsWith(prefix) && n.endsWith(suffix) } }.toList()
            }
        }.getOrDefault(emptyList())
        return dirs.mapNotNull { dir ->
            val version = Version.parse(dir.fileName.toString().removePrefix(prefix).removeSuffix(suffix)) ?: return@mapNotNull null
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            val stamp = runCatching { Files.newInputStream(dir.resolve(STAMP)).use { s -> Properties().apply { load(s) } } }
                .getOrNull() ?: return@mapNotNull null
            val handover = BuildInfo.handoverOf(stamp)
            val adopts = stamp.getProperty("adopts")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                ?.takeIf { it.isNotEmpty() } ?: setOf(handover)
            Image(
                dir = dir,
                version = version,
                adopts = adopts,
                tried = Files.exists(dir.resolve(TRIED)),
                healthy = Files.exists(dir.resolve(HEALTHY)),
                failed = Files.exists(dir.resolve(FAILED)),
            )
        }.sortedBy { it.version }
    }

    /**
     * Downloads [release], checks it against its signed manifest, and stages it beside [home] as a
     * version of its own. With a delta in the release, files the [reuse] directories already hold
     * (by hash) are copied from there and only the delta is downloaded; otherwise, or when that does
     * not add up to the whole image, the whole tarball. [progress] hears the bytes downloaded so far
     * and the size of what is being downloaded. Throws [UpdateException] for anything that stops it,
     * having cleared away whatever it wrote.
     */
    fun stage(
        home: Path,
        release: Release,
        reuse: List<Path> = emptyList(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Image {
        val platform = platform ?: throw UpdateException("nop does not update itself on this platform")
        val manifest = manifestOf(release, platform)
        val target = imageDir(home, release.version).toAbsolutePath().normalize()
        images(home).firstOrNull { it.dir.toAbsolutePath().normalize() == target }?.let { return it }

        val work = sibling(target, UNPACKING)
        val download = sibling(target, DOWNLOAD)
        try {
            synchronized(lock) { deleteTree(target) }
            deleteTree(work)
            Files.createDirectories(work)
            val image = work.resolve(safeName(manifest.archive.root))
            val assembled = manifest.delta?.let { delta ->
                runCatching { fromDelta(release, manifest, delta, reuse, work, download, image, progress) }
                    .getOrElse { failure ->
                        iondrive.nop.Log.info("update: the delta for ${release.version} did not do: ${failure.message}; downloading it whole")
                        false
                    }
            } ?: false
            if (!assembled) {
                deleteTree(image)
                fetch(release, manifest.archive.name, manifest.archive.sha256, manifest.archive.size, download, progress)
                untar(download, work)
            }
            check(image, manifest)
            val answer = probe(image, release.version)
            Files.writeString(
                image.resolve(STAMP),
                "version=${release.version}\nhandover=${answer.handover}\nadopts=${answer.adopts.sorted().joinToString(",")}\n",
            )
            // Only whoever may read the install may read a version of it.
            reuse.firstOrNull()?.let { from -> runCatching { Files.setPosixFilePermissions(image, Files.getPosixFilePermissions(from)) } }
            synchronized(lock) { Files.move(image, target, StandardCopyOption.ATOMIC_MOVE) }
            return images(home).first { it.dir.toAbsolutePath().normalize() == target }
        } finally {
            Files.deleteIfExists(download)
            deleteTree(work)
        }
    }

    /**
     * Copies [image], a version that has come up, over the macOS app bundle at [bundle] — the one the
     * user installed and starts nop from — so that it starts the new version itself. The copy is made
     * beside [bundle] (a clone on APFS, so it takes no time or space) and swapped in by one rename:
     * [bundle] is the old version or the new, never missing or half written. Throws when macOS does
     * not let nop change [bundle], having left it as it was.
     */
    fun install(image: Path, bundle: Path) = synchronized(lock) {
        if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)) throw UpdateException("$bundle is not there to replace")
        val incoming = bundle.resolveSibling(".${bundle.fileName}.incoming")
        deleteTree(incoming)
        try {
            // -c clones on APFS (macOS's cp; it copies where cloning cannot be done).
            val clone = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) listOf("-c") else emptyList()
            run(
                "copying ${image.fileName} to ${incoming.parent}",
                *(listOf("cp", "-Rp") + clone + listOf(image.toString(), incoming.toString())).toTypedArray(),
            )
            swap(incoming, bundle)
        } finally {
            // The old version once the swap is done, or the copy if it was not.
            deleteTree(incoming)
        }
        // So Finder and the Dock see the version that is there now. Only a cache: nothing depends on it.
        if (registerBundles && Files.isExecutable(Path.of(LSREGISTER))) {
            runCatching { run("registering $bundle", LSREGISTER, "-f", bundle.toString()) }
        }
    }

    /** Exchanges [a] and [b], both directories: in one step where the filesystem can, else in three. */
    private fun swap(a: Path, b: Path) {
        if (iondrive.nop.terminal.Posix.swap(a.toString(), b.toString())) return
        val old = b.resolveSibling(".${b.fileName}.old")
        deleteTree(old)
        Files.move(b, old, StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(a, b, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(old, b, StandardCopyOption.ATOMIC_MOVE)
            throw e
        }
        Files.move(old, a, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun run(doing: String, vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw UpdateException("$doing took over ten minutes")
        }
        if (process.exitValue() != 0) throw UpdateException("$doing failed: ${output.trim().lines().lastOrNull().orEmpty()}")
    }

    /** Writes [marker] into [image], with [text] in it. */
    fun mark(image: Path, marker: String, text: String = "") {
        Files.writeString(image.resolve(marker), text)
    }

    /** What [TRIED] in [image] says it was started from, or null. */
    fun triedFrom(image: Path): Pair<Path, Version?>? {
        val props = runCatching { Files.newInputStream(image.resolve(TRIED)).use { s -> Properties().apply { load(s) } } }
            .getOrNull() ?: return null
        val from = props.getProperty("from")?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        return from to props.getProperty("fromVersion")?.let(Version::parse)
    }

    /**
     * Marks [image] as a version that did not come up, and drops its files: only the markers stay,
     * so the same release is not downloaded and tried again.
     */
    fun fail(image: Path) = synchronized(lock) {
        mark(image, FAILED)
        Files.list(image).use { s -> s.toList() }
            .filter { it.fileName.toString() !in MARKERS }
            .forEach { deleteTree(it) }
    }

    /**
     * Points [home] at [image]. A home that is still a directory of its own — an install that predates
     * version directories — is moved to [aside] first, which must not exist.
     */
    fun activate(home: Path, image: Path, aside: Path) = synchronized(lock) {
        val link = sibling(home, "link")
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, home.toAbsolutePath().parent.relativize(image.toAbsolutePath()))
        if (Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) Files.move(home, aside, StandardCopyOption.ATOMIC_MOVE)
        // rename(2): the home is a link to the old version or to the new, never missing.
        Files.move(link, home, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Deletes the versions beside [home] that are not in [keep], and anything a stage left half done.
     * A failed version's markers stay, since they are what stops it being tried again.
     */
    fun prune(home: Path, keep: Set<Path>) = synchronized(lock) {
        for (image in images(home)) {
            // By what the paths lead to, not how they are spelled: macOS's /var is /private/var.
            if (image.failed || keep.any { same(it, image.dir) }) continue
            deleteTree(image.dir)
        }
    }

    /** The name of the release's manifest for [platform], when it has one with a signature beside it. */
    private fun manifestName(release: Release, platform: String): String? {
        // Per platform, and on Linux the name releases had before there were others.
        val names = listOf("update-$platform.json") + if (platform == "linux-x64") listOf(MANIFEST) else emptyList()
        return names.firstOrNull { it in release.assets && "$it.sig" in release.assets }
    }

    /** The release's manifest for [platform], checked against its signature. */
    private fun manifestOf(release: Release, platform: String): UpdateManifest {
        val name = manifestName(release, platform)
            ?: throw UpdateException("release ${release.version} has no signed update for $platform")
        val manifest = UpdateManifest.verified(
            asset(release, name).use { it.readNBytes(MAX_MANIFEST_BYTES) },
            asset(release, "$name.sig").use { it.readNBytes(MAX_MANIFEST_BYTES) },
            key,
        )
        if (Version.parse(manifest.version) != release.version) {
            throw UpdateException("release ${release.version}'s $name is for ${manifest.version}")
        }
        if (manifest.platform != platform) throw UpdateException("release ${release.version}'s $name is for ${manifest.platform}")
        for (entry in manifest.files) safeName(entry.path)
        return manifest
    }

    /**
     * Builds [image] from the delta and the [reuse] directories: each file from the delta when it is
     * there, else copied from a local file with the same hash. False when some file is in neither.
     */
    private fun fromDelta(
        release: Release,
        manifest: UpdateManifest,
        delta: UpdateManifest.Delta,
        reuse: List<Path>,
        work: Path,
        download: Path,
        image: Path,
        progress: (Long, Long) -> Unit,
    ): Boolean {
        if (delta.name !in release.assets || reuse.isEmpty()) return false
        val files = manifest.files.filter { it.link == null }
        val local = LocalFiles(reuse, files)
        val deltaHas = HashSet<String>()
        fetch(release, delta.name, delta.sha256, delta.size, download, progress)
        val unpacked = work.resolve("delta")
        Files.createDirectories(unpacked)
        untar(download, unpacked)
        val root = unpacked.resolve(safeName(delta.root))
        if (Files.isDirectory(root)) {
            Files.walk(root).use { s -> s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { deltaHas += root.relativize(it).joinToString("/") } }
        }
        if (files.any { it.path !in deltaHas && local[it.sha256] == null }) return false
        for (entry in manifest.files) {
            val dest = image.resolve(entry.path)
            Files.createDirectories(dest.parent)
            when {
                entry.link != null -> Files.createSymbolicLink(dest, Path.of(entry.link))
                entry.path in deltaHas -> Files.move(root.resolve(entry.path), dest)
                // A copy rather than a hard link: a link would share the permissions set on it below.
                else -> Files.copy(local[entry.sha256]!!, dest)
            }
        }
        deleteTree(unpacked)
        return true
    }

    /** The local files whose size some manifest file has, by hash, hashing only those. */
    private class LocalFiles(dirs: List<Path>, wanted: List<UpdateManifest.Entry>) {
        private val byHash = HashMap<String, Path>()

        init {
            val sizes = wanted.mapNotNull { it.size }.toSet()
            val hashes = wanted.mapNotNull { it.sha256?.lowercase() }.toSet()
            for (dir in dirs) {
                if (!Files.isDirectory(dir)) continue
                runCatching {
                    Files.walk(dir).use { s ->
                        s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                            .filter { runCatching { Files.size(it) in sizes }.getOrDefault(false) }
                            .forEach { file -> sha256(file).takeIf { it in hashes }?.let { byHash.putIfAbsent(it, file) } }
                    }
                }
            }
        }

        operator fun get(sha256: String?): Path? = sha256?.lowercase()?.let { byHash[it] }
    }

    private fun fetch(release: Release, name: String, sha256: String, size: Long, into: Path, progress: (Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        asset(release, name).use { input ->
            Files.newOutputStream(into).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    done += n
                    // The size is signed, so a longer download is not the release's.
                    if (done > size) throw UpdateException("$name is longer than the manifest says")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    progress(done, size)
                }
            }
        }
        if (done != size) throw UpdateException("$name stopped at $done of $size bytes")
        if (HexFormat.of().formatHex(digest.digest()) != sha256.lowercase()) {
            throw UpdateException("$name does not match the hash in the signed manifest")
        }
    }

    /** `tar`, which every Linux and macOS has, rather than a tar reader of nop's own. */
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
            throw UpdateException("the update has no ${manifest.archive.root}/ directory")
        }
        val expected = manifest.files.associateBy { it.path }
        val seen = HashSet<String>()
        Files.walk(image).use { paths ->
            for (path in paths) {
                if (path == image) continue
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isDirectory) continue
                val rel = image.relativize(path).joinToString("/")
                val entry = expected[rel] ?: throw UpdateException("$rel is in the update but not in the signed manifest")
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
            throw UpdateException("the update lacks ${missing.first()}" + if (missing.size > 1) " and ${missing.size - 1} more" else "")
        }
    }

    /**
     * Runs the staged launcher with [UpdateProbe.FLAG]: proof that it starts, and what it adopts. A
     * build older than the flag says what it adopts in the stamp in its jar instead.
     */
    private fun probe(image: Path, version: Version): UpdateProbe.Answer =
        when (val outcome = UpdateProbe.run(launcherIn(image))) {
            is UpdateProbe.Outcome.Answered -> outcome.answer.also {
                if (it.version != version) throw UpdateException("the update's build says it is ${it.version}, not $version")
            }
            UpdateProbe.Outcome.TooOld -> {
                val handover = jarHandover(image, version)
                UpdateProbe.Answer(version, handover, setOf(handover))
            }
            is UpdateProbe.Outcome.Failed -> {
                // The running build's own launcher, started the same way, says whose fault it is: an
                // environment no nop can start in, or this update.
                val control = iondrive.nop.ipc.Restart.launcher()?.let { UpdateProbe.run(it) }
                val blame = when (control) {
                    null -> ""
                    is UpdateProbe.Outcome.Failed -> "; nop's own launcher does the same when started from here (${control.why}), so it is something about this nop's environment, not the update"
                    else -> "; nop's own launcher starts from here, so it is the update"
                }
                iondrive.nop.Log.warn(
                    "update: started to check it works, the update's launcher ${outcome.why.removePrefix("it ")}$blame. It printed:\n" +
                        outcome.output.ifBlank { "(nothing)" }.take(4000),
                )
                throw UpdateException("the update does not start: ${outcome.why}$blame")
            }
        }

    /** The handover in the stamp of the one app jar that has one, whose version must be [version]. */
    private fun jarHandover(image: Path, version: Version): Int {
        val app = image.resolve(if (isBundle(image)) "Contents/app" else "lib/app")
        // By name, so which jars are opened does not depend on the filesystem's listing order.
        val jars = Files.list(app).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList() }
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
        /** Newest first, as GitHub lists them; far more than ever sit between two good releases. */
        val RELEASES: URI = URI.create("https://api.github.com/repos/iondrive-co/nop/releases?per_page=30")

        /** The Linux manifest's name in releases from before there was one per platform. */
        const val MANIFEST = "update.json"

        /** The version and the handovers its build adopts, once every file in it was checked. */
        const val STAMP = ".update-staged"

        /** A nop started this version; says which version it was started from. */
        const val TRIED = ".update-tried"

        /** The version came up after it was started. */
        const val HEALTHY = ".update-healthy"

        /** The version was started and did not come up; it is not tried again. */
        const val FAILED = ".update-failed"

        private val MARKERS = setOf(STAMP, TRIED, HEALTHY, FAILED)

        const val DOWNLOAD = "download"
        const val UNPACKING = "unpacking"

        /** Far above any real manifest (one line per file in the image), and a cap on a hostile one. */
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

        private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")
        private val PLAIN = PosixFilePermissions.fromString("rw-r--r--")

        /** `<path>.<what>`, beside [path]. */
        fun sibling(path: Path, what: String): Path = path.resolveSibling("${path.fileName}.$what")

        private fun same(a: Path, b: Path): Boolean =
            runCatching { Files.isSameFile(a, b) }.getOrElse { a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize() }

        /** Whether [image] is a macOS app bundle, as its name says. */
        fun isBundle(image: Path): Boolean = image.fileName?.toString()?.endsWith(APP) == true

        /** The launcher in [image]: `bin/nop`, or `Contents/MacOS/nop` in an app bundle. */
        fun launcherIn(image: Path): Path =
            if (isBundle(image)) image.resolve("Contents").resolve("MacOS").resolve("nop") else image.resolve("bin").resolve("nop")

        /** [home]'s name without and with its `.app`, if it has one: what a version's name goes between. */
        private fun nameParts(home: Path): Pair<String, String> {
            val name = home.fileName.toString()
            return if (name.endsWith(APP) && name.length > APP.length) name.removeSuffix(APP) to APP else name to ""
        }

        private const val APP = ".app"

        private const val LSREGISTER =
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

        /** [path] if it stays inside whatever it is resolved against: relative, with no `..`. */
        private fun safeName(path: String): String {
            val p = runCatching { Path.of(path) }.getOrNull()
            if (p == null || p.isAbsolute || path.isBlank() || p.any { it.toString() == ".." } || '\\' in path) {
                throw UpdateException("the manifest names $path, which is not a path inside the image")
            }
            return path
        }

        internal fun sha256(path: Path): String {
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

        internal fun deleteTree(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path)
                return
            }
            Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
