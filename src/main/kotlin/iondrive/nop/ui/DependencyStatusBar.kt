package iondrive.nop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text

/** Keep import logs out of the editor until the user asks for them. */
@Composable
internal fun DependencyStatusBar(status: String) {
    var dismissed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    if (dismissed) return
    val summary = when {
        status.startsWith("Gradle import failed") -> "Gradle import failed — F5 to retry"
        else -> status.lineSequence().first().trim()
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("dependency-status-bar").padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (expanded) "Hide details" else "Details", modifier = Modifier.clickable(role = Role.Button) { expanded = !expanded })
            Text("Dismiss", modifier = Modifier.clickable(role = Role.Button) { dismissed = true })
        }
        if (expanded) {
            SelectionContainer {
                Text(status, modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}
