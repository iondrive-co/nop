package iondrive.nop.terminal

import iondrive.nop.Log
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Interactive screen region selection tool.
 *
 * Freezes the desktop across all connected monitors, overlays a darkened veil, and lets the user
 * drag a rectangle to select an area of the screen. Upon release, crops that region, saves it as a
 * temporary PNG image via [saveImageToTemp], and invokes [onSelected] with its path.
 *
 * Pressing Escape or right-clicking cancels the selection without capturing anything.
 */
internal object ScreenSnip {

    private val isSnapping = AtomicBoolean(false)

    fun start(owner: Window? = null, onSelected: (Path) -> Unit) {
        if (!isSnapping.compareAndSet(false, true)) return

        thread(name = "screen-snip-capture") {
            try {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val devices = ge.screenDevices
                if (devices.isEmpty()) {
                    isSnapping.set(false)
                    return@thread
                }

                var totalBounds = Rectangle()
                for (gd in devices) {
                    totalBounds = totalBounds.union(gd.defaultConfiguration.bounds)
                }

                val robot = java.awt.Robot()
                val fullDesktop = robot.createScreenCapture(totalBounds)

                SwingUtilities.invokeLater {
                    showOverlay(owner, devices, totalBounds, fullDesktop, onSelected)
                }
            } catch (e: Throwable) {
                Log.warn("screen snip capture failed: $e")
                isSnapping.set(false)
            }
        }
    }

    private fun showOverlay(
        owner: Window?,
        devices: Array<GraphicsDevice>,
        totalBounds: Rectangle,
        fullDesktop: BufferedImage,
        onSelected: (Path) -> Unit,
    ) {
        val dialogs = mutableListOf<JDialog>()
        var startPoint: Point? = null
        var currentPoint: Point? = null

        fun closeAll() {
            for (d in dialogs) {
                runCatching { d.dispose() }
            }
            dialogs.clear()
            isSnapping.set(false)
        }

        fun repaintAll() {
            for (d in dialogs) {
                d.contentPane.repaint()
            }
        }

        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) {
                    closeAll()
                    return
                }
                startPoint = Point(e.xOnScreen, e.yOnScreen)
                currentPoint = Point(e.xOnScreen, e.yOnScreen)
                repaintAll()
            }

            override fun mouseDragged(e: MouseEvent) {
                currentPoint = Point(e.xOnScreen, e.yOnScreen)
                repaintAll()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val s = startPoint
                val c = currentPoint
                closeAll()

                if (s != null && c != null) {
                    val rx = minOf(s.x, c.x)
                    val ry = minOf(s.y, c.y)
                    val rw = abs(c.x - s.x)
                    val rh = abs(c.y - s.y)
                    if (rw >= 4 && rh >= 4) {
                        val cropX = (rx - totalBounds.x).coerceIn(0, fullDesktop.width - 1)
                        val cropY = (ry - totalBounds.y).coerceIn(0, fullDesktop.height - 1)
                        val cropW = rw.coerceAtMost(fullDesktop.width - cropX)
                        val cropH = rh.coerceAtMost(fullDesktop.height - cropY)
                        if (cropW > 0 && cropH > 0) {
                            val cropped = fullDesktop.getSubimage(cropX, cropY, cropW, cropH)
                            thread(name = "save-snip-image") {
                                saveImageToTemp(cropped)?.let { path ->
                                    SwingUtilities.invokeLater {
                                        onSelected(path)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (gd in devices) {
            val bounds = gd.defaultConfiguration.bounds
            val dialog = JDialog(owner as? Frame)
            dialog.isUndecorated = true
            dialog.isAlwaysOnTop = true
            dialog.bounds = bounds
            dialog.cursor = Cursor(Cursor.CROSSHAIR_CURSOR)
            dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

            val panel = object : JComponent() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g as? Graphics2D ?: return
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                    // 1. Draw screen slice from fullDesktop
                    val sx = bounds.x - totalBounds.x
                    val sy = bounds.y - totalBounds.y
                    g2.drawImage(
                        fullDesktop,
                        0, 0, bounds.width, bounds.height,
                        sx, sy, sx + bounds.width, sy + bounds.height,
                        null,
                    )

                    // 2. Dim mask
                    g2.color = Color(0, 0, 0, 110)
                    g2.fillRect(0, 0, bounds.width, bounds.height)

                    val s = startPoint
                    val c = currentPoint
                    if (s != null && c != null) {
                        val rx = minOf(s.x, c.x)
                        val ry = minOf(s.y, c.y)
                        val rw = abs(c.x - s.x)
                        val rh = abs(c.y - s.y)
                        val selRect = Rectangle(rx, ry, rw, rh)

                        val inter = selRect.intersection(bounds)
                        if (!inter.isEmpty && inter.width > 0 && inter.height > 0) {
                            val lx = inter.x - bounds.x
                            val ly = inter.y - bounds.y
                            val imgX = inter.x - totalBounds.x
                            val imgY = inter.y - totalBounds.y

                            // Clear unmasked region
                            g2.drawImage(
                                fullDesktop,
                                lx, ly, lx + inter.width, ly + inter.height,
                                imgX, imgY, imgX + inter.width, imgY + inter.height,
                                null,
                            )

                            // Outline border
                            val localSelX = rx - bounds.x
                            val localSelY = ry - bounds.y
                            g2.color = Color(0x38, 0x9F, 0xD6)
                            g2.stroke = BasicStroke(2f)
                            g2.drawRect(localSelX, localSelY, rw, rh)

                            // Dimension badge
                            if (rw > 30 && rh > 20) {
                                val label = "$rw × $rh"
                                val font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                                g2.font = font
                                val fm = g2.fontMetrics
                                val textW = fm.stringWidth(label)
                                val badgeW = textW + 16
                                val badgeH = 22

                                var badgeX = localSelX + rw - badgeW
                                var badgeY = localSelY + rh + 6
                                if (badgeY + badgeH > bounds.height) {
                                    badgeY = localSelY - badgeH - 6
                                }
                                if (badgeX < 4) badgeX = 4

                                if (badgeX in -badgeW..bounds.width && badgeY in -badgeH..bounds.height) {
                                    g2.color = Color(0, 0, 0, 190)
                                    g2.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 10, 10)
                                    g2.color = Color.WHITE
                                    g2.drawString(label, badgeX + 8, badgeY + fm.ascent + 2)
                                }
                            }
                        }
                    } else {
                        // Hint banner at the top of each monitor
                        val hint = "Select an area to show agent  ·  Esc to cancel"
                        val font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
                        g2.font = font
                        val fm = g2.fontMetrics
                        val textW = fm.stringWidth(hint)
                        val badgeW = textW + 28
                        val badgeH = 30
                        val badgeX = (bounds.width - badgeW) / 2
                        val badgeY = 32

                        g2.color = Color(0, 0, 0, 190)
                        g2.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 14, 14)
                        g2.color = Color(0xDD, 0xDD, 0xDD)
                        g2.drawString(hint, badgeX + 14, badgeY + fm.ascent + (badgeH - fm.height) / 2)
                    }
                }
            }

            panel.addMouseListener(mouseAdapter)
            panel.addMouseMotionListener(mouseAdapter)
            panel.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        closeAll()
                    }
                }
            })

            dialog.contentPane = panel
            dialog.rootPane.registerKeyboardAction(
                { closeAll() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW,
            )

            dialogs.add(dialog)
        }

        for (d in dialogs) {
            d.isVisible = true
        }

        // Focus dialog containing cursor (or the first one)
        val mouseLoc = MouseInfo.getPointerInfo()?.location
        val activeDialog = dialogs.firstOrNull { it.bounds.contains(mouseLoc ?: Point(0, 0)) } ?: dialogs.firstOrNull()
        activeDialog?.toFront()
        activeDialog?.requestFocus()
    }
}
