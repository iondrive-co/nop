package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.AgentSessions
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.EventQueue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SidePanelCollapseTest {

    private class Harness(width: Int = 900, height: Int = 600) : AutoCloseable {
        var sidePanelCollapsed by mutableStateOf(false)
        var toolsCollapsed by mutableStateOf(true)
        var selectedTool by mutableStateOf(ToolTab.Commit)
        var ratio by mutableStateOf(0.6f)
        var viewerBounds = Rect.Zero
        var viewerIdentityToken = 0
        var time = 0L

        private val terminals = RunSessions()
        private val agents = AgentSessions()
        private val runs = RunSessions()
        private val histories = HistorySessions()

        private val scene = onEdt {
            ImageComposeScene(width, height, Density(1f)) {
                IntUiTheme(isDark = true) {
                    HorizontalSplit(
                        modifier = Modifier.fillMaxSize(),
                        ratio = ratio,
                        onRatioChange = { ratio = it },
                        minFirstDp = 240.dp,
                        minSecondDp = 240.dp,
                        secondCollapsed = sidePanelCollapsed,
                        collapsedSecond = {
                            CollapsedSidePanelStrip(onExpand = { sidePanelCollapsed = false })
                        },
                        first = {
                            val token = remember { ++viewerIdentityToken }
                            Box(Modifier.fillMaxSize().onGloballyPositioned { viewerBounds = it.boundsInRoot() }) {
                                Text("Viewer $token")
                            }
                        },
                        second = {
                            ToolTabs(
                                selected = selectedTool,
                                onSelect = { tab ->
                                    if (selectedTool == tab && !toolsCollapsed) toolsCollapsed = true
                                    else {
                                        selectedTool = tab
                                        toolsCollapsed = false
                                    }
                                },
                                sessionTab = ToolTab.Agent,
                                collapsed = toolsCollapsed,
                                onHideSidePanel = { sidePanelCollapsed = true },
                                paneRatio = 0.58f,
                                onPaneRatioChange = {},
                                terminals = terminals,
                                onNewTerminal = {},
                                onSelectTerminal = {},
                                onCloseTerminal = {},
                                agents = agents,
                                onShowPicker = {},
                                onSelectAgent = {},
                                onCloseAgent = {},
                                onRenameAgent = { _, _ -> },
                                runs = runs,
                                onSelectRun = {},
                                onCloseRun = {},
                                histories = histories,
                                onSelectHistory = {},
                                onCloseHistory = {},
                                terminal = { Text("Terminal pane") },
                                agent = { Text("Agent pane") },
                                commit = { Text("Commit pane") },
                                diff = {},
                                search = {},
                                usages = {},
                                stash = {},
                                preview = {},
                                run = {},
                                history = {},
                                slack = {},
                                slackVisible = false,
                                slackFloating = false,
                                onToggleSlack = {},
                                slackPaneRatio = 0.6f,
                                onSlackPaneRatioChange = {},
                                slackWorkspaces = emptyList(),
                                knownSlackWorkspaces = emptyList(),
                                slackAttention = emptyMap(),
                                onCloseSlack = {},
                                selectedSlackId = null,
                                onSelectSlack = {},
                                onNewSlack = {},
                            )
                        },
                    )
                }
            }
        }

        fun settle() = repeat(8) {
            time += 16_000_000
            onEdt { scene.render(time) }
            Thread.sleep(5)
        }

        fun allTexts(): List<String> = onEdt {
            fun collect(node: SemanticsNode): List<String> =
                node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                    node.children.flatMap(::collect)
            scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
        }

        fun bounds(text: String): Rect = onEdt {
            fun find(node: SemanticsNode): SemanticsNode? =
                node.takeIf { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
                    ?: node.children.firstNotNullOfOrNull(::find)
            scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode) }.boundsInRoot
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

        override fun close() = onEdt { scene.close() }

        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    @Test
    fun `hide button collapses the entire side panel including the agent tab and restore brings it back`() {
        Harness(width = 900, height = 600).use { h ->
            h.settle()
            assertTrue("Agent pane" in h.allTexts(), "agent pane should start visible")
            assertTrue("✦ Agent" in h.allTexts(), "agent tab should start visible")
            val openViewerWidth = h.viewerBounds.width
            assertTrue(openViewerWidth < 700f)

            // Clicking ▶ hides the whole side panel including the agent tab and session strip.
            h.click("▶")
            assertTrue(h.sidePanelCollapsed)
            assertFalse("Agent pane" in h.allTexts(), "agent pane must hide with the side panel")
            assertFalse("✦ Agent" in h.allTexts(), "agent tab must hide with the side panel")
            assertFalse("Commit" in h.allTexts(), "tool strip must hide with the side panel")
            assertTrue("◀" in h.allTexts(), "collapsed rail must show the restore button")
            assertTrue(h.viewerBounds.width > 850f, "viewer should expand into the hidden side panel space")
            assertEquals(1, h.viewerIdentityToken, "viewer composition identity must survive collapse")

            // Clicking ◀ brings the side panel and agent tab back.
            h.click("◀")
            assertFalse(h.sidePanelCollapsed)
            assertTrue("Agent pane" in h.allTexts(), "agent pane should return when side panel is restored")
            assertTrue("✦ Agent" in h.allTexts(), "agent tab should return when side panel is restored")
            assertEquals(openViewerWidth, h.viewerBounds.width, 1f)
            assertEquals(1, h.viewerIdentityToken, "viewer composition identity must survive restore")
        }
    }
}
