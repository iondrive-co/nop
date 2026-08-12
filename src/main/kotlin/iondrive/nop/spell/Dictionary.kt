package iondrive.nop.spell

import iondrive.nop.Log
import iondrive.nop.Settings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/**
 * The words nop will not underline.
 *
 * Three layers, unioned into one lowercase set: the bundled English list (British, American and
 * Australian spellings merged — see scripts/build-dictionary.sh), a hand-kept list of programming
 * jargon the English dictionaries never carried, and the user's own words at
 * `$XDG_CONFIG_HOME/nop/dictionary`, which is what "Add to dictionary" appends to.
 *
 * Loading is lazy and happens on whichever thread asks first. That thread is never the UI thread in
 * practice — the editor spellchecks on a background dispatcher (see TabbedViewerPanel) — but the
 * set is published through a @Volatile field so a second thread arriving mid-load either waits on
 * the lock or sees a fully-built set, never a half-filled one.
 */
object Dictionary {
    private const val BUNDLED_WORDS = "/dictionary/words.txt.gz"
    private const val TECH_WORDS = "/dictionary/tech.txt"

    /** Bundled layers, loaded once per process — they cannot change while nop is running. */
    @Volatile private var bundled: Set<String>? = null

    /** Bundled + user words. Dropped when the user adds a word so the next lookup rebuilds it. */
    @Volatile private var combined: Set<String>? = null

    private val lock = Any()

    private val userFile: Path get() = Settings.configRoot.resolve("nop").resolve("dictionary")

    /**
     * Whether [word] is spelled correctly. Case is ignored, and a word the list only holds in its
     * singular form is still accepted in the plural: the bundled English list carries its own
     * inflections, but "repos" and "params" would otherwise have to be spelled out beside every
     * jargon word in tech.txt and every word the user adds.
     */
    fun knows(word: String): Boolean {
        val w = word.lowercase()
        val words = words()
        if (w in words) return true
        // "the parser's job" — a possessive is the noun plus punctuation, not a different word.
        val bare = w.removeSuffix("'s")
        if (bare in words) return true
        return when {
            bare.endsWith("ies") && bare.dropLast(3) + "y" in words -> true
            bare.endsWith("es") && bare.dropLast(2) in words -> true
            bare.endsWith("s") && bare.dropLast(1) in words -> true
            else -> false
        }
    }

    /**
     * Adds [word] to the user dictionary, on disk and in memory. Lowercased on the way in, since
     * lookups are case-insensitive and a stored "Kubernetes" would otherwise read as if case
     * mattered. A word already known is not written again, so repeatedly accepting the same word
     * can't grow the file.
     */
    fun add(word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty() || knows(w)) return
        synchronized(lock) {
            runCatching {
                val f = userFile
                Files.createDirectories(f.parent)
                Files.writeString(
                    f,
                    w + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }.onFailure { Log.error("could not add \"$w\" to the user dictionary at $userFile", it) }
            // Held in memory even if the write failed: the squiggle the user just dismissed should
            // go away for this session regardless of whether it will still be gone tomorrow.
            combined = (combined ?: loadBundled()) + w
        }
    }

    /**
     * Loads the word lists if they aren't loaded yet. Call from a background thread at startup: the
     * first lookup otherwise pays for ~90k words of parsing, and in a diff — where checking happens
     * during composition, a line at a time — that bill would land on the UI thread.
     */
    fun warmUp() {
        words()
    }

    /** The user's own words, in the order they were added. Empty when nothing has been added. */
    fun userWords(): List<String> = readWords(userFile)

    /** Drops the in-memory sets. For tests, which point [Settings.configRoot] at a temp directory. */
    internal fun invalidate() {
        synchronized(lock) { combined = null }
    }

    /** Every word the dictionary holds, for [suggestionsFor] to search. Loads it if it hasn't been. */
    internal fun words(): Set<String> {
        combined?.let { return it }
        return synchronized(lock) {
            combined ?: (loadBundled() + readWords(userFile).map { it.lowercase() }).also { combined = it }
        }
    }

    private fun loadBundled(): Set<String> {
        bundled?.let { return it }
        return synchronized(lock) {
            bundled ?: buildBundled().also { bundled = it }
        }
    }

    private fun buildBundled(): Set<String> {
        val started = System.nanoTime()
        // Sized for the ~90k bundled words so the load doesn't rehash its way there.
        val words = HashSet<String>(1 shl 17)
        readResource(BUNDLED_WORDS, gzipped = true) { words += it }
        readResource(TECH_WORDS, gzipped = false) { words += it }
        Log.info("spellcheck dictionary loaded: ${words.size} words in ${(System.nanoTime() - started) / 1_000_000}ms")
        return words
    }

    /**
     * Streams a classpath word list, one word per line, skipping blanks and `#` comments. A missing
     * or unreadable resource is logged and left empty rather than thrown: a jar built without the
     * dictionary should spellcheck nothing, not fail to open files.
     */
    private fun readResource(path: String, gzipped: Boolean, consume: (String) -> Unit) {
        runCatching {
            val stream = Dictionary::class.java.getResourceAsStream(path)
            if (stream == null) {
                Log.warn("spellcheck dictionary resource missing: $path")
                return
            }
            val raw = if (gzipped) GZIPInputStream(stream) else stream
            BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach { line ->
                    val word = line.trim()
                    if (word.isNotEmpty() && !word.startsWith("#")) consume(word.lowercase())
                }
            }
        }.onFailure { Log.error("could not read spellcheck dictionary $path", it) }
    }

    private fun readWords(file: Path): List<String> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching { Files.readAllLines(file) }.getOrElse {
            Log.error("could not read the user dictionary at $file", it)
            emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
