package iondrive.nop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import iondrive.nop.agent.*
import iondrive.nop.agent.transcript.ClaudeTailer
import kotlinx.serialization.json.*
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.awt.EventQueue
import java.awt.Robot
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

/** Opens real windows only on a private display, with synthetic history and no running agent. */
@EnabledIfEnvironmentVariable(named = "NOP_NATIVE_UI_TEST", matches = "1")
class ConversationBranchDialogTest {
    @TempDir lateinit var root: Path

    @Test fun `cancel leaves history alone and Send opens only the requested branch in the same tab position`() {
        val account = Account("agent-larch", Provider.Anthropic, root.resolve("home").toString())
        val id = UUID.randomUUID().toString()
        val file = account.homePath.resolve("projects").resolve(ClaudeTailer.slug(root)).resolve("$id.jsonl")
        Files.createDirectories(file.parent)
        var parent: String? = null
        val records = listOf("user" to "Describe the orchard layout.", "assistant" to "The orchard has an eastern path.",
            "user" to "Describe the greenhouse layout.", "assistant" to "The orchard has an eastern path.").map { (role, body) ->
            val uuid = UUID.randomUUID().toString()
            buildJsonObject {
                put("uuid", uuid); put("parentUuid", parent); put("sessionId", id); put("type", role)
                put("message", buildJsonObject {
                    put("role", role); put("content", body)
                    if (role == "assistant") put("stop_reason", "end_turn")
                })
            }.also { parent = uuid }
        }
        Files.writeString(file, records.joinToString("\n", postfix = "\n"))
        val original = Files.readString(file)
        val sessions = AgentSessions()
        val source = onEdt { sessions.open(root.toFile(), account, resumeId = id, title = "Orchard discussion") }
        source.log.append(AgentEvent.RunStarted("anthropic", account.name, home = account.home,
            nativeSessionId = id, transcriptPath = file.toString(), at = System.currentTimeMillis()))
        val click = ConversationClick(listOf("Describe the orchard layout.", "The orchard has an eastern path.",
            "Describe the greenhouse layout.", "The orchard has an eastern path."), 1)
        var shown by mutableStateOf(true)
        val owner = onEdt {
            ComposeWindow().apply {
                setBounds(40, 40, 900, 720)
                title = "Branch preview"
                val host = this
                setContent {
                    CompositionLocalProvider(LocalWindow provides host) {
                        IntUiTheme(isDark = false) {
                            if (shown) ConversationBranchDialog(source, click, sessions, listOf(account), onClose = { shown = false })
                        }
                    }
                }
                isVisible = true
            }
        }
        val robot = Robot().apply { autoDelay = 70 }
        fun key(code: Int) { robot.keyPress(code); robot.keyRelease(code) }
        fun dialog() = onEdt { owner.ownedWindows.singleOrNull { it.isShowing && it is java.awt.Dialog && it.title == "Branch Here" } }
        try {
            await { dialog() != null }
            Thread.sleep(700)
            key(KeyEvent.VK_ESCAPE)
            await { dialog() == null }
            assertEquals(source, onEdt { sessions.selected })
            assertFalse(source.ended)
            assertEquals(original, Files.readString(file))
            assertEquals(1L, Files.list(file.parent).use { it.count() })

            onEdt { shown = true }
            await { dialog() != null }
            Thread.sleep(700)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("Could we widen the eastern path?"), null)
            robot.keyPress(KeyEvent.VK_CONTROL); key(KeyEvent.VK_V); robot.keyRelease(KeyEvent.VK_CONTROL)
            robot.waitForIdle()
            val output = Path.of("build/test-previews/branch")
            Files.createDirectories(output)
            ImageIO.write(robot.createScreenCapture(onEdt { dialog()!!.bounds }), "png", output.resolve("dialog.png").toFile())
            val send = onEdt { dialog()!!.let { java.awt.Point(it.x + it.insets.left + 170, it.y + it.height - it.insets.bottom - 30) } }
            robot.mouseMove(send.x, send.y)
            Thread.sleep(1000)
            ImageIO.write(robot.createScreenCapture(onEdt { owner.bounds }), "png", output.resolve("tooltip.png").toFile())
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            try { await { onEdt { sessions.selected !== source } } }
            catch (failure: Exception) {
                ImageIO.write(robot.createScreenCapture(onEdt { owner.bounds }), "png", output.resolve("failure.png").toFile())
                throw failure
            }
            val branch = onEdt { sessions.selected!! }
            assertEquals(1, sessions.sessions.size)
            assertTrue(source.ended)
            assertTrue(branch.run.command.argv.contains("Could we widen the eastern path?"))
            assertNotEquals(id, branch.run.nativeSessionId)
            val branchFile = Transcripts.find(Provider.Anthropic, account.homePath, root.toFile(), branch.run.nativeSessionId!!)!!
            assertFalse(Files.readString(branchFile).contains("greenhouse"))
            assertEquals(original, Files.readString(file))
            val lineage = branch.log.events().filterIsInstance<AgentEvent.ConversationBranched>().single()
            assertEquals(source.sessionId, lineage.sourceSession)
            assertTrue(Files.isRegularFile(source.log.file), "the original remains available in history")
        } finally { onEdt { owner.dispose(); sessions.disposeAll() } }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (!condition()) { check(System.nanoTime() < end) { "Branch dialog did not reach the expected state" }; Thread.sleep(25) }
    }
    private fun <T> onEdt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
