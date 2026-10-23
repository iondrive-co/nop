package iondrive.nop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

@OptIn(ExperimentalComposeUiApi::class)
class SpikeWindowRecoveryTest {
    @Test
    fun `one window's failure can be recovered by rebuilding it`() {
        assumeTrue(System.getenv("NOP_SPIKE_WINDOWS") == "1")
        for (mode in listOf("measure", "compose")) runMode(mode)
    }

    private fun runMode(mode: String) {
        val log = Collections.synchronizedList(mutableListOf<String>())
        var boom by mutableStateOf(false)
        var generation by mutableStateOf(0)
        var tick by mutableStateOf(0)
        var otherSaw = -1
        var rebuiltComposed = false
        val app = thread(name = "app-$mode") {
            application(exitProcessOnExit = false) {
                val factory = WindowExceptionHandlerFactory { window ->
                    WindowExceptionHandler { t ->
                        log += "handler: ${t.javaClass.simpleName}: ${t.message?.take(60)}"
                        SwingUtilities.invokeLater { boom = false; generation++ }
                    }
                }
                CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides factory) {
                    key(generation) {
                        Window(onCloseRequest = {}, title = "victim $generation") {
                            if (generation > 0) SideEffect { rebuiltComposed = true }
                            if (mode == "compose" && boom) error("bad content")
                            Box(Modifier.horizontalScroll(rememberScrollState())) {
                                Box(Modifier.width(if (mode == "measure" && boom) 546566.dp else 100.dp)) {
                                    BasicText("line")
                                }
                            }
                        }
                    }
                    Window(onCloseRequest = {}, title = "bystander") {
                        BasicText("tick $tick")
                        SideEffect { otherSaw = tick }
                        LaunchedEffect(Unit) {
                            delay(1500); boom = true
                            delay(2500); tick = 42
                            delay(1500); exitApplication()
                        }
                    }
                }
            }
        }
        app.join(20_000)
        println("[$mode] log=$log generation=$generation rebuiltComposed=$rebuiltComposed otherSaw=$otherSaw alive=${app.isAlive}")
        check(!app.isAlive) { "application did not end" }
        check(log.isNotEmpty()) { "handler never ran" }
        check(rebuiltComposed) { "rebuilt window never composed" }
        check(otherSaw == 42) { "bystander window stopped recomposing (saw $otherSaw)" }
    }
}
