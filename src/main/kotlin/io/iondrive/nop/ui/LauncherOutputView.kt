package io.iondrive.nop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun LauncherOutputView(tab: Tab.LauncherOutput) {
    val scrollState = rememberScrollState()
    val output = tab.run.output

    // Stick to the bottom while output is streaming so new lines stay visible.
    LaunchedEffect(output.length) {
        if (tab.run.running) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val status = when {
                tab.run.running -> "running"
                tab.run.exitCode == 0 -> "exit 0"
                tab.run.exitCode != null -> "exit ${tab.run.exitCode}"
                else -> "pending"
            }
            Text("${tab.run.launcher.name}  ·  ${tab.run.launcher.command}", modifier = Modifier.weight(1f))
            Text(status, color = if (tab.run.exitCode != null && tab.run.exitCode != 0) ChangeColors.REMOVED else ChangeColors.UNTRACKED)
            if (tab.run.running) {
                OutlinedButton(onClick = { tab.run.stop() }) { Text("Stop") }
            } else {
                OutlinedButton(onClick = { tab.run.start() }) { Text("Re-run") }
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
            val fg = androidx.compose.ui.graphics.Color(0xFFA9B7C6)
            Text(
                text = output,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scrollState),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = fg),
            )
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                style = NopScrollbarStyle,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(10.dp)
                    .fillMaxHeight(),
            )
        }
    }
}
