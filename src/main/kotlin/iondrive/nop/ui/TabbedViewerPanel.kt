package iondrive.nop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.git.GitRepo
import iondrive.nop.index.JumpResolver
import iondrive.nop.index.JumpTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.awt.CardLayout
import java.io.File
import javax.swing.JPanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.component.styling.TabIcons
import org.jetbrains.jewel.ui.component.styling.TabStyle
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.theme.editorTabStyle

// Jewel's TabStrip pulls its close glyph from the IntelliJ Platform icons jar
// (which we don't depend on), so editor-tab close buttons render as magenta
// missing-icon placeholders. Bundle a local SVG and override the style.
private object TabIconsClass
private val TabCloseIconKey = PathIconKey("icons/close-small.svg", TabIconsClass::class.java)

/** How long to wait for the typing to settle before writing the buffer to disk. */
private const val AUTOSAVE_DEBOUNCE_MS = 400L

@OptIn(ExperimentalJewelApi::class)
@Composable
fun TabbedViewerPanel(
    tabsState: TabsState,
    repo: GitRepo?,
    editStore: FileEditStore,
    onFileSaved: () -> Unit = {},
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget? = { _, _, _ -> null },
    onJump: (File, Int) -> Unit = { _, _ -> },
    onDiffTopLine: (Int) -> Unit = {},
    findInFileTrigger: Int = 0,
) {
    val selected = tabsState.selectedTab

    if (tabsState.tabs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text("Click a file in the tree, or a change in the commit panel, to view it here")
        }
        return
    }

    // Closing a tab also flushes its edit buffer and stops any launcher process behind it. Shared
    // by the close button (onClose) and the "Close Other Tabs" context-menu action so both paths
    // tear a tab down the same way.
    fun cleanUp(tab: Tab) {
        editStore.close(tab.id)
        if (tab is Tab.Terminal) tab.session.dispose()
    }

    val tabData = tabsState.tabs.map { tab ->
        val label = labelFor(tab, editStore)
        TabData.Editor(
            selected = tab.id == tabsState.selectedId,
            content = { state ->
                ContextMenuArea(
                    items = {
                        if (tabsState.tabs.size > 1) {
                            listOf(ContextMenuItem("Close Other Tabs") {
                                tabsState.closeOthers(tab.id).forEach(::cleanUp)
                            })
                        } else {
                            emptyList()
                        }
                    },
                ) {
                    SimpleTabContent(label = label, state = state)
                }
            },
            onClick = { tabsState.select(tab.id) },
            onClose = {
                cleanUp(tab)
                tabsState.close(tab.id)
            },
        )
    }

    val baseTabStyle = JewelTheme.editorTabStyle
    val tabStyle = remember(baseTabStyle) {
        TabStyle(
            colors = baseTabStyle.colors,
            metrics = baseTabStyle.metrics,
            icons = TabIcons(close = TabCloseIconKey),
            contentAlpha = baseTabStyle.contentAlpha,
            scrollbarStyle = baseTabStyle.scrollbarStyle,
        )
    }

    // One shared Swing CardLayout panel hosts every terminal widget (see TerminalView for why a
    // SwingPanel-per-tab can't work). Remembered here so it — and the live PTYs inside it —
    // outlive switches to non-terminal tabs.
    val terminalCards = remember { JPanel(CardLayout()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabData, style = tabStyle)
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = selected) {
                is Tab.FileView -> {
                    val pendingLine = tabsState.pendingJumpLine(current.id)
                    if (current.file.extension.equals("md", ignoreCase = true)) {
                        MarkdownEditWithPreview(current, editStore, onFileSaved, findInFileTrigger)
                    } else {
                        FileEditView(
                            tab = current,
                            store = editStore,
                            onSaved = onFileSaved,
                            onResolveAt = { text, offset -> onResolveAt(current.file, text, offset) },
                            onJump = onJump,
                            pendingLine = pendingLine,
                            onPendingLineConsumed = { tabsState.clearJumpLine(current.id) },
                            findInFileTrigger = findInFileTrigger,
                        )
                    }
                }
                is Tab.Diff -> if (repo != null) DiffView(
                    repo = repo,
                    tab = current,
                    editStore = editStore,
                    onFileSaved = onFileSaved,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                    onTopLine = onDiffTopLine,
                )
                is Tab.History -> if (repo != null) HistoryView(repo, current, tabsState)
                is Tab.CommitDiff -> if (repo != null) CommitDiffView(repo, current)
                is Tab.Terminal -> TerminalView(current, terminalCards)
                null -> {}
            }
        }
    }
}

@Composable
private fun labelFor(tab: Tab, editStore: FileEditStore): String = when (tab) {
    is Tab.FileView -> {
        val edit = editStore.peek(tab.id)
        val base = tab.file.name
        if (edit != null && edit.isModified) "*$base" else base
    }
    is Tab.Diff -> tab.title
    is Tab.CommitDiff -> tab.title
    is Tab.History -> tab.title
    is Tab.Terminal -> tab.title
}

@OptIn(FlowPreview::class)
@Composable
private fun FileEditView(
    tab: Tab.FileView,
    store: FileEditStore,
    onSaved: () -> Unit,
    onResolveAt: (text: String, offset: Int) -> JumpTarget? = { _, _ -> null },
    onJump: (File, Int) -> Unit = { _, _ -> },
    pendingLine: Int? = null,
    onPendingLineConsumed: () -> Unit = {},
    findInFileTrigger: Int = 0,
    modifier: Modifier = Modifier,
) {
    val edit = remember(tab.id) { store.edit(tab) }
    val focusRequester = remember(tab.id) { FocusRequester() }
    val scrollState = rememberScrollState()
    val savedCallback by rememberUpdatedState(onSaved)
    val resolveCallback by rememberUpdatedState(onResolveAt)
    val jumpCallback by rememberUpdatedState(onJump)
    val pendingConsumedCallback by rememberUpdatedState(onPendingLineConsumed)

    // In-file search state. Per-tab so two open tabs each remember their own query and the bar
    // stays open in whichever tab the user opened it. Matches are recomputed whenever the text
    // or query changes; currentMatch is the active hit that's highlighted and scrolled into view.
    var searchOpen by remember(tab.id) { mutableStateOf(false) }
    val searchState = rememberTextFieldState()
    var currentMatch by remember(tab.id) { mutableStateOf(0) }
    val searchFocusRequester = remember(tab.id) { FocusRequester() }
    // Snapshot the trigger as of this tab's first composition. We only open the bar on a
    // strictly later value — otherwise switching to a tab inherits whatever Ctrl+F count the
    // sibling tab racked up and the bar pops open unexpectedly.
    val triggerBaseline = remember(tab.id) { findInFileTrigger }
    LaunchedEffect(findInFileTrigger) {
        if (findInFileTrigger > triggerBaseline) {
            searchOpen = true
            // Wait for a frame so the FindBar's BasicTextField is composed + laid out and its
            // FocusRequester modifier is attached. requestFocus() throws if called before the
            // requester is part of the focus tree; without the wait, keypresses keep hitting
            // whatever had focus before Ctrl+F (notably the project tree's "H" shortcut).
            withFrameNanos { }
            runCatching { searchFocusRequester.requestFocus() }
            // Select the existing query so re-pressing Ctrl+F lets the user immediately type a
            // new one without manually clearing the field first.
            val len = searchState.text.length
            if (len > 0) searchState.edit { selection = TextRange(0, len) }
        }
    }

    // Compute match ranges off the (file text, query) pair. Case-insensitive substring; empty
    // query — or the bar being closed — collapses to no matches so the highlight goes away.
    val matches by remember(tab.id) {
        derivedStateOf {
            val q = searchState.text.toString()
            if (!searchOpen || q.isEmpty()) emptyList()
            else findAllMatches(edit.state.text.toString(), q)
        }
    }
    // Keep currentMatch in range as matches change (typing narrows the result set).
    LaunchedEffect(matches.size) {
        if (currentMatch >= matches.size) currentMatch = 0
    }

    // We intentionally do NOT requestFocus() on tab activation. Keeping focus on the tree means
    // tree-bound shortcuts (Delete to remove the file, H to view history) keep working after the
    // user clicks a file. Click into the editor body to start typing.

    // Autosave: debounce edits, write to disk on a background thread, then notify the rest of
    // the app (commit panel) that on-disk state may have changed.
    LaunchedEffect(edit) {
        snapshotFlow { edit.state.text.toString() }
            .drop(1) // skip the initial value already in sync with disk
            .debounce(AUTOSAVE_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { text ->
                if (text != edit.savedText) {
                    // save() is a compare-and-swap: if the file changed under us (git checkout/pull,
                    // an agent), it refuses to overwrite and the background poll reconciles instead.
                    // Only refresh git status when we actually wrote.
                    if (withContext(Dispatchers.IO) { edit.save() } is SaveResult.Saved) savedCallback()
                }
            }
    }

    val isDark = JewelTheme.isDark
    val fg = if (isDark) androidx.compose.ui.graphics.Color(0xFFA9B7C6) else androidx.compose.ui.graphics.Color(0xFF1F2329)
    val palette = if (isDark) HighlightPalette.Dark else HighlightPalette.Light
    val tokenize = remember(tab.id) { tokenizerForExtension(tab.file.extension) }
    // Tokenize once per text change, not on every OutputTransformation invocation. The
    // transformation below is re-applied on each recomposition (every keystroke, every
    // Ctrl-hover that moves the underline), and re-running the regex sweep + per-call
    // BooleanArray(text.length) there was the editor's main typing-latency cost. Caching the
    // token list in a derivedStateOf keyed on the text means the heavy pass runs once per edit
    // and the transformation body is just a cheap span-application.
    val tokens by remember(tab.id, tokenize) {
        derivedStateOf { tokenize?.invoke(edit.state.text.toString()) ?: emptyList() }
    }

    // Range of the word currently under the mouse pointer while Ctrl is held *and* the symbol
    // index resolves the word to a jump target. Drawn as an underline so the user knows the
    // click will hand them off to another file. Inclusive on both ends (matches JumpResolver).
    var hoverUnderline by remember(tab.id) { mutableStateOf<IntRange?>(null) }
    val matchHighlight = if (isDark) Color(0xFF5A4A20) else Color(0xFFFFF38C)
    val activeMatchHighlight = if (isDark) Color(0xFF8A6D1A) else Color(0xFFFFB74D)
    val transformation = remember(tokens, palette, hoverUnderline, matches, currentMatch, matchHighlight, activeMatchHighlight) {
        OutputTransformation {
            applyTokens(this, tokens, palette)
            val range = hoverUnderline
            if (range != null && range.first in 0 until length && range.last in 0 until length) {
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), range.first, range.last + 1)
            }
            matches.forEachIndexed { idx, m ->
                if (m.first in 0..length && m.last + 1 in 0..length) {
                    val bg = if (idx == currentMatch) activeMatchHighlight else matchHighlight
                    addStyle(SpanStyle(background = bg), m.first, m.last + 1)
                }
            }
        }
    }

    // We keep the text layout around so Ctrl-click can map mouse coordinates to text offsets,
    // and so an inbound jump request can scroll a target line to the top of the viewport.
    var layout by remember(tab.id) { mutableStateOf<TextLayoutResult?>(null) }

    // Moving the caret/viewport to a match is driven *only* by explicit search actions — a query
    // change (below) and Next/Prev — never by a reactive effect keyed on the document or its layout.
    // That's the whole point: an effect that re-asserted the caret whenever the text or layout
    // changed would yank the cursor back onto a match every time the user typed somewhere else.
    // Keeping match-navigation imperative makes that entire class of cursor-stealing bug impossible
    // by construction. The highlights still track the text reactively via the transformation above.
    val searchScope = rememberCoroutineScope()
    suspend fun jumpToMatch(index: Int) {
        val m = matches.getOrNull(index) ?: return
        // The find field holds focus while searching, so the editor is unfocused and its built-in
        // scroll-to-cursor won't fire — set the selection (for when focus returns) and scroll the
        // viewport ourselves, the way an inbound jump does. Only scroll when the match is off-screen
        // so stepping between two on-screen matches doesn't jolt the page.
        edit.state.edit { selection = TextRange(m.first) }
        val tl = layout ?: return
        val line = tl.getLineForOffset(m.first.coerceIn(0, tl.layoutInput.text.length))
        val lineTop = tl.getLineTop(line)
        val lineBottom = tl.getLineBottom(line)
        val viewTop = scrollState.value
        val viewport = scrollState.viewportSize
        if (viewport > 0 && (lineTop < viewTop || lineBottom > viewTop + viewport)) {
            val lineHeight = (lineBottom - lineTop).coerceAtLeast(1f)
            val target = (lineTop - lineHeight * 3).toInt().coerceIn(0, scrollState.maxValue)
            scrollState.scrollTo(target)
        }
    }

    // Re-aim at the first match when the *query* (or the bar's open state) changes — keyed on the
    // query string alone, so editing the document never retriggers it.
    LaunchedEffect(searchOpen, searchState.text.toString()) {
        if (!searchOpen) return@LaunchedEffect
        currentMatch = 0
        jumpToMatch(0)
    }

    // Inbound jump: once the layout for this tab exists, scroll the requested line to ~3 lines
    // below the top so the user can see context around the landing site. Also drop the cursor
    // at the line start so the visual anchor is obvious.
    LaunchedEffect(tab.id, layout, pendingLine) {
        val tl = layout ?: return@LaunchedEffect
        val line = pendingLine ?: return@LaunchedEffect
        val safe = (line - 1).coerceIn(0, maxOf(0, tl.lineCount - 1))
        val lineHeight = (tl.getLineBottom(0) - tl.getLineTop(0)).coerceAtLeast(1f)
        val targetTop = (tl.getLineTop(safe) - lineHeight * 3).toInt().coerceAtLeast(0)
        scrollState.scrollTo(targetTop)
        val lineStart = tl.getLineStart(safe)
        edit.state.edit { selection = TextRange(lineStart) }
        pendingConsumedCallback()
    }

    Column(modifier = modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        if (searchOpen) {
            FindBar(
                state = searchState,
                focusRequester = searchFocusRequester,
                matchCount = matches.size,
                currentIndex = currentMatch,
                onNext = {
                    if (matches.isNotEmpty()) {
                        val next = (currentMatch + 1) % matches.size
                        currentMatch = next
                        searchScope.launch { jumpToMatch(next) }
                    }
                },
                onPrev = {
                    if (matches.isNotEmpty()) {
                        val prev = (currentMatch - 1 + matches.size) % matches.size
                        currentMatch = prev
                        searchScope.launch { jumpToMatch(prev) }
                    }
                },
                onClose = { searchOpen = false },
            )
        }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        BasicTextField(
            state = edit.state,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp)
                .focusRequester(focusRequester)
                // Ctrl-click → jump-to-source, and Ctrl-hover → underline the jumpable word so
                // the user can see the click target before commiting. Both run on the Initial
                // pass; only Press consumes its change so cursor placement on bare clicks keeps
                // working. Moves never consume, otherwise the field's own selection-by-drag
                // would break.
                .pointerInput(tab.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val ctrl = event.keyboardModifiers.isCtrlPressed
                            val change = event.changes.firstOrNull()
                            val tl = layout

                            // Maintain the hover underline on every event: clear it whenever
                            // Ctrl is released, the pointer leaves the field, or the resolved
                            // target disappears (cursor moved off the word).
                            if (event.type == PointerEventType.Exit || !ctrl || change == null || tl == null) {
                                hoverUnderline = null
                            } else {
                                val text = edit.state.text.toString()
                                val offset = tl.getOffsetForPosition(change.position)
                                hoverUnderline = if (resolveCallback(text, offset) != null) {
                                    JumpResolver.wordRangeAt(text, offset)
                                } else {
                                    null
                                }
                            }

                            if (event.type == PointerEventType.Press && ctrl && change != null && tl != null) {
                                val offset = tl.getOffsetForPosition(change.position)
                                val target = resolveCallback(edit.state.text.toString(), offset)
                                if (target != null) {
                                    change.consume()
                                    jumpCallback(target.file, target.line)
                                }
                            }
                        }
                    }
                },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = fg,
            ),
            cursorBrush = SolidColor(fg),
            lineLimits = TextFieldLineLimits.MultiLine(),
            scrollState = scrollState,
            outputTransformation = transformation,
            onTextLayout = { getResult ->
                val r = getResult()
                if (r != null) layout = r
            },
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            style = NopScrollbarStyle,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.CenterEnd)
                .width(10.dp)
                .fillMaxHeight(),
        )
    }
    }
}

/** Markdown tab layout: editor on the left, live-rendered preview on the right. */
@Composable
private fun MarkdownEditWithPreview(
    tab: Tab.FileView,
    store: FileEditStore,
    onSaved: () -> Unit,
    findInFileTrigger: Int = 0,
) {
    val edit = remember(tab.id) { store.edit(tab) }
    val previewText by remember(edit) {
        derivedStateOf { edit.state.text.toString() }
    }
    HorizontalSplitLayout(
        first = { FileEditView(tab, store, onSaved, findInFileTrigger = findInFileTrigger) },
        second = {
            MarkdownPreview(
                text = previewText,
                modifier = Modifier.fillMaxSize(),
            )
        },
        state = rememberSplitLayoutState(0.5f),
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Finds all non-overlapping occurrences of [query] in [text], case-insensitive. Returns each
 * match as a closed-open IntRange (start inclusive, end inclusive of the last matched char).
 * Falls back to empty for empty queries — callers should also skip when empty.
 */
internal fun findAllMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var i = 0
    while (true) {
        val hit = text.indexOf(query, startIndex = i, ignoreCase = true)
        if (hit < 0) break
        out += hit..(hit + query.length - 1)
        i = hit + query.length
        // Bail at a high cap so a degenerate single-char query in a megabyte file can't run away.
        if (out.size >= 5000) break
    }
    return out
}

/**
 * Slim search bar pinned above the file content. Enter / Shift+Enter cycle through matches;
 * Esc closes the bar and clears the highlight. The count chip reads "n of m" so the user can
 * see both their position and how many total matches exist for the current query.
 */
@Composable
private fun FindBar(
    state: TextFieldState,
    focusRequester: FocusRequester,
    matchCount: Int,
    currentIndex: Int,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    val isDark = JewelTheme.isDark
    val barBg = if (isDark) Color(0xFF2B2D30) else Color(0xFFEDEEF2)
    val fg = if (isDark) Color(0xFFA9B7C6) else Color(0xFF1F2329)
    val mutedFg = if (isDark) Color(0xFF7A8290) else Color(0xFF6B7280)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.Enter, Key.NumPadEnter -> {
                        if (event.isShiftPressed) onPrev() else onNext()
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = fg),
            cursorBrush = SolidColor(fg),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        val countText = when {
            state.text.isEmpty() -> ""
            matchCount == 0 -> "no matches"
            else -> "${currentIndex + 1} of $matchCount"
        }
        if (countText.isNotEmpty()) {
            Text(countText, color = mutedFg, style = TextStyle(fontSize = 12.sp))
        }
        Text(
            "▲",
            color = if (matchCount > 0) fg else mutedFg,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) onPrev()
                        }
                    }
                },
        )
        Text(
            "▼",
            color = if (matchCount > 0) fg else mutedFg,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) onNext()
                        }
                    }
                },
        )
        Text(
            "×",
            color = mutedFg,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) onClose()
                        }
                    }
                },
        )
    }
}
