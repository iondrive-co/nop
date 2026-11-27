package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
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
    private class Reader(
        val log: Path,
        val state: ConversationSummaryState = ConversationSummaryState(),
        private val dark: Boolean = false,
        private val onBranch: ((iondrive.nop.agent.ConversationClick) -> Unit)? = null,
    ) : AutoCloseable {
        var visible by mutableStateOf(true)
        private var time = 0L
        // On the EDT, as in nop. A scene's coroutines otherwise run Unconfined: they resume on whatever
        // thread woke them, and the scrollbar's press-and-hold scroll, which repeats from 300 ms on,
        // then measures the list from a timer thread while the EDT is measuring it too. The GitHub
        // runner is slow enough that one settle() lasts that long.
        private val scene = onEdt {
            ImageComposeScene(900, 650, Density(1f), coroutineContext = Dispatchers.Swing) {
                IntUiTheme(isDark = dark) {
                    if (visible) AgentSummaryView(state, log, onBranchHere = onBranch)
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

        fun refresh() = clickText("Refresh")

        private fun textNode(text: String) = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

        fun clickText(text: String) = click(textNode(text).boundsInRoot.center)

        fun disabled(text: String) = generateSequence(textNode(text)) { it.parent }.any {
            it.config.contains(SemanticsProperties.Disabled)
        }

        fun clickScrollbar() = click(Offset(896f, 500f))

        fun branchAt(text: String) {
            val node = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            val at = node.boundsInRoot.center
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary) }
            settle()
            val action = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Branch Here" } == true }
            click(action.boundsInRoot.center)
        }

        /**
         * Presses the scrollbar track and lets go at once. The press queues its first page of scrolling
         * before the release, and the release stops it before any repeat, however slow the machine.
         */
        fun tapScrollbar() {
            val at = Offset(896f, 500f)
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        /** How far the conversation list is scrolled, as it reports it to accessibility. */
        fun listPosition(): Float {
            val range = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.single()
            return onEdt { range.value() }
        }

        /**
         * Presses the scrollbar track and holds it, the pointer still, until [done] or a minute has
         * passed. The track repeats on wall time, so a fixed hold proves nothing on a loaded machine.
         */
        fun holdScrollbar(done: () -> Boolean) {
            val at = Offset(896f, 500f)
            val pressed = PointerButtons(isPrimaryPressed = true)
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = pressed, button = PointerButton.Primary) }
            val deadline = System.nanoTime() + 60_000_000_000
            while (!done() && System.nanoTime() < deadline) {
                time += 16_000_000
                onEdt { scene.render(time).close() }
                // A held mouse still reports where it is, and each report measures the scene first.
                onEdt { scene.sendPointerEvent(PointerEventType.Move, at, buttons = pressed) }
                Thread.sleep(5)
            }
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        private fun click(at: Offset) {
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        fun screenshot(suffix: String = "") {
            val file = Path.of("build/test-screenshots/agent-summary$suffix${if (dark) "-dark" else ""}.png")
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
    fun `summary opens on the latest run and reads earlier runs one at a time`() {
        val log = dir.resolve("runs.jsonl")
        val start = AgentEvent.RunStarted(provider = "anthropic", account = "claude-alpha", at = 1)
        append(log, start)
        append(log, AgentEvent.UserMessage("Describe the orchard.", 2))
        append(log, start.copy(at = 3))
        append(log, AgentEvent.AssistantMessage(listOf(Block.Text("The pond is quiet.")), at = 4))
        append(log, start.copy(at = 5))
        append(log, AgentEvent.UserMessage("Describe the greenhouse.", 6))

        Reader(log).use { reader ->
            reader.settle()
            assertTrue(reader.shows("Describe the greenhouse."))
            assertFalse(reader.shows("The pond is quiet."))
            assertFalse(reader.shows("Describe the orchard."))
            assertTrue(reader.disabled("Next run"))

            reader.clickText("Previous run")
            assertTrue(reader.shows("The pond is quiet."))
            assertFalse(reader.shows("Describe the greenhouse."))
            assertFalse(reader.shows("Describe the orchard."))
            reader.clickText("Previous run")
            assertTrue(reader.shows("Describe the orchard."))
            assertFalse(reader.shows("The pond is quiet."))
            assertTrue(reader.disabled("Previous run"))
            reader.visible = false
            reader.settle()
            reader.visible = true
            reader.settle()
            assertTrue(reader.shows("Describe the orchard."))

            append(log, start.copy(at = 7))
            append(log, AgentEvent.UserMessage("Describe the hillside.", 8))
            reader.clickText("Next run")
            assertTrue(reader.shows("The pond is quiet."))
            reader.clickText("Next run")
            assertTrue(reader.shows("Describe the greenhouse."))
            assertTrue(reader.disabled("Next run"))
            reader.clickText("Previous run")
            reader.refresh()
            assertTrue(reader.shows("Describe the hillside."))
            assertTrue(reader.disabled("Next run"))
        }
    }

    @Test
    fun `right clicking an earlier run offers branching with the clicked conversation position`() {
        val state = ConversationSummaryState().apply {
            snapshot = ConversationSummary(listOf(
                ConversationSummary.Message("You", "Describe the pond.", false, 1),
                ConversationSummary.Message("You", "Describe the orchard.", false, 2),
                ConversationSummary.Message("Agent", "The orchard has an eastern path.", true, 3),
                ConversationSummary.Message("You", "Describe the greenhouse.", false, 4),
            ), 5, runStarts = listOf(0, 1, 3))
        }
        var captured: iondrive.nop.agent.ConversationClick? = null
        Reader(dir.resolve("unused.jsonl"), state, onBranch = { captured = it }).use { reader ->
            reader.settle()
            reader.clickText("Previous run")
            reader.branchAt("The orchard has an eastern path.")
        }
        val click = assertNotNull(captured)
        assertEquals(3L, click.messageAt)
        assertEquals("The orchard has an eastern path.", click.lines[click.row])
    }

    @Test
    fun `prompt marks leave the scrollbar usable in both themes`() {
        for (dark in listOf(false, true)) {
            val state = ConversationSummaryState().apply {
                snapshot = ConversationSummary((1..30).map { index ->
                    val user = index % 5 == 1
                    val incoming = index % 5 == 3
                    ConversationSummary.Message(
                        if (user) "You" else if (incoming) "From agent Review" else "Agent",
                        "Message $index.\n" + "The checks are progressing.\n".repeat(index % 4),
                        !user && !incoming,
                        index.toLong(),
                    )
                }, 1)
            }
            Reader(dir.resolve("unused.jsonl"), state, dark).use { reader ->
                reader.settle()
                reader.screenshot("-markers")
                reader.clickScrollbar()
                reader.visible = false
                reader.settle()
                assertTrue(state.scrollIndex > 0, "marks must not intercept scrollbar clicks")
            }
        }
    }

    @Test
    fun `holding the scrollbar track keeps scrolling`() {
        fun conversation() = ConversationSummaryState().apply {
            snapshot = ConversationSummary((1..60).map { index ->
                ConversationSummary.Message(if (index % 5 == 1) "You" else "Agent",
                    "Message $index.\n" + "The checks are progressing.\n".repeat(index % 4), index % 5 != 1, index.toLong())
            }, 1)
        }
        val tapped = conversation()
        var tappedAt = 0f
        Reader(dir.resolve("unused.jsonl"), tapped).use { reader ->
            reader.settle()
            reader.tapScrollbar()
            tappedAt = reader.listPosition()
            reader.visible = false
            reader.settle()
        }
        // Held past the 300 ms after which the track repeats its scroll, until a repeat has moved it on.
        val held = conversation()
        Reader(dir.resolve("unused.jsonl"), held).use { reader ->
            reader.settle()
            reader.holdScrollbar { reader.listPosition() > tappedAt }
            reader.visible = false
            reader.settle()
        }
        assertTrue(tapped.scrollIndex > 0, "a tap on the track scrolls")
        assertTrue(held.scrollIndex > tapped.scrollIndex, "holding scrolls on: ${held.scrollIndex} after holding, ${tapped.scrollIndex} after a tap")
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
        Reader(log, dark = true).use { reader ->
            reader.settle()
            reader.screenshot()
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
