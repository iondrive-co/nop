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

enum class BottomTab { Commit, Search, Stash }

/**
 * Three persistent tabs at the bottom of the window — Commit, Search and Stash. None is closeable;
 * the strip is part of the chrome, not a user-managed tab collection. Selection state lives in
 * [App] so external triggers (e.g. Ctrl+Shift+F) can flip to Search without poking the panel.
 */
@Composable
fun BottomTabs(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    commit: @Composable () -> Unit,
    search: @Composable () -> Unit,
    stash: @Composable () -> Unit,
) {
    val tabs = listOf(
        TabData.Default(
            selected = selected == BottomTab.Commit,
            closable = false,
            onClose = {},
            onClick = { onSelect(BottomTab.Commit) },
            content = { state -> SimpleTabContent(label = "Commit", state = state) },
        ),
        TabData.Default(
            selected = selected == BottomTab.Search,
            closable = false,
            onClose = {},
            onClick = { onSelect(BottomTab.Search) },
            content = { state -> SimpleTabContent(label = "Search", state = state) },
        ),
        TabData.Default(
            selected = selected == BottomTab.Stash,
            closable = false,
            onClose = {},
            onClick = { onSelect(BottomTab.Stash) },
            content = { state -> SimpleTabContent(label = "Stash", state = state) },
        ),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                BottomTab.Commit -> commit()
                BottomTab.Search -> search()
                BottomTab.Stash -> stash()
            }
        }
    }
}
