package iondrive.nop.index

import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A single match within a file. [matchStart]/[matchEnd] are char offsets within [lineText]. */
data class SearchHit(
    val path: String,
    val line: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int,
)

/**
 * Case-insensitive literal scan over the project file index. Reads each file off the EDT,
 * skipping anything too big to plausibly be source, anything with a known-binary extension, and
 * anything whose first few KB contain a NUL byte — so images, archives, and compiled blobs don't
 * tank a search (or pollute it with garbage matches).
 *
 * Files are scanned in parallel across a small worker pool; results are merged into a deterministic
 * order at the end, independent of which worker finished first.
 *
 * The scan is cancellation-aware — call sites that re-search on every keystroke can wrap this in
 * [kotlinx.coroutines.flow.collectLatest] and trust that the prior coroutine drops out promptly.
 */
object SearchEngine {
    /** Same cutoff [iondrive.nop.index.Indexer] uses for source readability. */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    /** Cap per-file matches so a query that hits "the" in a giant log doesn't OOM the panel. */
    private const val MAX_HITS_PER_FILE = 200
    /** Cap total matches so the result list stays scrollable and the UI stays responsive. */
    const val MAX_TOTAL_HITS = 2000
    /** Probe this many leading bytes for a NUL — the cheap "is this binary?" heuristic git uses. */
    private const val BINARY_SNIFF_BYTES = 8192

    /**
     * Extensions never worth scanning for text — images, media, archives, compiled artefacts, fonts,
     * office documents, and data blobs. These are skipped without being read at all, so a project
     * full of assets doesn't drag the search down. Text-ish formats (svg, json, csv, …) are absent
     * on purpose so they stay searchable; anything not listed still gets the NUL-byte content check.
     */
    private val BINARY_EXTENSIONS = setOf(
        // images
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "icns", "webp", "tiff", "tif", "heic", "avif", "psd",
        // video / audio
        "mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "mp3", "wav", "flac", "ogg", "m4a", "aac",
        // archives
        "zip", "gz", "tgz", "bz2", "xz", "zst", "tar", "rar", "7z", "jar", "war", "ear",
        // compiled / executables
        "class", "o", "obj", "a", "lib", "so", "dll", "dylib", "exe", "bin", "wasm", "node",
        "pyc", "pyo", "pdb",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
        // fonts
        "woff", "woff2", "ttf", "otf", "eot",
        // databases / opaque data
        "db", "sqlite", "sqlite3", "dat",
    )

    suspend fun search(
        projectRoot: Path,
        files: List<String>,
        query: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<SearchHit> {
        if (query.isEmpty()) return emptyList()
        val root = projectRoot.toAbsolutePath().normalize().toFile()
        val needle = query.lowercase()
        return withContext(dispatcher) {
            val total = AtomicInteger(0)
            // A handful of workers saturates disk + parsing without thrashing; cap so a big-core box
            // doesn't spawn dozens of competing readers.
            val parallelism = minOf(8, maxOf(2, Runtime.getRuntime().availableProcessors()))
            // Round-robin files across the pool so a clump of large files in one directory doesn't
            // land entirely on one worker.
            val buckets = Array(parallelism) { ArrayList<String>() }
            files.forEachIndexed { i, rel -> buckets[i % parallelism].add(rel) }
            val merged = ArrayList(coroutineScope {
                buckets.map { bucket ->
                    async {
                        val local = ArrayList<SearchHit>()
                        for (rel in bucket) {
                            ensureActive()
                            // Coarse global-cap check between files; final list is trimmed exactly.
                            if (total.get() >= MAX_TOTAL_HITS) break
                            val added = scanFile(root, rel, needle, local)
                            if (added > 0) total.addAndGet(added)
                        }
                        local
                    }
                }.awaitAll()
            }.flatten())
            // Deterministic order regardless of worker completion: by path, then line, then column.
            merged.sortWith(compareBy({ it.path.lowercase() }, { it.line }, { it.matchStart }))
            if (merged.size > MAX_TOTAL_HITS) ArrayList(merged.subList(0, MAX_TOTAL_HITS)) else merged
        }
    }

    /** Scans one file into [out], returning the number of hits added (0 if skipped). */
    private fun scanFile(root: File, rel: String, needle: String, out: MutableList<SearchHit>): Int {
        if (hasBinaryExtension(rel)) return 0
        val file = File(root, rel)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return 0
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return 0
        if (looksBinary(bytes)) return 0
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return 0
        return scanText(rel, text, needle, out)
    }

    /** True when [rel]'s extension is in the never-scan [BINARY_EXTENSIONS] denylist. */
    private fun hasBinaryExtension(rel: String): Boolean {
        val name = rel.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot + 1).lowercase() in BINARY_EXTENSIONS
    }

    /** Treats a file as binary if a NUL byte appears in its first [BINARY_SNIFF_BYTES] bytes. */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, BINARY_SNIFF_BYTES)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    private fun scanText(rel: String, text: String, needle: String, out: MutableList<SearchHit>): Int {
        var perFile = 0
        var lineNumber = 1
        var lineStart = 0
        val len = text.length
        var i = 0
        while (i <= len) {
            val isEnd = i == len
            val ch = if (isEnd) '\n' else text[i]
            if (ch == '\n') {
                // Strip a trailing \r so Windows line endings don't bleed into the highlight.
                val lineEnd = if (i > lineStart && text[i - 1] == '\r') i - 1 else i
                val line = text.substring(lineStart, lineEnd)
                perFile += findInLine(rel, lineNumber, line, needle, MAX_HITS_PER_FILE - perFile, out)
                if (perFile >= MAX_HITS_PER_FILE) return perFile
                lineNumber++
                lineStart = i + 1
            }
            i++
        }
        return perFile
    }

    private fun findInLine(
        rel: String,
        lineNumber: Int,
        line: String,
        needle: String,
        remaining: Int,
        out: MutableList<SearchHit>,
    ): Int {
        if (remaining <= 0) return 0
        val haystack = line.lowercase()
        var from = 0
        var added = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            out += SearchHit(
                path = rel,
                line = lineNumber,
                lineText = line,
                matchStart = at,
                matchEnd = at + needle.length,
            )
            added++
            if (added >= remaining) break
            from = at + maxOf(needle.length, 1)
        }
        return added
    }
}
