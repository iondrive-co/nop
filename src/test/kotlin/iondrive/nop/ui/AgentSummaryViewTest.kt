package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Block
import iondrive.nop.agent.ConversationSummary
import iondrive.nop.agent.ConversationSummaryState
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSummaryViewTest {
    @TempDir lateinit var dir: Path

    private fun append(log: Path, event: AgentEvent) {
        Files.writeString(log, Json.encodeToString(AgentEvent.serializer(), event) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Reader(val log: Path, val state: ConversationSummaryState = ConversationSummaryState()) : AutoCloseable {
        var visible by mutableStateOf(true)
        private var time = 0L
        private val scene = onEdt {
            ImageComposeScene(900, 650, Density(1f)) {
                IntUiTheme(isDark = false) {
                    if (visible) AgentSummaryView(state, log)
                }
            }
        }

        fun settle() = repeat(20) {
            time += 16_000_000
            onEdt { scene.render(time).close() }
            Thread.sleep(5)
        }

        fun shows(text: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
        }

        private fun nodes(): List<SemanticsNode> = onEdt {
            fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
            scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
        }

        fun refresh() {
            val node = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Refresh" } == true }
            val at = node.boundsInRoot.center
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        fun screenshot() {
            val file = Path.of("build/test-screenshots/agent-summary.png")
            Files.createDirectories(file.parent)
            onEdt {
                scene.render(time).use { image ->
                    checkNotNull(image.encodeToData()).use { Files.write(file, it.bytes) }
                }
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
    fun `the reading view stays static across panel switches and refresh loads new prose`() {
        val log = dir.resolve("conversation.jsonl")
        append(log, AgentEvent.UserMessage("Please review the changes.", 1))
        append(log, AgentEvent.UserMessage("[Message via nop from agent \"Review\", not the user. Reply: nop-msg send review \"...\"]\n\nThe API checks passed.", 2))
        append(log, AgentEvent.AssistantMessage(listOf(Block.Text("I checked the **parser**.\n\n- Inputs are validated.\n- Errors are reported."), Block.Thinking("Hidden reasoning")), at = 3))
        append(log, AgentEvent.ToolFinished("check", "Hidden tool output", at = 4))
        Reader(log).use { reader ->
            reader.settle()
            assertTrue(reader.shows("The API checks passed."))
            assertTrue(reader.shows("I checked the parser."))
            assertFalse(reader.shows("Hidden reasoning"))
            assertFalse(reader.shows("Hidden tool output"))
            append(log, AgentEvent.AssistantMessage(listOf(Block.Text("The final checks passed.")), at = 5))
            reader.settle()
            assertFalse(reader.shows("The final checks passed."))
            reader.visible = false
            reader.settle()
            reader.visible = true
            reader.settle()
            assertFalse(reader.shows("The final checks passed."))
            reader.screenshot()
            reader.refresh()
            assertTrue(reader.shows("The final checks passed."))
        }
    }

    @Test
    fun `a failed read can be retried without leaving summary`() {
        val log = dir.resolve("pending.jsonl")
        Reader(log).use { reader ->
            reader.settle()
            assertTrue(reader.shows("Could not read this conversation."))
            append(log, AgentEvent.UserMessage("A new conversation.", 1))
            reader.refresh()
            assertTrue(reader.shows("A new conversation."))
            assertEquals(null, reader.state.error)
        }
    }

    @Test
    fun `a long conversation keeps its reading position when its panel is hidden`() {
        val state = ConversationSummaryState().apply {
            snapshot = ConversationSummary((1..60).map {
                ConversationSummary.Message("You", "Question $it\nA second line.", false, it.toLong())
            }, 1)
            scrollIndex = 20
            scrollOffset = 15
        }
        Reader(dir.resolve("unused.jsonl"), state).use { reader ->
            reader.settle()
            assertTrue(reader.shows("Question 21"))
            reader.visible = false
            reader.settle()
            val index = state.scrollIndex
            val offset = state.scrollOffset
            assertTrue(index > 0)
            reader.visible = true
            reader.settle()
            assertTrue(reader.shows("Question ${index + 1}"))
            reader.visible = false
            reader.settle()
            assertEquals(index, state.scrollIndex)
            assertEquals(offset, state.scrollOffset)
            assertNotNull(state.snapshot)
        }
    }
}
