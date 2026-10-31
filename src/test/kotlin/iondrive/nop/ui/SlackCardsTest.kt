package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.CardLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SlackCardsTest {
    @Test
    fun `switching workspaces leaves the terminal visible in its own host`() {
        SwingUtilities.invokeAndWait {
            val terminalCards = JPanel(CardLayout())
            val terminal = JPanel()
            terminalCards.add(terminal, "terminal")
            val slackCards = JPanel(CardLayout())
            val first = JPanel()
            val second = JPanel()
            showSlackCard(slackCards, first, "first")
            showSlackCard(slackCards, second, "second")
            assertSame(terminalCards, terminal.parent)
            assertTrue(terminal.isVisible)
            assertSame(slackCards, second.parent)
            assertTrue(second.isVisible)
            assertFalse(first.isVisible)
        }
    }

    @Test
    fun `a new workspace becomes visible even when the saved card marker already names it`() {
        SwingUtilities.invokeAndWait {
            val cards = JPanel(CardLayout())
            val first = JPanel()
            val second = JPanel()
            showSlackCard(cards, first, "first")
            cards.putClientProperty(SHOWN_ID, "second")
            assertTrue(showSlackCard(cards, second, "second"))
            assertTrue(second.isVisible)
            assertFalse(first.isVisible)
            assertTrue(showSlackCard(cards, first, "first"))
            assertTrue(first.isVisible)
            assertFalse(second.isVisible)
            assertFalse(showSlackCard(cards, first, "first"))
        }
    }
}
