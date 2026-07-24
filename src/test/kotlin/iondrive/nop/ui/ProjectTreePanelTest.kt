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

    @Test fun `deleteTargetsFor returns the whole selection when the clicked row is in it`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").toFile()
        val b = tmp.resolve("b.txt").toFile()
        val selection = listOf(a, b)
        assertEquals(selection, deleteTargetsFor(a, selection))
    }

    @Test fun `deleteTargetsFor targets only the clicked row when it is outside the selection`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").toFile()
        val b = tmp.resolve("b.txt").toFile()
        val c = tmp.resolve("c.txt").toFile()
        assertEquals(listOf(c), deleteTargetsFor(c, listOf(a, b)))
    }

    @Test fun `deleteTargetsFor falls back to the clicked row when nothing is selected`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").toFile()
        assertEquals(listOf(a), deleteTargetsFor(a, emptyList()))
    }
}
