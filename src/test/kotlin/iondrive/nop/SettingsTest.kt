package iondrive.nop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SettingsTest {
    private val originalRoot: Path = Settings.configRoot

    @AfterEach
    fun restoreRoot() {
        Settings.configRoot = originalRoot
    }

    @Test
    fun `loadLastProject returns null when no state file exists`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertNull(Settings.loadLastProject())
    }

    @Test
    fun `save then load round-trips the path`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("some/project").also { Files.createDirectories(it) }
        Settings.saveLastProject(project)

        val loaded = Settings.loadLastProject()
        assertEquals(project.toAbsolutePath().normalize(), loaded)
    }

    @Test
    fun `loadLastProject tolerates a blank state file`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "\n   \n")
        }
        assertNull(Settings.loadLastProject(), "blank state should produce null, not throw — got file at $state")
    }

    @Test
    fun `legacy single-line state still resolves the project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "/home/u/old-format-project\n")
        }
        assertEquals(java.nio.file.Paths.get("/home/u/old-format-project"), Settings.loadLastProject())
    }

    @Test
    fun `window geometry round-trips alongside the project path`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Settings.saveLastProject(tmp.resolve("project").also { Files.createDirectories(it) })
        Settings.saveWindowGeometry(WindowGeometry(width = 1024, height = 768, x = 50, y = 100))

        val w = Settings.loadWindowGeometry()
        assertEquals(WindowGeometry(1024, 768, 50, 100), w)

        // Saving geometry must not clobber the project entry
        assertEquals(tmp.resolve("project").toAbsolutePath().normalize(), Settings.loadLastProject())
    }

    @Test
    fun `loadWindowGeometry returns null when size missing`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Settings.saveLastProject(tmp.resolve("project").also { Files.createDirectories(it) })
        assertNull(Settings.loadWindowGeometry())
    }
}
