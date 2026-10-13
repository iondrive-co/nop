package iondrive.nop.update

import com.sun.net.httpserver.HttpServer
import iondrive.nop.LaunchArgs
import iondrive.nop.ipc.Handover
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UpdaterTest {

    @TempDir
    lateinit var tmp: Path

    private val key: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val served = HashMap<String, ByteArray>()
    private val requested = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            synchronized(requested) { requested += path }
            val body = served[path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    private fun updater() = Updater(URI.create("$base/latest"), key.public, platform = "linux-x64")

    /** A launcher that answers the probe as [version] adopting [adopts], or as [behaviour] says. */
    private fun launcherScript(version: String, adopts: Set<Int>, behaviour: String = "answers") = when (behaviour) {
        "answers" -> """
            #!/bin/sh
            if [ "${'$'}1" = "--update-probe" ]; then
              echo "nop-update-probe version=$version handover=${adopts.max()} adopts=${adopts.sorted().joinToString(",")}"
              exit 0
            fi
            echo "launcher $version"
        """.trimIndent()
        "too old" -> "#!/bin/sh\necho \"nop: unknown option: ${'$'}1\"\nexit 2\n"
        else -> "#!/bin/sh\necho 'cannot start'\nexit 1\n"
    }

    /** An app image of [version]: a launcher, the app jar with its stamp, a dependency. */
    private fun image(
        dir: Path,
        version: String,
        adopts: Set<Int> = setOf(Handover.VERSION),
        launcher: String = "answers",
        library: String = "a dependency",
    ): Path {
        dir.resolve("bin").createDirectories()
        dir.resolve("bin/nop").writeText(launcherScript(version, adopts, launcher))
        Files.setPosixFilePermissions(dir.resolve("bin/nop"), PosixFilePermissions.fromString("rwxr-xr-x"))
        dir.resolve("lib/app").createDirectories()
        ZipOutputStream(Files.newOutputStream(dir.resolve("lib/app/nop-$version.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry(BuildInfo.RESOURCE_PATH))
            zip.write("version=$version\nhandover=${adopts.max()}\n".toByteArray())
            zip.closeEntry()
        }
        dir.resolve("lib/app/library.jar").writeText(library)
        return dir
    }

    /** The install a release nop runs from: home `opt/nop`, as a directory of its own. */
    private fun install(version: String = "1.0.0"): Path = image(tmp.resolve("opt/nop"), version)

    private fun place(image: Path, version: String, home: Path = tmp.resolve("opt/nop"), system: Boolean = false) =
        SelfUpdate.Place(image, home, Version.parse(version)!!, system)

    private fun tar(dir: Path, archive: Path, vararg members: String) {
        val tar = ProcessBuilder(listOf("tar", "-czf", archive.toString(), "-C", dir.toString()) + members).start()
        assertEquals(0, tar.waitFor())
    }

    /**
     * Publishes release [version] on the stand-in for GitHub: its image as a tarball, with a signed
     * manifest. [alter] changes the image after the manifest is written, as a tampered tarball would
     * be. [deltaFiles], when given, also publishes a delta holding only those files.
     */
    private fun publish(
        version: String = "9.9.9",
        adopts: Set<Int> = setOf(Handover.VERSION),
        launcher: String = "answers",
        signer: KeyPair = key,
        manifestName: String = "update-linux-x64.json",
        deltaFiles: List<String>? = null,
        alter: (Path) -> Unit = {},
    ) {
        val root = tmp.resolve("release-$version")
        val image = image(root.resolve("nop"), version, adopts, launcher)
        val files = Files.walk(image).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }.joinToString(",\n") { f ->
            val rel = image.relativize(f).joinToString("/")
            """{"path":"$rel","sha256":"${sha256(Files.readAllBytes(f))}","size":${Files.size(f)},"executable":${Files.isExecutable(f)}}"""
        }
        alter(image)
        val archive = root.resolve("nop-$version.tar.gz")
        tar(root, archive, "nop")
        val bytes = Files.readAllBytes(archive)
        val delta = deltaFiles?.let { list ->
            val d = root.resolve("nop-$version-delta.tar.gz")
            tar(root, d, *list.map { "nop/$it" }.toTypedArray())
            val db = Files.readAllBytes(d)
            served["/assets/nop-$version-delta.tar.gz"] = db
            ""","delta":{"from":"9.9.8","name":"nop-$version-delta.tar.gz","sha256":"${sha256(db)}","size":${db.size},"root":"nop"}"""
        }.orEmpty()
        val manifest = """
            {"format":1,"version":"$version","platform":"linux-x64",
             "archive":{"name":"nop-$version.tar.gz","sha256":"${sha256(bytes)}","size":${bytes.size},"root":"nop"},
             "files":[$files]$delta}
        """.trimIndent().toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer.private)
            update(manifest)
            sign()
        }
        served["/assets/$manifestName"] = manifest
        served["/assets/$manifestName.sig"] = signature
        served["/assets/nop-$version.tar.gz"] = bytes
        val assets = served.keys.filter { it.startsWith("/assets/") }.joinToString(",") { path ->
            """{"name":"${path.removePrefix("/assets/")}","browser_download_url":"$base$path"}"""
        }
        served["/latest"] = """{"tag_name":"v$version","assets":[$assets]}""".toByteArray()
    }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun leftovers(home: Path) =
        Files.list(home.parent).use { s -> s.toList() }.map { it.fileName.toString() }.filter { it.endsWith(".download") || it.endsWith(".unpacking") }

    @Test
    fun `a signed release is staged as a version of its own, leaving the install alone`() {
        val home = install()
        publish()
        val updater = updater()

        val staged = updater.stage(home, updater.latest())

        assertEquals(tmp.resolve("opt/nop-9.9.9"), staged.dir)
        assertEquals(Version(9, 9, 9), staged.version)
        assertEquals(setOf(Handover.VERSION), staged.adopts)
        assertFalse(staged.tried)
        assertTrue(Files.isExecutable(staged.launcher))
        assertTrue("launcher 1.0.0" in home.resolve("bin/nop").readText(), "staging leaves the running install alone")
        assertEquals(emptyList(), leftovers(home))
        assertEquals(listOf(staged), updater.images(home))
    }

    @Test
    fun `a manifest signed with any other key is refused, and nothing is downloaded`() {
        val home = install()
        publish(signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        val updater = updater()

        val refused = assertFailsWith<UpdateException> { updater.stage(home, updater.latest()) }

        assertTrue("not signed" in refused.message!!, refused.message)
        assertTrue(requested.none { it.endsWith(".tar.gz") })
        assertEquals(emptyList(), updater.images(home))
    }

    @Test
    fun `an update that differs from its signed manifest is refused`() {
        val home = install()
        publish(alter = { image -> image.resolve("lib/app/library.jar").writeText("something else") })
        val updater = updater()

        assertFailsWith<UpdateException> { updater.stage(home, updater.latest()) }

        assertEquals(emptyList(), updater.images(home))
        assertEquals(emptyList(), leftovers(home))
    }

    @Test
    fun `a file the manifest does not list is refused`() {
        val home = install()
        publish(alter = { image -> image.resolve("lib/app/extra.jar").writeText("unlisted") })
        val updater = updater()

        val refused = assertFailsWith<UpdateException> { updater.stage(home, updater.latest()) }

        assertTrue("extra.jar" in refused.message!!, refused.message)
    }

    @Test
    fun `an update whose launcher does not start is refused`() {
        val home = install()
        publish(launcher = "broken")
        val updater = updater()

        val refused = assertFailsWith<UpdateException> { updater.stage(home, updater.latest()) }

        assertTrue("does not start" in refused.message!!, refused.message)
        assertEquals(emptyList(), updater.images(home))
    }

    @Test
    fun `a build older than the probe says what it adopts in its jar`() {
        val home = install()
        publish(launcher = "too old", manifestName = Updater.MANIFEST)
        val updater = updater()

        val staged = updater.stage(home, updater.latest())

        assertEquals(setOf(Handover.VERSION), staged.adopts)
    }

    @Test
    fun `with a delta, only it is downloaded and the rest comes from the running install`() {
        val home = install()
        // The running install's dependency is the release's, so only the app jar and launcher differ.
        publish(deltaFiles = listOf("bin/nop", "lib/app/nop-9.9.9.jar"))
        val updater = updater()

        val staged = updater.stage(home, updater.latest(), reuse = listOf(home))

        assertTrue(requested.any { it.endsWith("-delta.tar.gz") })
        assertTrue(requested.none { it.endsWith("/nop-9.9.9.tar.gz") }, "the whole tarball is not downloaded")
        assertEquals("a dependency", staged.dir.resolve("lib/app/library.jar").readText())
        assertTrue("launcher 9.9.9" in staged.dir.resolve("bin/nop").readText())
    }

    @Test
    fun `a delta the local files cannot complete falls back to the whole tarball`() {
        val home = image(tmp.resolve("opt/nop"), "1.0.0", library = "an older dependency")
        publish(deltaFiles = listOf("bin/nop", "lib/app/nop-9.9.9.jar"))
        val updater = updater()

        val staged = updater.stage(home, updater.latest(), reuse = listOf(home))

        assertTrue(requested.any { it.endsWith("/nop-9.9.9.tar.gz") })
        assertEquals("a dependency", staged.dir.resolve("lib/app/library.jar").readText())
    }

    @Test
    fun `a restart starts the staged version from its own directory, and the home moves once it is up`() {
        val home = install()
        publish()
        val updater = updater()
        updater.stage(home, updater.latest())
        val old = place(home, "1.0.0")

        val target = SelfUpdate.restartTarget(old, updater)

        assertEquals(tmp.resolve("opt/nop-9.9.9/bin/nop"), target)
        assertTrue(Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS), "nothing moves until the new version is up")

        // The new version, started from its own directory, comes up.
        val new = place(tmp.resolve("opt/nop-9.9.9"), "9.9.9")
        SelfUpdate.markHealthy(new, updater)

        assertTrue(Files.isSymbolicLink(home))
        assertEquals(tmp.resolve("opt/nop-9.9.9").toRealPath(), home.toRealPath())
        assertTrue("launcher 1.0.0" in tmp.resolve("opt/nop-1.0.0/bin/nop").readText(), "the old install is kept, as its version")
        assertTrue(updater.images(home).single { it.version == Version(9, 9, 9) }.healthy)
        assertNull(SelfUpdate.restartTarget(new, updater), "nothing newer to start")
    }

    @Test
    fun `a version that never comes up is marked failed by the next start, and not tried again`() {
        val home = install()
        publish()
        val updater = updater()
        updater.stage(home, updater.latest())
        val old = place(home, "1.0.0")
        assertNotNull(SelfUpdate.restartTarget(old, updater))

        // 9.9.9 died before it was up: the next start is 1.0.0 again, since the home never moved.
        assertEquals(Version(9, 9, 9), SelfUpdate.noteFailedStarts(old, updater))

        val image = updater.images(home).single()
        assertTrue(image.failed)
        assertFalse(image.launcher.exists(), "a failed version keeps only its markers")
        assertTrue(Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS))
        assertNull(SelfUpdate.restartTarget(old, updater))
        assertNull(SelfUpdate.redirectAtStart(old, updater, automatic = true))
    }

    @Test
    fun `an update that cannot take over the terminals waits for a start rather than a restart`() {
        val home = install()
        publish(adopts = setOf(Handover.VERSION + 1))
        val updater = updater()
        updater.stage(home, updater.latest())
        val old = place(home, "1.0.0")

        assertNull(SelfUpdate.restartTarget(old, updater))
        assertEquals(tmp.resolve("opt/nop-9.9.9/bin/nop"), SelfUpdate.redirectAtStart(old, updater, automatic = true))
    }

    @Test
    fun `a start never goes back to the version it runs from, whatever its build says it is`() {
        val home = install()
        val updater = updater()
        publish(version = "9.9.9")
        val nine = updater.stage(home, updater.latest())
        updater.mark(nine.dir, Updater.TRIED)
        updater.mark(nine.dir, Updater.HEALTHY)

        // A build in nop-9.9.9 that says it is 1.0.0 is still 9.9.9 here: nothing to go on to.
        assertNull(SelfUpdate.redirectAtStart(place(nine.dir, "1.0.0"), updater, automatic = true))
        assertNull(SelfUpdate.restartTarget(place(nine.dir, "1.0.0"), updater))
    }

    @Test
    fun `a start does not take up a staged version when the checks are off`() {
        val home = install()
        publish()
        val updater = updater()
        updater.stage(home, updater.latest())

        assertNull(SelfUpdate.redirectAtStart(place(home, "1.0.0"), updater, automatic = false))
    }

    @Test
    fun `a package install keeps its versions in the user's home and puts that in the menu`() {
        val system = image(tmp.resolve("system/nop"), "1.0.0")
        val home = tmp.resolve("user/.local/opt/nop")
        publish()
        val updater = updater()
        updater.stage(home, updater.latest(), reuse = listOf(system))
        val old = place(system, "1.0.0", home = home, system = true)

        assertEquals(tmp.resolve("user/.local/opt/nop-9.9.9/bin/nop"), SelfUpdate.redirectAtStart(old, updater, automatic = true))
        val applications = tmp.resolve("user/.local/share/applications")
        SelfUpdate.markHealthy(place(tmp.resolve("user/.local/opt/nop-9.9.9"), "9.9.9", home = home, system = true), updater, applications)

        assertTrue(Files.isSymbolicLink(home))
        assertTrue("Exec=$home/bin/nop" in applications.resolve(DesktopEntry.NAME).readText())
        // An older launcher of the package goes on to it at its next start.
        assertEquals(home.resolve("bin/nop").toRealPath(), SelfUpdate.redirectAtStart(old, updater, automatic = true)?.toRealPath())
    }

    @Test
    fun `an install is located by its launcher`() {
        val legacy = install()
        val located = assertIs<SelfUpdate.Located.At>(SelfUpdate.locate(legacy.resolve("bin/nop"), Version(1, 0, 0), tmp))
        assertEquals(legacy, located.place.home)

        val versioned = image(tmp.resolve("opt/nop-2.0.0"), "2.0.0")
        val next = assertIs<SelfUpdate.Located.At>(SelfUpdate.locate(versioned.resolve("bin/nop"), Version(2, 0, 0), tmp))
        assertEquals(legacy, next.place.home)
        assertFalse(next.place.system)

        legacy.resolve(Installation.BUILT_FROM).writeText("/home/dev/nop\n")
        assertIs<SelfUpdate.Located.Nowhere>(SelfUpdate.locate(versioned.resolve("bin/nop"), Version(2, 0, 0), tmp))
    }

    @Test
    fun `an install in a directory the user cannot write gets a home of the user's own`() {
        val system = image(tmp.resolve("system/nop"), "1.0.0")
        Files.setPosixFilePermissions(system.parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val located = assertIs<SelfUpdate.Located.At>(SelfUpdate.locate(system.resolve("bin/nop"), Version(1, 0, 0), tmp.resolve("user")))
            assertEquals(tmp.resolve("user/.local/opt/nop"), located.place.home)
            assertTrue(located.place.system)
        } finally {
            Files.setPosixFilePermissions(system.parent, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `the probe's answer parses, and the flag is a launch of its own`() {
        assertEquals(
            UpdateProbe.Answer(Version(1, 2, 6), 2, setOf(1, 2)),
            UpdateProbe.parse("noise\nnop-update-probe version=1.2.6 handover=2 adopts=1,2\n"),
        )
        assertNull(UpdateProbe.parse("nop: unknown option: --update-probe"))
        assertEquals(LaunchArgs.UpdateProbe, LaunchArgs.parse(arrayOf(UpdateProbe.FLAG)))
        assertIs<LaunchArgs.Invalid>(LaunchArgs.parse(arrayOf(UpdateProbe.FLAG, "/tmp")))
        assertEquals(UpdateProbe.parse(UpdateProbe.answer())?.adopts, Handover.ADOPTS)
    }

    @Test
    fun `this build stamps the handover it adopts`() {
        assertEquals(Handover.VERSION, BuildInfo.handover)
    }
}
