package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.CardLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SlackCardsTest {
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
