package iondrive.nop.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class ProjectTreePanelTest {
    @Test fun `flattened row index matches DFS-of-expanded-nodes`(@TempDir tmp: Path) {
        // Layout (alphabetical, dirs before files at each level):
        //   root            <- row 0
        //     ansible/      <- row 1
        //       roles/      <- row 2
        //         a/        <- row 3  (expanded)
        //           tasks/  <- row 4
        //         b/        <- row 5  (not expanded — children not counted)
        //       all.yml     <- row 6
        //     README.md     <- row 7
        tmp.resolve("ansible/roles/a/tasks").createDirectories()
        tmp.resolve("ansible/roles/b").createDirectories()
        tmp.resolve("ansible/all.yml").createFile().writeText("")
        tmp.resolve("README.md").createFile().writeText("")

        val root = tmp.toFile()
        val openIds = setOf(
            root.absolutePath,
            tmp.resolve("ansible").absolutePathString(),
            tmp.resolve("ansible/roles").absolutePathString(),
            tmp.resolve("ansible/roles/a").absolutePathString(),
            // b/ is intentionally NOT in openIds
        )

        assertEquals(0, flattenedRowIndexOf(root, root.absolutePath, openIds))
        assertEquals(1, flattenedRowIndexOf(root, tmp.resolve("ansible").absolutePathString(), openIds))
        assertEquals(4, flattenedRowIndexOf(root, tmp.resolve("ansible/roles/a/tasks").absolutePathString(), openIds))
        assertEquals(5, flattenedRowIndexOf(root, tmp.resolve("ansible/roles/b").absolutePathString(), openIds))
        assertEquals(6, flattenedRowIndexOf(root, tmp.resolve("ansible/all.yml").absolutePathString(), openIds))
        assertEquals(7, flattenedRowIndexOf(root, tmp.resolve("README.md").absolutePathString(), openIds))
    }

    @Test fun `returns -1 when ancestor is collapsed`(@TempDir tmp: Path) {
        tmp.resolve("a/b/c").createDirectories()
        val root = tmp.toFile()
        val openIds = setOf(root.absolutePath) // a/ not expanded

        // a/b/c is not reachable when a/ is collapsed.
        assertEquals(-1, flattenedRowIndexOf(root, tmp.resolve("a/b/c").absolutePathString(), openIds))
    }

    @Test fun `directoryPathAtY finds the row whose band contains the pointer`() {
        val ranges = mapOf(
            "/root" to 0f..20f,
            "/root/a" to 20f..40f,
            "/root/b" to 40f..60f,
        )
        assertEquals("/root", directoryPathAtY(ranges, 5f))
        assertEquals("/root/a", directoryPathAtY(ranges, 25f))
        assertEquals("/root/b", directoryPathAtY(ranges, 60f))
    }

    @Test fun `directoryPathAtY returns null outside every tracked band`() {
        val ranges = mapOf("/root" to 0f..20f)
        assertEquals(null, directoryPathAtY(ranges, 25f))
        assertEquals(null, directoryPathAtY(ranges, -5f))
        assertEquals(null, directoryPathAtY(emptyMap(), 10f))
    }

    @Test fun `selectedFilesOf keeps existing non-root paths and drops the rest`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val a = tmp.resolve("a.txt").createFile().toFile()
        val sub = tmp.resolve("sub").createDirectories().toFile()
        val gone = tmp.resolve("gone.txt").toFile() // never created on disk

        val keys = setOf<Any?>(
            root.absolutePath, // the root itself is never a selectable target
            a.absolutePath,
            sub.absolutePath,
            gone.absolutePath, // filtered out — doesn't exist
            42,                // non-String keys are ignored
        )
        val names = selectedFilesOf(keys, root.absolutePath).map { it.name }.toSet()
        assertEquals(setOf("a.txt", "sub"), names)
    }

    @Test fun `selectedFilesOf is empty when only the root is selected`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        assertEquals(emptyList(), selectedFilesOf(setOf(root.absolutePath), root.absolutePath))
    }

    @Test fun `menuTargetsFor returns the whole selection when the clicked row is in it`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").toFile()
        val b = tmp.resolve("b.txt").toFile()
        val selection = listOf(a, b)
        assertEquals(selection, menuTargetsFor(a, selection))
    }

    @Test fun `menuTargetsFor targets only the clicked row when it is outside the selection`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").toFile()
        val b = tmp.resolve("b.txt").toFile()
        val c = tmp.resolve("c.txt").toFile()
        assertEquals(listOf(c), menuTargetsFor(c, listOf(a, b)))
    }

    @Test fun `menuTargetsFor falls back to the clicked row when nothing is selected`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").toFile()
        assertEquals(listOf(a), menuTargetsFor(a, emptyList()))
    }

    @Test fun `computeDirectoryEntries does not compress when files count is small`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val files = (1..4).map { tmp.resolve("file_$it.txt").createFile().toFile() }
        val entries = computeDirectoryEntries(dir, files, emptySet(), emptySet(), minItemsForEllipsis = 6)
        assertEquals(4, entries.size)
        assert(entries.all { it is TreeEntry.Node })
    }

    @Test fun `computeDirectoryEntries compresses long run of files around an open file`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val files = (0..19).map { tmp.resolve(String.format("file_%02d.txt", it)).createFile().toFile() }
        val openFile = files[10]
        val entries = computeDirectoryEntries(
            dir = dir,
            files = files,
            openFilePaths = setOf(openFile.absolutePath),
            expandedEllipsisKeys = emptySet(),
            minItemsForEllipsis = 6,
        )

        // Expected:
        // [0] Ellipsis (files 0..8, count 9)
        // [1] Node (file 9 - context)
        // [2] Node (file 10 - open)
        // [3] Node (file 11 - context)
        // [4] Ellipsis (files 12..19, count 8)
        assertEquals(5, entries.size)
        assert(entries[0] is TreeEntry.Ellipsis)
        assertEquals(9, (entries[0] as TreeEntry.Ellipsis).hiddenFiles.size)
        assert(!(entries[0] as TreeEntry.Ellipsis).isDirectory)

        assert(entries[1] is TreeEntry.Node)
        assertEquals(files[9].absolutePath, (entries[1] as TreeEntry.Node).file.absolutePath)

        assert(entries[2] is TreeEntry.Node)
        assertEquals(files[10].absolutePath, (entries[2] as TreeEntry.Node).file.absolutePath)

        assert(entries[3] is TreeEntry.Node)
        assertEquals(files[11].absolutePath, (entries[3] as TreeEntry.Node).file.absolutePath)

        assert(entries[4] is TreeEntry.Ellipsis)
        assertEquals(8, (entries[4] as TreeEntry.Ellipsis).hiddenFiles.size)
    }

    @Test fun `computeDirectoryEntries expands ellipsis when key in expandedEllipsisKeys`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val files = (0..19).map { tmp.resolve(String.format("file_%02d.txt", it)).createFile().toFile() }
        val openFile = files[10]

        // First find the rangeId of the first ellipsis
        val initialEntries = computeDirectoryEntries(
            dir = dir,
            files = files,
            openFilePaths = setOf(openFile.absolutePath),
            expandedEllipsisKeys = emptySet(),
            minItemsForEllipsis = 6,
        )
        val ellipsis1 = initialEntries[0] as TreeEntry.Ellipsis

        val expandedEntries = computeDirectoryEntries(
            dir = dir,
            files = files,
            openFilePaths = setOf(openFile.absolutePath),
            expandedEllipsisKeys = setOf(ellipsis1.rangeId),
            minItemsForEllipsis = 6,
        )

        // The first ellipsis should now be expanded into 9 nodes + 1 collapse entry
        // Total: 9 nodes + 1 collapse + 3 nodes (9, 10, 11) + 1 trailing ellipsis = 14 entries
        assertEquals(14, expandedEntries.size)
        for (i in 0..8) {
            assert(expandedEntries[i] is TreeEntry.Node)
            assertEquals(files[i].absolutePath, (expandedEntries[i] as TreeEntry.Node).file.absolutePath)
        }
        assert(expandedEntries[9] is TreeEntry.Collapse)
        assertEquals(9, (expandedEntries[9] as TreeEntry.Collapse).count)
        assert(!(expandedEntries[9] as TreeEntry.Collapse).isDirectory)
    }

    @Test fun `computeDirectoryEntries shows first 3 files and ellipsis when no files are open`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val files = (0..19).map { tmp.resolve(String.format("file_%02d.txt", it)).createFile().toFile() }

        val entries = computeDirectoryEntries(
            dir = dir,
            files = files,
            openFilePaths = emptySet(),
            expandedEllipsisKeys = emptySet(),
            minItemsForEllipsis = 6,
        )

        // First 3 files as nodes + 1 ellipsis for remaining 17 files
        assertEquals(4, entries.size)
        assert(entries[0] is TreeEntry.Node)
        assert(entries[1] is TreeEntry.Node)
        assert(entries[2] is TreeEntry.Node)
        assert(entries[3] is TreeEntry.Ellipsis)
        assertEquals(17, (entries[3] as TreeEntry.Ellipsis).hiddenFiles.size)
        assert(!(entries[3] as TreeEntry.Ellipsis).isDirectory)
    }

    @Test fun `computeDirectoryEntries compresses long run of directories around a dir containing open file`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val dirs = (0..19).map { tmp.resolve(String.format("module_%02d", it)).createDirectories().toFile() }
        val targetDir = dirs[10]
        val openFile = targetDir.toPath().resolve("Main.kt").createFile().toFile()

        val entries = computeDirectoryEntries(
            dir = dir,
            files = dirs,
            openFilePaths = setOf(openFile.absolutePath),
            expandedEllipsisKeys = emptySet(),
            minItemsForEllipsis = 6,
        )

        // Expected:
        // [0] Ellipsis (dirs 0..8, count 9, isDirectory = true)
        // [1] Node (module_09 - context)
        // [2] Node (module_10 - contains open file)
        // [3] Node (module_11 - context)
        // [4] Ellipsis (dirs 12..19, count 8, isDirectory = true)
        assertEquals(5, entries.size)
        assert(entries[0] is TreeEntry.Ellipsis)
        val ellipsis0 = entries[0] as TreeEntry.Ellipsis
        assert(ellipsis0.isDirectory)
        assertEquals(9, ellipsis0.hiddenItems.size)

        assert(entries[1] is TreeEntry.Node)
        assertEquals(dirs[9].absolutePath, (entries[1] as TreeEntry.Node).file.absolutePath)

        assert(entries[2] is TreeEntry.Node)
        assertEquals(dirs[10].absolutePath, (entries[2] as TreeEntry.Node).file.absolutePath)

        assert(entries[3] is TreeEntry.Node)
        assertEquals(dirs[11].absolutePath, (entries[3] as TreeEntry.Node).file.absolutePath)

        assert(entries[4] is TreeEntry.Ellipsis)
        val ellipsis4 = entries[4] as TreeEntry.Ellipsis
        assert(ellipsis4.isDirectory)
        assertEquals(8, ellipsis4.hiddenItems.size)
    }

    @Test fun `computeDirectoryEntries expands directory ellipsis and provides directory collapse entry`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val dirs = (0..19).map { tmp.resolve(String.format("dir_%02d", it)).createDirectories().toFile() }
        val targetDir = dirs[10]
        val openFile = targetDir.toPath().resolve("file.txt").createFile().toFile()

        val initial = computeDirectoryEntries(
            dir = dir,
            files = dirs,
            openFilePaths = setOf(openFile.absolutePath),
            expandedEllipsisKeys = emptySet(),
            minItemsForEllipsis = 6,
        )
        val ellipsis0 = initial[0] as TreeEntry.Ellipsis

        val expanded = computeDirectoryEntries(
            dir = dir,
            files = dirs,
            openFilePaths = setOf(openFile.absolutePath),
            expandedEllipsisKeys = setOf(ellipsis0.rangeId),
            minItemsForEllipsis = 6,
        )

        // First 9 dirs expanded as Nodes + 1 Collapse(isDirectory = true) + 3 Nodes + 1 trailing Ellipsis
        assertEquals(14, expanded.size)
        for (i in 0..8) {
            assert(expanded[i] is TreeEntry.Node)
            assertEquals(dirs[i].absolutePath, (expanded[i] as TreeEntry.Node).file.absolutePath)
        }
        assert(expanded[9] is TreeEntry.Collapse)
        val collapse = expanded[9] as TreeEntry.Collapse
        assertEquals(9, collapse.count)
        assert(collapse.isDirectory)
    }

    @Test fun `computeDirectoryEntries keeps expanded directories visible`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val dirs = (0..19).map { tmp.resolve(String.format("pkg_%02d", it)).createDirectories().toFile() }
        val expandedDir = dirs[5]

        val entries = computeDirectoryEntries(
            dir = dir,
            files = dirs,
            openFilePaths = emptySet(),
            expandedEllipsisKeys = emptySet(),
            openDirectoryIds = setOf(expandedDir.absolutePath),
            minItemsForEllipsis = 6,
        )

        // Expanded directory pkg_05 and context (pkg_04, pkg_06) must be visible
        // pkg_00..pkg_03 -> Ellipsis (4 dirs)
        // pkg_04 -> Node
        // pkg_05 -> Node
        // pkg_06 -> Node
        // pkg_07..pkg_19 -> Ellipsis (13 dirs)
        assertEquals(5, entries.size)
        assert(entries[0] is TreeEntry.Ellipsis)
        assertEquals(4, (entries[0] as TreeEntry.Ellipsis).hiddenItems.size)

        assert(entries[1] is TreeEntry.Node)
        assertEquals(dirs[4].absolutePath, (entries[1] as TreeEntry.Node).file.absolutePath)
        assert(entries[2] is TreeEntry.Node)
        assertEquals(dirs[5].absolutePath, (entries[2] as TreeEntry.Node).file.absolutePath)
        assert(entries[3] is TreeEntry.Node)
        assertEquals(dirs[6].absolutePath, (entries[3] as TreeEntry.Node).file.absolutePath)

        assert(entries[4] is TreeEntry.Ellipsis)
        assertEquals(13, (entries[4] as TreeEntry.Ellipsis).hiddenItems.size)
    }
}
