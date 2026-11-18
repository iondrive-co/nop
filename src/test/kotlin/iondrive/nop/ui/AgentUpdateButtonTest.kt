package iondrive.nop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import iondrive.nop.ProjectTab
import iondrive.nop.Workspace
import iondrive.nop.agent.CliUpdates
import iondrive.nop.agent.Provider
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentUpdateButtonTest {

    @AfterTest
    fun reset() {
        CliUpdates.status = CliUpdates.Status.Idle
    }

    private fun <T> onEdt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun Image.countColor(argb: Int): Int {
        val bitmap = Bitmap.makeFromImage(this)
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = bitmap.getColor(x, y)
            val close = listOf(16, 8, 0).all { shift ->
                kotlin.math.abs(((c shr shift) and 0xFF) - ((argb shr shift) and 0xFF)) < 24
            }
            if (close) n++
        }
        return n
    }

    @Test
    fun `tooltip summarizes idle, updating, and completed outcomes`() {
        assertTrue("Update agents" in agentUpdateTooltip(CliUpdates.Status.Idle))
        assertTrue("Checking" in agentUpdateTooltip(CliUpdates.Status.Updating()))
        assertTrue("Updating Codex" in agentUpdateTooltip(CliUpdates.Status.Updating(Provider.OpenAI)))
        assertTrue("Refreshing" in agentUpdateTooltip(CliUpdates.Status.Updating(refreshingModels = true)))
        assertTrue("No updatable" in agentUpdateTooltip(CliUpdates.Status.Done(emptyList())))

        val done = CliUpdates.Status.Done(
            listOf(
                CliUpdates.ProviderResult(
                    Provider.OpenAI,
                    CliUpdates.Outcome(true, "exit 0: ==> Updating\nCodex CLI 0.169.1 installed successfully."),
                ),
                CliUpdates.ProviderResult(
                    Provider.Anthropic,
                    CliUpdates.Outcome(false, "exit 5: update unavailable"),
                ),
            ),
        )
        val tip = agentUpdateTooltip(done)
        assertTrue("Codex: Codex CLI 0.169.1 installed successfully." in tip, tip)
        assertTrue("Claude Code: exit 5: update unavailable" in tip, tip)
    }

    @Test
    fun `button renders to the right of theme toggle, clicks when idle, and ignores clicks while updating`() {
        var clicks = 0
        var status by mutableStateOf<CliUpdates.Status>(CliUpdates.Status.Idle)
        var buttonCenter = Offset.Zero
        var themeCenter = Offset.Zero

        val scene = onEdt {
            ImageComposeScene(160, 40, Density(1f)) {
                IntUiTheme(isDark = false) {
                    Row {
                        ThemeToggleButton(
                            onToggle = {},
                            modifier = androidx.compose.ui.Modifier.onGloballyPositioned {
                                themeCenter = it.boundsInRoot().center
                            },
                        )
                        AgentUpdateButton(
                            status = status,
                            onUpdate = { clicks++ },
                            modifier = androidx.compose.ui.Modifier.onGloballyPositioned {
                                buttonCenter = it.boundsInRoot().center
                            },
                        )
                    }
                }
            }
        }
        try {
            fun render(): Image {
                var img = onEdt { scene.render(0) }
                repeat(4) { i -> img = onEdt { scene.render((i + 1) * 16_000_000L) } }
                return img
            }

            fun click(at: Offset) {
                onEdt {
                    scene.sendPointerEvent(PointerEventType.Move, at)
                    scene.sendPointerEvent(
                        PointerEventType.Press,
                        at,
                        buttons = PointerButtons(isPrimaryPressed = true),
                        button = PointerButton.Primary,
                    )
                    scene.sendPointerEvent(
                        PointerEventType.Release,
                        at,
                        buttons = PointerButtons(),
                        button = PointerButton.Primary,
                    )
                }
                render()
            }

            render()
            assertTrue(buttonCenter.x > themeCenter.x, "expected agent update button to the right of theme toggle")

            click(buttonCenter)
            assertEquals(1, clicks)

            onEdt { status = CliUpdates.Status.Updating(Provider.OpenAI) }
            val updatingImage = render()
            assertTrue(updatingImage.countColor(AgentStatusColors(isDark = false).working.toArgb()) > 10)
            click(buttonCenter)
            assertEquals(1, clicks, "clicks while updating should be ignored")

            onEdt {
                status = CliUpdates.Status.Done(
                    listOf(
                        CliUpdates.ProviderResult(Provider.OpenAI, CliUpdates.Outcome(false, "exit 1: failed")),
                    ),
                )
            }
            val failedImage = render()
            assertTrue(failedImage.countColor(ChangeColors.REMOVED.toArgb()) > 10)
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun `project bar renders with agent update button`() {
        val tab = ProjectTab(1L, Path.of("/home/dev/demo"), "demo")
        val scene = onEdt {
            ImageComposeScene(800, 40, Density(1f)) {
                IntUiTheme(isDark = false) {
                    ProjectBar(
                        tabs = listOf(tab),
                        activeTab = tab.id,
                        dirtyProjects = emptySet(),
                        agentNews = emptyMap(),
                        windows = listOf(Workspace(1L, "", listOf(tab), tab.id)),
                        windowId = 1L,
                        onSelect = {},
                        onClose = {},
                        onNewTab = {},
                        onRenameTab = { _, _ -> },
                        onReorder = {},
                        onOpenOther = {},
                        onNewWindow = {},
                        onRenameWindow = {},
                        onRenameOtherWindow = { _, _ -> },
                        onShowWindow = {},
                        onDiscardWindow = {},
                        onMoveToWindow = { _, _ -> },
                        onMoveToNewWindow = { _, _ -> },
                        onToggleTheme = {},
                        isDark = false,
                        onRestart = {},
                    )
                }
            }
        }
        try {
            onEdt { scene.render(0) }
        } finally {
            onEdt { scene.close() }
        }
    }
}
