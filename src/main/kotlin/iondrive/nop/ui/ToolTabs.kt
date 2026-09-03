package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle

enum class ToolTab { Commit, Search, Usages, Stash }

/**
 * The persistent tabs in the tool panel on the window's right edge — Commit, Search, Usages and
 * Stash. None is closeable; the strip is part of the chrome, not a user-managed tab collection.
 * Selection state lives in [App] so external triggers (Ctrl+Shift+F for Search, Alt+F7 for Usages)
 * can flip to a tab without poking the panel.
 */
@Composable
fun ToolTabs(
    selected: ToolTab,
    onSelect: (ToolTab) -> Unit,
    commit: @Composable () -> Unit,
    search: @Composable () -> Unit,
    usages: @Composable () -> Unit,
    stash: @Composable () -> Unit,
) {
    val tabs = listOf(
        TabData.Default(
            selected = selected == ToolTab.Commit,
            closable = false,
            onClose = {},
            onClick = { onSelect(ToolTab.Commit) },
            content = { state -> SimpleTabContent(label = "Commit", state = state) },
        ),
        TabData.Default(
            selected = selected == ToolTab.Search,
            closable = false,
            onClose = {},
            onClick = { onSelect(ToolTab.Search) },
            content = { state -> SimpleTabContent(label = "Search", state = state) },
        ),
        TabData.Default(
            selected = selected == ToolTab.Usages,
            closable = false,
            onClose = {},
            onClick = { onSelect(ToolTab.Usages) },
            content = { state -> SimpleTabContent(label = "Usages", state = state) },
        ),
        TabData.Default(
            selected = selected == ToolTab.Stash,
            closable = false,
            onClose = {},
            onClick = { onSelect(ToolTab.Stash) },
            content = { state -> SimpleTabContent(label = "Stash", state = state) },
        ),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                ToolTab.Commit -> commit()
                ToolTab.Search -> search()
                ToolTab.Usages -> usages()
                ToolTab.Stash -> stash()
            }
        }
    }
}
