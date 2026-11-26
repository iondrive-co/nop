package iondrive.nop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.agent.LimitResets
import iondrive.nop.agent.ResetOffer
import iondrive.nop.agent.ResetOutcome
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResetsPanelTest {
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun `a changed offer needs a fresh confirmation before it is used`() {
        val offer = ResetOffer("example", "weekly reset", 2, null, "Refills weekly usage", done = "Weekly reset used")
        val shown = mutableStateOf(LimitResets(offers = listOf(offer)))
        val used = mutableListOf<ResetOffer>()
        val scene = onEdt {
            ImageComposeScene(520, 220, Density(1f)) {
                IntUiTheme(isDark = false) {
                    ResetsPanel(shown.value) {
                        used += it
                        ResetOutcome(true, it.done)
                    }
                }
            }
        }
        var time = 0L
        fun draw() = repeat(6) { onEdt { scene.render(++time * 16_000_000).close() } }
        fun click(label: String) {
            onEdt {
                fun nodes(node: SemanticsNode): Sequence<SemanticsNode> =
                    sequenceOf(node) + node.children.asSequence().flatMap(::nodes)
                val button = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }.first {
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            }
            draw()
        }
        try {
            draw()
            click("Review reset")
            val changed = offer.copy(left = 1)
            onEdt { shown.value = LimitResets(offers = listOf(changed)) }
            draw()
            click("Review reset")
            click("Cancel")
            assertTrue(onEdt { used.isEmpty() })
            click("Review reset")
            click("Use reset")
            assertEquals(listOf(changed), onEdt { used.toList() })
        } finally {
            onEdt { scene.close() }
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
