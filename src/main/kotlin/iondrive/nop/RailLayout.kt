package iondrive.nop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * One row in the left project rail. Either a [Project] tab (a directory the workspace can switch
 * to) or a [Separator] — a standalone, user-named bold label that heads the group of tabs below
 * it (down to the next separator). A separator is a lightweight divider the user can drag anywhere
 * in the rail; it can also be [collapsed], which hides the tabs in its group until it's expanded.
 *
 * The rail is a [GroupedStrip], which owns the block/drag/collapse maths the rows share with the
 * horizontal editor tab bar.
 */
sealed interface RailItem : GroupedEntry<RailItem> {
    data class Project(val path: Path) : RailItem {
        override val isHeader: Boolean get() = false
        override fun expanded(): RailItem = this
    }

    /**
     * [id] gives the separator a stable identity for drag-reorder keys and rename/remove, since two
     * separators may share a name. It is runtime-only — not persisted — so it's left out of equality
     * (used by snapshot diffing and list comparisons); [collapsed] *is* persisted and part of
     * equality, so toggling it registers as a change that gets saved.
     */
    class Separator(val name: String, val id: Long, override val collapsed: Boolean = false) : RailItem {
        override val isHeader: Boolean get() = true
        override fun expanded(): RailItem = Separator(name, id, collapsed = false)
        override fun equals(other: Any?): Boolean =
            other is Separator && other.name == name && other.collapsed == collapsed
        override fun hashCode(): Int = 31 * name.hashCode() + collapsed.hashCode()
        override fun toString(): String = "Separator($name, collapsed=$collapsed)"
    }
}

/**
 * Pure helpers for the rail's ordered list and its on-disk encoding, kept out of the Compose/IO
 * layers so they can be unit-tested directly. Reordering itself lives in [GroupedStrip].
 */
object RailLayout {
    private const val PROJECT_PREFIX = "project:"
    private const val SEPARATOR_PREFIX = "sep:"
    // A separate prefix for a collapsed separator keeps the format backward-compatible: rows written
    // by a pre-collapse build all use "sep:" and decode as expanded, which is the right default.
    private const val SEPARATOR_COLLAPSED_PREFIX = "sepc:"

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
        is RailItem.Separator ->
            (if (item.collapsed) SEPARATOR_COLLAPSED_PREFIX else SEPARATOR_PREFIX) +
                item.name.replace('\n', ' ').replace('\r', ' ')
    }

    /**
     * Parses one encoded rail row. Separators are assigned [separatorId] (the caller supplies a
     * unique id per row). Returns null for a malformed project path or unrecognized prefix so a
     * single bad line is skipped rather than aborting the whole load.
     */
    fun decode(value: String, separatorId: Long): RailItem? = when {
        value.startsWith(PROJECT_PREFIX) ->
            runCatching { Paths.get(value.removePrefix(PROJECT_PREFIX)) }.getOrNull()?.let { RailItem.Project(it) }
        value.startsWith(SEPARATOR_COLLAPSED_PREFIX) ->
            RailItem.Separator(value.removePrefix(SEPARATOR_COLLAPSED_PREFIX), separatorId, collapsed = true)
        value.startsWith(SEPARATOR_PREFIX) ->
            RailItem.Separator(value.removePrefix(SEPARATOR_PREFIX), separatorId)
        else -> null
    }
}
