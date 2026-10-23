package iondrive.nop.recovery

import androidx.compose.runtime.mutableStateMapOf
import iondrive.nop.Log
import iondrive.nop.Settings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Tabs nop will not draw until the user says to, because drawing them went wrong.
 *
 * A tab is put here when the window showing it failed (see [WindowRecovery]) or when a launch died
 * before it was up while the tab was on screen (see [LaunchJournal]). Either way the tab stays open,
 * and selecting it shows what happened and lets the user try again, rather than doing the same
 * thing again: a tab is restored on every launch, so one that takes its window down would otherwise
 * take every launch down with it. That is how nop once could not start at all — a diff with a
 * 70k-character line, reopened each time.
 *
 * Kept on disk because a launch that dies is exactly when it is needed; written as it changes, not
 * on the way out. Keys are [iondrive.nop.ui.recoveryKey]s, which name a tab without its project.
 */
object Quarantine {
    /** One tab held back: when, and the error to show for it — a single line. */
    data class Entry(val key: String, val at: Long, val reason: String)

    private val entries = mutableStateMapOf<String, Entry>()

    /** Where the entries live: beside nop's other state, unless a test points it somewhere of its own. */
    internal var fileOverride: Path? = null
    private val file: Path get() = fileOverride ?: Settings.configRoot.resolve("nop").resolve("quarantine")

    /** Reads the entries off disk, replacing any held. Once, at startup, before any window opens. */
    fun load() {
        val loaded = runCatching { if (Files.exists(file)) decode(Files.readString(file)) else emptyList() }
            .onFailure { Log.error("could not read $file; starting with nothing held back", it) }
            .getOrDefault(emptyList())
        entries.clear()
        loaded.forEach { entries[it.key] = it }
    }

    /** Why [key] is held back, or null when it may be drawn. Reads snapshot state: a composition re-reads it on change. */
    fun entry(key: String): Entry? = entries[key]

    /** Holds back each of [keys] for [reason], keeping the first reason of one already held. */
    fun add(keys: Collection<String>, reason: String, at: Long = System.currentTimeMillis()) {
        val fresh = keys.filter { it !in entries }
        if (fresh.isEmpty()) return
        val line = oneLine(reason)
        fresh.forEach { entries[it] = Entry(it, at, line) }
        Log.warn("holding back ${fresh.joinToString()}: $line")
        save()
    }

    /** Lets [key] be drawn again — the user's "Try again", or the tab closing. */
    fun release(key: String) {
        if (entries.remove(key) != null) save()
    }

    internal fun clearForTest() = entries.clear()

    private fun save() {
        val body = encode(entries.values.sortedBy { it.at })
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, body)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.error("could not write $file", it) }
    }

    /** `key<TAB>epochMillis<TAB>reason`, one per line. Keys hold paths, which may hold tabs: escaped. */
    internal fun encode(entries: Collection<Entry>): String =
        entries.joinToString("") { "${escape(it.key)}\t${it.at}\t${escape(it.reason)}\n" }

    internal fun decode(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3) return@mapNotNull null
        val at = parts[1].toLongOrNull() ?: return@mapNotNull null
        Entry(unescape(parts[0]), at, unescape(parts[2]))
    }.toList()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun oneLine(s: String) = s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(MAX_REASON)

    private const val MAX_REASON = 500
}
