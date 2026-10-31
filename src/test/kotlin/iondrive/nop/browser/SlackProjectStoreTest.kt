package iondrive.nop.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SlackProjectStoreTest {
    @Test
    fun `associations survive restart and closing a tab only changes its project`(@TempDir root: Path) {
        val first = root.resolve("workshop")
        val second = root.resolve("studio")
        val store = SlackProjectStore { it.resolve("config") }
        assertTrue(store.load(first).isEmpty())
        store.save(first, setOf("TALPHA"))
        store.save(second, setOf("TBRAVO"))
        val restarted = SlackProjectStore { it.resolve("config") }
        assertEquals(setOf("TALPHA"), restarted.load(first))
        assertEquals(setOf("TBRAVO"), restarted.load(second))
        restarted.save(first, emptySet())
        assertTrue(restarted.load(first).isEmpty())
        assertEquals(setOf("TBRAVO"), restarted.load(second))
        assertTrue(restarted.load(root.resolve("library")).isEmpty())
    }

    @Test
    fun `invalid or malformed association files cannot introduce arbitrary URLs`(@TempDir root: Path) {
        val store = SlackProjectStore { it }
        val file = root.resolve("slack-workspaces.json")
        Files.writeString(file, """["TALPHA","../other","https://example.invalid","TALPHA","TBRAVO/other"]""")
        assertEquals(setOf("TALPHA"), store.load(root))
        Files.writeString(file, "not JSON")
        assertTrue(store.load(root).isEmpty())
        store.save(root, setOf("TBRAVO", "../other"))
        assertEquals(setOf("TBRAVO"), store.load(root))
    }
}
