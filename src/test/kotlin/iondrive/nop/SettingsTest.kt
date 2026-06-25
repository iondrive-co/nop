package iondrive.nop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SettingsTest {
    private val originalRoot: Path = Settings.configRoot

    @AfterEach
    fun restoreRoot() {
        Settings.configRoot = originalRoot
    }

    @Test
    fun `loadOpenProjects returns empty when no state file exists`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertTrue(Settings.loadOpenProjects().isEmpty())
    }

    @Test
    fun `save then load round-trips a single open project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("some/project").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(project))

        val loaded = Settings.loadOpenProjects()
        assertEquals(listOf(project.toAbsolutePath().normalize()), loaded)
    }

    @Test
    fun `save then load preserves order across multiple open projects`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        val c = tmp.resolve("c").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(a, b, c))

        assertEquals(
            listOf(a, b, c).map { it.toAbsolutePath().normalize() },
            Settings.loadOpenProjects(),
        )
    }

    @Test
    fun `loadOpenProjects tolerates a blank state file`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "\n   \n")
        }
        assertTrue(Settings.loadOpenProjects().isEmpty(), "blank state should produce empty list — got file at $state")
    }

    @Test
    fun `legacy single-line state still resolves the project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "/home/u/old-format-project\n")
        }
        assertEquals(listOf(Paths.get("/home/u/old-format-project")), Settings.loadOpenProjects())
    }

    @Test
    fun `legacy project= key is migrated into the open list`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "project=/home/u/proj\ntheme=light\n")
        }
        assertEquals(listOf(Paths.get("/home/u/proj")), Settings.loadOpenProjects())

        // Saving a fresh list should drop the legacy `project=` key.
        val replacement = tmp.resolve("replacement").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(replacement))
        val raw = Files.readString(state)
        val keys = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue("project" !in keys, "legacy project= key should be dropped after a save, got:\n$raw")
        assertTrue(raw.contains("open.0="), "should write open.0= entry, got:\n$raw")
    }

    @Test
    fun `window geometry round-trips alongside the open projects`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(project))
        Settings.saveWindowGeometry(WindowGeometry(width = 1024, height = 768, x = 50, y = 100))

        val w = Settings.loadWindowGeometry()
        assertEquals(WindowGeometry(1024, 768, 50, 100), w)

        // Saving geometry must not clobber the open-projects list.
        assertEquals(listOf(project.toAbsolutePath().normalize()), Settings.loadOpenProjects())
    }

    @Test
    fun `loadWindowGeometry returns null when size missing`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Settings.saveOpenProjects(listOf(tmp.resolve("project").also { Files.createDirectories(it) }))
        assertNull(Settings.loadWindowGeometry())
    }

    @Test
    fun `recent projects round-trip in saved order`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.saveRecentProjects(listOf(a, b))
        assertEquals(
            listOf(a, b).map { it.toAbsolutePath().normalize() },
            Settings.loadRecentProjects(),
        )
    }

    @Test
    fun `addRecentProject prepends and dedupes`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.addRecentProject(a)
        Settings.addRecentProject(b)
        // Re-adding `a` should move it to the front, not duplicate.
        Settings.addRecentProject(a)
        assertEquals(
            listOf(a, b).map { it.toAbsolutePath().normalize() },
            Settings.loadRecentProjects(),
        )
    }

    @Test
    fun `addRecentProject caps the list at ten`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val dirs = (0 until 15).map { i ->
            tmp.resolve("p$i").also { Files.createDirectories(it) }
        }
        for (d in dirs) Settings.addRecentProject(d)

        val loaded = Settings.loadRecentProjects()
        assertEquals(10, loaded.size, "should cap at 10, got ${loaded.size}")
        // Most-recently-added is first; earliest entries fell off.
        assertEquals(dirs.last().toAbsolutePath().normalize(), loaded.first())
        assertEquals(dirs[5].toAbsolutePath().normalize(), loaded.last())
    }

    @Test
    fun `loadSplitRatios returns nulls when nothing is saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val r = Settings.loadSplitRatios()
        assertNull(r.horizontal)
        assertNull(r.vertical)
    }

    @Test
    fun `split ratios round-trip`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Settings.saveSplitRatios(horizontal = 0.31f, vertical = 0.42f)
        val r = Settings.loadSplitRatios()
        assertEquals(0.31f, r.horizontal)
        assertEquals(0.42f, r.vertical)
    }

    @Test
    fun `loadSplitRatios rejects out-of-range values`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "split.h=1.5\nsplit.v=-0.2\n")
        }
        val r = Settings.loadSplitRatios()
        assertNull(r.horizontal, "h=1.5 should be rejected")
        assertNull(r.vertical, "v=-0.2 should be rejected")
    }

    @Test
    fun `commit message height returns null when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        assertNull(Settings.loadCommitMessageHeight(project))
    }

    @Test
    fun `commit message height round-trips per project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.saveCommitMessageHeight(a, 142.5f)
        Settings.saveCommitMessageHeight(b, 200.0f)
        assertEquals(142.5f, Settings.loadCommitMessageHeight(a))
        assertEquals(200.0f, Settings.loadCommitMessageHeight(b))
    }

    @Test
    fun `commit message height rejects out-of-range values`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val heightFile = Settings.projectDataDir(project).resolve("commit-height")
        Files.createDirectories(heightFile.parent)
        Files.writeString(heightFile, "-50")
        assertNull(Settings.loadCommitMessageHeight(project), "negative height should be rejected")
        Files.writeString(heightFile, "9001")
        assertNull(Settings.loadCommitMessageHeight(project), "absurdly large height should be rejected")
        Files.writeString(heightFile, "garbage")
        assertNull(Settings.loadCommitMessageHeight(project), "non-numeric should be rejected")
    }

    @Test
    fun `recent commit messages return empty when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        assertTrue(Settings.loadRecentCommitMessages(project).isEmpty())
    }

    @Test
    fun `recent commit messages round-trip per project preserving order and multi-line bodies`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        // Messages with embedded spaces and newlines must survive the NUL-delimited round-trip intact.
        val messagesA = listOf("Fix the bug", "Add feature\n\nWith a longer body line", "Tweak things")
        Settings.saveRecentCommitMessages(a, messagesA)
        Settings.saveRecentCommitMessages(b, listOf("Other project message"))

        assertEquals(messagesA, Settings.loadRecentCommitMessages(a))
        assertEquals(listOf("Other project message"), Settings.loadRecentCommitMessages(b))
    }

    @Test
    fun `active project returns null when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertNull(Settings.loadActiveProject())
    }

    @Test
    fun `active project round-trips and clears`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        Settings.saveActiveProject(proj)
        assertEquals(proj.toAbsolutePath().normalize(), Settings.loadActiveProject())

        // Saving null clears the key so the next launch falls back to the first tab.
        Settings.saveActiveProject(null)
        assertNull(Settings.loadActiveProject())
    }

    @Test
    fun `saving the active project does not clobber the open list`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(proj))
        Settings.saveActiveProject(proj)
        assertEquals(listOf(proj.toAbsolutePath().normalize()), Settings.loadOpenProjects())
    }

    @Test
    fun `saving recent projects does not clobber open projects or window geometry`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(proj))
        Settings.saveWindowGeometry(WindowGeometry(800, 600, 0, 0))
        Settings.addRecentProject(a)

        assertEquals(listOf(proj.toAbsolutePath().normalize()), Settings.loadOpenProjects())
        assertEquals(WindowGeometry(800, 600, 0, 0), Settings.loadWindowGeometry())
        assertEquals(listOf(a.toAbsolutePath().normalize()), Settings.loadRecentProjects())
    }
}
