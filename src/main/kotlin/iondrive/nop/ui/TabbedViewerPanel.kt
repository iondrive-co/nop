package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.history.LocalHistory
import iondrive.nop.index.JumpResolver
import iondrive.nop.index.JumpTarget
import iondrive.nop.spell.Typo
import iondrive.nop.spell.findTypos
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
import org.jetbrains.jewel.ui.component.ContextMenuDivider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.theme.editorTabStyle

/** How long to wait for the typing to settle before writing the buffer to disk. */
private const val AUTOSAVE_DEBOUNCE_MS = 400L

/**
 * How long the text must sit still before it is spellchecked — longer than the autosave debounce,
 * because underlining a word the user is still halfway through typing is the one thing every
 * spellchecker gets complained about. The first check of a file (and the one after a word is added
 * to the dictionary) skips the wait; see the effect that uses this.
 */
private const val SPELLCHECK_DEBOUNCE_MS = 600L

/** Characters measured in one go to derive the editor's monospace advance. */
private const val ADVANCE_SAMPLE = 100

/**
 * Slack kept beyond the last character of the longest line, and the gap the caret is kept clear of
 * either edge by while scrolling sideways — so the character being typed is never flush against the
 * viewport's edge.
 */
private val CARET_MARGIN = 24.dp

@OptIn(ExperimentalJewelApi::class)
@Composable
fun TabbedViewerPanel(
    tabsState: TabsState,
    repo: GitRepo?,
    editStore: FileEditStore,
    localHistory: LocalHistory,
    onFileSaved: () -> Unit = {},
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget? = { _, _, _ -> null },
    onJump: (File, Int) -> Unit = { _, _ -> },
    onDiffTopLine: (Int) -> Unit = {},
    findInFileTrigger: Int = 0,
    replaceInFileTrigger: Int = 0,
    saveTrigger: Int = 0,
    blameEnabled: Boolean = false,
    wrapLines: Boolean = false,
    onToggleWrap: () -> Unit = {},
    spellcheck: Boolean = true,
    onToggleSpellcheck: () -> Unit = {},
    diffSplitRatio: Float = 0.5f,
    onDiffSplitRatioChange: (Float) -> Unit = {},
) {
    val selected = tabsState.selectedTab

    // Closing a tab also flushes its edit buffer and stops any launcher process behind it. Shared
    // by the close button (onClose) and the "Close Other Tabs" context-menu action so both paths
    // tear a tab down the same way.
    fun cleanUp(tab: Tab) {
        editStore.close(tab.id)
        if (tab is Tab.Terminal) tab.session.dispose()
    }

    // One shared Swing CardLayout panel hosts every terminal widget (see TerminalView for why a
    // SwingPanel-per-tab can't work). Remembered here so it — and the live PTYs inside it —
    // outlive switches to non-terminal tabs.
    val terminalCards = remember { JPanel(CardLayout()) }

    // One flag for every view under the strip, so the toggle in the strip means the same thing to a
    // file tab and to either diff — see [LocalWrapLines].
    CompositionLocalProvider(LocalWrapLines provides wrapLines) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Drawn even with nothing open: the strip is where the tab groups live, so a project whose
        // tabs were all closed (or were terminals and diffs, which don't survive a restart) still
        // shows its groups and the "+" that adds one.
        TabStripBar(
            state = tabsState,
            style = JewelTheme.editorTabStyle,
            labelFor = { labelFor(it, editStore) },
            onTabsClosed = { closed -> closed.forEach(::cleanUp) },
            wrapLines = wrapLines,
            onToggleWrap = onToggleWrap,
            spellcheck = spellcheck,
            onToggleSpellcheck = onToggleSpellcheck,
        )
        // One answer per tab to "is this spellcheckable, and as what?", inherited by every text
        // surface underneath — the editor, and both halves of whichever diff is open.
        CompositionLocalProvider(
            LocalSpellcheckExtension provides if (spellcheck) spellcheckExtensionOf(selected) else null,
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = selected) {
                is Tab.FileView -> {
                    val pendingLine = tabsState.pendingJumpLine(current.id)
                    val pendingSearch = tabsState.pendingSearchQuery(current.id)
                    if (current.file.extension.equals("md", ignoreCase = true)) {
                        MarkdownEditWithPreview(
                            tab = current,
                            store = editStore,
                            onSaved = onFileSaved,
                            pendingLine = pendingLine,
                            onPendingLineConsumed = { tabsState.clearJumpLine(current.id) },
                            pendingSearchQuery = pendingSearch,
                            onPendingSearchConsumed = { tabsState.clearSearchQuery(current.id) },
                            findInFileTrigger = findInFileTrigger,
                            replaceInFileTrigger = replaceInFileTrigger,
                            saveTrigger = saveTrigger,
                            onShowLocalHistory = { tabsState.open(Tab.LocalHistory(current.file)) },
                        )
                    } else {
                        FileEditView(
                            tab = current,
                            store = editStore,
                            onSaved = onFileSaved,
                            onResolveAt = { text, offset -> onResolveAt(current.file, text, offset) },
                            onJump = onJump,
                            pendingLine = pendingLine,
                            onPendingLineConsumed = { tabsState.clearJumpLine(current.id) },
                            pendingSearchQuery = pendingSearch,
                            onPendingSearchConsumed = { tabsState.clearSearchQuery(current.id) },
                            findInFileTrigger = findInFileTrigger,
                            replaceInFileTrigger = replaceInFileTrigger,
                            saveTrigger = saveTrigger,
                            repo = repo,
                            blameEnabled = blameEnabled,
                            onShowLocalHistory = { tabsState.open(Tab.LocalHistory(current.file)) },
                            // A blame line resolves to the commit that last touched it; open that
                            // commit's diff for this file so the user can read the change in full.
                            onOpenBlameCommit = { sha ->
                                val rel = repo?.let { repoRelativePath(it, current.file) }
                                if (repo != null && rel != null) {
                                    tabsState.open(
                                        Tab.CommitDiff(
                                            sha = sha,
                                            shortSha = sha.take(7),
                                            file = CommitFile(rel, CommitFileChange.MODIFIED),
                                            repoRoot = repo.rootDir.toFile(),
                                        ),
                                    )
                                }
                            },
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
                    splitRatio = diffSplitRatio,
                    onSplitRatioChange = onDiffSplitRatioChange,
                    reloadKey = tabsState.reloadKey(current.id),
                    findTrigger = findInFileTrigger,
                    saveTrigger = saveTrigger,
                )
                is Tab.History -> if (repo != null) HistoryView(repo, current, tabsState)
                is Tab.LocalHistory -> LocalHistoryView(
                    history = localHistory,
                    tab = current,
                    tabsState = tabsState,
                    reloadKey = tabsState.reloadKey(current.id),
                )
                is Tab.LocalDiff -> LocalDiffView(
                    history = localHistory,
                    tab = current,
                    splitRatio = diffSplitRatio,
                    onSplitRatioChange = onDiffSplitRatioChange,
                    onTopLine = onDiffTopLine,
                    reloadKey = tabsState.reloadKey(current.id),
                    findTrigger = findInFileTrigger,
                )
                is Tab.CommitDiff -> if (repo != null) CommitDiffView(
                    repo = repo,
                    tab = current,
                    splitRatio = diffSplitRatio,
                    onSplitRatioChange = onDiffSplitRatioChange,
                    onTopLine = onDiffTopLine,
                    reloadKey = tabsState.reloadKey(current.id),
                    findTrigger = findInFileTrigger,
                )
                is Tab.Terminal -> TerminalView(current, terminalCards)
                null -> Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text("Click a file in the tree, or a change in the commit panel, to view it here")
                }
            }
        }
        }
    }
    }
}

/**
 * The file extension the spellchecker should treat [tab]'s content as, or null for a tab that shows
 * no file. Every diff resolves to the file it is a diff *of*: a commit's version of `notes.md` is
 * still markdown, so it gets checked as prose like the working copy beside it.
 */
private fun spellcheckExtensionOf(tab: Tab?): String? = when (tab) {
    is Tab.FileView -> tab.file.extension
    is Tab.Diff -> File(tab.change.path).extension
    is Tab.CommitDiff -> File(tab.file.path).extension
    is Tab.LocalDiff -> tab.file.extension
    is Tab.History, is Tab.LocalHistory, is Tab.Terminal, null -> null
}

/**
 * Tab caption. `*` is the ordinary unsaved-changes marker; `!` replaces it when the buffer can't
 * reach disk at all, so a file that has silently stopped saving is distinguishable at a glance
 * from one that simply hasn't hit its autosave debounce yet — the banner over the editor says
 * which of the two it is. Diff tabs carry the same marker: their working side is the same buffer.
 */
@Composable
private fun labelFor(tab: Tab, editStore: FileEditStore): String = when (tab) {
    is Tab.FileView -> saveMarker(editStore.peek(tab.id)) + tab.file.name
    is Tab.Diff -> saveMarker(editStore.peek(Tab.FileView(File(tab.repoRoot, tab.change.path)).id)) + tab.title
    is Tab.CommitDiff -> tab.title
    is Tab.History -> tab.title
    // A local-history revision is a snapshot of a file that may well be open and dirty beside it,
    // but the revision itself is fixed — no save marker, same as a commit diff.
    is Tab.LocalHistory, is Tab.LocalDiff -> tab.title
    is Tab.Terminal -> tab.title
}

private fun saveMarker(edit: FileEdit?): String = when {
    edit == null -> ""
    edit.saveBlock != null -> "!"
    edit.isModified -> "*"
    else -> ""
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
    pendingSearchQuery: String? = null,
    onPendingSearchConsumed: () -> Unit = {},
    findInFileTrigger: Int = 0,
    replaceInFileTrigger: Int = 0,
    saveTrigger: Int = 0,
    repo: GitRepo? = null,
    blameEnabled: Boolean = false,
    onOpenBlameCommit: (sha: String) -> Unit = {},
    onShowLocalHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val edit = remember(tab.id) { store.edit(tab) }
    val focusRequester = remember(tab.id) { FocusRequester() }
    // Viewport and find/replace text come from the per-file FileEdit rather than a remember here, so
    // each tab keeps its own across switches instead of inheriting the last file's — see FileEdit.
    val scrollState = edit.scroll
    val savedCallback by rememberUpdatedState(onSaved)
    val resolveCallback by rememberUpdatedState(onResolveAt)
    val jumpCallback by rememberUpdatedState(onJump)
    val pendingConsumedCallback by rememberUpdatedState(onPendingLineConsumed)
    val pendingSearchConsumedCallback by rememberUpdatedState(onPendingSearchConsumed)

    // In-file search state. Per-tab so two open tabs each remember their own query and the bar
    // stays open in whichever tab the user opened it. Matches are recomputed whenever the text
    // or query changes; currentMatch is the active hit that's highlighted and scrolled into view.
    var searchOpen by remember(tab.id) { mutableStateOf(false) }
    val searchState = edit.findQuery
    var currentMatch by remember(tab.id) { mutableStateOf(0) }
    val searchFocusRequester = remember(tab.id) { FocusRequester() }
    // Replace half of the bar (Ctrl+R). replaceOpen is separate from searchOpen because Ctrl+F opens
    // a find-only bar and Ctrl+R widens it; the text itself is per-file like the query.
    var replaceOpen by remember(tab.id) { mutableStateOf(false) }
    val replaceState = edit.replaceWith
    val replaceFocusRequester = remember(tab.id) { FocusRequester() }
    // Bumped by genuine user find activity — opening the bar with Ctrl+F, and typing in the field
    // (via the FindBar's InputTransformation). The "re-aim at the first match" effect keys off this
    // instead of the query text, so a *programmatic* seed (a global-search pick, which sets the
    // query via state.edit and never trips the InputTransformation) can aim at its own match
    // without the reset stealing it back to the first hit.
    var findRevision by remember(tab.id) { mutableStateOf(0) }
    // Snapshot the trigger as of this tab's first composition. We only open the bar on a
    // strictly later value — otherwise switching to a tab inherits whatever Ctrl+F count the
    // sibling tab racked up and the bar pops open unexpectedly.
    val triggerBaseline = remember(tab.id) { findInFileTrigger }
    LaunchedEffect(findInFileTrigger) {
        if (findInFileTrigger > triggerBaseline) {
            val wasOpen = searchOpen
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
            // Aim at the first match only when the bar is opening fresh — re-pressing Ctrl+F on an
            // already-open bar just re-selects the query and keeps the user's current position.
            if (!wasOpen) findRevision++
        }
    }

    // Ctrl+R is Ctrl+F plus the replacement half: the bar opens (or widens, if find was already up)
    // and focus lands wherever there's still something to type. With a query already in hand —
    // Ctrl+F, type, then Ctrl+R — that's the replacement field; from cold it's the query field,
    // because there's nothing to replace yet. Baselined for the same reason as the find trigger.
    val replaceTriggerBaseline = remember(tab.id) { replaceInFileTrigger }
    LaunchedEffect(replaceInFileTrigger) {
        if (replaceInFileTrigger > replaceTriggerBaseline) {
            val wasOpen = searchOpen
            searchOpen = true
            replaceOpen = true
            withFrameNanos { }
            val hasQuery = searchState.text.isNotEmpty()
            val field = if (hasQuery) replaceFocusRequester else searchFocusRequester
            runCatching { field.requestFocus() }
            // Select what's already there so the next keystroke replaces it, as Ctrl+F does.
            val target = if (hasQuery) replaceState else searchState
            val len = target.text.length
            if (len > 0) target.edit { selection = TextRange(0, len) }
            if (!wasOpen) findRevision++
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
                // Only persist buffer changes the user actually made (hasUserEdit). A buffer mutated
                // by nop itself — adopting an externally-changed file — must never be written back,
                // or we'd revert a checkout/pull/merge the user did outside nop. save() is also a
                // compare-and-swap as a second line of defence. Only refresh git status on a real write.
                if (edit.hasUserEdit && text != edit.savedText) {
                    if (withContext(Dispatchers.IO) { edit.save() } is SaveResult.Saved) savedCallback()
                }
            }
    }

    // Ctrl+S: write now instead of waiting out the debounce. Baselined like the find triggers so
    // switching to this tab doesn't inherit a sibling's count and fire a save on arrival. Runs
    // save() even when the buffer looks clean — that's the cheap way to re-test a file that's been
    // stuck on an external change, since save() clears the block itself once disk agrees again.
    val saveTriggerBaseline = remember(tab.id) { saveTrigger }
    LaunchedEffect(saveTrigger) {
        if (saveTrigger > saveTriggerBaseline) {
            if (withContext(Dispatchers.IO) { edit.save() } is SaveResult.Saved) savedCallback()
        }
    }

    val isDark = JewelTheme.isDark
    val fg =if (isDark) androidx.compose.ui.graphics.Color(0xFFBCBEC4) else androidx.compose.ui.graphics.Color(0xFF000000)
    val palette = if (isDark) HighlightPalette.Dark else HighlightPalette.Light
    // Word wrap, from the toggle in the tab strip. Off, the field is laid out at the width of the
    // file's longest line and the viewport scrolls sideways over it; on, it takes the pane's width
    // and the text engine folds what doesn't fit — which is what a multi-line field does by default.
    val wrap = LocalWrapLines.current
    val hScroll = edit.hScroll
    val editorStyle = remember(fg) {
        TextStyle(
            fontFamily = NopFonts.Mono,
            fontSize = 13.sp,
            // Give each line room to breathe (~1.5x). A generous line height is a big part of
            // why a modern IDE's editor reads as calm rather than cramped; the default (font
            // intrinsic) leading packs lines together.
            lineHeight = 20.sp,
            color = fg,
        )
    }
    // How wide the unwrapped text is, as a character count times the monospace advance — the same
    // measurement the diff halves size themselves by, and for the same reason: a file whose longest
    // line is a minified blob costs a scan of the text rather than a layout of it.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(measurer, density, editorStyle) {
        val sample = measurer.measure("0".repeat(ADVANCE_SAMPLE), editorStyle).size.width
        with(density) { (sample / ADVANCE_SAMPLE.toFloat()).toDp() }
    }
    val longestLine by remember(tab.id) {
        derivedStateOf { longestLineLength(edit.state.text.toString()) }
    }
    val contentWidth = charWidth * longestLine + CARET_MARGIN
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

    // Misspellings in this file's prose. Unlike the token list this deliberately does *not* live in
    // a derivedStateOf: it walks the text and probes a 90k-word hash set for every word it finds,
    // which is far too much to put on the keystroke path. Instead it runs on a background
    // dispatcher once typing has settled, and the result is published as ordinary state for the
    // squiggle layer to draw. A file with spellcheck off, or with nothing checkable in it, simply
    // never gets a list.
    var typos by remember(tab.id) { mutableStateOf<List<Typo>>(emptyList()) }
    // Null means "not spellcheckable" — the toggle is off, or this isn't a file with prose in it.
    // SpellcheckRevision restarts the effect when a word is added to the dictionary, so the word
    // the user just accepted stops being underlined without waiting for the next edit.
    val spellcheckExt = LocalSpellcheckExtension.current
    LaunchedEffect(tab.id, spellcheckExt, SpellcheckRevision.value) {
        if (spellcheckExt == null) {
            typos = emptyList()
            return@LaunchedEffect
        }
        // Opening a file — and accepting a word, which restarts this effect — checks straight away;
        // only edits made afterwards wait for the typing to settle.
        var immediate = true
        snapshotFlow { edit.state.text.toString() }
            .debounce { if (immediate) 0L.also { immediate = false } else SPELLCHECK_DEBOUNCE_MS }
            .distinctUntilChanged()
            .collect { text ->
                // Regions are read here, on the composition's thread, so the token list and the
                // text they index into are the same snapshot; only the scan itself moves off it.
                val regions = spellcheckRegions(spellcheckExt, text, tokens)
                typos = if (regions.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) { findTypos(text, regions) }
                }
            }
    }

    // Range of the word currently under the mouse pointer while Ctrl is held *and* the symbol
    // index resolves the word to a jump target. Drawn as an underline so the user knows the
    // click will hand them off to another file. Inclusive on both ends (matches JumpResolver).
    var hoverUnderline by remember(tab.id) { mutableStateOf<IntRange?>(null) }
    // Where the last right-click landed, in document offsets — the word "Add to dictionary" acts on.
    var rightClickOffset by remember(tab.id) { mutableStateOf<Int?>(null) }
    val matchHighlight = findMatchColor()
    val activeMatchHighlight = findActiveMatchColor()
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

    // Keep the caret in view sideways. The field scrolls itself vertically, but the horizontal
    // scroll belongs to the box around it, so without this typing past the right edge — or a find
    // hit landing there, since those move the selection too — would leave the caret off-screen with
    // no sign of where it went.
    LaunchedEffect(tab.id, wrap) {
        if (wrap) return@LaunchedEffect
        // Driven by the caret alone, with the layout read (not observed) as of each move: keying the
        // flow on the layout too would compare one TextLayoutResult against another — a whole-text
        // comparison — on every keystroke, to answer a question the caret has already answered.
        snapshotFlow { edit.state.selection.end }
            .distinctUntilChanged()
            .collect { caret ->
                val tl = layout ?: return@collect
                val viewport = hScroll.viewportSize
                if (viewport <= 0) return@collect
                val margin = with(density) { CARET_MARGIN.toPx() }
                val x = tl.getHorizontalPosition(
                    caret.coerceIn(0, tl.layoutInput.text.length),
                    usePrimaryDirection = true,
                )
                val left = hScroll.value
                if (x < left + margin) {
                    hScroll.scrollTo((x - margin).toInt().coerceIn(0, hScroll.maxValue))
                } else if (x > left + viewport - margin) {
                    hScroll.scrollTo((x - viewport + margin).toInt().coerceIn(0, hScroll.maxValue))
                }
            }
    }

    // Per-line git blame for the gutter, lazily computed only while the annotate column is on.
    // null means "loading"; an empty list means "no blame available" (untracked, binary, no repo).
    // Recomputed when the gutter is turned on and after each save (savedText advances) so a fresh
    // commit or a reverted edit re-attributes the affected lines.
    var blame by remember(tab.id) { mutableStateOf<List<iondrive.nop.git.BlameLine>?>(null) }
    LaunchedEffect(tab.id, blameEnabled, repo, edit.savedText) {
        if (!blameEnabled || repo == null) {
            blame = null
            return@LaunchedEffect
        }
        blame = null
        val rel = repoRelativePath(repo, tab.file)
        blame = if (rel == null) emptyList()
        else withContext(Dispatchers.IO) { repo.blame(rel) } ?: emptyList()
    }

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

    // Swap the hit the user is parked on for the replacement text, then step onto the next one so
    // repeated Enter walks the file. The replaced hit has gone by the time this coroutine runs, so
    // the *same* index now addresses the following match (and wraps past the end).
    fun replaceCurrent() {
        val m = matches.getOrNull(currentMatch) ?: return
        edit.markUserEdit()
        replaceMatches(edit.state, listOf(m), replaceState.text.toString())
        searchScope.launch {
            val next = if (matches.isEmpty()) 0 else currentMatch % matches.size
            currentMatch = next
            jumpToMatch(next)
        }
    }

    // Every hit in one edit, so it lands as a single autosave and a single undo-sized change rather
    // than a burst of them. Deliberately computed from the matches on screen: what the "n of m"
    // chip says is what gets replaced.
    fun replaceAll() {
        if (matches.isEmpty()) return
        edit.markUserEdit()
        replaceMatches(edit.state, matches, replaceState.text.toString())
    }

    // Re-aim at the first match on genuine user find activity — opening the bar with Ctrl+F or
    // typing in the field, both of which bump findRevision. Keyed on that counter rather than the
    // query text so a programmatic seed from a global-search pick (below) keeps its own match
    // instead of being yanked back to the first hit, and so editing the document never retriggers it.
    LaunchedEffect(findRevision) {
        if (!searchOpen || findRevision == 0) return@LaunchedEffect
        currentMatch = 0
        jumpToMatch(0)
    }

    // A global "Find in files" pick arrives here as a query plus the line it matched on. Seed the
    // in-file find bar with that query so the editor lights up exactly the matches a manual Ctrl+F
    // would (same case-insensitive substring scan), and make the occurrence on the clicked line the
    // active (orange) one. Scrolling is left to the inbound-line jump below, which already copes with
    // the layout not being ready when a not-yet-open file is picked. Setting the query via state.edit
    // (not user input) deliberately doesn't bump findRevision, so the reset effect leaves it alone.
    LaunchedEffect(tab.id, pendingSearchQuery) {
        val q = pendingSearchQuery ?: return@LaunchedEffect
        if (q.isNotEmpty()) {
            searchOpen = true
            searchState.edit { replace(0, length, q) }
            val text = edit.state.text.toString()
            currentMatch = matchIndexForLine(text, findAllMatches(text, q), pendingLine ?: 1)
        }
        pendingSearchConsumedCallback()
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
        SaveStatusStrip(edit, onSaved = savedCallback)
        if (searchOpen) {
            FindBar(
                state = searchState,
                focusRequester = searchFocusRequester,
                matchCount = matches.size,
                currentIndex = currentMatch,
                onUserEdit = { findRevision++ },
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
                replace = if (replaceOpen) {
                    ReplaceFields(
                        state = replaceState,
                        focusRequester = replaceFocusRequester,
                        onReplace = ::replaceCurrent,
                        onReplaceAll = ::replaceAll,
                    )
                } else {
                    null
                },
            )
        }
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        // IntelliJ-style annotate column, kept to the left of the text and aligned to it by sharing
        // the editor's scrollState + layout. The top padding mirrors the text Box's so line 0 of the
        // gutter sits level with line 0 of the file.
        if (blameEnabled && repo != null) {
            BlameGutter(
                blame = blame,
                layout = layout,
                scrollState = scrollState,
                text = edit.state.text.toString(),
                onLineClick = { line -> line.sha?.let(onOpenBlameCommit) },
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            )
        }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .padding(12.dp),
    ) {
    // Everything that has to line up with the text — the field and the error squiggles over it —
    // sits inside one box that is the width of the *content*, so the whole thing slides together
    // under the horizontal scroll. Wrapping collapses that to the viewport width: there is nothing
    // to the right to scroll to, and BoxWithConstraints hands us the width to fold the text into.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
        // At least the viewport wide, so clicking in the space beside a short line still lands in
        // the field rather than on the panel behind it.
        val fieldWidth = maxOf(contentWidth, maxWidth)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
        ) {
        // Appended to the text field's own right-click menu (cut/copy/paste/select all), which is
        // where the file in front of the user is: "what did this look like before?" belongs beside
        // the editing actions rather than behind a trip to the project tree. "Add to dictionary"
        // joins them when the click landed on an underlined word — keyed off where the pointer was,
        // not the caret, because a right-click in Compose's text field doesn't move the caret and
        // an entry that silently accepted some *other* word would be worse than no entry at all.
        ContextMenuDataProvider(items = {
            spellingMenuItems(
                typo = rightClickOffset?.let { offset ->
                    typos.firstOrNull { offset in it.range.first..(it.range.last + 1) }
                },
                onReplace = { typo, replacement ->
                    replaceTypo(edit.state, typo, replacement) { edit.markUserEdit() }
                },
            ) + listOf(ContextMenuDivider, ContextMenuItem("Show local history", onShowLocalHistory))
        }) {
        BasicTextField(
            state = edit.state,
            // Runs only for genuine user input (typing, paste, IME) — never for programmatic
            // state.edit {} updates — so it's the precise signal that the buffer now holds a user
            // edit worth saving. Reconcile/adopt mutate the buffer without tripping this.
            inputTransformation = InputTransformation { edit.markUserEdit() },
            modifier = Modifier
                .then(if (wrap) Modifier.fillMaxSize() else Modifier.width(fieldWidth).fillMaxHeight())
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

                            // The pointer position is in the field's viewport space, but the text
                            // layout is laid out in document space; shift by the scroll offset so the
                            // offset lookup is correct once the file has been scrolled (matches how
                            // BlameGutter and jumpToMatch map between the two). Without this, Ctrl
                            // hover/click on anything below the fold resolved the wrong word — or none.
                            fun docOffset(pos: Offset): Int =
                                tl?.getOffsetForPosition(pos.copy(y = pos.y + scrollState.value)) ?: 0

                            // Maintain the hover underline on every event: clear it whenever
                            // Ctrl is released, the pointer leaves the field, or the resolved
                            // target disappears (cursor moved off the word).
                            if (event.type == PointerEventType.Exit || !ctrl || change == null || tl == null) {
                                hoverUnderline = null
                            } else {
                                val text = edit.state.text.toString()
                                val offset = docOffset(change.position)
                                hoverUnderline = if (resolveCallback(text, offset) != null) {
                                    JumpResolver.wordRangeAt(text, offset)
                                } else {
                                    null
                                }
                            }

                            // Remembered for the context menu, which is built after the press has
                            // been and gone and has no idea where the pointer was.
                            if (event.type == PointerEventType.Press &&
                                event.buttons.isSecondaryPressed &&
                                change != null && tl != null
                            ) {
                                rightClickOffset = docOffset(change.position)
                            }

                            if (event.type == PointerEventType.Press && ctrl && change != null && tl != null) {
                                val offset = docOffset(change.position)
                                val target = resolveCallback(edit.state.text.toString(), offset)
                                if (target != null) {
                                    change.consume()
                                    jumpCallback(target.file, target.line)
                                }
                            }
                        }
                    }
                },
            textStyle = editorStyle,
            cursorBrush = SolidColor(fg),
            lineLimits = TextFieldLineLimits.MultiLine(),
            scrollState = scrollState,
            outputTransformation = transformation,
            onTextLayout = { getResult ->
                val r = getResult()
                if (r != null) layout = r
            },
        )
        }
        // Wavy underlines: red under syntax-error ranges (native YAML errors today), and the
        // spellchecker's colour under misspelled words. Aligned to the text the same way BlameGutter
        // is: layout positions are in document space, shifted up by the scroll offset. Sized to the
        // field rather than the viewport (matchParentSize) so they travel with the text under the
        // horizontal scroll instead of staying pinned to the pane. Decorative only — no
        // pointerInput, so Ctrl-click still reaches the field.
        val errorColor = palette.error.color
        val typoColor = typoSquiggleColor()
        Canvas(modifier = Modifier.matchParentSize()) {
            val tl = layout ?: return@Canvas
            val textLen = tl.layoutInput.text.length
            val scroll = scrollState.value.toFloat()
            for (t in tokens) {
                if (t.kind != TokenKind.ERROR) continue
                drawSquiggle(tl, t.start, t.endExclusive, textLen, scroll, errorColor)
            }
            for (typo in typos) {
                drawSquiggle(tl, typo.range.first, typo.range.last + 1, textLen, scroll, typoColor)
            }
        }
        }
    }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            style = NopScrollbarStyle,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.CenterEnd)
                .width(8.dp)
                .fillMaxHeight(),
        )
    }
    }
        // Only claimed when a line actually overruns the pane, so a file that fits keeps the full
        // height — the same rule the diff's side scrollbars follow.
        if (!wrap && hScroll.maxValue > 0) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                style = NopScrollbarStyle,
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(start = 12.dp, end = 24.dp),
            )
        }
    }
}

/** Repo-relative, forward-slashed path for [file], or null if it falls outside [repo]. */
internal fun repoRelativePath(repo: GitRepo, file: File): String? = runCatching {
    repo.rootDir.toAbsolutePath().normalize()
        .relativize(file.toPath().toAbsolutePath().normalize())
        .toString().replace(File.separatorChar, '/')
}.getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("..") }

/**
 * Markdown tab layout: editor on the left, live-rendered preview on the right.
 *
 * Everything the plain editor is given has to be forwarded to the editor half — a markdown file is
 * still a file, and dropping the inbound jump/search parameters here is what used to make a global
 * "Find in files" hit in a .md open the tab at the top with nothing highlighted (and leave the
 * queued jump uncleared in TabsState, since nothing ever consumed it).
 */
@Composable
private fun MarkdownEditWithPreview(
    tab: Tab.FileView,
    store: FileEditStore,
    onSaved: () -> Unit,
    pendingLine: Int? = null,
    onPendingLineConsumed: () -> Unit = {},
    pendingSearchQuery: String? = null,
    onPendingSearchConsumed: () -> Unit = {},
    findInFileTrigger: Int = 0,
    replaceInFileTrigger: Int = 0,
    saveTrigger: Int = 0,
    onShowLocalHistory: () -> Unit = {},
) {
    val edit = remember(tab.id) { store.edit(tab) }
    val previewText by remember(edit) {
        derivedStateOf { edit.state.text.toString() }
    }
    HorizontalSplitLayout(
        first = {
            FileEditView(
                tab = tab,
                store = store,
                onSaved = onSaved,
                pendingLine = pendingLine,
                onPendingLineConsumed = onPendingLineConsumed,
                pendingSearchQuery = pendingSearchQuery,
                onPendingSearchConsumed = onPendingSearchConsumed,
                findInFileTrigger = findInFileTrigger,
                replaceInFileTrigger = replaceInFileTrigger,
                saveTrigger = saveTrigger,
                onShowLocalHistory = onShowLocalHistory,
            )
        },
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
 * Longest line of [text], in characters — the monospace stand-in for "widest", and so how wide the
 * editor lays itself out when word wrap is off. Counted over a single pass rather than by splitting,
 * because this runs on every keystroke and a large file would otherwise allocate its own line list
 * each time. A trailing newline contributes a final empty line, which never wins.
 */
internal fun longestLineLength(text: String): Int {
    var longest = 0
    var lineStart = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            if (i - lineStart > longest) longest = i - lineStart
            lineStart = i + 1
        }
    }
    if (text.length - lineStart > longest) longest = text.length - lineStart
    return longest
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
 * Replace [matches] in [state] with [replacement], as one edit.
 *
 * Splices from the last match backwards so every range still addresses the text it was found in —
 * rewriting front-to-back would shift each later match by the length difference of the ones before
 * it. Doing the lot inside a single `edit {}` also means Compose maps the caret through the splices
 * instead of collapsing it to the end of the document the way a whole-buffer rewrite does, and the
 * debounced autosave sees one change rather than a burst.
 *
 * [matches] must be non-overlapping and ascending — what [findAllMatches] returns.
 */
internal fun replaceMatches(state: TextFieldState, matches: List<IntRange>, replacement: String) {
    if (matches.isEmpty()) return
    state.edit {
        for (m in matches.asReversed()) {
            val start = m.first.coerceIn(0, length)
            val end = (m.last + 1).coerceIn(start, length)
            replace(start, end, replacement)
        }
    }
}

/** Char offset of the first character of 1-based [line] in [text], clamped into range. */
internal fun lineStartOffset(text: String, line: Int): Int {
    if (line <= 1) return 0
    var offset = 0
    var current = 1
    while (current < line) {
        val nl = text.indexOf('\n', offset)
        if (nl < 0) return text.length
        offset = nl + 1
        current++
    }
    return offset.coerceAtMost(text.length)
}

/**
 * Index into [matches] of the first match that begins on or after the start of 1-based [line] —
 * i.e. the occurrence a global-search hit reported on that line refers to. Falls back to 0 when
 * [matches] is empty or nothing starts at/after the line (shouldn't happen for a real hit), so a
 * valid active-match index is always returned.
 */
internal fun matchIndexForLine(text: String, matches: List<IntRange>, line: Int): Int {
    if (matches.isEmpty()) return 0
    val start = lineStartOffset(text, line)
    val idx = matches.indexOfFirst { it.first >= start }
    return if (idx >= 0) idx else 0
}

