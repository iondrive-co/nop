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
import kotlin.test.assertNull
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

    @Test fun `moveFile relocates a file into a directory`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        source.writeText("hello")
        val dir = tmp.resolve("dest").createDirectories().toFile()
        val moved = FileOperations.moveFile(source, dir)
        assertEquals(tmp.resolve("dest/orig.txt").toFile(), moved)
        assertEquals("hello", moved.readText())
        assertFalse(source.exists())
    }

    @Test fun `moveFile relocates a directory and its contents`(@TempDir tmp: Path) {
        val source = tmp.resolve("src").createDirectories().toFile()
        tmp.resolve("src/child.txt").createFile().writeText("x")
        val dir = tmp.resolve("dest").createDirectories().toFile()
        val moved = FileOperations.moveFile(source, dir)
        assertEquals(tmp.resolve("dest/src").toFile(), moved)
        assertTrue(tmp.resolve("dest/src/child.txt").toFile().isFile)
        assertFalse(source.exists())
    }

    @Test fun `moveFile is a no-op when dropped on its own parent`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        val moved = FileOperations.moveFile(source, tmp.toFile())
        assertEquals(source, moved)
        assertTrue(source.exists())
    }

    @Test fun `moveFile refuses to overwrite an existing entry`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        val dir = tmp.resolve("dest").createDirectories().toFile()
        tmp.resolve("dest/orig.txt").createFile().toFile().writeText("existing")
        assertThrows<IOException> { FileOperations.moveFile(source, dir) }
        assertTrue(source.exists())
        assertEquals("existing", tmp.resolve("dest/orig.txt").toFile().readText())
    }

    @Test fun `moveFile refuses to move a directory into itself`(@TempDir tmp: Path) {
        val source = tmp.resolve("src").createDirectories().toFile()
        assertThrows<IllegalArgumentException> { FileOperations.moveFile(source, source) }
    }

    @Test fun `moveFile refuses to move a directory into its own descendant`(@TempDir tmp: Path) {
        val source = tmp.resolve("src").createDirectories().toFile()
        val child = tmp.resolve("src/child").createDirectories().toFile()
        assertThrows<IllegalArgumentException> { FileOperations.moveFile(source, child) }
        assertTrue(source.exists())
    }

    @Test fun `moveFile refuses a non-directory target`(@TempDir tmp: Path) {
        val source = tmp.resolve("orig.txt").createFile().toFile()
        val notADir = tmp.resolve("other.txt").createFile().toFile()
        assertThrows<IllegalArgumentException> { FileOperations.moveFile(source, notADir) }
    }

    @Test fun `copyInto keeps the name when pasting into a different directory`(@TempDir tmp: Path) {
        val source = tmp.resolve("Main.kt").apply { writeText("hi") }.toFile()
        val target = tmp.resolve("sub").createDirectories().toFile()

        val copy = FileOperations.copyInto(source, target)

        assertEquals("Main.kt", copy.name)
        assertEquals("hi", copy.readText())
        assertTrue(source.isFile)
    }

    @Test fun `copyInto adds a copy suffix when pasting into the source's own directory`(@TempDir tmp: Path) {
        val source = tmp.resolve("Main.kt").apply { writeText("hi") }.toFile()

        val first = FileOperations.copyInto(source, tmp.toFile())
        val second = FileOperations.copyInto(source, tmp.toFile())

        assertEquals("Main (copy).kt", first.name)
        assertEquals("Main (copy 2).kt", second.name)
        assertEquals("hi", first.readText())
    }

    @Test fun `copyInto copies a directory recursively`(@TempDir tmp: Path) {
        tmp.resolve("pkg/inner").createDirectories()
        tmp.resolve("pkg/inner/a.txt").writeText("deep")
        val source = tmp.resolve("pkg").toFile()

        val copy = FileOperations.copyInto(source, tmp.toFile())

        assertEquals("pkg (copy)", copy.name)
        assertEquals("deep", tmp.resolve("pkg (copy)/inner/a.txt").toFile().readText())
    }

    @Test fun `copyInto refuses a directory into its own subtree`(@TempDir tmp: Path) {
        val source = tmp.resolve("pkg").createDirectories().toFile()
        val inner = tmp.resolve("pkg/inner").createDirectories().toFile()

        assertThrows<IllegalArgumentException> { FileOperations.copyInto(source, inner) }
        assertThrows<IllegalArgumentException> { FileOperations.copyInto(source, source) }
    }

    @Test fun `copyInto rejects a source that no longer exists`(@TempDir tmp: Path) {
        val gone = tmp.resolve("gone.txt").toFile()
        assertThrows<IllegalArgumentException> { FileOperations.copyInto(gone, tmp.toFile()) }
    }

    @Test fun `copyName leaves a free name alone`() {
        assertEquals("Main.kt", FileOperations.copyName("Main.kt") { false })
    }

    @Test fun `copyName suffixes an extensionless name at the end`() {
        assertEquals("README (copy)", FileOperations.copyName("README") { it == "README" })
    }

    @Test fun `copyName treats a leading dot as part of the name`() {
        assertEquals(".gitignore (copy)", FileOperations.copyName(".gitignore") { it == ".gitignore" })
    }

    @Test fun `copyName numbers past a taken copy name`() {
        val taken = setOf("a.txt", "a (copy).txt", "a (copy 2).txt")
        assertEquals("a (copy 3).txt", FileOperations.copyName("a.txt") { it in taken })
    }

    @Test fun `rename changes the name in place`(@TempDir tmp: Path) {
        val file = tmp.resolve("Old.kt").apply { writeText("body") }.toFile()

        val renamed = FileOperations.rename(file, "New.kt")

        assertEquals(tmp.resolve("New.kt").toFile(), renamed)
        assertEquals("body", renamed.readText())
        assertFalse(file.exists())
    }

    @Test fun `rename moves a directory's contents with it`(@TempDir tmp: Path) {
        tmp.resolve("pkg/inner").createDirectories()
        tmp.resolve("pkg/inner/a.txt").writeText("deep")

        val renamed = FileOperations.rename(tmp.resolve("pkg").toFile(), "renamed")

        assertEquals("renamed", renamed.name)
        assertEquals("deep", tmp.resolve("renamed/inner/a.txt").toFile().readText())
    }

    @Test fun `rename to the same name is a no-op`(@TempDir tmp: Path) {
        val file = tmp.resolve("Same.kt").createFile().toFile()
        assertEquals(file.absoluteFile, FileOperations.rename(file, "Same.kt"))
        assertTrue(file.exists())
    }

    @Test fun `rename trims surrounding whitespace`(@TempDir tmp: Path) {
        val file = tmp.resolve("Old.kt").createFile().toFile()
        assertEquals("New.kt", FileOperations.rename(file, "  New.kt  ").name)
    }

    @Test fun `rename rejects an existing sibling`(@TempDir tmp: Path) {
        val file = tmp.resolve("Old.kt").createFile().toFile()
        tmp.resolve("Taken.kt").createFile()
        assertThrows<IOException> { FileOperations.rename(file, "Taken.kt") }
        assertTrue(file.exists())
    }

    @Test fun `rename rejects a path separator`(@TempDir tmp: Path) {
        val file = tmp.resolve("Old.kt").createFile().toFile()
        assertThrows<IllegalArgumentException> { FileOperations.rename(file, "sub/New.kt") }
        assertThrows<IllegalArgumentException> { FileOperations.rename(file, "sub\\New.kt") }
    }

    @Test fun `rename rejects a blank or traversing name`(@TempDir tmp: Path) {
        val file = tmp.resolve("Old.kt").createFile().toFile()
        assertThrows<IllegalArgumentException> { FileOperations.rename(file, "   ") }
        assertThrows<IllegalArgumentException> { FileOperations.rename(file, "..") }
    }

    @Test fun `rename rejects a target that no longer exists`(@TempDir tmp: Path) {
        assertThrows<IllegalArgumentException> { FileOperations.rename(tmp.resolve("gone.txt").toFile(), "New.txt") }
    }

    @Test fun `remapPath moves the renamed entry itself`(@TempDir tmp: Path) {
        val old = tmp.resolve("old").toFile()
        val new = tmp.resolve("new").toFile()
        assertEquals(new.absoluteFile, FileOperations.remapPath(old, old, new))
    }

    @Test fun `remapPath keeps the relative position under a renamed directory`(@TempDir tmp: Path) {
        val old = tmp.resolve("old").toFile()
        val new = tmp.resolve("new").toFile()
        val nested = tmp.resolve("old/inner/a.txt").toFile()

        assertEquals(tmp.resolve("new/inner/a.txt").toFile(), FileOperations.remapPath(nested, old, new))
    }

    @Test fun `remapPath ignores an unrelated path`(@TempDir tmp: Path) {
        val old = tmp.resolve("old").toFile()
        val new = tmp.resolve("new").toFile()
        assertNull(FileOperations.remapPath(tmp.resolve("elsewhere/a.txt").toFile(), old, new))
        // A sibling that merely shares the prefix isn't inside the renamed directory.
        assertNull(FileOperations.remapPath(tmp.resolve("oldish").toFile(), old, new))
    }
}
