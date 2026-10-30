package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import java.awt.EventQueue
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommitSelectionInteractionTest {
    private class Rows : AutoCloseable {
        val paths = listOf("src/One.kt", "src/Two.kt", "tests/OneTest.kt", "docs/readme.md")
        var ticks by mutableStateOf(CommitSelection.loaded(setOf(paths[0], paths[2])))
        var selection by mutableStateOf(CommitRowSelection())
        val opened = mutableListOf<String>()
        private var time = 0L
        private val scene = onEdt {
            ImageComposeScene(400, 200, Density(1f)) {
                IntUiTheme(isDark = false) {
                    Column {
                        for (path in paths) {
                            Box(Modifier.height(40.dp)) {
                                ChangeRow(
                                    change = FileChange(path, ChangeKind.MODIFIED),
                                    label = path,
                                    checked = path in ticks.ticked,
                                    selected = path in selection.paths,
                                    mark = null,
                                    onToggle = { ticks = ticks.toggle(path) },
                                    onPathClick = { toggle, range ->
                                        selection = selection.click(path, paths, toggle, range)
                                        if (!toggle && !range) opened += path
                                    },
                                    onRevert = {},
                                )
                            }
                        }
                    }
                }
            }
        }

        fun settle() = repeat(10) {
            time += 16_000_000
            onEdt { scene.render(time) }
            Thread.sleep(5)
        }

        fun highlightedRows(): Set<Int> = onEdt {
            time += 16_000_000
            Bitmap.makeFromImage(scene.render(time)).use { bitmap ->
                paths.indices.filter { bitmap.getColor(350, it * 40 + 5) == 0xFFCEE0FD.toInt() }.toSet()
            }
        }

        fun click(row: Int, checkbox: Boolean = false, ctrl: Boolean = false, shift: Boolean = false, meta: Boolean = false) {
            val position = Offset(if (checkbox) 10f else 160f, row * 40f + 14f)
            val modifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl, isShiftPressed = shift, isMetaPressed = meta)
            onEdt { scene.sendPointerEvent(PointerEventType.Move, position, keyboardModifiers = modifiers) }
            settle()
            onEdt {
                scene.sendPointerEvent(
                    PointerEventType.Press, position, buttons = PointerButtons(isPrimaryPressed = true),
                    keyboardModifiers = modifiers, button = PointerButton.Primary,
                )
            }
            settle()
            onEdt {
                scene.sendPointerEvent(
                    PointerEventType.Release, position, buttons = PointerButtons(),
                    keyboardModifiers = modifiers, button = PointerButton.Primary,
                )
            }
            settle()
        }

        override fun close() = onEdt { scene.close() }

        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    @Test
    fun `plain ctrl and meta clicks select rows without changing any checkbox`() {
        Rows().use { rows ->
            rows.settle()
            val ticks = rows.ticks
            rows.click(0)
            rows.click(2, meta = true)
            rows.click(3, ctrl = true)
            assertEquals(setOf(rows.paths[0], rows.paths[2], rows.paths[3]), rows.selection.paths)
            assertEquals(setOf(0, 2, 3), rows.highlightedRows())
            assertEquals(listOf(rows.paths[0]), rows.opened)

            rows.click(2, ctrl = true)
            assertEquals(setOf(rows.paths[0], rows.paths[3]), rows.selection.paths)
            assertEquals(setOf(0, 3), rows.highlightedRows())
            rows.click(1)
            assertEquals(setOf(rows.paths[1]), rows.selection.paths)
            assertEquals(setOf(1), rows.highlightedRows())
            assertEquals(listOf(rows.paths[0], rows.paths[1]), rows.opened)
            assertEquals(ticks, rows.ticks)
        }
    }

    @Test
    fun `shift selects and shrinks a range without changing any checkbox`() {
        Rows().use { rows ->
            rows.settle()
            val ticks = rows.ticks
            rows.click(0)
            rows.click(3, shift = true)
            assertEquals(rows.paths.toSet(), rows.selection.paths)
            assertEquals(setOf(0, 1, 2, 3), rows.highlightedRows())
            rows.click(2, shift = true)
            assertEquals(rows.paths.take(3).toSet(), rows.selection.paths)
            assertEquals(setOf(0, 1, 2), rows.highlightedRows())
            assertEquals(listOf(rows.paths[0]), rows.opened)
            assertEquals(ticks, rows.ticks)
        }
    }

    @Test
    fun `checkbox clicks keep their single-file behavior with modifiers and preserve row selection`() {
        Rows().use { rows ->
            rows.settle()
            rows.click(1)
            val selection = rows.selection
            rows.click(0, checkbox = true, ctrl = true)
            rows.click(2, checkbox = true, shift = true)
            assertEquals(emptySet<String>(), rows.ticks.ticked)
            assertEquals(selection, rows.selection)
            assertEquals(listOf(rows.paths[1]), rows.opened)

            rows.click(3, checkbox = true)
            assertEquals(setOf(rows.paths[3]), rows.ticks.ticked)
            assertEquals(selection, rows.selection)
        }
    }
}
