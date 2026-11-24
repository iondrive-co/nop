package iondrive.nop

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Why a file or git blob cannot be loaded into a text editor or diff view. */
sealed interface TextFileRefusal {
    val sizeBytes: Long?

    data class Binary(override val sizeBytes: Long?) : TextFileRefusal

    data class TooLarge(override val sizeBytes: Long, val limitBytes: Long) : TextFileRefusal
}

/** Result of reading a file or git blob for display in a text surface. */
sealed interface TextFileRead {
    data class Content(val text: String) : TextFileRead

    data object Missing : TextFileRead

    data class Refused(val reason: TextFileRefusal) : TextFileRead

    data class Unreadable(val message: String) : TextFileRead
}

/**
 * Guardrails for deciding whether a file or git blob can safely be loaded into text editors,
 * previews, and diff views.
 *
 * Text surfaces lay out their document in heap and Skia; passing a binary archive or an oversized
 * file freezes the shared UI thread and inflates memory. Checking known binary extensions, sniffing
 * only the leading bytes for NUL, and capping reads to [MAX_TEXT_BYTES] rejects those inputs before
 * their full bytes are allocated.
 */
object TextFiles {
    /** Largest file or git blob loaded into a text editor, preview, or diff view. */
    const val MAX_TEXT_BYTES: Long = 2L * 1024 * 1024

    /** Leading bytes inspected for NUL to detect binary files without a binary extension. */
    const val BINARY_SNIFF_BYTES: Int = 8192

    /**
     * Extensions that are always binary — images, media, archives, compiled artefacts, fonts,
     * office documents, databases, and serialized data blobs. Checked before reading any bytes.
     * Text formats (svg, json, csv, xml, html, …) are omitted so they stay editable; files with
     * unlisted extensions still go through the NUL-byte content sniff.
     */
    val BINARY_EXTENSIONS: Set<String> = setOf(
        // images
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "icns", "webp", "tiff", "tif", "heic", "avif", "psd",
        // video / audio
        "mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "mp3", "wav", "flac", "ogg", "opus", "m4a", "aac",
        // archives & packages
        "zip", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "tzst", "tar", "rar", "7z", "lz4", "lzma",
        "jar", "war", "ear", "jmod", "whl", "deb", "rpm", "apk", "ipa", "dmg", "pkg", "msi", "iso", "img",
        "appimage",
        // compiled / executables
        "class", "o", "obj", "a", "lib", "so", "dll", "dylib", "exe", "bin", "wasm", "node",
        "pyc", "pyo", "pdb", "elf", "ko", "dex",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub",
        // fonts
        "woff", "woff2", "ttf", "otf", "eot",
        // databases / columnar / array / model blobs
        "db", "sqlite", "sqlite3", "duckdb",
        "parquet", "arrow", "feather", "orc", "avro", "npy", "npz", "pkl", "pickle", "h5", "hdf5",
        "pt", "pth", "onnx", "safetensors", "gguf",
    )

    /** True when [pathOrName]'s extension is in [BINARY_EXTENSIONS]. */
    fun hasBinaryExtension(pathOrName: String): Boolean {
        val name = pathOrName.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in BINARY_EXTENSIONS
    }

    /** Treats content as binary when a NUL byte appears in the first [BINARY_SNIFF_BYTES] bytes. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean =
        containsNul(bytes, minOf(length, bytes.size, BINARY_SNIFF_BYTES))

    private fun containsNul(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, bytes.size)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    /** Treats already-decoded text as binary when a NUL character appears in its leading characters. */
    fun looksBinaryText(text: String): Boolean {
        val n = minOf(text.length, BINARY_SNIFF_BYTES)
        for (i in 0 until n) if (text[i] == '\u0000') return true
        return false
    }

    /**
     * Reads [file] as UTF-8 text if it is a regular, non-binary file within [maxBytes].
     *
     * Known binary extensions are refused after a single stat with zero bytes read. Any other
     * regular file reads at most [BINARY_SNIFF_BYTES] before refusing a binary or oversized file,
     * and bounds the full read so a file growing concurrently cannot allocate unbounded memory.
     */
    fun read(file: File, maxBytes: Long = MAX_TEXT_BYTES): TextFileRead {
        val path = file.toPath()
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (t: Throwable) {
            return if (!Files.exists(path)) {
                TextFileRead.Missing
            } else {
                TextFileRead.Unreadable(t.message ?: t::class.simpleName ?: "Could not inspect ${file.name}")
            }
        }
        if (!attrs.isRegularFile) {
            return TextFileRead.Unreadable(
                if (attrs.isDirectory) "${file.name} is a directory" else "${file.name} is not a regular file",
            )
        }
        val size = attrs.size()
        if (hasBinaryExtension(file.name)) {
            return TextFileRead.Refused(TextFileRefusal.Binary(size))
        }
        return readStream(file.name, knownSize = size, maxBytes = maxBytes) { file.inputStream() }
    }

    /**
     * Reads UTF-8 text from a stream opened lazily by [openStream], enforcing the same binary and
     * [maxBytes] guards as [read]. Known binary extensions return [TextFileRead.Refused] without
     * calling [openStream].
     */
    fun readStream(
        pathOrName: String,
        knownSize: Long? = null,
        maxBytes: Long = MAX_TEXT_BYTES,
        openStream: () -> InputStream,
    ): TextFileRead {
        if (hasBinaryExtension(pathOrName)) {
            return TextFileRead.Refused(TextFileRefusal.Binary(knownSize))
        }
        return try {
            openStream().use { input ->
                val head = input.readNBytes(BINARY_SNIFF_BYTES)
                if (looksBinary(head)) {
                    return TextFileRead.Refused(TextFileRefusal.Binary(knownSize ?: head.size.toLong()))
                }
                if (knownSize != null && knownSize > maxBytes) {
                    return TextFileRead.Refused(TextFileRefusal.TooLarge(knownSize, maxBytes))
                }
                if (head.size.toLong() > maxBytes) {
                    return TextFileRead.Refused(
                        TextFileRefusal.TooLarge(maxOf(knownSize ?: 0L, head.size.toLong()), maxBytes),
                    )
                }
                if (head.size < BINARY_SNIFF_BYTES) {
                    return TextFileRead.Content(String(head, Charsets.UTF_8))
                }
                val remainingLimit = (maxBytes - head.size).coerceAtMost(Int.MAX_VALUE.toLong() - 1).toInt()
                val rest = input.readNBytes(remainingLimit + 1)
                val total = head.size.toLong() + rest.size.toLong()
                if (total > maxBytes) {
                    return TextFileRead.Refused(
                        TextFileRefusal.TooLarge(maxOf(knownSize ?: 0L, total), maxBytes),
                    )
                }
                if (containsNul(rest)) {
                    return TextFileRead.Refused(TextFileRefusal.Binary(maxOf(knownSize ?: 0L, total)))
                }
                val bytes = if (rest.isEmpty()) {
                    head
                } else {
                    ByteArray(head.size + rest.size).also { combined ->
                        head.copyInto(combined, destinationOffset = 0)
                        rest.copyInto(combined, destinationOffset = head.size)
                    }
                }
                TextFileRead.Content(String(bytes, Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            TextFileRead.Unreadable(t.message ?: t::class.simpleName ?: "Could not read $pathOrName")
        }
    }

    /** Human-readable byte count for refusal messages. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
