package iondrive.nop.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DirectoryChildrenTest {
    @Test fun `unchanged scans reuse the prepared listing and its rows`(@TempDir tmp: Path) {
        tmp.resolve("notes.txt").createFile()
        tmp.resolve("assets").createDirectories()
        val first = DirectoryChildren.prepare(listEntries(tmp.toFile()))
        val second = DirectoryChildren.prepare(listEntries(tmp.toFile(), first), first)

        assertSame(first, second)
        assertSame(first.rows, second.rows)
        assertSame(first.byName.getValue("notes.txt"), second.byName.getValue("notes.txt"))
        assertEquals(listOf("assets", "notes.txt"), first.map { it.name })
        // Directory streams need not enumerate unchanged entries in the same order.
        assertSame(first, DirectoryChildren.prepare(first.reversed(), first))
    }

    @Test fun `only changed entries and rows are replaced`(@TempDir tmp: Path) {
        val changed = tmp.resolve("changing").createFile()
        tmp.resolve("kept.txt").createFile()
        val first = DirectoryChildren.prepare(listEntries(tmp.toFile()))
        Files.delete(changed)
        changed.createDirectories()
        tmp.resolve("added.txt").createFile()

        val second = DirectoryChildren.prepare(listEntries(tmp.toFile(), first), first)
        assertNotSame(first, second)
        assertNotSame(first.byName.getValue("changing"), second.byName.getValue("changing"))
        assertSame(first.byName.getValue("kept.txt"), second.byName.getValue("kept.txt"))
        assertSame(first.byName.getValue("kept.txt").row, second.byName.getValue("kept.txt").row)
        assertEquals(listOf("changing", "added.txt", "kept.txt"), second.map { it.name })
    }

    @Test fun `changing open files reuses the cached display order`(@TempDir tmp: Path) {
        tmp.resolve("zeta.txt").createFile()
        tmp.resolve("Alpha.txt").createFile()
        val prepared = DirectoryChildren.prepare(listEntries(tmp.toFile()))
        val before = computeDirectoryEntries(tmp.toFile(), prepared, emptySet(), emptySet())
        val after = computeDirectoryEntries(tmp.toFile(), prepared, setOf(tmp.resolve("zeta.txt").toString()), emptySet())

        assertSame(prepared.rows, before)
        assertSame(before, after)
        assertEquals(listOf("Alpha.txt", "zeta.txt"), after.map { (it as TreeEntry.Node).file.name })
    }
}
