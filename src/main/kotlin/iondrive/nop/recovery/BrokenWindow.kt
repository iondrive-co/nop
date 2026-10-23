package iondrive.nop.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * A window [WindowRecovery] has given up drawing as itself: what went wrong, and a way to try again.
 *
 * The last rung of the ladder, so it is made of as little as possible — foundation text and boxes,
 * no theme, nothing from the project — to have nothing in common with whatever failed. The agents
 * and terminals the window held are still running; trying again draws them again.
 */
@Composable
fun BrokenWindow(name: String, reason: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Window(onCloseRequest = onClose, title = "$name — nop", state = rememberWindowState(size = DpSize(720.dp, 360.dp))) {
        val style = TextStyle(fontSize = 14.sp, color = Color(0xFF1E1F22))
        Column(
            Modifier.fillMaxSize().background(Color(0xFFF7F8FA)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("This window kept failing while it was drawn, so nop has stopped rebuilding it.", style = style)
            BasicText(
                "Your agents and terminals are still running, and nop's other windows are unaffected. " +
                    "Try again to draw it afresh; the error is in nop's log too.",
                style = style.copy(color = Color(0xFF6C707E)),
            )
            SelectionContainer { BasicText(reason, style = style.copy(color = Color(0xFFC94F4F))) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlainButton("Try again", onRetry)
                PlainButton("Copy error") {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(reason), null)
                }
            }
        }
    }
}

@Composable
private fun PlainButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, Color(0xFFC9CCD6)).background(Color.White).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        BasicText(label, style = TextStyle(fontSize = 13.sp, color = Color(0xFF1E1F22)))
    }
}
