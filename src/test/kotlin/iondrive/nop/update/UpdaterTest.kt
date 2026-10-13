package iondrive.nop.update

import com.sun.net.httpserver.HttpServer
import iondrive.nop.ipc.Handover
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UpdaterTest {

    @TempDir
    lateinit var tmp: Path

    private val key: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val served = HashMap<String, ByteArray>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = served[exchange.requestURI.path]
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

    private fun updater(signedWith: KeyPair = key) =
        Updater(URI.create("$base/latest"), signedWith.public, platform = "linux-x64")

    /** The install a release nop runs from: an image under `opt/nop`. */
    private fun install(): Path {
        val root = tmp.resolve("opt/nop")
        root.resolve("bin").createDirectories()
        root.resolve("bin/nop").writeText("old launcher")
        root.resolve("lib/app").createDirectories()
        root.resolve("lib/app/nop-old.jar").writeText("old jar")
        return root
    }

    /**
     * Publishes release [version] on the stand-in for GitHub: an image with a launcher, the app jar
     * stamped with [version] and [handover], and a dependency, as a tarball with a signed manifest.
     * [alter] changes the image after the manifest is written, as a tampered tarball would be.
     */
    private fun publish(
        version: String = "9.9.9",
        handover: Int = Handover.VERSION,
        signer: KeyPair = key,
        alter: (Path) -> Unit = {},
    ) {
        val image = tmp.resolve("release-$version/nop")
        image.resolve("bin").createDirectories()
        image.resolve("bin/nop").writeText("new launcher")
        Files.setPosixFilePermissions(image.resolve("bin/nop"), PosixFilePermissions.fromString("rwxr-xr-x"))
        image.resolve("lib/app").createDirectories()
        ZipOutputStream(Files.newOutputStream(image.resolve("lib/app/nop-new.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry(BuildInfo.RESOURCE_PATH))
            zip.write("version=$version\nhandover=$handover\n".toByteArray())
            zip.closeEntry()
        }
        image.resolve("lib/app/library.jar").writeText("a dependency")

        val files = Files.walk(image).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }.joinToString(",\n") { f ->
            val rel = image.relativize(f).joinToString("/")
            """{"path":"$rel","sha256":"${sha256(Files.readAllBytes(f))}","size":${Files.size(f)},"executable":${Files.isExecutable(f)}}"""
        }
        alter(image)
        val archive = tmp.resolve("release-$version/nop-$version.tar.gz")
        val tar = ProcessBuilder("tar", "-czf", archive.toString(), "-C", image.parent.toString(), "nop").start()
        assertEquals(0, tar.waitFor())
        val bytes = Files.readAllBytes(archive)
        val manifest = """
            {"format":1,"version":"$version","platform":"linux-x64",
             "archive":{"name":"nop-$version.tar.gz","sha256":"${sha256(bytes)}","size":${bytes.size},"root":"nop"},
             "files":[$files]}
        """.trimIndent().toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer.private)
            update(manifest)
            sign()
        }
        served["/assets/update.json"] = manifest
        served["/assets/update.json.sig"] = signature
        served["/assets/nop-$version.tar.gz"] = bytes
        served["/latest"] = """
            {"tag_name":"v$version","assets":[
              {"name":"update.json","browser_download_url":"$base/assets/update.json"},
              {"name":"update.json.sig","browser_download_url":"$base/assets/update.json.sig"},
              {"name":"nop-$version.tar.gz","browser_download_url":"$base/assets/nop-$version.tar.gz"}]}
        """.trimIndent().toByteArray()
    }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun leftovers(root: Path) = listOf(Updater.DOWNLOAD, Updater.UNPACKING).filter { Updater.sibling(root, it).exists() }

    @Test
    fun `a signed release is staged beside the install and swapped in by install`() {
        val root = install()
        publish()
        val updater = updater()

        val latest = updater.latest()
        assertEquals(Version(9, 9, 9), latest.version)
        val staged = updater.stage(root, latest)

        assertEquals(Updater.Staged(Version(9, 9, 9), Handover.VERSION), staged)
        assertEquals(staged, updater.staged(root))
        assertEquals("old launcher", root.resolve("bin/nop").readText(), "staging leaves the running install alone")
        assertEquals(emptyList(), leftovers(root))

        assertEquals(staged, updater.install(root))
        assertEquals("new launcher", root.resolve("bin/nop").readText())
        assertTrue(Files.isExecutable(root.resolve("bin/nop")))
        assertEquals("old launcher", Updater.sibling(root, Updater.PREVIOUS).resolve("bin/nop").readText())
        assertNull(updater.staged(root), "nothing is left staged once it is in")
    }

    @Test
    fun `a manifest signed with any other key is refused, and nothing is downloaded`() {
        val root = install()
        publish(signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        val updater = updater()

        val refused = assertFailsWith<UpdateException> { updater.stage(root, updater.latest()) }

        assertTrue("not signed" in refused.message!!, refused.message)
        assertNull(updater.staged(root))
        assertEquals(emptyList(), leftovers(root))
    }

    @Test
    fun `a tarball that differs from the signed manifest is refused`() {
        val root = install()
        publish(alter = { image -> image.resolve("lib/app/library.jar").writeText("something else") })
        val updater = updater()

        assertFailsWith<UpdateException> { updater.stage(root, updater.latest()) }

        assertNull(updater.staged(root))
        assertEquals(emptyList(), leftovers(root))
        assertEquals("old launcher", root.resolve("bin/nop").readText())
    }

    @Test
    fun `a file the manifest does not list is refused`() {
        val root = install()
        publish(alter = { image -> image.resolve("lib/app/extra.jar").writeText("unlisted") })
        val updater = updater()

        val refused = assertFailsWith<UpdateException> { updater.stage(root, updater.latest()) }

        assertTrue("extra.jar" in refused.message!!, refused.message)
        assertNull(updater.staged(root))
    }

    @Test
    fun `a restart installs a staged update only when its build adopts this nop's handover`() {
        val root = install()
        val launcher = root.resolve("bin/nop")

        publish(handover = Handover.VERSION + 1)
        updater().stage(root, updater().latest())
        assertNull(SelfUpdate.installForRestart(launcher), "an update that would hang up every terminal waits")
        assertEquals("old launcher", launcher.readText())

        publish(version = "9.9.10")
        updater().stage(root, updater().latest())
        assertEquals(Version(9, 9, 10), SelfUpdate.installForRestart(launcher))
        assertEquals("new launcher", launcher.readText())
    }

    @Test
    fun `a copy of a checkout's build is never swapped for a release`() {
        val root = install()
        root.resolve(".built-from-checkout").writeText("/home/dev/nop\n")
        publish()
        updater().stage(root, updater().latest())

        assertNull(SelfUpdate.installForRestart(root.resolve("bin/nop")))
        assertFalse(root.resolve("bin/nop").readText() == "new launcher")
    }

    @Test
    fun `this build stamps the handover it adopts`() {
        assertEquals(Handover.VERSION, BuildInfo.handover)
    }
}
