package iondrive.nop.index

import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-project tally of how many times each file has been opened from the double-shift file search.
 * Feeds [iondrive.nop.ui.FileSearchRanking] so the most-frequently-accessed files fill the top
 * slots when the search box first opens (before the user types anything).
 *
 * Immutable: [record] returns a new instance so it drops straight into Compose state. Persisted as
 * TSV (`count<TAB>relative/path`) under the project data dir; lines that don't parse are skipped so
 * a partially-corrupt file never takes the feature offline.
 */
class AccessFrequency(val counts: Map<String, Int> = emptyMap()) {

    /** Returns a copy with [path]'s access count incremented by one. */
    fun record(path: String): AccessFrequency {
        if (path.isEmpty()) return this
        return AccessFrequency(counts + (path to ((counts[path] ?: 0) + 1)))
    }

    companion object {
        fun load(path: Path): AccessFrequency {
            if (!Files.isRegularFile(path)) return AccessFrequency()
            val text = runCatching { Files.readString(path) }.getOrNull() ?: return AccessFrequency()
            val map = LinkedHashMap<String, Int>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val count = line.substring(0, tab).toIntOrNull() ?: continue
                val file = line.substring(tab + 1)
                if (count > 0 && file.isNotEmpty()) map[file] = count
            }
            return AccessFrequency(map)
        }

        fun save(path: Path, freq: AccessFrequency) {
            runCatching {
                Files.createDirectories(path.parent)
                val body = freq.counts.entries
                    .filter { it.value > 0 }
                    .sortedByDescending { it.value }
                    .joinToString("\n") { "${it.value}\t${it.key}" }
                Files.writeString(path, body)
            }
        }
    }
}
