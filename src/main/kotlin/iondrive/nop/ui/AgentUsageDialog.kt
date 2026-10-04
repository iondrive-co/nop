package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.Ago
import iondrive.nop.ShortPath
import iondrive.nop.agent.Account
import iondrive.nop.agent.AccountBreakdown
import iondrive.nop.agent.SessionBreakdownItem
import iondrive.nop.agent.UsageBreakdown
import iondrive.nop.agent.UsageReading
import iondrive.nop.agent.UsageScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import java.nio.file.Path

private val ModelColors = listOf(
    Color(0xFF5FAD65), // Green
    Color(0xFF6B9ED8), // Blue
    Color(0xFFCF8E6D), // Orange / amber
    Color(0xFFA074C4), // Purple
    Color(0xFF4DB6AC), // Teal
    Color(0xFFE05555), // Red
)

/** Puts a popup directly under the control that opened it. */
private val BelowAnchorProvider: PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        y = anchorBounds.bottom.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

/**
 * A dedicated window displaying the usage breakdown for agent accounts, showing what percentage
 * of usage went to different models (e.g. Sol vs. Astra) and which agent sessions did what tasks.
 *
 * Separates active quota usage from historical activity prior to the last reset.
 */
@Composable
fun AgentUsageDialog(
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    onClose: () -> Unit,
) {
    DialogFrame(title = "Agent usage", onClose = onClose, size = DpSize(840.dp, 720.dp)) {
        var scope by remember { mutableStateOf(UsageScope.Weekly7d) }
        var selectedAccountName by remember(accounts) {
            mutableStateOf(accounts.firstOrNull()?.name.orEmpty())
        }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        var breakdowns by remember { mutableStateOf<Map<String, AccountBreakdown>>(emptyMap()) }
        var loading by remember { mutableStateOf(true) }
        var showHistorical by remember { mutableStateOf(true) }

        LaunchedEffect(scope, refreshTrigger, accounts) {
            loading = true
            breakdowns = withContext(Dispatchers.IO) {
                UsageBreakdown.compute(accounts, readings, scope)
            }
            loading = false
        }

        val cardBorder = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        val cardBg = if (JewelTheme.isDark) Color(0xFF26282E) else Color(0xFFF2F4F7)
        val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
        val selectedAccount = accounts.firstOrNull { it.name == selectedAccountName }
            ?: accounts.firstOrNull()

        // ── Window Header ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Agent usage", fontWeight = FontWeight.SemiBold)
                Text(
                    "Usage breakdown by model and task across configured accounts",
                    color = AgentMuted,
                )
            }

            // Scope toggle: Active session vs Current week
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UsageScope.entries.forEach { s ->
                    val isSelected = scope == s
                    if (isSelected) {
                        DefaultButton(onClick = {}) { Text(s.label) }
                    } else {
                        OutlinedButton(onClick = { scope = s }) { Text(s.label) }
                    }
                }
            }
        }

        // ── Account Selector Toolbar (Dropdown) ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Account:", fontWeight = FontWeight.Medium, color = AgentMuted)
            AccountDropdown(
                accounts = accounts,
                selected = selectedAccount,
                readings = readings,
                onSelect = { selectedAccountName = it.name },
            )

            selectedAccount?.let { acc ->
                readings[acc.name]?.let { r ->
                    Text("·", color = AgentMuted)
                    Text(usageLine(r), color = AgentMuted)
                }
            }
        }

        Divider(orientation = Orientation.Horizontal, modifier = Modifier.padding(vertical = 6.dp))

        // ── Main Content Area ──
        val breakdown = selectedAccount?.let { breakdowns[it.name] }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) {
                Text("Analyzing usage logs across sessions…", color = AgentMuted, modifier = Modifier.padding(12.dp))
            } else if (selectedAccount == null) {
                Text("No account selected.", color = AgentMuted)
            } else if (breakdown == null) {
                Text("No breakdown data available.", color = AgentMuted)
            } else {
                // Account summary and quota timing banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(selectedAccount.name, fontWeight = FontWeight.Bold)
                        Text(selectedAccount.provider.label, color = AgentMuted)
                    }

                    val formattedStart = UsageBreakdown.formatPeriodStart(breakdown.windowStart)
                    val etaText = when (scope) {
                        UsageScope.Session5h -> breakdown.reading?.session?.eta()?.let { "resets in $it" }
                        UsageScope.Weekly7d -> breakdown.reading?.weekly?.eta()?.let { "resets in $it" }
                    }
                    val timingDesc = buildString {
                        append("Window started: $formattedStart")
                        if (etaText != null) append(" · $etaText")
                    }
                    Text(timingDesc, color = AgentMuted)
                }

                // ── Card 1: Active Quota Window - Model Breakdown ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Active quota usage by model", fontWeight = FontWeight.SemiBold)
                        if (breakdown.currentPeriod.totalTokens > 0) {
                            Text(
                                "${UsageBreakdown.formatTokens(breakdown.currentPeriod.totalTokens)} tokens in current window",
                                color = AgentMuted,
                            )
                        } else if (breakdown.currentPeriod.totalSteps > 0) {
                            Text("${breakdown.currentPeriod.totalSteps} steps in current window", color = AgentMuted)
                        }
                    }

                    if (breakdown.currentPeriod.models.isNotEmpty()) {
                        // Segmented progress bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        ) {
                            breakdown.currentPeriod.models.forEachIndexed { idx, m ->
                                val color = ModelColors[idx % ModelColors.size]
                                Box(
                                    modifier = Modifier
                                        .weight(m.percent.toFloat().coerceAtLeast(0.1f))
                                        .fillMaxHeight()
                                        .background(color),
                                )
                            }
                        }

                        // Model legend & percentages
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            breakdown.currentPeriod.models.forEachIndexed { idx, m ->
                                val color = ModelColors[idx % ModelColors.size]
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                                    Text(m.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${"%.1f".format(m.percent)}%",
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (breakdown.currentPeriod.totalTokens > 0) {
                                        Text(
                                            "(${UsageBreakdown.formatTokens(m.tokens)})",
                                            color = AgentMuted,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No usage recorded in the current quota window.", color = AgentMuted)
                    }
                }

                // ── Card 2: Tasks & Conversations in Current Period ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Tasks in current window (${breakdown.currentPeriod.sessions.size})",
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (breakdown.currentPeriod.sessions.isEmpty()) {
                        Text("No tasks active in this quota window.", color = AgentMuted)
                    } else {
                        breakdown.currentPeriod.sessions.forEach { item ->
                            SessionRow(item)
                        }
                    }
                }

                // ── Card 3: Historical Activity Section (Prior to Reset) ──
                val histSessions = breakdown.historicalPeriod.sessions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showHistorical = !showHistorical },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Historical activity (${histSessions.size} tasks)",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text("· prior to reset", color = AgentMuted)
                            }
                            Text(
                                "Sessions from previous quota periods (past 30 days)",
                                color = AgentMuted,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (breakdown.historicalPeriod.totalTokens > 0) {
                                Text(
                                    "${UsageBreakdown.formatTokens(breakdown.historicalPeriod.totalTokens)} tokens",
                                    color = AgentMuted,
                                )
                            } else if (breakdown.historicalPeriod.totalSteps > 0) {
                                Text("${breakdown.historicalPeriod.totalSteps} steps", color = AgentMuted)
                            }
                            Canvas(Modifier.size(9.dp)) { drawDisclosure(tint, collapsed = !showHistorical) }
                        }
                    }

                    if (showHistorical) {
                        if (histSessions.isEmpty()) {
                            Text("No activity recorded prior to this window in the past 30 days.", color = AgentMuted)
                        } else {
                            if (breakdown.historicalPeriod.models.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    breakdown.historicalPeriod.models.forEachIndexed { idx, m ->
                                        val color = ModelColors[idx % ModelColors.size]
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                                            Text(m.displayName, color = AgentMuted)
                                            Text(
                                                "${"%.1f".format(m.percent)}%",
                                                fontWeight = FontWeight.Medium,
                                                color = AgentMuted,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                            }

                            histSessions.forEach { item ->
                                SessionRow(item)
                            }
                        }
                    }
                }
            }
        }

        // ── Window Footer ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { refreshTrigger++ }) { Text("Refresh") }
            DefaultButton(onClick = onClose) { Text("Done") }
        }
    }
}

/** Dropdown menu for selecting any configured agent account without horizontal overflow. */
@Composable
private fun AccountDropdown(
    accounts: List<Account>,
    selected: Account?,
    readings: Map<String, UsageReading>,
    onSelect: (Account) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)

    Box {
        OutlinedButton(onClick = { open = !open }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    Text(selected.name, fontWeight = FontWeight.SemiBold)
                    Text("(${selected.provider.label})", color = AgentMuted)
                    readings[selected.name]?.let { r ->
                        val pct = r.weekly?.percent?.toInt() ?: r.session?.percent?.toInt()
                        if (pct != null) {
                            Text("· $pct% used", color = AgentMuted)
                        }
                    }
                } else {
                    Text("Select account…", color = AgentMuted)
                }
                Canvas(Modifier.size(9.dp)) { drawDisclosure(tint, collapsed = false) }
            }
        }

        if (open) {
            Popup(
                popupPositionProvider = BelowAnchorProvider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(JewelTheme.globalColors.panelBackground)
                        .border(1.dp, border, RoundedCornerShape(6.dp))
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                ) {
                    accounts.forEach { account ->
                        key(account.name) {
                            val isSelected = account.name == selected?.name
                            val interaction = remember { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            val reading = readings[account.name]

                            Row(
                                modifier = Modifier
                                    .widthIn(min = 280.dp, max = 420.dp)
                                    .hoverable(interaction)
                                    .background(
                                        if (hovered) JewelTheme.defaultTabStyle.colors.backgroundHovered
                                        else Color.Transparent,
                                    )
                                    .clickable {
                                        open = false
                                        onSelect(account)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (isSelected) "✓" else "  ",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) JewelTheme.globalColors.text.normal else Color.Transparent,
                                )
                                Text(
                                    account.name,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(account.provider.label, color = AgentMuted)
                                reading?.let { r ->
                                    val sessionPct = r.session?.percent?.toInt()
                                    val weeklyPct = r.weekly?.percent?.toInt()
                                    val pctStr = when {
                                        weeklyPct != null -> "$weeklyPct% wk"
                                        sessionPct != null -> "$sessionPct% ses"
                                        else -> null
                                    }
                                    if (pctStr != null) {
                                        Text(pctStr, color = AgentMuted, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(item: SessionBreakdownItem) {
    val divider = if (JewelTheme.isDark) Color(0xFF32343A) else Color(0xFFE2E4E8)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Percentage badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ChangeColors.ADDED.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "${"%.1f".format(item.percent)}%",
                    color = ChangeColors.ADDED,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Title
            Text(
                item.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // When active
            Text(
                Ago.of(item.lastActiveAt.toEpochMilli()),
                color = AgentMuted,
            )
        }

        // Secondary detail line
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 54.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val projName = ShortPath.of(Path.of(item.projectPath), max = 30)
            Text("[$projName]", color = AgentMuted)

            if (item.models.isNotEmpty()) {
                val formattedModels = item.models.joinToString(", ") { UsageBreakdown.formatModelName(it) }
                Text(formattedModels, color = AgentMuted)
            }

            if (item.tokens > 0) {
                Text("·   ${UsageBreakdown.formatTokens(item.tokens)} tokens", color = AgentMuted)
            } else if (item.steps > 0) {
                Text("·   ${item.steps} steps", color = AgentMuted)
            }
        }

        Spacer(Modifier.height(4.dp))
        Divider(orientation = Orientation.Horizontal, color = divider)
    }
}
