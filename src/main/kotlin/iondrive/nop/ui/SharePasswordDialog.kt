package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Backup
import iondrive.nop.agent.NetworkShare
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** A password field shows each character as a dot. */
private val Masked = OutputTransformation { replace(0, length, "•".repeat(length)) }

/**
 * The backup share asking for a password: opened by itself over the window in front when a backup
 * finds the share wants one, and again from the accounts dialog after a "Not now". Signing in mounts
 * the share and carries on with the backup. Told to remember, GVFS keeps the password in the
 * keyring the way the file manager does, and neither asks for it again.
 *
 * The password field is not focused on opening: the prompt can open while the user is typing in a
 * terminal, and their next line belongs there, not here.
 */
@Composable
internal fun SharePasswordDialog(request: NetworkShare.PasswordRequest) {
    val status = Backup.status
    val user = rememberTextFieldState(request.user)
    val domain = rememberTextFieldState(request.domain)
    val password = rememberTextFieldState()
    var remember by remember { mutableStateOf(true) }
    val busy = status.running

    fun signIn() {
        if (busy || password.text.isEmpty()) return
        Backup.signIn(
            NetworkShare.Credentials(
                user = user.text.toString().trim(),
                domain = domain.text.toString().trim(),
                password = password.text.toString(),
                remember = remember,
            ),
        )
        password.clearText()
    }

    DialogFrame(title = "Backup share", onClose = Backup::notNow, size = DpSize(440.dp, Dp.Unspecified), onSubmit = ::signIn) {
        Text("Sign in to ${request.share}", fontWeight = FontWeight.SemiBold)
        Text("Agent sessions are backed up there, and the share wants a user name and password.", color = AgentMuted)
        if (request.asksUser) Field("User", user)
        if (request.asksDomain) Field("Domain", domain)
        Field("Password", password, masked = true)
        CheckboxRow(
            text = "Remember it in the keyring, as the file manager does",
            checked = remember,
            onCheckedChange = { remember = it },
        )
        val error = when {
            busy -> null
            request.refused -> "The share did not accept that user name and password."
            status.password == null -> status.lastError
            else -> null
        }
        error?.let { Text(it, color = ChangeColors.REMOVED) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) Text("Signing in…", color = AgentMuted)
            OutlinedButton(onClick = Backup::notNow) { Text("Not now") }
            DefaultButton(onClick = ::signIn, enabled = !busy && password.text.isNotEmpty()) { Text("Sign in") }
        }
    }
}

@Composable
private fun Field(label: String, state: TextFieldState, masked: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(72.dp))
        TextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            outputTransformation = if (masked) Masked else null,
        )
    }
}
