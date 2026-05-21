package io.iondrive.nop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
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

    val tabData = tabsState.tabs.map { tab ->
        val label = labelFor(tab, editStore)
        TabData.Editor(
            selected = tab.id == tabsState.selectedId,
            content = { state -> SimpleTabContent(label = label, state = state) },
            onClick = { tabsState.select(tab.id) },
            onClose = {
                editStore.close(tab.id)
                if (tab is Tab.LauncherOutput) tab.run.stop()
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

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabData, style = tabStyle)
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = selected) {
                is Tab.FileView -> FileEditView(current, editStore, onFileSaved)
                is Tab.Diff -> if (repo != null) DiffView(repo, current)
                is Tab.History -> if (repo != null) HistoryView(repo, current)
                is Tab.LauncherOutput -> LauncherOutputView(current)
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
    is Tab.History -> tab.title
    is Tab.LauncherOutput -> tab.title
}

@OptIn(FlowPreview::class)
@Composable
private fun FileEditView(tab: Tab.FileView, store: FileEditStore, onSaved: () -> Unit) {
    val edit = remember(tab.id) { store.edit(tab) }
    val focusRequester = remember(tab.id) { FocusRequester() }
    val scrollState = rememberScrollState()
    val savedCallback by rememberUpdatedState(onSaved)

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
                    withContext(Dispatchers.IO) { edit.save() }
                    savedCallback()
                }
            }
    }

    val fg = androidx.compose.ui.graphics.Color(0xFFA9B7C6)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(12.dp),
    ) {
        BasicTextField(
            state = edit.state,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = fg,
            ),
            cursorBrush = SolidColor(fg),
            lineLimits = TextFieldLineLimits.MultiLine(),
            scrollState = scrollState,
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
