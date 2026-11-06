package iondrive.nop.ui

import java.awt.CardLayout
import java.awt.Container

/** Hidden terminals keep their geometry, so a pane resize does not make every CLI redraw its history. */
internal class TerminalCardLayout : CardLayout() {
    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val width = (parent.width - insets.left - insets.right - 2 * hgap).coerceAtLeast(0)
            val height = (parent.height - insets.top - insets.bottom - 2 * vgap).coerceAtLeast(0)
            for (component in parent.components) {
                if (component.isVisible) {
                    component.setBounds(insets.left + hgap, insets.top + vgap, width, height)
                }
            }
        }
    }
}
