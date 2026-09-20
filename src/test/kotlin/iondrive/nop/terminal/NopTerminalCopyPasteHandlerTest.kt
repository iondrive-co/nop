package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class NopTerminalCopyPasteHandlerTest {

    private val handler = NopTerminalCopyPasteHandler()

    @Test
    fun `pasting text returns the text`() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection("hello terminal"), null)

        val result = handler.getContents(false)
        assertEquals("hello terminal", result)
    }

    @Test
    fun `pasting an image writes to temp and returns the quoted path with trailing space`() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)

        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor?): Any {
                if (flavor == DataFlavor.imageFlavor) return img
                throw UnsupportedFlavorException(flavor)
            }
        }

        clipboard.setContents(transferable, null)

        val result = handler.getContents(false)
        assertNotNull(result)
        assertTrue(result!!.endsWith(".png "), "expected path ending in .png but got: $result")
        val cleanPath = result.trim().removeSurrounding("'")
        val path = Path.of(cleanPath)
        assertTrue(Files.exists(path), "saved image file does not exist: $path")

        Files.deleteIfExists(path)
    }

    @Test
    fun `pasting files returns quoted paths with trailing space`() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val file1 = File("/tmp/test file 1.txt")
        val file2 = File("/tmp/test2.txt")

        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor?): Any {
                if (flavor == DataFlavor.javaFileListFlavor) return listOf(file1, file2)
                throw UnsupportedFlavorException(flavor)
            }
        }

        clipboard.setContents(transferable, null)

        val result = handler.getContents(false)
        assertEquals("'/tmp/test file 1.txt' /tmp/test2.txt ", result)
    }
}
