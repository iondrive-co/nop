package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Account
import iondrive.nop.agent.CliTools
import iondrive.nop.agent.DEFAULT_CHOICE
import iondrive.nop.agent.UsageReading
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Builds the unique list of model options to offer for [account], starting with [DEFAULT_CHOICE],
 * including the account's configured model if set, and any live [discovered] models.
 */
fun modelOptionsFor(account: Account, discovered: List<String>): List<String> {
    val result = mutableListOf<String>()
    result.add(DEFAULT_CHOICE)
    account.model?.takeIf { it != DEFAULT_CHOICE && it.isNotBlank() }?.let {
        result.add(it)
    }
    for (m in discovered) {
        if (m.isNotBlank() && m !in result) {
            result.add(m)
        }
    }
    return result
}

/** Formats an account's quota percentage and reset ETA for the handover card. */
fun usageDescription(reading: UsageReading?): String {
    if (reading == null) return ""
    if (reading.unavailable != null) return reading.unavailable
    val window = reading.session ?: reading.weekly ?: return ""
    val pct = "${window.percent.toInt()}% used"
    val eta = window.eta()
    return if (eta != null) "$pct · resets in $eta" else pct
}

/**
 * The structured list of available providers for handover.
 *
 * Replaces the cramped flow of buttons with individual provider cards, each with its quota reading,
 * CLI status, a model selection dropdown, and an action button to start the handover with that model.
 */
@Composable
fun HandoverProviderList(
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    modelsFor: (Account) -> List<String>,
    actionLabel: String = "Hand over",
    onHandOver: (Account) -> Unit,
    onUpdateAccount: ((Account) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val selectedModels = remember(accounts) {
        mutableStateMapOf<String, String>().apply {
            accounts.forEach { put(it.name, it.model ?: DEFAULT_CHOICE) }
        }
    }
    var openDropdownAccount by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        accounts.forEach { account ->
            key(account.name) {
                val availableModels = remember(account, modelsFor) {
                    modelOptionsFor(account, modelsFor(account))
                }
                val currentModel = selectedModels[account.name] ?: account.model ?: DEFAULT_CHOICE
                HandoverProviderCard(
                    account = account,
                    reading = readings[account.name],
                    models = availableModels,
                    selectedModel = currentModel,
                    onSelectModel = { chosen ->
                        selectedModels[account.name] = chosen
                        openDropdownAccount = null
                        val updated = account.copy(model = chosen.takeIf { it != DEFAULT_CHOICE })
                        onUpdateAccount?.invoke(updated)
                    },
                    isDropdownOpen = openDropdownAccount == account.name,
                    onToggleDropdown = {
                        openDropdownAccount = if (openDropdownAccount == account.name) null else account.name
                    },
                    actionLabel = actionLabel,
                    onAction = {
                        val target = account.copy(model = currentModel.takeIf { it != DEFAULT_CHOICE })
                        onHandOver(target)
                    },
                )
            }
        }
    }
}

/**
 * One provider card in the handover panel.
 *
 * Shows the account name, vendor name, usage, a model dropdown picker, and the start/handover button.
 */
@Composable
fun HandoverProviderCard(
    account: Account,
    reading: UsageReading?,
    models: List<String>,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    isDropdownOpen: Boolean,
    onToggleDropdown: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val cardBackground = if (JewelTheme.isDark) Color(0xFF26282B) else Color(0xFFF7F8FA)
    val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
    val cli = CliTools.locate(account.provider)
    val isCliInstalled = cli != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(cardBackground)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    account.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("·", color = AgentMuted)
                Text(
                    account.provider.label,
                    color = AgentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val usage = usageDescription(reading)
            if (usage.isNotEmpty()) {
                Text(
                    usage,
                    color = AgentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!isCliInstalled) {
            Text(
                "${account.provider.binary} is not installed",
                color = ChangeColors.REMOVED,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text("Model:", color = AgentMuted)
                OutlinedButton(
                    onClick = onToggleDropdown,
                    enabled = isCliInstalled,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedModel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                        Canvas(Modifier.size(9.dp)) {
                            drawDisclosure(tint, collapsed = false)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onAction,
                enabled = isCliInstalled,
            ) {
                Text(actionLabel)
            }
        }

        if (isDropdownOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(JewelTheme.globalColors.panelBackground)
                    .border(1.dp, border, RoundedCornerShape(6.dp))
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                models.forEach { option ->
                    key(option) {
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        val isSelected = option == selectedModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .hoverable(interaction)
                                .background(
                                    if (hovered) JewelTheme.defaultTabStyle.colors.backgroundHovered
                                    else Color.Transparent,
                                )
                                .clickable {
                                    onSelectModel(option)
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isSelected) "✓" else "  ",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                option,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
