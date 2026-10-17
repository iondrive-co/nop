package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import iondrive.nop.agent.Account
import iondrive.nop.agent.HandoverDraft
import iondrive.nop.agent.Provider
import java.awt.event.KeyEvent as AwtKeyEvent
import java.nio.file.Path
import javax.swing.JPanel
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The prompt editor is dropped whenever another tab is in front, so what has been typed in it has
 * to be in the draft its holder keeps, and come back from there.
 */
class HandoverPromptEditorTest {

    private fun draft(account: String, n: Int) = HandoverDraft(
        Account(account, Provider.Anthropic, "/home/dev/.accounts/$account"),
        Path.of("/home/dev/.local/share/nop/agent/handoffs/s-$n.md"),
        "Carry on from s-$n.md.",
    )

    // The KeyEvent factory is the only way to type into a scene that has no window behind it.
    @OptIn(InternalComposeUiApi::class)
    private class Editor(first: HandoverDraft) : AutoCloseable {
        var draft by mutableStateOf(first)
        var shown by mutableStateOf(true)
        private var time = 0L
        private val source = JPanel()
        private val scene = ImageComposeScene(600, 300, Density(1f)) {
            IntUiTheme(isDark = false) {
                if (shown) {
                    HandoverPromptEditor(
                        draft = draft,
                        actionLabel = "Hand over",
                        onConfirm = {},
                        onCancel = {},
                        onEdit = { draft = draft.copy(prompt = it) },
                    )
                }
            }
        }

        fun settle() = repeat(10) {
            time += 16_000_000
            scene.render(time)
            Thread.sleep(5)
        }

        /**
         * A click past the end of the prompt's one line, which gives the text area the keyboard
         * with the caret after the last character.
         */
        fun focus() {
            val at = Offset(590f, 60f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            settle()
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            settle()
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            settle()
        }

        /** Text, as a window delivers it: a key down carrying AWT's KEY_TYPED, which is what a text field reads. */
        fun type(text: String) {
            for (c in text) {
                val awt = AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, AwtKeyEvent.VK_UNDEFINED, c)
                scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.KeyDown, c.code, nativeEvent = awt))
            }
            settle()
        }

        override fun close() = scene.close()
    }

    @Test
    fun `what was typed comes back after the editor has been off screen`() {
        val first = draft("claude-bravo", 1)
        Editor(first).use { editor ->
            editor.settle()
            editor.focus()
            editor.type(" Then run the tests.")
            assertEquals(first.prompt + " Then run the tests.", editor.draft.prompt, "each edit reaches the draft")

            // Another tab in front, then this one again.
            editor.shown = false
            editor.settle()
            editor.shown = true
            editor.settle()

            editor.focus()
            editor.type("!")
            assertEquals(first.prompt + " Then run the tests.!", editor.draft.prompt)
        }
    }

    @Test
    fun `another tab's draft in the same place starts from its own text`() {
        val first = draft("claude-bravo", 1)
        val other = draft("codex-alpha", 2)
        Editor(first).use { editor ->
            editor.settle()
            editor.focus()
            editor.type(" Then run the tests.")

            editor.draft = other
            editor.settle()
            editor.focus()
            editor.type("!")
            assertEquals(other.prompt + "!", editor.draft.prompt)
        }
    }
}
