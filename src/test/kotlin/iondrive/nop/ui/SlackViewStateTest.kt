package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SlackViewStateTest {
    @Test
    fun `placement survives restart and malformed settings fall back safely`(@TempDir root: Path) {
        val project = root.resolve("workshop")
        val store = SlackWindowStore { it }
        val placement = SlackPlacement(floating = true, visible = true, workspace = "TALPHA", width = 820, height = 640, x = 72, y = 96)
        store.save(project, placement)
        assertEquals(placement, SlackWindowStore { it }.load(project))
        assertEquals(SlackPlacement(), store.load(root.resolve("studio")))
        Files.writeString(project.resolve("slack-window.json"), """{"width":-1,"height":999999,"workspace":"https://example.invalid"}""")
        val clamped = store.load(project)
        assertEquals(400, clamped.width)
        assertEquals(10000, clamped.height)
        assertNull(clamped.workspace)
        Files.writeString(project.resolve("slack-window.json"), "invalid")
        assertEquals(SlackPlacement(), store.load(project))
    }

    @Test
    fun `floating keeps its browser alive until a docked view takes over`() {
        var leases = 0
        val view = SlackViewState(Path.of("/example/workshop")) {
            leases++
            AutoCloseable { leases-- }
        }
        view.show("TALPHA")
        view.float()
        view.float()
        assertEquals(1, leases)
        view.show("TBRAVO")
        view.dock()
        assertEquals(1, leases)
        assertFalse(view.floating)
        assertEquals("TBRAVO", view.selectedId.value)
        view.dockedViewReady()
        view.dockedViewReady()
        assertEquals(0, leases)
        assertTrue(view.visible.value)
    }

    @Test
    fun `closing and reopening floating view releases and reacquires its browser lease`() {
        var leases = 0
        val view = SlackViewState(Path.of("/example/studio"), SlackPlacement(floating = true, visible = true, workspace = "TALPHA")) {
            leases++
            AutoCloseable { leases-- }
        }
        assertEquals(1, leases)
        view.hide()
        view.hide()
        assertEquals(0, leases)
        assertFalse(view.visible.value)
        assertTrue(view.floating)
        view.show()
        assertEquals(1, leases)
        assertEquals("TALPHA", view.placement().workspace)
        view.hide()
        val restored = SlackViewState(view.project, view.placement()) {
            leases++
            AutoCloseable { leases-- }
        }
        assertEquals(0, leases)
        assertTrue(restored.floating)
        assertFalse(restored.visible.value)
    }
}
