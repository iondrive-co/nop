package io.iondrive.nop.launchers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LauncherStoreTest {
    @Test
    fun `load returns empty when file is missing`(@TempDir tmp: Path) {
        val store = LauncherStore(tmp)
        assertEquals(emptyList<Launcher>(), store.load())
    }

    @Test
    fun `save then load round-trips entries in order`(@TempDir tmp: Path) {
        val store = LauncherStore(tmp)
        val launchers = listOf(
            Launcher("Build", "./gradlew createDistributable"),
            Launcher("Run tests", "./gradlew test"),
        )
        store.save(launchers)

        assertTrue(Files.isRegularFile(tmp.resolve(".nop/launchers.txt")), "file should be created in .nop/")
        assertEquals(launchers, store.load())
    }

    @Test
    fun `load skips blank lines and comments`(@TempDir tmp: Path) {
        val file = tmp.resolve(".nop/launchers.txt")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            # a comment

            Build${'\t'}make build
            # another
            Test${'\t'}make test
            """.trimIndent() + "\n",
        )

        val store = LauncherStore(tmp)
        assertEquals(
            listOf(Launcher("Build", "make build"), Launcher("Test", "make test")),
            store.load(),
        )
    }

    @Test
    fun `load drops malformed lines without throwing`(@TempDir tmp: Path) {
        val file = tmp.resolve(".nop/launchers.txt")
        Files.createDirectories(file.parent)
        // no-tab line and trailing-tab line are both invalid
        Files.writeString(file, "no-tab-here\nName${'\t'}\nGood${'\t'}echo ok\n")

        val store = LauncherStore(tmp)
        assertEquals(listOf(Launcher("Good", "echo ok")), store.load())
    }

    @Test
    fun `Launcher rejects names containing tab or newline`() {
        assertThrows(IllegalArgumentException::class.java) {
            Launcher("bad\tname", "echo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Launcher("bad\nname", "echo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Launcher("ok", "")
        }
    }

    @Test
    fun `Launcher rejects newlines in command`() {
        assertThrows(IllegalArgumentException::class.java) {
            Launcher("Build", "echo a\necho b")
        }
    }
}
