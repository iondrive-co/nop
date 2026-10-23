package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import iondrive.nop.history.LocalHistory
import iondrive.nop.recovery.LocalWindowId
import iondrive.nop.recovery.Quarantine
import iondrive.nop.recovery.WindowRecovery
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Real windows, wired the way Main wires nop's: one showing a file tab whose content throws from
 * measure — what the 70k-character line did — and a bystander. Run in a JVM of its own on a display
 * of its own by [WindowRecoveryWindowTest]; prints `CHECK name value` lines and exits 0 if the
 * failure cost only the victim window, rebuilt with the tab held back.
 */
@OptIn(ExperimentalComposeUiApi::class)
object WindowRecoveryScenario {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Paths.get(args[0])
        Quarantine.fileOverride = dir.resolve("quarantine")
        WindowRecovery.showingFileOverride = dir.resolve("showing")
        val file = dir.resolve("IDEAS.md").also { Files.writeString(it, "# ideas\n") }.toFile()
        val tab = Tab.FileView(file)
        val victim = 1L

        var contentFails by mutableStateOf(false)
        var chromeFails by mutableStateOf(false)
        var tick by mutableStateOf(0)
        var bystanderSaw = -1
        var restarts = 0
        val checks = linkedMapOf<String, Any?>()
        WindowRecovery.restart = { restarts++; true }

        application(exitProcessOnExit = false) {
            key(victim, WindowRecovery.generation(victim)) {
                val onFailure = remember { WindowRecovery.handlerFactory(victim) }
                CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides onFailure, LocalWindowId provides victim) {
                    Window(onCloseRequest = {}, title = "victim") {
                        if (chromeFails) error("the window's own chrome failed")
                        IntUiTheme(isDark = false) {
                            val tabsState = remember { TabsState().apply { open(tab) } }
                            TabbedViewerPanel(
                                tabsState = tabsState,
                                repo = null,
                                editStore = remember { FileEditStore() },
                                localHistory = remember { LocalHistory(dir.resolve("history")) },
                            )
                            // The tab's content failing, as long as the tab is drawn at all.
                            if (contentFails && Quarantine.entry(tab.recoveryKey) == null) {
                                Box(Modifier.layout { _, _ -> throw IllegalArgumentException("Can't represent a width of 546566 and height of 0 in Constraints") })
                            }
                        }
                    }
                }
            }
            Window(onCloseRequest = {}, title = "bystander") {
                BasicText("tick $tick")
                SideEffect { bystanderSaw = tick }
                LaunchedEffect(Unit) {
                    delay(2_000)
                    checks["showing before"] = WindowRecovery.showingIn(victim).toList()

                    contentFails = true
                    checks["rebuilt"] = withTimeoutOrNull(10_000) {
                        while (WindowRecovery.generation(victim) < 1) delay(50)
                        true
                    } ?: false
                    delay(1_500)
                    checks["held back"] = Quarantine.entry(tab.recoveryKey)?.reason
                    checks["showing after"] = WindowRecovery.showingIn(victim).toList()
                    checks["generation after"] = WindowRecovery.generation(victim)

                    chromeFails = true
                    checks["restarted for unblamed failure"] = withTimeoutOrNull(10_000) {
                        while (restarts < 1) delay(50)
                        true
                    } ?: false

                    tick = 42
                    delay(500)
                    checks["bystander"] = bystanderSaw
                    exitApplication()
                }
            }
        }

        checks.forEach { (name, value) -> println("CHECK $name = $value") }
        val ok = checks["showing before"] == listOf(tab.recoveryKey) &&
            checks["rebuilt"] == true &&
            (checks["held back"] as? String)?.contains("546566") == true &&
            checks["showing after"] == emptyList<String>() &&
            checks["generation after"] == 1 &&
            checks["restarted for unblamed failure"] == true &&
            checks["bystander"] == 42
        println(if (ok) "RESULT OK" else "RESULT FAILED")
        exitProcess(if (ok) 0 else 1)
    }
}
