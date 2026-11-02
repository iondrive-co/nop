package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.browser.SlackWorkspace
import iondrive.nop.browser.SlackAttention
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SlackFloatingContentTest {
    private val first = SlackWorkspace("TALPHA", "Workshop")
    private val second = SlackWorkspace("TBRAVO", "Studio")

    private class Preview(width: Int, val height: Int, workspace: SlackWorkspace, dark: Boolean) : AutoCloseable {
        var workspaces by mutableStateOf(listOf(workspace))
        var selected by mutableStateOf(workspace.id)
        var attention by mutableStateOf<Map<String, SlackAttention>>(emptyMap())
        var additions = 0
        var docks = 0
        var body = Rect.Zero
        private var time = 0L
        private val scene = onEdt {
            ImageComposeScene(width, height, Density(1f)) {
                IntUiTheme(isDark = dark) {
                    SlackFloatingContent(
                        workspaces, selected, attention,
                        onDock = { docks++ }, onSelect = { selected = it },
                        onClose = { id -> workspaces = workspaces.filterNot { it.id == id } },
                        onAdd = { additions++ },
                    ) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { body = it.boundsInRoot() }) {
                            Text("Conversation fixture")
                        }
                    }
                }
            }
        }

        fun settle() = repeat(8) {
            time += 16_000_000
            onEdt { scene.render(time) }
            Thread.sleep(5)
        }

        fun bounds(text: String): Rect = onEdt {
            fun find(node: SemanticsNode): SemanticsNode? =
                node.takeIf { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
                    ?: node.children.firstNotNullOfOrNull(::find)
            scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode) }.boundsInRoot
        }

        fun statusDescriptions(): List<String> = onEdt {
            fun descriptions(node: SemanticsNode): List<String> =
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.children.flatMap(::descriptions)
            scene.semanticsOwners.flatMap { descriptions(it.unmergedRootSemanticsNode) }
                .filter { it.startsWith("Unread") || it.contains("unread mention") }
        }

        fun click(text: String) {
            val at = bounds(text).center
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        fun screenshot(name: String) = onEdt {
            val file = Path.of("build/test-screenshots/slack-floating-$name.png")
            Files.createDirectories(file.parent)
            scene.render(time).use { image ->
                checkNotNull(image.encodeToData()).use { Files.write(file, it.bytes) }
            }
        }

        override fun close() = onEdt { scene.close() }

        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    @Test
    fun `connected workspace tabs leave room for the browser and another workspace`() {
        for (dark in listOf(false, true)) {
            Preview(900, 700, first, dark).use { preview ->
                preview.settle()
                assertTrue(preview.body.height > 600, "connected workspace must leave the conversation visible")
                assertTrue(preview.bounds(first.name).bottom <= preview.body.top, "workspace tab belongs in the toolbar")
                preview.click("Add workspace…")
                assertEquals(1, preview.additions)
                preview.workspaces = listOf(first, second)
                preview.settle()
                preview.click(second.name)
                assertEquals(second.id, preview.selected)
                assertTrue(preview.body.height > 600, "a second workspace cannot displace the browser")
                preview.screenshot(if (dark) "dark" else "light")
                preview.click("Dock")
                assertEquals(1, preview.docks)
            }
        }
    }

    @Test
    fun `workspace badges track unread and mentions independently of tab selection`() {
        for (dark in listOf(false, true)) {
            Preview(900, 700, first, dark).use { preview ->
                preview.workspaces = listOf(first, second)
                preview.attention = mapOf(first.id to SlackAttention(unread = true))
                preview.settle()
                assertEquals(listOf("Unread messages"), preview.statusDescriptions())
                assertTrue(preview.bounds("Unread").bottom <= preview.body.top)

                preview.attention = preview.attention + (second.id to SlackAttention(mentions = 7))
                preview.settle()
                assertEquals(listOf("Unread messages", "7 unread mentions"), preview.statusDescriptions())
                preview.click(second.name)
                assertEquals(listOf("Unread messages", "7 unread mentions"), preview.statusDescriptions())
                preview.screenshot(if (dark) "unread-dark" else "unread-light")

                preview.attention = preview.attention + (first.id to SlackAttention())
                preview.settle()
                assertEquals(listOf("7 unread mentions"), preview.statusDescriptions())
                preview.attention = mapOf(second.id to SlackAttention(mentions = 123))
                preview.settle()
                assertTrue(preview.bounds("99+").width > 0)
                assertEquals(listOf("123 unread mentions"), preview.statusDescriptions())

                preview.attention = emptyMap()
                preview.settle()
                assertTrue(preview.statusDescriptions().isEmpty())
            }
        }
    }

    @Test
    fun `add workspace stays reachable when many workspace tabs overflow`() {
        Preview(440, 360, first, false).use { preview ->
            preview.workspaces = (1..8).map { SlackWorkspace("TTEAM$it", "Example workspace $it") }
            preview.settle()
            val add = preview.bounds("Add workspace…")
            assertTrue(add.left > 0 && add.right <= 440 && add.bottom < preview.body.top)
            assertTrue(preview.body.height > 260)
            preview.click("Add workspace…")
            assertEquals(1, preview.additions)
        }
    }
}
