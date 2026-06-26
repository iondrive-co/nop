package iondrive.nop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * One row in the left project rail. Either a [Project] tab (a directory the workspace can switch
 * to) or a [Separator] — a standalone, user-named bold label used purely to visually group the
 * tabs below it. Separators carry no containment logic; they're just dividers the user can drag
 * anywhere in the rail.
 */
sealed interface RailItem {
    data class Project(val path: Path) : RailItem

    /**
     * [id] gives the separator a stable identity for drag-reorder keys and rename/remove, since two
     * separators may share a name. It is runtime-only — not persisted — so equality (used by
     * snapshot diffing and list comparisons) is by name alone.
     */
    class Separator(val name: String, val id: Long) : RailItem {
        override fun equals(other: Any?): Boolean = other is Separator && other.name == name
        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "Separator($name)"
    }
}

/**
 * Pure helpers for the rail's ordered list and its on-disk encoding, kept out of the Compose/IO
 * layers so they can be unit-tested directly.
 */
object RailLayout {
    private const val PROJECT_PREFIX = "project:"
    private const val SEPARATOR_PREFIX = "sep:"

    /** The project paths in rail order, dropping separators. */
    fun projects(items: List<RailItem>): List<Path> =
        items.filterIsInstance<RailItem.Project>().map { it.path }

    /**
     * Moves the item at [from] to index [to], shifting the rest. Out-of-range indices (or from==to)
     * return the list unchanged, so callers can wire it straight to a drag handler without guarding.
     */
    fun move(items: List<RailItem>, from: Int, to: Int): List<RailItem> {
        if (from == to || from !in items.indices || to !in items.indices) return items
        val out = items.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** State-file value for one rail row. Newlines in a name are flattened so the line format holds. */
    fun encode(item: RailItem): String = when (item) {
        is RailItem.Project -> PROJECT_PREFIX + item.path.toAbsolutePath().normalize().toString()
        is RailItem.Separator -> SEPARATOR_PREFIX + item.name.replace('\n', ' ').replace('\r', ' ')
    }

    /**
     * Parses one encoded rail row. Separators are assigned [separatorId] (the caller supplies a
     * unique id per row). Returns null for a malformed project path or unrecognized prefix so a
     * single bad line is skipped rather than aborting the whole load.
     */
    fun decode(value: String, separatorId: Long): RailItem? = when {
        value.startsWith(PROJECT_PREFIX) ->
            runCatching { Paths.get(value.removePrefix(PROJECT_PREFIX)) }.getOrNull()?.let { RailItem.Project(it) }
        value.startsWith(SEPARATOR_PREFIX) ->
            RailItem.Separator(value.removePrefix(SEPARATOR_PREFIX), separatorId)
        else -> null
    }
}
