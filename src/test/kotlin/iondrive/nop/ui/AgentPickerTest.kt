package iondrive.nop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.agent.Account
import iondrive.nop.agent.Provider
import java.awt.EventQueue
import java.nio.file.Path
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An account is removed from the picker only once the user has said so twice. */
class AgentPickerTest {

    private val accounts = listOf(
        Account("claude-alpha", Provider.Anthropic, "/home/dev/.accounts/claude-alpha"),
        Account("codex-alpha", Provider.OpenAI, "/home/dev/.accounts/codex-alpha"),
    )

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Picker(accounts: List<Account>) : AutoCloseable {
        val removed = mutableListOf<String>()
        private var time = 0L
        // Every call into the scene is made on the AWT thread: see HandoverPromptEditorTest.
        private val scene = onEdt {
            ImageComposeScene(700, 600, Density(1f)) {
                IntUiTheme(isDark = false) {
                    AgentPicker(
                        accounts = accounts,
                        readings = emptyMap(),
                        sessions = emptyList(),
                        projectDir = Path.of("/home/dev/project"),
                        onRemoveAccount = { removed += it.name },
                        onLaunch = {},
                        onReopen = {},
                        onSettings = {},
                    )
                }
            }
        }

        fun settle() = repeat(10) {
            time += 16_000_000
            onEdt { scene.render(time) }
            Thread.sleep(5)
        }

        /** Every node drawing exactly [text], top to bottom. */
        fun nodes(text: String): List<SemanticsNode> = onEdt {
            scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
                .filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
                .sortedBy { it.boundsInRoot.top }
        }

        /** Whether any node draws text starting with [prefix]. */
        fun shows(prefix: String): Boolean = onEdt {
            scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
                .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true }
        }

        fun click(node: SemanticsNode) {
            val at = node.boundsInRoot.center
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        override fun close() = onEdt { scene.close() }

        private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    @Test
    fun `each account card offers to remove it`() {
        Picker(accounts).use { picker ->
            picker.settle()
            assertEquals(2, picker.nodes("Remove").size)
        }
    }

    @Test
    fun `remove asks before it removes`() {
        Picker(accounts).use { picker ->
            picker.settle()
            picker.click(picker.nodes("Remove").first())

            assertTrue(picker.removed.isEmpty(), "the first click only asks")
            assertTrue(picker.shows("Remove claude-alpha from nop?"))

            // The question replaces the first card's link, so the first Remove is now the other
            // card's and the one under the question is the answer.
            val question = picker.nodes("Keep it").single().boundsInRoot.top
            picker.click(picker.nodes("Remove").first { it.boundsInRoot.top >= question - 1f })

            assertEquals(listOf("claude-alpha"), picker.removed)
        }
    }

    @Test
    fun `keep it takes the question back without removing anything`() {
        Picker(accounts).use { picker ->
            picker.settle()
            picker.click(picker.nodes("Remove").first())
            picker.click(picker.nodes("Keep it").single())

            assertTrue(picker.removed.isEmpty())
            assertTrue(!picker.shows("Remove claude-alpha from nop?"))
            assertEquals(2, picker.nodes("Remove").size, "both cards offer Remove again")
        }
    }
}
