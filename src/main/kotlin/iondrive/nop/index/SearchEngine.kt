package iondrive.nop.index

import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
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
 * How far a running [SearchEngine.search] has got, readable from another thread while it runs.
 *
 * It exists for the search that is taking too long. A count of files read is what tells someone
 * waiting whether to keep waiting, and the hits found so far are what a stopped search still has
 * to show for itself — without them, stopping a scan twenty minutes in would throw away everything
 * it had found.
 */
class SearchProgress {
    /** Files looked at so far, skipped ones included, so it ends at the size of the list searched. */
    val scanned = AtomicInteger(0)
    private val found = ConcurrentLinkedQueue<SearchHit>()

    internal fun record(hits: List<SearchHit>) {
        if (hits.isNotEmpty()) found.addAll(hits)
    }

    /** The hits found so far, in the order a finished search would give them. */
    fun hitsSoFar(): List<SearchHit> = SearchEngine.ordered(found.toList())
}

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
        // columnar and array data, pickles — what a data-heavy repository holds most of
        "parquet", "arrow", "feather", "orc", "avro", "npy", "npz", "pkl", "pickle", "h5", "hdf5",
        "lz4", "whl",
    )

    suspend fun search(
        projectRoot: Path,
        files: List<String>,
        query: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        progress: SearchProgress? = null,
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
                            val before = local.size
                            val added = scanFile(root, rel, needle, local)
                            if (added > 0) {
                                total.addAndGet(added)
                                progress?.record(local.subList(before, local.size))
                            }
                            progress?.scanned?.incrementAndGet()
                        }
                        local
                    }
                }.awaitAll()
            }.flatten())
            ordered(merged)
        }
    }

    /**
     * Deterministic order regardless of worker completion — by path, then line, then column — cut
     * to [MAX_TOTAL_HITS].
     */
    internal fun ordered(hits: List<SearchHit>): List<SearchHit> {
        val sorted = hits.sortedWith(compareBy({ it.path.lowercase() }, { it.line }, { it.matchStart }))
        return if (sorted.size > MAX_TOTAL_HITS) sorted.subList(0, MAX_TOTAL_HITS).toList() else sorted
    }

    /** Scans one file into [out], returning the number of hits added (0 if skipped). */
    private fun scanFile(root: File, rel: String, needle: String, out: MutableList<SearchHit>): Int {
        if (hasBinaryExtension(rel)) return 0
        val file = File(root, rel)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return 0
        // The sniff comes before the rest of the file is read, not after. Most of what a large tree
        // holds is data, and a NUL in the first 8 KB settles a 2 MB file without reading the rest.
        val bytes = runCatching {
            file.inputStream().use { input ->
                val head = input.readNBytes(BINARY_SNIFF_BYTES)
                if (looksBinary(head)) null else head + input.readAllBytes()
            }
        }.getOrNull() ?: return 0
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

    /**
     * Finds [needle] in [text] with one lowercased copy of the whole file, going back to the line
     * only where there is a hit.
     *
     * Almost every file in a big search has no match at all, and for those the line-by-line scan
     * below paid a substring and a lowercased copy for every line just to learn that. Lowercasing
     * can change a string's length, though (a Turkish dotted capital I becomes two chars), and then
     * offsets into the copy stop pointing into [text] — a file like that takes the line-by-line
     * path instead.
     */
    private fun scanText(rel: String, text: String, needle: String, out: MutableList<SearchHit>): Int {
        // The query box is one line, and a match was only ever looked for within a line.
        if ('\n' in needle || '\r' in needle) return 0
        val haystack = text.lowercase()
        if (haystack.length != text.length) return scanLines(rel, text, needle, out)
        var perFile = 0
        var lineNumber = 1
        var counted = 0
        var from = 0
        while (perFile < MAX_HITS_PER_FILE) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            for (i in counted until at) if (text[i] == '\n') lineNumber++
            counted = at
            val lineStart = text.lastIndexOf('\n', at - 1) + 1
            var lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            // Strip a trailing \r so Windows line endings don't bleed into the highlight.
            if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
            out += SearchHit(
                path = rel,
                line = lineNumber,
                lineText = text.substring(lineStart, lineEnd),
                matchStart = at - lineStart,
                matchEnd = at - lineStart + needle.length,
            )
            perFile++
            from = at + maxOf(needle.length, 1)
        }
        return perFile
    }

    private fun scanLines(rel: String, text: String, needle: String, out: MutableList<SearchHit>): Int {
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
        // Offsets into a lowercased copy only point into [line] while the two are the same length,
        // and this path is taken exactly when some line in the file is not — so it compares in
        // place instead, which is slower and never needs a copy.
        var from = 0
        var added = 0
        while (true) {
            val at = line.indexOf(needle, from, ignoreCase = true)
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
