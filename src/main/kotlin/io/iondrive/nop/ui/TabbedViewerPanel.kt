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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.editorTabStyle

@OptIn(ExperimentalJewelApi::class)
@Composable
fun TabbedViewerPanel(tabsState: TabsState, repo: GitRepo?, editStore: FileEditStore) {
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
                tabsState.close(tab.id)
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabData, style = JewelTheme.editorTabStyle)
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = selected) {
                is Tab.FileView -> FileEditView(current, editStore)
                is Tab.Diff -> if (repo != null) DiffView(repo, current)
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
}

@Composable
private fun FileEditView(tab: Tab.FileView, store: FileEditStore) {
    val edit = remember(tab.id) { store.edit(tab) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember(tab.id) { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(tab.id) {
        // Take focus when the tab activates so Ctrl+S goes to this editor
        runCatching { focusRequester.requestFocus() }
    }

    val fg = androidx.compose.ui.graphics.Color(0xFFA9B7C6)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(12.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
                    scope.launch { withContext(Dispatchers.IO) { edit.save() } }
                    true
                } else {
                    false
                }
            },
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
