package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.AgentSession
import iondrive.nop.agent.AgentSessions
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.styling.TabStyle
import org.jetbrains.jewel.ui.theme.defaultTabStyle

enum class ToolTab(val label: String) {
    /**
     * The terminals at the head of the strip. Unlike every other entry this is not one tab — the
     * strip draws one per open terminal and [RunSessions.selectedId] says which of them is on
     * screen. It is still an entry here so that "what is the tool panel showing" stays one value:
     * a separate "a terminal is selected" flag beside this enum could fall out of step with it.
     */
    Terminal("Terminal"),

    /**
     * The vendor coding agents. Like [Terminal] this is not one tab: each open session draws its
     * own, and its "+" opens the picker rather than a session, because which account to spend is a
     * choice and not a default. Selected with nothing picked, that picker is what shows.
     *
     * It has no fixed tab of its own — the "+" is how you get a new one, the way it is for the
     * terminals.
     */
    Agent("Agent"),
    Commit("Commit"),
    Search("Search"),
    Usages("Usages"),
    Stash("Stash"),

    /** Rendered markdown for whichever .md file the editor is showing. */
    Preview("Preview"),

    /** The launcher runs. */
    Run("Run"),
}

/**
 * The tabs with a fixed label. Terminals and agent sessions are both absent: they come and go, each
 * draws its own tab, and each has a "+" of its own to make another.
 */
private val FIXED_TOOL_TABS: List<ToolTab> =
    ToolTab.entries.filterNot { it == ToolTab.Terminal || it == ToolTab.Agent }

/** Caps how wide one tab may grow, so a long terminal name can't push the rest off the strip. */
private val TAB_MAX_WIDTH = 160.dp

/** How far an arrow press moves the strip when the viewport hasn't been measured yet. */
private const val MIN_SCROLL_STEP = 80

/** Room for a renamed tab's text. Fixed: the strip measures its children against no width at all. */
private val RENAME_FIELD_WIDTH = 120.dp

/**
 * Marks the terminal tabs out from the fixed ones beside them. Drawn with the label rather than
 * stored in the name, so a tab the user has renamed to "build" is still visibly a terminal.
 */
private const val TERMINAL_GLYPH = "⌨"

/**
 * Marks an agent session's tab. Like [TERMINAL_GLYPH] it is drawn with the label rather than stored
 * in it, so a session renamed by the CLI's own title is still visibly an agent.
 */
private const val AGENT_GLYPH = "✦"

/**
 * The tabs in the tool panel on the window's right edge, and the panel under them.
 *
 * The strip is in three parts. The terminals come first — a shell at the project root, plus one for
 * every press of the "+" that follows them — then any open agent sessions; each of those is
 * closeable, because closing one is what kills the process behind it. After them come the fixed
 * tabs (Agent, Commit, Search, …), which are part of the chrome rather than a user-managed
 * collection and so have no ×.
 *
 * Selection state lives in [App] so external triggers can flip to a tab without poking the panel —
 * Ctrl+Shift+F for Search, Alt+F7 for Usages, selecting a markdown file for Preview, and starting a
 * launcher for Run.
 *
 * Every panel is composed into the same slot, so only the selected one exists at a time. That is
 * why the state each keeps alive across switches is hoisted into [App] — the commit message, the
 * search query, the terminals — rather than remembered inside the panel, which would lose it the
 * moment the user looked at something else.
 */
@Composable
fun ToolTabs(
    selected: ToolTab,
    onSelect: (ToolTab) -> Unit,
    terminals: RunSessions,
    onNewTerminal: () -> Unit,
    onSelectTerminal: (String) -> Unit,
    onCloseTerminal: (String) -> Unit,
    agents: AgentSessions,
    onNewAgent: () -> Unit,
    onShowPicker: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onCloseAgent: (String) -> Unit,
    onRenameAgent: (String, String) -> Unit,
    terminal: @Composable () -> Unit,
    agent: @Composable () -> Unit,
    commit: @Composable () -> Unit,
    search: @Composable () -> Unit,
    usages: @Composable () -> Unit,
    stash: @Composable () -> Unit,
    preview: @Composable () -> Unit,
    run: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ToolTabStrip(
            selected = selected,
            onSelect = onSelect,
            terminals = terminals,
            onNewTerminal = onNewTerminal,
            onSelectTerminal = onSelectTerminal,
            onCloseTerminal = onCloseTerminal,
            agents = agents,
            onNewAgent = onNewAgent,
            onShowPicker = onShowPicker,
            onSelectAgent = onSelectAgent,
            onCloseAgent = onCloseAgent,
            onRenameAgent = onRenameAgent,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                ToolTab.Terminal -> terminal()
                ToolTab.Agent -> agent()
                ToolTab.Commit -> commit()
                ToolTab.Search -> search()
                ToolTab.Usages -> usages()
                ToolTab.Stash -> stash()
                ToolTab.Preview -> preview()
                ToolTab.Run -> run()
            }
        }
    }
}

/**
 * The strip itself: terminals, the "+", then the fixed tabs, scrolling sideways under two arrows.
 *
 * Drawn here rather than with Jewel's `TabStrip` for the same reason the editor bar is (see
 * [TabStripBar]): this strip is not a flat list of tabs. It has a button wedged between its two
 * halves, and it needs the scroll position that `TabStrip` keeps to itself — the tool panel is only
 * a few hundred pixels wide, so a couple of terminals is all it takes to push Commit off the end,
 * and arrows the user can press are the way back. The tab itself is still drawn from the theme's
 * tab style, so it looks like the Jewel strip it replaces.
 */
@Composable
private fun ToolTabStrip(
    selected: ToolTab,
    onSelect: (ToolTab) -> Unit,
    terminals: RunSessions,
    onNewTerminal: () -> Unit,
    onSelectTerminal: (String) -> Unit,
    onCloseTerminal: (String) -> Unit,
    agents: AgentSessions,
    onNewAgent: () -> Unit,
    onShowPicker: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onCloseAgent: (String) -> Unit,
    onRenameAgent: (String, String) -> Unit,
) {
    val style = JewelTheme.defaultTabStyle
    val isDark = JewelTheme.isDark
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // maxValue is Int.MAX_VALUE until the row has been measured once; treating that as overflow
    // would flash both arrows on the first frame every time a project opens.
    val overflowing = scroll.maxValue in 1 until Int.MAX_VALUE
    val step = (scroll.viewportSize * 3 / 4).coerceAtLeast(MIN_SCROLL_STEP)

    // Opening a terminal scrolls the "+" back into view, not the tab it just made. The tab sits
    // immediately left of the "+" so it comes along anyway, and stopping at the tab would leave the
    // button that made it off the end of the strip — with "and so on" then costing a press of the
    // right arrow first. Only a *new* terminal does this: closing one must not move the strip.
    // The terminal whose tab is currently being renamed in place, if any.
    var renamingId by remember { mutableStateOf<String?>(null) }

    val plus = remember { BringIntoViewRequester() }
    var openTerminals by remember { mutableStateOf(terminals.sessions.size) }
    LaunchedEffect(terminals.sessions.size) {
        val now = terminals.sessions.size
        if (now > openTerminals) plus.bringIntoView()
        openTerminals = now
    }
    val agentPlus = remember { BringIntoViewRequester() }
    var openAgents by remember { mutableStateOf(agents.sessions.size) }
    LaunchedEffect(agents.sessions.size) {
        val now = agents.sessions.size
        if (now > openAgents) agentPlus.bringIntoView()
        openAgents = now
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(style.metrics.tabHeight)
            .background(style.colors.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (overflowing) {
            ScrollArrow(pointsLeft = true, enabled = scroll.canScrollBackward, isDark = isDark) {
                scope.launch { scroll.animateScrollTo(scroll.value - step) }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scroll)
                .selectableGroup(),
        ) {
            terminals.sessions.forEach { run ->
                // Keyed by run id so a tab's hover state follows the terminal it belongs to when one
                // further left is closed.
                key(run.id) {
                    if (run.id == renamingId) {
                        TabRenameField(
                            initial = run.title,
                            style = style,
                            onCommit = { name ->
                                terminals.rename(run.id, name)
                                renamingId = null
                            },
                            onCancel = { renamingId = null },
                        )
                    } else {
                        ToolStripTab(
                            label = "$TERMINAL_GLYPH ${run.title}",
                            selected = selected == ToolTab.Terminal && run.id == terminals.selectedId,
                            isDark = isDark,
                            style = style,
                            onClick = { onSelectTerminal(run.id) },
                            onClose = { onCloseTerminal(run.id) },
                            onRename = { renamingId = run.id },
                        )
                    }
                }
            }
            NewTerminalButton(
                isDark = isDark,
                onClick = onNewTerminal,
                modifier = Modifier.bringIntoViewRequester(plus),
            )
            // Agent sessions sit between the terminals and the fixed tabs, so both kinds of live
            // process are at the head of the strip and the chrome stays together at the tail. Each
            // is closeable and renameable for the same reasons a terminal is: the × is what stops
            // the process, and the account name says which quota is being spent, never which piece
            // of work the tab is doing.
            // With nothing running there is still a tab, and it is still called "Agent": the strip
            // should look the same whether or not a session happens to be open, the way the
            // terminals' does. A shape that appears and disappears is one the eye has to re-find.
            // Clicking it shows the picker, which is what the panel holds with nothing selected.
            if (agents.sessions.isEmpty() && agents.pickerTabVisible) {
                ToolStripTab(
                    label = "$AGENT_GLYPH ${AgentSession.DEFAULT_TITLE}",
                    selected = selected == ToolTab.Agent,
                    isDark = isDark,
                    style = style,
                    onClick = onShowPicker,
                    // Closes like a terminal's does, and reserves the same room whether or not the
                    // × is showing. What it closes is the tab, not a process — there isn't one yet
                    // — which is the same thing closing the last terminal does to the strip.
                    onClose = { agents.hidePickerTab() },
                )
            }
            agents.sessions.forEach { agentSession ->
                key(agentSession.sessionId) {
                    if (agentSession.sessionId == renamingId) {
                        TabRenameField(
                            initial = agentSession.title,
                            style = style,
                            onCommit = { name ->
                                onRenameAgent(agentSession.sessionId, name)
                                renamingId = null
                            },
                            onCancel = { renamingId = null },
                        )
                    } else {
                        ToolStripTab(
                            label = "$AGENT_GLYPH ${agentSession.title}",
                            selected = selected == ToolTab.Agent &&
                                agentSession.sessionId == agents.selectedId,
                            isDark = isDark,
                            style = style,
                            onClick = { onSelectAgent(agentSession.sessionId) },
                            onClose = { onCloseAgent(agentSession.sessionId) },
                            onRename = { renamingId = agentSession.sessionId },
                        )
                    }
                }
            }
            NewAgentButton(
                isDark = isDark,
                onClick = onNewAgent,
                modifier = Modifier.bringIntoViewRequester(agentPlus),
            )
            FIXED_TOOL_TABS.forEach { tab ->
                key(tab) {
                    val requester = remember { BringIntoViewRequester() }
                    RevealWhenSelected(requester, selected == tab)
                    ToolStripTab(
                        label = tab.label,
                        selected = selected == tab,
                        isDark = isDark,
                        style = style,
                        onClick = { onSelect(tab) },
                        onClose = null,
                        modifier = Modifier.bringIntoViewRequester(requester),
                    )
                }
            }
        }
        if (overflowing) {
            ScrollArrow(pointsLeft = false, enabled = scroll.canScrollForward, isDark = isDark) {
                scope.launch { scroll.animateScrollTo(scroll.value + step) }
            }
        }
    }

}

/**
 * One tab in the strip: its label, an accent underline while selected, and — for a terminal — a
 * close "x" once the pointer is on it and [onRename] on a right-click.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolStripTab(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    style: TabStyle,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
    onRename: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> style.colors.backgroundSelected
        hovered -> style.colors.backgroundHovered
        else -> style.colors.background
    }
    val content = when {
        selected -> style.colors.contentSelected
        hovered -> style.colors.contentHovered
        else -> style.colors.content
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .tabUnderline(selected, style.colors.underlineSelected, style.metrics.underlineThickness)
            .hoverable(interaction)
            // Right-click, caught on the Initial pass so it is seen before `clickable` below claims
            // the press — and consumed there, so the tab doesn't also select itself.
            .let { m ->
                if (onRename == null) m else m.pointerInput(onRename) { secondaryClicks(onRename) }
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(style.metrics.closeContentGap),
        ) {
            Text(
                text = label,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
            )
            if (onClose != null) {
                // Reserve the close zone whether or not it's showing, so the label doesn't shift as
                // the pointer moves across the strip. Shown on hover or when selected.
                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (hovered || selected) CloseButton(isDark = isDark, onClose = onClose)
                }
            }
        }
    }
}

/**
 * Calls [onRightClick] for every press of the secondary button, taking the event on the
 * [Initial][PointerEventPass.Initial] pass and consuming it so nothing behind this node — the tab's
 * own `clickable` included — treats it as a click of its own.
 */
private suspend fun PointerInputScope.secondaryClicks(onRightClick: () -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onRightClick()
            }
        }
    }
}

/**
 * A terminal tab mid-rename: the label swapped for an editable one, filled with the current name and
 * selected, so typing replaces it. Enter keeps the new name, Esc — or clicking anywhere else —
 * leaves the old one.
 *
 * Renaming happens here in the strip rather than through a menu or a dialog because the strip is the
 * only part of the tool panel that is certain to be on screen: the terminal under it is a
 * heavyweight AWT component, and every Compose popup — a context menu included — is composited
 * *below* it, so a menu opened over a terminal is drawn behind it and all the user sees is the
 * sliver that overhangs the strip.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabRenameField(
    initial: String,
    style: TabStyle,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val state = rememberTextFieldState(initial, TextRange(0, initial.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The field is composed unfocused and asks for focus a beat later, so "lost focus" only means
    // the user clicked away once it has actually had it — without this the first callback, which
    // arrives before the request lands, cancels the rename the instant it opens.
    var everFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .background(style.colors.backgroundSelected)
            .tabUnderline(true, style.colors.underlineSelected, style.metrics.underlineThickness)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .width(RENAME_FIELD_WIDTH)
                .focusRequester(focus)
                // A rename left open on a tab the user has moved on from is a trap — the next click
                // in the strip would be typing into it. Losing focus drops it.
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) everFocused = true else if (everFocused) onCancel()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onCommit(state.text.toString()); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
            textStyle = JewelTheme.defaultTextStyle.copy(color = style.colors.contentSelected),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(style.colors.contentSelected),
        )
    }
}

/**
 * Scrolls a tab into view when it becomes the selected one, which is what makes a jump made from
 * outside the strip — Ctrl+Shift+F to Search, Alt+F7 to Usages — land somewhere the user can see.
 *
 * Skipped on the first pass: the strip should open at its start, showing the terminals, rather than
 * scrolled along to whichever tab happened to be selected when the project was laid out.
 */
@Composable
private fun RevealWhenSelected(requester: BringIntoViewRequester, selected: Boolean) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (selected && settled) requester.bringIntoView()
        settled = true
    }
}

/** The "+" after the terminals: opens another one at the project root, to the right of the last. */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NewTerminalButton(isDark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    Tooltip(tooltip = { Text("New terminal at the project root") }) {
        Box(
            modifier = modifier.fillMaxHeight().width(24.dp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) { drawPlusIcon(tint) }
        }
    }
}

/** The "+" after the agent sessions: starts another one. Drawn exactly as the terminals' is. */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NewAgentButton(isDark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    Tooltip(tooltip = { Text("New agent session") }) {
        Box(
            modifier = modifier.fillMaxHeight().width(24.dp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) { drawPlusIcon(tint) }
        }
    }
}

/**
 * One end of the strip when it has more tabs than it can show. Both arrows appear together — an
 * arrow that comes and goes as the strip reaches its ends is a moving target — and the one with
 * nothing left to reveal greys out.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScrollArrow(pointsLeft: Boolean, enabled: Boolean, isDark: Boolean, onClick: () -> Unit) {
    val base = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    val tint = if (enabled) base else base.copy(alpha = 0.35f)
    Box(
        modifier = Modifier.fillMaxHeight().width(18.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(9.dp)) { drawChevron(tint, pointsLeft) }
    }
}
