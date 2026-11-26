package iondrive.nop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Account
import iondrive.nop.agent.ClaudeResets
import iondrive.nop.agent.CodexResets
import iondrive.nop.agent.LimitResets
import iondrive.nop.agent.Provider
import iondrive.nop.agent.ResetOffer
import iondrive.nop.agent.ResetOutcome
import iondrive.nop.agent.Usage
import iondrive.nop.agent.UsageReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * The usage-limit resets one account holds, each with a way to use one. Using one always asks first,
 * in place, and says what it does and what it leaves alone, because a reset spent by a stray click
 * is gone. [onUse] spends it and answers what happened; nothing here retries.
 */
@Composable
internal fun ResetsPanel(resets: LimitResets?, onUse: suspend (ResetOffer) -> ResetOutcome) {
    var confirming by remember { mutableStateOf<ResetOffer?>(null) }
    var busy by remember { mutableStateOf<ResetOffer?>(null) }
    var result by remember { mutableStateOf<ResetOutcome?>(null) }
    val scope = rememberCoroutineScope()
    val offers = resets?.offers.orEmpty()
    val confirmation = confirming?.takeIf { it in offers }

    // A fresh reading may no longer hold what was about to be used.
    LaunchedEffect(resets) {
        if (confirming !in offers) confirming = null
    }

    if (resets == null && busy == null && result == null) return

    fun use(offer: ResetOffer) {
        if (busy != null) return
        busy = offer
        confirming = null
        result = null
        scope.launch {
            result = try {
                onUse(offer)
            } finally {
                busy = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val blocked = resets?.blocked.orEmpty()
        if (offers.isNotEmpty() || blocked.isNotEmpty()) Text("Limit resets", fontWeight = FontWeight.SemiBold)
        offers.forEach { offer ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(offer.count().replaceFirstChar(Char::uppercaseChar))
                offer.until?.let { Text("use by ${resetDate(it)}", color = AgentMuted) }
                Link(
                    "Review reset",
                    enabled = busy == null && confirmation == null,
                    onClick = { confirming = offer },
                )
            }
            if (confirmation == offer) ResetConfirm(offer, onUse = { use(offer) }, onCancel = { confirming = null })
        }
        blocked.forEach { Text(it, color = AgentMuted) }
        if (offers.isEmpty() && blocked.isEmpty()) resets?.none?.let { Text(it, color = AgentMuted) }
        busy?.let { Text("Using your ${it.kind}…", color = AgentMuted) }
        result?.let { Text(it.text, color = if (it.ok) ChangeColors.ADDED else ChangeColors.REMOVED) }
    }
}

/** The question asked before [offer] is used: what it does, what it leaves, and whether it is early. */
@Composable
private fun ResetConfirm(offer: ResetOffer, onUse: () -> Unit, onCancel: () -> Unit) {
    val border = if (JewelTheme.isDark) Color(0xFF4E5157) else Color(0xFFC9CCD6)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(confirmQuestion(offer))
        Text(confirmDetail(offer), color = AgentMuted)
        offer.early?.let { Text(it, color = ChangeColors.CONFLICT) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = onUse) { Text("Use reset") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** "Use your Fable reset now?", or "Use one of your 2 5-hour resets now?". */
internal fun confirmQuestion(offer: ResetOffer): String =
    if (offer.left <= 1) "Use your ${offer.kind} now?" else "Use one of your ${offer.count()} now?"

/** "Refills your 5-hour limit now · your weekly limit still resets Mon 30 Nov, 14:00". */
internal fun confirmDetail(offer: ResetOffer, zone: ZoneId = ZoneId.systemDefault()): String =
    offer.effect + (offer.weekResets?.let { " · your weekly limit still resets ${resetDate(it, zone)}" } ?: "")

private val RESET_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

internal fun resetDate(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = RESET_DATE.format(at.atZone(zone))

private val resetsInProgress = ConcurrentHashMap.newKeySet<Pair<Provider, Path>>()

/**
 * Spends [offer] for [account] through its provider, then reads the account's usage and resets again
 * so the dialog shows what the reset changed. Each provider call is made once; see
 * [ClaudeResets.claim] and [CodexResets.consume].
 */
internal suspend fun useReset(
    account: Account,
    offer: ResetOffer,
    onReading: (Account, UsageReading) -> Unit,
): ResetOutcome = withContext(Dispatchers.IO) {
    val home = runCatching { account.homePath.toRealPath() }.getOrElse { account.homePath.toAbsolutePath().normalize() }
    val key = account.provider to home
    if (!resetsInProgress.add(key)) return@withContext ResetOutcome(false, "A reset is already running for this account")
    try {
        val outcome = if (offer.until?.isAfter(Instant.now()) == false) {
            ResetOutcome(false, "This reset has expired")
        } else when (account.provider) {
            Provider.Anthropic -> ClaudeResets.claim(account, offer)
            Provider.OpenAI -> CodexResets.consume(account, offer)
            else -> ResetOutcome(false, "${account.provider.label} has no usage limit resets")
        }
        Usage.invalidate(account)
        val reading = runCatching {
            if (account.provider == Provider.OpenAI) Usage.refreshCodexResets(account)
            Usage.read(account)
        }.getOrNull()
        if (reading != null) withContext(Dispatchers.Main) { onReading(account, reading) }
        outcome
    } finally {
        resetsInProgress.remove(key)
    }
}
