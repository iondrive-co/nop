package iondrive.nop.index

import java.nio.file.Files
import java.nio.file.Path

enum class SymbolKind {
    ANSIBLE_ROLE,
    ANSIBLE_TASKS,
    ANSIBLE_TEMPLATE,
    ANSIBLE_VAR,
    TS_SYMBOL,
    KOTLIN_SYMBOL,
    // Java entries come from a real parse tree rather than a line regex, so they carry members —
    // methods and fields — that the regex kinds above can't see. See iondrive.nop.lang.JavaSymbols.
    JAVA_TYPE,
    JAVA_METHOD,
    JAVA_FIELD,
}

data class IndexEntry(
    val name: String,
    /** Path relative to the project root, using forward slashes. */
    val file: String,
    /** 1-based line number of the definition. */
    val line: Int,
    val kind: SymbolKind,
    /**
     * What encloses this definition, for the kinds that have an answer: the fully-qualified name of
     * the type holding a Java member, or the package holding a top-level Java type. Empty for
     * everything else.
     *
     * This is what lets a lookup distinguish two same-named members of different classes — without
     * it, an index is a bag of bare words and every `size` in the project is the same symbol.
     */
    val owner: String = "",
    /** For a [SymbolKind.JAVA_TYPE], its `extends`/`implements` names as written in the source. */
    val supertypes: List<String> = emptyList(),
) {
    /** Dotted name: the owner-qualified name, or just [name] when there is no owner. */
    val fqn: String get() = if (owner.isEmpty()) name else "$owner.$name"
}

/**
 * In-memory lookup over a list of [IndexEntry].
 *
 * Stored on disk as TSV — `name<TAB>file<TAB>line<TAB>kind<TAB>owner<TAB>supertypes`, one entry per
 * line, supertypes comma-separated — behind a
 * version header. Lines that don't parse are skipped so a partially-corrupt cache doesn't take the
 * lookup offline; a cache written by an older nop, whose lines mean something different, is rejected
 * whole by the header check instead. That distinction matters: silently reading a stale format would
 * leave a project with an empty index and no rebuild, because the freshness probe would see a cache
 * file that is perfectly up to date.
 */
class SymbolIndex(private val entries: List<IndexEntry> = emptyList()) {
    // Sized exactly rather than left as groupBy builds it: most names are defined once, and
    // groupBy gives each its own ten-slot ArrayList — ~6 MB of empty slots on a large Java project,
    // held for as long as the project is open.
    private val byName: Map<String, List<IndexEntry>> = HashMap<String, List<IndexEntry>>().also { map ->
        for ((name, group) in entries.groupBy { it.name }) {
            map[name] = if (group.size == 1) listOf(group[0]) else ArrayList(group)
        }
    }

    val size: Int get() = entries.size

    fun lookup(name: String): List<IndexEntry> = byName[name].orEmpty()

    fun all(): List<IndexEntry> = entries

    /** Java types that declare a supertype, keyed by that supertype's simple name. */
    private val bySupertypeName: Map<String, List<Pair<IndexEntry, String>>> by lazy {
        entries.asSequence()
            .filter { it.kind == SymbolKind.JAVA_TYPE }
            .flatMap { entry -> entry.supertypes.asSequence().map { entry to it } }
            .groupBy({ it.second.substringAfterLast('.') }, { it })
    }

    /**
     * The Java types that directly extend or implement the type whose fully-qualified name is [fqn].
     *
     * A supertype is matched by the name its subtype wrote: a qualified `extends a.b.Base` must be a
     * suffix of [fqn], and a bare `extends Base` matches any type called `Base`. Without a classpath
     * that is as far as a name can be followed, so two same-named types in different packages share
     * their subtypes here.
     */
    fun subtypesOf(fqn: String): List<IndexEntry> {
        val simple = fqn.substringAfterLast('.')
        return bySupertypeName[simple].orEmpty()
            .filter { (entry, written) -> (fqn == written || fqn.endsWith(".$written")) && entry.fqn != fqn }
            .map { it.first }
            .distinct()
    }

    companion object {
        /**
         * Bumped whenever a line means something new. A cache stamped with anything else is treated
         * as absent, which forces a rebuild rather than a silent downgrade.
         */
        private const val HEADER = "#nop-index 3"

        /** The cached index at [path], or null when there isn't one this nop can read. */
        fun load(path: Path): SymbolIndex? {
            if (!Files.isRegularFile(path)) return null
            val text = runCatching { Files.readString(path) }.getOrNull() ?: return null
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != HEADER) return null
            val parsed = lines.asSequence().mapNotNull { parseLine(it) }.toList()
            return SymbolIndex(parsed)
        }

        fun save(path: Path, index: SymbolIndex) {
            runCatching {
                Files.createDirectories(path.parent)
                val body = index.all().joinToString("\n") {
                    "${it.name}\t${it.file}\t${it.line}\t${it.kind.name}\t${it.owner}\t${it.supertypes.joinToString(",")}"
                }
                Files.writeString(path, "$HEADER\n$body")
            }
        }

        private fun parseLine(line: String): IndexEntry? {
            if (line.isBlank()) return null
            val parts = line.split('\t')
            if (parts.size != 6) return null
            val ln = parts[2].toIntOrNull() ?: return null
            val kind = runCatching { SymbolKind.valueOf(parts[3]) }.getOrNull() ?: return null
            val supertypes = if (parts[5].isEmpty()) emptyList() else parts[5].split(',')
            return IndexEntry(parts[0], parts[1], ln, kind, parts[4], supertypes)
        }
    }
}
