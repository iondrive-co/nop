package iondrive.nop.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/**
 * The project tree's Ctrl+C / Ctrl+V clipboard.
 *
 * Copies go to the *system* clipboard as a file list, so a Ctrl+C in the tree can be pasted into
 * Thunar/Nautilus/Finder, and files copied there can be pasted back into the tree. The last
 * in-app copy is also kept in memory as a fallback for when the system clipboard can't be read
 * (headless test runs, or another app holding content that isn't a file list) — so copy-then-paste
 * inside nop always works, even if something else has since claimed the clipboard.
 */
object FileClipboard {
    @Volatile
    private var lastCopied: List<File> = emptyList()

    /** Put [files] on the clipboard. Empty lists are ignored so a stray Ctrl+C can't clear it. */
    fun copy(files: List<File>) {
        val absolute = files.map { it.absoluteFile }
        if (absolute.isEmpty()) return
        lastCopied = absolute
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(FileListTransferable(absolute), null)
        }
    }

    /** What a paste should copy: the system clipboard's file list, else the last in-app copy. */
    fun files(): List<File> = systemFiles() ?: lastCopied

    /** Whether a paste would do anything — drives whether the context menu offers "Paste". */
    fun hasFiles(): Boolean = files().isNotEmpty()

    // Only for tests: forget the in-app fallback so one case can't leak into the next.
    internal fun clear() {
        lastCopied = emptyList()
    }

    // null (rather than empty) when the clipboard holds something that isn't a file list, so
    // callers can tell "nothing to read here" from "read it, it was empty" and fall back.
    private fun systemFiles(): List<File>? = runCatching {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
        @Suppress("UNCHECKED_CAST")
        (contents.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
            .map { it.absoluteFile }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
        return files
    }
}
