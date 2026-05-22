package iondrive.nop.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileIndexTest {

    @Test
    fun `build walks files and returns project-relative posix paths`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("src/main"))
        Files.writeString(tmp.resolve("src/main/Foo.kt"), "")
        Files.writeString(tmp.resolve("README.md"), "")

        val idx = FileIndex.build(tmp)
        assertTrue("README.md" in idx.files)
        assertTrue("src/main/Foo.kt" in idx.files)
        assertEquals(2, idx.files.size)
    }

    @Test
    fun `build skips ignored directories`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".git"))
        Files.writeString(tmp.resolve(".git/HEAD"), "")
        Files.createDirectories(tmp.resolve("node_modules"))
        Files.writeString(tmp.resolve("node_modules/index.js"), "")
        Files.writeString(tmp.resolve("kept.txt"), "")

        val idx = FileIndex.build(tmp)
        assertEquals(listOf("kept.txt"), idx.files)
    }

    @Test
    fun `build skips hidden files`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("visible.txt"), "")
        Files.writeString(tmp.resolve(".hidden"), "")
        val idx = FileIndex.build(tmp)
        assertFalse(".hidden" in idx.files)
        assertTrue("visible.txt" in idx.files)
    }

    @Test
    fun `save then load round-trips`(@TempDir tmp: Path) {
        val target = tmp.resolve("files.txt")
        val original = FileIndex(listOf("a/b.kt", "c.md"))
        FileIndex.save(target, original)
        val loaded = FileIndex.load(target)
        assertEquals(original.files, loaded.files)
    }

    @Test
    fun `load returns empty when the cache file is missing`(@TempDir tmp: Path) {
        val loaded = FileIndex.load(tmp.resolve("does-not-exist.txt"))
        assertEquals(emptyList<String>(), loaded.files)
    }
}
