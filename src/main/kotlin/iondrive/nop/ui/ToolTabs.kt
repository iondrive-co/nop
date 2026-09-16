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

/**
 * Which half of the tool region a tab draws into — see [ToolTabs].
 *
 * [Session] is everything with a live process behind it, and they share one AWT card panel, so only
 * one of them can be visible at a time however the panes are arranged. [Tool] is everything Compose
 * draws itself, which can sit beside a running session without fighting it for the screen.
 */
enum class ToolSide { Session, Tool }

enum class ToolTab(val label: String, val side: ToolSide) {
    /**
     * The terminals at the head of the strip. Unlike every other entry this is not one tab — the
     * strip draws one per open terminal and [RunSessions.selectedId] says which of them is on
     * screen. It is still an entry here so that "what is the tool panel showing" stays one value:
     * a separate "a terminal is selected" flag beside this enum could fall out of step with it.
     */
    Terminal("Terminal", ToolSide.Session),

    /**
     * The vendor coding agents. Like [Terminal] this is not one tab: each open session draws its
     * own, and its "+" opens the picker rather than a session, because which account to spend is a
     * choice and not a default. Selected with nothing picked, that picker is what shows.
     *
     * It has no fixed tab of its own — the "+" is how you get a new one, the way it is for the
     * terminals.
     */
    Agent("Agent", ToolSide.Session),
    Commit("Commit", ToolSide.Tool),

    /**
     * The changed files and their diffs, against HEAD, the branch, or the point the agent on the
     * left started from. It is nop's own answer to the review panel the vendor CLIs draw inside
     * their TUI — see [DiffPanel] for why it is worth having twice.
     */
    Diff("Diff", ToolSide.Tool),
    Search("Search", ToolSide.Tool),
    Usages("Usages", ToolSide.Tool),
    Stash("Stash", ToolSide.Tool),

    /** Rendered markdown for whichever .md file the editor is showing. */
    Preview("Preview", ToolSide.Tool),

    /**
     * The launcher runs. Like [Terminal] and [Agent] this is not one tab: each run started from the
     * ▶ menu draws its own, and [RunSessions.selectedId] says which of them is on screen.
     *
     * No "+" beside them, unlike the other two. A run is a *named script*, so the thing that makes
     * one is the launcher menu, which is where the names are; a "+" here would be a second entry
     * point that still had to open that menu to ask which script it meant.
     */
    Run("Run", ToolSide.Session),

    /**
     * The git logs. Like [Terminal], [Agent] and [Run] this is not one tab: each path whose log is
     * open draws its own, and [HistorySessions.selectedId] says which of them is on screen.
     *
     * No "+" beside them, for the same reason the runs have none. A log is *of* a path, so the
     * thing that opens one is the file — the tree's right-click menu, or the editor's — and a "+"
     * here would be a second entry point that still had to ask which path it meant.
     */
    History("History", ToolSide.Tool),
}

/**
 * The tool strip's tabs with a fixed label. Terminals, agent sessions, launcher runs and git logs
 * are all absent: they come and go, and each draws its own tab.
 */
private val FIXED_TOOL_TABS: List<ToolTab> =
    ToolTab.entries.filter { it.side == ToolSide.Tool && it != ToolTab.History }

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
 * Marks a launcher run's tab. Drawn here rather than kept in the session's name for the same reason
 * the other two are — and for one more: a run tab restored at the next start is rebuilt from the
 * launcher's own name, so a glyph baked into the title would come back doubled.
 */
private const val RUN_GLYPH = "▶"

/**
 * Marks a git log's tab. Drawn with the label like the other three, which here also keeps the tab
 * reading as the file it is a log of: the label is the plain file name, so two logs of `build.gradle.kts`
 * in different modules are told apart by position rather than by a decorated name.
 */
private const val HISTORY_GLYPH = "⎇"

/**
 * The tool region on the window's right edge: two strips of tabs, and under them the session on the
 * left beside whichever tool is selected on the right.
 *
 * The side-by-side split is the point of the arrangement. An agent session is a full-screen TUI
 * that wants every pixel it can get and is read for minutes at a time; a tool panel is glanced at.
 * As one strip, looking at a diff cost you sight of the agent that made it — so they sit beside
 * each other now, with the tool half collapsible for when the agent is all that matters.
 *
 * Which collection is on which side is a constraint, not a taste. Every terminal in the window —
 * shells, launcher runs and agent sessions alike — lives in one shared heavyweight AWT card panel
 * (see [TerminalView]), so exactly one of them can be on screen at any moment. Putting all three on
 * the left and only the Compose-drawn panels on the right is what keeps that invisible: the two
 * sides can never want a terminal at the same time.
 *
 * Selection is two values, one per side, both living in [App] so external triggers can flip either
 * without poking the strip — Ctrl+Shift+F for Search, Alt+F7 for Usages, selecting a markdown file
 * for Preview, starting a launcher for Run.
 *
 * Every panel is composed into its side's slot, so only the selected one exists at a time. That is
 * why the state each keeps alive across switches is hoisted into [App] — the commit message, the
 * search query, the terminals — rather than remembered inside the panel, which would lose it the
 * moment the user looked at something else.
 */
@Composable
fun ToolTabs(
    /** The tool on the right. Always a [ToolSide.Tool] tab; [App] guarantees it. */
    selected: ToolTab,
    onSelect: (ToolTab) -> Unit,
    /**
     * Which collection the left pane is drawing, or null for none — which shows the agent picker,
     * because a region with no session in it is one whose next move is to start one.
     */
    sessionTab: ToolTab?,
    /** Whether the tool half is folded away, leaving the whole region to the session. */
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    paneRatio: Float,
    onPaneRatioChange: (Float) -> Unit,
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
    runs: RunSessions,
    onSelectRun: (String) -> Unit,
    onCloseRun: (String) -> Unit,
    histories: HistorySessions,
    onSelectHistory: (String) -> Unit,
    onCloseHistory: (String) -> Unit,
    terminal: @Composable () -> Unit,
    agent: @Composable () -> Unit,
    commit: @Composable () -> Unit,
    diff: @Composable () -> Unit,
    search: @Composable () -> Unit,
    usages: @Composable () -> Unit,
    stash: @Composable () -> Unit,
    preview: @Composable () -> Unit,
    run: @Composable () -> Unit,
    history: @Composable () -> Unit,
) {
    // Hoisted out of the two branches below so the composable identity survives a collapse: the
    // session pane holds a SwingPanel, and one that is torn down and rebuilt takes the terminal's
    // scroll position and focus with it every time the tools are folded away.
    val sessionPane: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            when (sessionTab) {
                ToolTab.Terminal -> terminal()
                ToolTab.Run -> run()
                // Agent, and nothing-selected, which is the picker — see [sessionTab].
                else -> agent()
            }
        }
    }
    val toolPane: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                ToolTab.Commit -> commit()
                ToolTab.Diff -> diff()
                ToolTab.Search -> search()
                ToolTab.Usages -> usages()
                ToolTab.Stash -> stash()
                ToolTab.Preview -> preview()
                ToolTab.History -> history()
                // A session tab can never be the tool selection; drawing nothing beats drawing a
                // second copy of the pane beside it.
                ToolTab.Terminal, ToolTab.Agent, ToolTab.Run -> Unit
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ToolStrip(
            selected = selected,
            onSelect = onSelect,
            collapsed = collapsed,
            onToggleCollapsed = onToggleCollapsed,
            histories = histories,
            onSelectHistory = onSelectHistory,
            onCloseHistory = onCloseHistory,
        )
        SessionStrip(
            sessionTab = sessionTab,
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
            runs = runs,
            onSelectRun = onSelectRun,
            onCloseRun = onCloseRun,
        )
        if (collapsed) {
            sessionPane()
        } else {
            HorizontalSplit(
                modifier = Modifier.fillMaxSize(),
                ratio = paneRatio,
                onRatioChange = onPaneRatioChange,
                minFirstDp = 200.dp,
                minSecondDp = 200.dp,
                first = sessionPane,
                second = toolPane,
            )
        }
    }
}

/**
 * The upper strip: the git logs, then the fixed tool tabs, then the collapse control.
 *
 * The chevron sits outside the scrolling half so it is reachable whatever the strip is scrolled to
 * — it is the one control in the strip that is about the *region* rather than about a tab, and one
 * that scrolls off the end is one the user cannot use to get the tools back.
 */
@Composable
private fun ToolStrip(
    selected: ToolTab,
    onSelect: (ToolTab) -> Unit,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    histories: HistorySessions,
    onSelectHistory: (String) -> Unit,
    onCloseHistory: (String) -> Unit,
) {
    val style = JewelTheme.defaultTabStyle
    val isDark = JewelTheme.isDark
    StripRow(
        style = style,
        isDark = isDark,
        trailing = {
            CollapseToolsButton(collapsed = collapsed, isDark = isDark, onClick = onToggleCollapsed)
        },
    ) {
        // The git logs lead, for the same reason the sessions lead their own strip: they are the
        // user's own collection, arriving one at a time and closeable, and the chrome that is
        // always there stays together at the tail.
        histories.sessions.forEach { log ->
            key(log.id) {
                // Brought into view on selection because the menu that opened it is at the other
                // end of the window, so the tab has to come to the user rather than wait behind a
                // scroll arrow to be found.
                val requester = remember { BringIntoViewRequester() }
                val isSelected = selected == ToolTab.History && log.id == histories.selectedId
                LaunchedEffect(isSelected) { if (isSelected) requester.bringIntoView() }
                ToolStripTab(
                    label = "$HISTORY_GLYPH ${log.title}",
                    selected = isSelected,
                    isDark = isDark,
                    style = style,
                    onClick = { onSelectHistory(log.id) },
                    onClose = { onCloseHistory(log.id) },
                    // No rename: the label is the path the log is *of*, and a log the user has
                    // called something else is one they can no longer tell apart from the next.
                    onRename = null,
                    modifier = Modifier.bringIntoViewRequester(requester),
                )
            }
        }
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
}

/**
 * The lower strip: the terminals and their "+", the agent sessions and theirs, then the launcher
 * runs — every tab whose panel is a live process, in the order they were added to nop.
 *
 * One rename field for the whole strip rather than one per collection: only one tab can be being
 * renamed at a time, and the three collections' ids cannot collide.
 */
@Composable
private fun SessionStrip(
    sessionTab: ToolTab?,
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
    runs: RunSessions,
    onSelectRun: (String) -> Unit,
    onCloseRun: (String) -> Unit,
) {
    val style = JewelTheme.defaultTabStyle
    val isDark = JewelTheme.isDark
    // The tab currently being renamed in place, if any.
    var renamingId by remember { mutableStateOf<String?>(null) }

    // Opening a terminal scrolls the "+" back into view, not the tab it just made. The tab sits
    // immediately left of the "+" so it comes along anyway, and stopping at the tab would leave the
    // button that made it off the end of the strip — with "and so on" then costing a press of the
    // right arrow first. Only a *new* terminal does this: closing one must not move the strip.
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

    StripRow(style = style, isDark = isDark) {
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
                        selected = sessionTab == ToolTab.Terminal && run.id == terminals.selectedId,
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
        // Running or not, there is a tab called "Agent", and it is always the first of them: the
        // strip should look the same whether or not a session happens to be open, the way the
        // terminals' does, and a shape that appears and disappears is one the eye has to re-find.
        // Clicking it shows the picker, which is what the pane holds with nothing selected.
        //
        // Drawn whatever else is in the strip, and not — as it was — only when nothing is running.
        // The picker is the one place a *past* session can be resumed from, and the "+" beside
        // these tabs starts a new session rather than opening it, so hiding this tab the moment a
        // session existed left the user with no way back to the list at all.
        if (agents.pickerTabVisible) {
            ToolStripTab(
                label = "$AGENT_GLYPH ${AgentSession.DEFAULT_TITLE}",
                // Only when the pane is actually holding the picker. Without the second half this
                // tab and the selected session's would both draw as the current one.
                selected = sessionTab == ToolTab.Agent && agents.selectedId == null,
                isDark = isDark,
                style = style,
                onClick = onShowPicker,
                // Closes like a terminal's does, and reserves the same room whether or not the × is
                // showing. What it closes is the tab and nothing else — no session hangs off it —
                // which is the same thing closing the last terminal does to the strip. The "+" puts
                // it back, and so does starting a session.
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
                        selected = sessionTab == ToolTab.Agent &&
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
        // The launcher runs close the strip. They arrive from the ▶ menu rather than from a "+"
        // here, so there is no button after them.
        runs.sessions.forEach { run ->
            key(run.id) {
                if (run.id == renamingId) {
                    TabRenameField(
                        initial = run.title,
                        style = style,
                        onCommit = { name ->
                            runs.rename(run.id, name)
                            renamingId = null
                        },
                        onCancel = { renamingId = null },
                    )
                } else {
                    // Starting a script selects its tab, and the ▶ menu that started it is at the
                    // other end of the window — so the tab has to come to the user rather than wait
                    // behind the scroll arrow for them to go and find it.
                    val requester = remember { BringIntoViewRequester() }
                    val isSelected = sessionTab == ToolTab.Run && run.id == runs.selectedId
                    // Not [RevealWhenSelected], which sits out the first pass so a project doesn't
                    // open scrolled along to whatever was selected when it was laid out. A run tab's
                    // first pass *is* the moment the user started it, and a restored one is never
                    // the selected tab, so there is nothing to sit out.
                    LaunchedEffect(isSelected) { if (isSelected) requester.bringIntoView() }
                    ToolStripTab(
                        label = "$RUN_GLYPH ${run.title}",
                        selected = isSelected,
                        isDark = isDark,
                        style = style,
                        onClick = { onSelectRun(run.id) },
                        onClose = { onCloseRun(run.id) },
                        onRename = { renamingId = run.id },
                        modifier = Modifier.bringIntoViewRequester(requester),
                    )
                }
            }
        }
    }
}

/**
 * One row of tabs: the scrolling run of them, an arrow at each end once they overflow, and an
 * optional control pinned past the right-hand arrow.
 *
 * Drawn here rather than with Jewel's `TabStrip` for the same reason the editor bar is (see
 * [TabStripBar]): these strips are not flat lists of tabs. They have buttons wedged between their
 * halves, and they need the scroll position that `TabStrip` keeps to itself — a tool region is only
 * a few hundred pixels wide, so a couple of terminals is all it takes to push the rest off the end,
 * and arrows the user can press are the way back. The tab itself is still drawn from the theme's
 * tab style, so it looks like the Jewel strip it replaces.
 */
@Composable
private fun StripRow(
    style: TabStyle,
    isDark: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // maxValue is Int.MAX_VALUE until the row has been measured once; treating that as overflow
    // would flash both arrows on the first frame every time a project opens.
    val overflowing = scroll.maxValue in 1 until Int.MAX_VALUE
    val step = (scroll.viewportSize * 3 / 4).coerceAtLeast(MIN_SCROLL_STEP)

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
            content()
        }
        if (overflowing) {
            ScrollArrow(pointsLeft = false, enabled = scroll.canScrollForward, isDark = isDark) {
                scope.launch { scroll.animateScrollTo(scroll.value + step) }
            }
        }
        trailing?.invoke()
    }
}

/** Folds the tool half away, and brings it back. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CollapseToolsButton(collapsed: Boolean, isDark: Boolean, onClick: () -> Unit) {
    val tint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    Tooltip(tooltip = { Text(if (collapsed) "Show the tool panel" else "Hide the tool panel") }) {
        Box(
            modifier = Modifier.size(22.dp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (collapsed) "◀" else "▶", color = tint)
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
