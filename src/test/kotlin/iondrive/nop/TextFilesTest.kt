package iondrive.nop

import iondrive.nop.git.GitRepo
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TextFilesTest {

    @Test
    fun `hasBinaryExtension matches common archive, media, compiled, and database extensions`() {
        val binaries = listOf(
            "bundle.tar.gz", "release.TGZ", "pkg.zip", "data.zst", "image.png", "photo.JPEG",
            "app.jar", "Foo.class", "native.so", "tool.exe", "manual.pdf", "store.sqlite3",
            "table.parquet", "weights.safetensors", "distro.iso", "pkg.deb", "nested/dir/blob.bin",
        )
        for (name in binaries) {
            assertTrue(TextFiles.hasBinaryExtension(name), "expected binary extension for $name")
        }

        val texts = listOf(
            "Main.kt", "App.java", "README.md", "config.json", "diagram.svg", "data.csv",
            "notes.txt", "Makefile", ".gitignore", "Dockerfile", "schema.xml", "script.sh",
        )
        for (name in texts) {
            assertFalse(TextFiles.hasBinaryExtension(name), "expected text-eligible name for $name")
        }
    }

    @Test
    fun `read refuses known binary extension without reading content`(@TempDir tmp: Path) {
        val file = tmp.resolve("archive.tar.gz").toFile().apply { writeText("plain ascii bytes") }
        val result = TextFiles.read(file)
        assertEquals(TextFileRead.Refused(TextFileRefusal.Binary(file.length())), result)

        val opened = AtomicBoolean(false)
        val streamResult = TextFiles.readStream("archive.tar.gz", knownSize = 512L) {
            opened.set(true)
            ByteArrayInputStream("plain ascii bytes".toByteArray())
        }
        assertEquals(TextFileRead.Refused(TextFileRefusal.Binary(512L)), streamResult)
        assertFalse(opened.get(), "known binary extensions must not open the stream")
    }

    @Test
    fun `read refuses extensionless binary by sniffing only the leading bytes`(@TempDir tmp: Path) {
        val payload = ByteArray(100_000) { 'a'.code.toByte() }.also { it[500] = 0 }
        val file = tmp.resolve("binary-tool").toFile().apply { writeBytes(payload) }
        assertEquals(TextFileRead.Refused(TextFileRefusal.Binary(payload.size.toLong())), TextFiles.read(file))

        val readBytes = AtomicLong(0)
        val result = TextFiles.readStream("binary-tool", knownSize = 50_000_000L) {
            countingStream(payload, readBytes)
        }
        assertEquals(TextFileRead.Refused(TextFileRefusal.Binary(50_000_000L)), result)
        assertTrue(readBytes.get() <= TextFiles.BINARY_SNIFF_BYTES, "sniff read ${readBytes.get()} bytes")

        // A file within the size limit whose binary payload starts past the 8 KiB sniff window is still binary.
        val headerThenBinary = ByteArray(TextFiles.BINARY_SNIFF_BYTES + 2048) { 'a'.code.toByte() }.also {
            it[TextFiles.BINARY_SNIFF_BYTES + 512] = 0
        }
        val hybrid = tmp.resolve("hybrid-archive").toFile().apply { writeBytes(headerThenBinary) }
        assertEquals(
            TextFileRead.Refused(TextFileRefusal.Binary(headerThenBinary.size.toLong())),
            TextFiles.read(hybrid),
        )
    }

    @Test
    fun `read refuses oversized text files without pulling the whole file into memory`(@TempDir tmp: Path) {
        val limit = 16_384L
        val payload = ByteArray(40_000) { 'x'.code.toByte() }
        val file = tmp.resolve("large.log").toFile().apply { writeBytes(payload) }
        assertEquals(
            TextFileRead.Refused(TextFileRefusal.TooLarge(payload.size.toLong(), limit)),
            TextFiles.read(file, maxBytes = limit),
        )

        val readBytes = AtomicLong(0)
        val result = TextFiles.readStream("large.log", knownSize = 200_000_000L, maxBytes = limit) {
            countingStream(payload, readBytes)
        }
        assertEquals(TextFileRead.Refused(TextFileRefusal.TooLarge(200_000_000L, limit)), result)
        assertTrue(readBytes.get() <= TextFiles.BINARY_SNIFF_BYTES, "oversized refusal read ${readBytes.get()} bytes")
    }

    @Test
    fun `read enforces exact boundary and bounds streams that grow past knownSize`(@TempDir tmp: Path) {
        val limit = 10_000L
        val exact = tmp.resolve("exact.txt").toFile().apply { writeText("a".repeat(limit.toInt())) }
        val over = tmp.resolve("over.txt").toFile().apply { writeText("a".repeat(limit.toInt() + 1)) }

        val exactRead = TextFiles.read(exact, maxBytes = limit)
        assertTrue(exactRead is TextFileRead.Content && exactRead.text.length == limit.toInt())
        assertEquals(
            TextFileRead.Refused(TextFileRefusal.TooLarge(limit + 1, limit)),
            TextFiles.read(over, maxBytes = limit),
        )

        // Stream delivers more bytes than knownSize claimed (concurrent growth).
        val growing = ByteArray(100_000) { 'z'.code.toByte() }
        val readBytes = AtomicLong(0)
        val growingRead = TextFiles.readStream("growing.log", knownSize = 5_000L, maxBytes = limit) {
            countingStream(growing, readBytes)
        }
        assertTrue(growingRead is TextFileRead.Refused && growingRead.reason is TextFileRefusal.TooLarge)
        assertTrue(readBytes.get() <= limit + 1, "bounded read pulled ${readBytes.get()} bytes for limit $limit")
    }

    @Test
    fun `read allows empty files, utf8 text, and reports missing or directories cleanly`(@TempDir tmp: Path) {
        val empty = tmp.resolve("empty.txt").toFile().apply { writeBytes(ByteArray(0)) }
        val utf8 = tmp.resolve("utf8.txt").toFile().apply { writeText("héllo 世界\n") }
        val missing = tmp.resolve("missing.txt").toFile()
        val dir = tmp.resolve("subdir").toFile().apply { mkdirs() }

        assertEquals(TextFileRead.Content(""), TextFiles.read(empty))
        assertEquals(TextFileRead.Content("héllo 世界\n"), TextFiles.read(utf8))
        assertEquals(TextFileRead.Missing, TextFiles.read(missing))
        assertTrue(TextFiles.read(dir) is TextFileRead.Unreadable)
    }

    @Test
    fun `git readTextAt and readWorkingTreeText refuse binary and oversized blobs`(@TempDir dir: File) {
        val textFile = File(dir, "notes.txt").apply { writeText("hello\n") }
        val archiveFile = File(dir, "bundle.tar.gz").apply { writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 1, 2, 3)) }
        val binFile = File(dir, "native-bin").apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0)) }
        val bigFile = File(dir, "huge.log").apply { writeText("x".repeat(32_000)) }

        Git.init().setDirectory(dir).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("init").setAuthor("dev", "dev@example.test")
                .setCommitter("dev", "dev@example.test").setSign(false).call()
        }

        val repo = GitRepo.discover(dir.toPath())!!
        try {
            assertEquals(TextFileRead.Content("hello\n"), repo.readHeadText(textFile.name))
            assertEquals(TextFileRead.Missing, repo.readHeadText("absent.txt"))

            val headArchive = repo.readHeadText(archiveFile.name)
            assertTrue(headArchive is TextFileRead.Refused && headArchive.reason is TextFileRefusal.Binary)

            val headBin = repo.readHeadText(binFile.name)
            assertTrue(headBin is TextFileRead.Refused && headBin.reason is TextFileRefusal.Binary)

            val headBig = repo.readHeadText(bigFile.name, maxBytes = 16_384L)
            assertTrue(headBig is TextFileRead.Refused && headBig.reason is TextFileRefusal.TooLarge)

            val wtArchive = repo.readWorkingTreeText(archiveFile.name)
            assertTrue(wtArchive is TextFileRead.Refused && wtArchive.reason is TextFileRefusal.Binary)

            val wtBig = repo.readWorkingTreeText(bigFile.name, maxBytes = 16_384L)
            assertTrue(wtBig is TextFileRead.Refused && wtBig.reason is TextFileRefusal.TooLarge)
        } finally {
            repo.close()
        }
    }

    private fun countingStream(data: ByteArray, counter: AtomicLong): InputStream {
        val delegate = ByteArrayInputStream(data)
        return object : InputStream() {
            override fun read(): Int {
                val v = delegate.read()
                if (v >= 0) counter.incrementAndGet()
                return v
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = delegate.read(b, off, len)
                if (n > 0) counter.addAndGet(n.toLong())
                return n
            }
        }
    }
}
