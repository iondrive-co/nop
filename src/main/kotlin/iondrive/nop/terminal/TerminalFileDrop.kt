package iondrive.nop.terminal

import iondrive.nop.Log
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Makes a terminal accept files dropped onto it, typing their paths in as if the user had.
 *
 * A path is the right answer rather than a poor substitute for one: it is how both vendor CLIs are
 * given a picture — `claude` and `codex` read an image off disk when a prompt names it — and how
 * every other terminal on the desktop handles a drop. So a screenshot dragged from the file manager
 * onto a running agent lands in its prompt ready to send.
 *
 * Three kinds of drop arrive at a terminal and all three end up as paths:
 *  - a file list, which is what a file manager offers;
 *  - a `text/uri-list`, which is what a good deal of GTK/Qt software offers instead, and whose
 *    `file:` entries are the same thing said differently;
 *  - raw image bytes, which is what dragging a picture straight out of a browser or an image viewer
 *    gives — there is no file to name, so [imageFile] writes one and the path names that.
 *
 * A remote `http:` URI is deliberately not followed. Downloading whatever a drag happens to point
 * at is a network fetch the user did not ask for, and the path that came back would be of something
 * nop chose to go and get.
 */
internal class TerminalFileDrop(private val onPaths: (List<String>) -> Unit) : DropTargetAdapter() {

    override fun dragEnter(event: DropTargetDragEvent) = acceptOrReject(event)

    override fun dragOver(event: DropTargetDragEvent) = acceptOrReject(event)

    /**
     * Shows the copy cursor over a drag carrying something usable, and the "no" cursor over one that
     * doesn't — dragging a picture onto a terminal that would silently ignore it is worse than being
     * told before the mouse button comes up.
     */
    private fun acceptOrReject(event: DropTargetDragEvent) {
        if (USABLE.any { event.isDataFlavorSupported(it) }) {
            event.acceptDrag(DnDConstants.ACTION_COPY)
        } else {
            event.rejectDrag()
        }
    }

    override fun drop(event: DropTargetDropEvent) {
        if (USABLE.none { event.isDataFlavorSupported(it) }) {
            event.rejectDrop()
            return
        }
        // The transferable is only readable after the drop is accepted, so this cannot be hoisted
        // above the accept to decide whether to take the drop at all.
        event.acceptDrop(DnDConstants.ACTION_COPY)
        val paths = runCatching { pathsFrom(event.transferable) }
            .onFailure { Log.warn("could not read the dropped files: $it") }
            .getOrDefault(emptyList())
        event.dropComplete(paths.isNotEmpty())
        if (paths.isNotEmpty()) onPaths(paths)
    }

    private fun pathsFrom(transferable: Transferable): List<String> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            if (files.isNotEmpty()) return files.map { it.absolutePath }
        }
        if (transferable.isDataFlavorSupported(URI_LIST)) {
            val uris = (transferable.getTransferData(URI_LIST) as String).let(::localPaths)
            if (uris.isNotEmpty()) return uris
        }
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image
            saveImageToTemp(image)?.let { return listOf(it.toAbsolutePath().toString()) }
        }
        return emptyList()
    }

    /** The `file:` entries of a `text/uri-list` payload, in order. Anything remote is dropped. */
    private fun localPaths(list: String): List<String> = list.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line -> runCatching { URI(line) }.getOrNull() }
        .filter { it.scheme == "file" }
        .mapNotNull { runCatching { Path.of(it).toAbsolutePath().toString() }.getOrNull() }
        .toList()

    private companion object {
        /**
         * The `text/uri-list` a good deal of Linux software offers. Asked for as a String rather
         * than through the `java.net.URI` list flavor, which the JDK only synthesises for some
         * sources — the raw text is always there when the flavor is.
         */
        val URI_LIST: DataFlavor = DataFlavor("text/uri-list;class=java.lang.String")

        val USABLE: List<DataFlavor> =
            listOf(DataFlavor.javaFileListFlavor, URI_LIST, DataFlavor.imageFlavor)
    }
}

/**
 * Writes an image out to the temp directory so there is a file path to name, and returns it.
 *
 * Under the system temp directory rather than in the project: a picture dragged in, pasted,
 * or snipped to be looked at is not part of the repository, and a file nop drops into the
 * working tree is one the user has to notice and delete — possibly after committing it.
 */
internal fun saveImageToTemp(image: Image?): Path? {
    if (image == null) return null
    val rendered = image as? RenderedImage ?: rasterizeImage(image) ?: return null
    return runCatching {
        val dir = Files.createDirectories(
            Path.of(System.getProperty("java.io.tmpdir"), "nop-drops"),
        )
        Files.createTempFile(dir, "image-", ".png").also { file ->
            Files.newOutputStream(file).use { out ->
                check(ImageIO.write(rendered, "png", out)) { "no PNG writer" }
            }
        }
    }.onFailure { Log.warn("could not save image: $it") }.getOrNull()
}

/** Copies a non-[RenderedImage] (a `ToolkitImage`, say) into one ImageIO can write. */
internal fun rasterizeImage(image: Image): RenderedImage? {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return null
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
        val g = copy.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
    }
}

/**
 * A dropped path as it should be typed into a prompt: quoted only when it has to be.
 *
 * Whitespace is the case that matters — an unquoted `/home/me/My Pictures/a.png` reaches the CLI as
 * two arguments and neither of them exists. Single quotes are what a shell and both vendor prompts
 * read the same way, and the `'\''` dance is how a single quote survives inside them.
 */
internal fun quoteForPrompt(path: String): String =
    if (path.none { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' }) path
    else "'" + path.replace("'", "'\\''") + "'"
