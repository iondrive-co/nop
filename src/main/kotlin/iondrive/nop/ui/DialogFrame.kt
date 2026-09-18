package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The window a dialog that may open over a terminal is drawn in: the agent dialogs, adding a
 * launcher, the window picker, and the single-field prompts that name files, tabs and windows.
 *
 * A real one — an AWT dialog of its own — and not a centred `Popup`, which is the whole reason this
 * exists separately. What is often on screen behind these dialogs is the tool region, and in it a
 * running vendor TUI or a shell: a heavyweight AWT component that Compose Desktop composites
 * *above* everything it draws itself. A popup there is not merely hard to read, it is not on screen
 * at all — including its close button, which is how a settings dialog became something that could
 * be opened and not shut. A centred popup is only safe when the window's middle is sure to be
 * Compose, and with the tool region open beside the editor it is not. The same reason the login
 * flow is sent to a terminal tab rather than run in here, and the same reason the usage strip makes
 * the terminal give a strip of height back.
 *
 * Being a window buys the rest for free: the platform's own title bar and close button, somewhere
 * to move it to, and a place above the terminal to stay.
 *
 * A [size] whose height is [Dp.Unspecified][androidx.compose.ui.unit.Dp.Unspecified] fits the window
 * to its content, for a dialog whose length depends on what it lists.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DialogFrame(
    title: String,
    onClose: () -> Unit,
    size: DpSize,
    onSubmit: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Read out here, in the composition that opens the dialog, because the theme is re-applied
    // inside a window that hosts its own: see below.
    val isDark = JewelTheme.isDark
    // The key handler is handed to the AWT dialog once, when it is created, and never replaced — so
    // without these, Enter would run the first composition's onSubmit for as long as the dialog is
    // open, and a form that could only be submitted once filled in could never be from the keyboard.
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    // Compose can size a window to its content itself, but measures that content at an unbounded
    // width, so text that wraps in the real window came out a line or two short and the buttons
    // under it were cut off. So the height is fitted here, from the content as laid out at the
    // window's own width; until then it is a guess.
    val fitsContent = !size.height.isSpecified
    val state = rememberDialogState(size = if (fitsContent) DpSize(size.width, 200.dp) else size)
    DialogWindow(
        onCloseRequest = onClose,
        state = state,
        title = title,
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@DialogWindow false
            when (event.key) {
                Key.Escape -> { currentOnClose(); true }
                Key.Enter, Key.NumPadEnter -> currentOnSubmit?.let { it(); true } ?: false
                else -> false
            }
        },
    ) {
        var contentHeight by remember { mutableStateOf<Dp?>(null) }
        if (fitsContent) {
            // The frame's insets are only known once the window manager has put the window up, so
            // the fit is done again when it opens.
            var opened by remember { mutableStateOf(window.isShowing) }
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowOpened(e: WindowEvent) {
                        opened = true
                    }
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener) }
            }
            LaunchedEffect(contentHeight, opened) {
                val height = contentHeight ?: return@LaunchedEffect
                val insets = window.insets
                state.size = DpSize(size.width, height + (insets.top + insets.bottom).dp)
            }
        }
        val density = LocalDensity.current
        // Applied again rather than inherited. A window composes its content in a sub-composition of
        // its own, and Jewel's components take their colours, metrics and typography from the theme
        // in scope where they are drawn — so a dialog that leaned on the main window's would be one
        // upstream change away from opening unstyled. Cheap insurance for something the user cannot
        // work around from inside.
        IntUiTheme(
            theme = if (isDark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
            styling = ComponentStyling.default(),
        ) {
            // nop's own text-field context menu, the same one the main window provides — see
            // [NopTextContextMenu].
            CompositionLocalProvider(LocalTextContextMenu provides NopTextContextMenu) {
                Box(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
                    Column(
                        modifier = if (fitsContent) {
                            // Its whole height, even while the window is still shorter than that.
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                .onSizeChanged { contentHeight = with(density) { it.height.toDp() } }
                        } else {
                            Modifier.fillMaxSize()
                        }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
