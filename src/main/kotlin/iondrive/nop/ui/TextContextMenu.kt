package iondrive.nop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocalization
import org.jetbrains.jewel.intui.standalone.styling.dark
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.ui.component.ContextMenuDivider
import org.jetbrains.jewel.ui.component.ContextMenuItemOption
import org.jetbrains.jewel.ui.component.ContextMenuItemOptionAction
import org.jetbrains.jewel.ui.component.styling.MenuColors
import org.jetbrains.jewel.ui.component.styling.MenuIcons
import org.jetbrains.jewel.ui.component.styling.MenuItemColors
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.icon.PathIconKey

/**
 * The right-click menu inside a text field — cut/copy/paste/select all — built without icons.
 *
 * Jewel's own version asks for the IntelliJ platform's action icons, which only exist inside the
 * IDE: standalone, the lookup fails and the menu draws a magenta "missing icon" square next to
 * Paste. nop ships no icon set of its own, and the menu reads fine without one (its other context
 * menus — project tree, tab strip, project rail — have never had icons), so this builds the same
 * four entries with none. Everything else is Jewel's: the same popup, the same keyboard handling,
 * and the same shortcut hints, which come from the action type rather than the label.
 *
 * Items contributed by an enclosing `ContextMenuDataProvider` (the editor's "Show local history")
 * are appended by Compose after these.
 */
@OptIn(ExperimentalFoundationApi::class)
object NopTextContextMenu : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val localization = LocalLocalization.current
        val items: () -> List<ContextMenuItem> = {
            buildList {
                // Each of these is null when it doesn't apply — cut/copy without a selection, paste
                // with nothing text-shaped on the clipboard — so the menu only ever offers what
                // would actually do something, and never shows a disabled entry.
                textManager.cut?.let {
                    add(option(ContextMenuItemOptionAction.CutMenuItemOptionAction, localization.cut, it))
                }
                textManager.copy?.let {
                    add(option(ContextMenuItemOptionAction.CopyMenuItemOptionAction, localization.copy, it))
                }
                textManager.paste?.let {
                    add(option(ContextMenuItemOptionAction.PasteMenuItemOptionAction, localization.paste, it))
                }
                textManager.selectAll?.let {
                    if (isNotEmpty()) add(ContextMenuDivider)
                    add(option(ContextMenuItemOptionAction.SelectAllMenuItemOptionAction, localization.selectAll, it))
                }
            }
        }
        // Jewel's own Area delegates here too: this is what makes a right-click select the word
        // under the pointer before the menu opens.
        TextContextMenuArea(textManager, items, state, content)
    }

    private fun option(action: ContextMenuItemOptionAction, label: String, onClick: () -> Unit) =
        ContextMenuItemOption(null, action, true, label, onClick)
}

// The "there's a submenu here" chevron, which Jewel otherwise asks the IntelliJ platform for — and
// standalone that lookup fails and draws a solid placeholder square, so "Replace With" in the
// spelling menu looked broken. Same bundled SVG the project tree's disclosure arrows use.
private object MenuIconsClass
private val SubmenuChevronIconKey = PathIconKey("icons/chevron-right.svg", MenuIconsClass::class.java)

/**
 * Jewel's menu styling with two changes: the keyboard-shortcut hint beside an entry is legible, and
 * the submenu chevron is one nop actually ships.
 *
 * The default hint tint is close enough to the disabled-content colour that "Ctrl+V" beside an
 * enabled Paste reads as a greyed-out control rather than as the shortcut for it. These sit a couple
 * of steps brighter — still clearly secondary to the label, but plainly *on*.
 */
fun nopMenuStyle(dark: Boolean): MenuStyle {
    val keybinding = if (dark) Color(0xFFA9B0BA) else Color(0xFF5C616B)
    val itemColors = if (dark) {
        MenuItemColors.dark(
            keybindingTint = keybinding,
            keybindingTintHovered = keybinding,
            keybindingTintFocused = keybinding,
            keybindingTintPressed = keybinding,
        )
    } else {
        MenuItemColors.light(
            keybindingTint = keybinding,
            keybindingTintHovered = keybinding,
            keybindingTintFocused = keybinding,
            keybindingTintPressed = keybinding,
        )
    }
    val icons = MenuIcons(submenuChevron = SubmenuChevronIconKey)
    return if (dark) {
        MenuStyle.dark(colors = MenuColors.dark(itemColors = itemColors), icons = icons)
    } else {
        MenuStyle.light(colors = MenuColors.light(itemColors = itemColors), icons = icons)
    }
}
