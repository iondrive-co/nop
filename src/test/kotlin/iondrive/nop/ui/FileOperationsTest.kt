package iondrive.nop.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileOperationsTest {
    @Test fun `parentDirFor returns the directory itself`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        assertEquals(dir, FileOperations.parentDirFor(dir))
    }

    @Test fun `parentDirFor returns a file's parent`(@TempDir tmp: Path) {
        val file = tmp.resolve("a.txt").createFile().toFile()
        assertEquals(tmp.toFile(), FileOperations.parentDirFor(file))
    }

    @Test fun `createFile makes an empty file`(@TempDir tmp: Path) {
        val created = FileOperations.createFile(tmp.toFile(), "Main.kt")
        assertTrue(created.isFile)
        assertEquals(tmp.resolve("Main.kt").toFile(), created)
        assertEquals("", created.readText())
    }

    @Test fun `createFile creates intermediate directories`(@TempDir tmp: Path) {
        val created = FileOperations.createFile(tmp.toFile(), "sub/dir/Main.kt")
        assertTrue(created.isFile)
        assertTrue(tmp.resolve("sub/dir").toFile().isDirectory)
    }

    @Test fun `createFile rejects an existing file`(@TempDir tmp: Path) {
        tmp.resolve("a.txt").createFile()
        assertThrows<IOException> { FileOperations.createFile(tmp.toFile(), "a.txt") }
    }

    @Test fun `createFile rejects a blank name`(@TempDir tmp: Path) {
        assertThrows<IllegalArgumentException> { FileOperations.createFile(tmp.toFile(), "   ") }
    }

    @Test fun `createFile rejects parent traversal`(@TempDir tmp: Path) {
        assertThrows<IllegalArgumentException> { FileOperations.createFile(tmp.toFile(), "../escape.txt") }
    }

    @Test fun `createDirectory nests on slashes`(@TempDir tmp: Path) {
        val created = FileOperations.createDirectory(tmp.toFile(), "a/b/c")
        assertEquals(tmp.resolve("a/b/c").toFile(), created)
        assertTrue(created.isDirectory)
        assertTrue(tmp.resolve("a/b").toFile().isDirectory)
    }

    @Test fun `createDirectory rejects an existing directory`(@TempDir tmp: Path) {
        tmp.resolve("a").createDirectories()
        assertThrows<IOException> { FileOperations.createDirectory(tmp.toFile(), "a") }
    }

    @Test fun `createPackage turns dots into nested directories`(@TempDir tmp: Path) {
        val created = FileOperations.createPackage(tmp.toFile(), "com.example.app")
        assertEquals(tmp.resolve("com/example/app").toFile(), created)
        assertTrue(created.isDirectory)
        assertTrue(tmp.resolve("com/example").toFile().isDirectory)
    }

    @Test fun `createPackage also accepts slashes`(@TempDir tmp: Path) {
        val created = FileOperations.createPackage(tmp.toFile(), "com/example.app")
        assertEquals(tmp.resolve("com/example/app").toFile(), created)
    }

    @Test fun `copyFile duplicates content under a new name`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        source.writeText("hello world")
        val copy = FileOperations.copyFile(source, "copy.txt")
        assertEquals(tmp.resolve("copy.txt").toFile(), copy)
        assertEquals("hello world", copy.readText())
        assertTrue(source.exists(), "source must be left in place")
    }

    @Test fun `copyFile can place the copy in a subdirectory`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        source.writeText("x")
        val copy = FileOperations.copyFile(source, "backup/orig.txt")
        assertEquals(tmp.resolve("backup/orig.txt").toFile(), copy)
        assertEquals("x", copy.readText())
    }

    @Test fun `copyFile refuses to overwrite`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        source.writeText("a")
        tmp.resolve("copy.txt").createFile().toFile().writeText("b")
        assertThrows<IOException> { FileOperations.copyFile(source, "copy.txt") }
        assertEquals("b", tmp.resolve("copy.txt").toFile().readText())
    }

    @Test fun `copyFile rejects copying a directory`(@TempDir tmp: Path) {
        val dir = tmp.resolve("d").createDirectories().toFile()
        assertThrows<IllegalArgumentException> { FileOperations.copyFile(dir, "d2") }
    }

    @Test fun `names with empty segments are rejected`(@TempDir tmp: Path) {
        assertThrows<IllegalArgumentException> { FileOperations.createDirectory(tmp.toFile(), "a//b") }
        assertFalse(tmp.resolve("a").toFile().exists())
    }
}
