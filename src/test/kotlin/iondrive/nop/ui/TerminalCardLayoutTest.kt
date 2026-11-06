package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingUtilities

class TerminalCardLayoutTest {
    @Test
    fun `hidden terminals keep their size until selected`() = SwingUtilities.invokeAndWait {
        val layout = TerminalCardLayout()
        val cards = JPanel(layout)
        val first = JPanel()
        val second = JPanel()
        cards.add(first, "first")
        cards.add(second, "second")
        cards.setSize(900, 600)
        cards.doLayout()
        layout.show(cards, "second")
        cards.doLayout()
        assertEquals(Dimension(900, 600), first.size)
        assertEquals(first.size, second.size)

        cards.setSize(620, 420)
        cards.doLayout()
        assertEquals(Dimension(900, 600), first.size)
        assertEquals(Dimension(620, 420), second.size)

        layout.show(cards, "first")
        cards.doLayout()
        assertTrue(first.isVisible)
        assertFalse(second.isVisible)
        assertEquals(Dimension(620, 420), first.size)
    }

    @Test
    fun `visible terminal fits the card insets and survives removing a tab`() = SwingUtilities.invokeAndWait {
        val layout = TerminalCardLayout().apply { hgap = 3; vgap = 4 }
        val cards = JPanel(layout).apply { border = BorderFactory.createEmptyBorder(5, 6, 7, 8) }
        val first = JPanel()
        val second = JPanel()
        cards.add(first, "first")
        cards.add(second, "second")
        cards.setSize(600, 400)
        cards.doLayout()
        assertEquals(java.awt.Rectangle(9, 9, 580, 380), first.bounds)
        cards.remove(first)
        cards.doLayout()
        assertTrue(second.isVisible)
        assertEquals(java.awt.Rectangle(9, 9, 580, 380), second.bounds)
    }
}
