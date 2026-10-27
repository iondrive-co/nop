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
import iondrive.nop.agent.Account
import iondrive.nop.agent.Provider
import java.awt.EventQueue
import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Account choices reach both saved settings and the session being started. */
class AgentPickerTest {
    @TempDir
    lateinit var home: Path
    private lateinit var originalHome: String

    @BeforeEach
    fun installStubClis() {
        originalHome = System.getProperty("user.home")
        val bin = Files.createDirectories(home.resolve(".local/bin"))
        Provider.entries.forEach {
            Files.writeString(bin.resolve(it.binary), "#!/bin/sh\nexit 0\n").toFile().setExecutable(true)
        }
        System.setProperty("user.home", home.toString())
    }

    @AfterEach
    fun restoreHome() {
        System.setProperty("user.home", originalHome)
    }

    private val accounts = listOf(
        Account("claude-alpha", Provider.Anthropic, "/home/dev/.accounts/claude-alpha"),
        Account("codex-alpha", Provider.OpenAI, "/home/dev/.accounts/codex-alpha"),
    )

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Picker(
        initialAccounts: List<Account>,
        handover: Boolean = false,
        persist: Boolean = true,
        width: Int = 700,
    ) : AutoCloseable {
        private var accounts by mutableStateOf(initialAccounts)
        val removed = mutableListOf<String>()
        val updated = mutableListOf<Account>()
        val launched = mutableListOf<Account>()
        private var time = 0L
        // Every call into the scene is made on the AWT thread: see HandoverPromptEditorTest.
        private val scene = onEdt {
            ImageComposeScene(width, 600, Density(1f)) {
                IntUiTheme(isDark = false) {
                    val update: (Account) -> Unit = { changed ->
                        updated += changed
                        if (persist) accounts = accounts.map { if (it.name == changed.name) changed else it }
                    }
                    if (handover) {
                        HandoverProviderList(
                            accounts = accounts,
                            readings = emptyMap(),
                            modelsFor = { listOf("code-model") },
                            onHandOver = { launched += it },
                            onUpdateAccount = update,
                        )
                    } else AgentPicker(
                        accounts = accounts,
                        readings = emptyMap(),
                        sessions = emptyList(),
                        projectDir = Path.of("/home/dev/project"),
                        modelsFor = { listOf("code-model") },
                        onUpdateAccount = update,
                        onRemoveAccount = { removed += it.name },
                        onLaunch = { launched += it },
                        onReopen = {},
                        onSettings = {},
                    )
                }
            }
        }

        fun settle() = repeat(10) {
            time += 16_000_000
            onEdt { scene.render(time).close() }
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

        fun snapshot(path: Path) {
            Files.createDirectories(path.parent)
            onEdt {
                scene.render(time).use { image ->
                    checkNotNull(image.encodeToData()).use { data -> Files.write(path, data.bytes) }
                }
            }
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
    @Test
    fun `reasoning and model choices are saved together and used by Start`() {
        Picker(listOf(accounts.first())).use { picker ->
            picker.settle()
            picker.click(picker.nodes("default")[1])
            picker.click(picker.nodes("high").single())
            assertEquals("high", picker.updated.last().reasoning)

            picker.click(picker.nodes("default").single())
            picker.click(picker.nodes("code-model").single())
            assertEquals("high", picker.updated.last().reasoning)
            assertEquals("code-model", picker.updated.last().model)
            picker.click(picker.nodes("Start").single())
            assertEquals(picker.updated.last(), picker.launched.single())
        }
    }

    @Test
    fun `default clears an existing reasoning override`() {
        Picker(listOf(accounts.first().copy(reasoning = "high"))).use { picker ->
            picker.settle()
            picker.click(picker.nodes("high").single())
            picker.click(picker.nodes("default").last())
            picker.click(picker.nodes("Start").single())
            assertEquals(null, picker.updated.last().reasoning)
            assertEquals(null, picker.launched.single().reasoning)
        }
    }

    @Test
    fun `reasoning options follow the selected provider and only one list is open`() {
        Picker(accounts).use { picker ->
            picker.settle()
            picker.click(picker.nodes("default")[1])
            assertTrue(picker.shows("Ultracode"))
            picker.click(picker.nodes("default")[4])
            assertTrue(!picker.shows("Ultracode"))
            assertTrue(picker.shows("minimal"))
            picker.click(picker.nodes("medium").single())
            assertEquals("codex-alpha", picker.updated.single().name)
            assertEquals("medium", picker.updated.single().reasoning)
        }
    }

    @Test
    fun `handover keeps both choices without saving settings`() {
        Picker(listOf(accounts.first()), handover = true, persist = false).use { picker ->
            picker.settle()
            picker.click(picker.nodes("default")[1])
            picker.click(picker.nodes("high").single())
            picker.click(picker.nodes("default").single())
            picker.click(picker.nodes("code-model").single())
            picker.click(picker.nodes("Hand over").single())
            assertEquals("high", picker.launched.single().reasoning)
            assertEquals("code-model", picker.launched.single().model)
        }
    }

    @Test
    fun `choices and Start fit a narrow picker`() {
        Picker(listOf(accounts.first()), width = 350).use { picker ->
            picker.settle()
            for (label in listOf("Model:", "Reasoning:", "Start")) {
                val bounds = picker.nodes(label).single().boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= 350, "$label must fit the picker")
            }
            picker.snapshot(Path.of("build/test-screenshots/agent-picker-reasoning.png"))
        }
    }

}
