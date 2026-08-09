package iondrive.nop.ui

import iondrive.nop.GroupedEntry
import iondrive.nop.GroupedStrip

/**
 * One group in the horizontal tab strip: a named, collapsible bucket owning a run of editor tabs.
 * Exactly one group is *active* at a time (see [TabsState.activeGroupId]) and everything the user
 * opens lands in it, so the groups work like the branches or reviews a session is split across —
 * hence the default name, "MR1".
 *
 * [id] is the group's stable identity for drag keys, membership and rename, since two groups may
 * share a name. It is runtime-only: the on-disk format identifies groups by position.
 */
data class TabGroup(val id: Long, val name: String, val collapsed: Boolean = false)

/**
 * One entry of the horizontal tab strip: a group [Header], or a [Slot] holding one tab of the group
 * headed to its left. The strip is a [GroupedEntry] list for the same reason the project rail is —
 * so both share [GroupedStrip]'s block, collapse and drag-reorder maths.
 */
sealed interface StripItem : GroupedEntry<StripItem> {
    data class Header(val group: TabGroup) : StripItem {
        override val isHeader: Boolean get() = true
        override val collapsed: Boolean get() = group.collapsed
        override fun expanded(): StripItem = Header(group.copy(collapsed = false))
    }

    data class Slot(val tabId: String) : StripItem {
        override val isHeader: Boolean get() = false
        override fun expanded(): StripItem = this
    }
}

/**
 * Pure helpers for the tab strip's group structure — naming, flattening to a draggable list, and
 * reading the result back. Kept out of [TabsState] (and the Compose layer) so they can be
 * unit-tested without a UI.
 */
object TabGroups {
    /** The group a fresh session starts with, and the name pattern new groups are numbered along. */
    const val DEFAULT_NAME = "MR1"

    private val AUTO_NAME = Regex("MR(\\d+)")

    /**
     * The name to give a new group: the lowest free MR<n>. Numbering from the gaps rather than from
     * the count means closing MR2 and adding a group gets MR2 back, instead of climbing forever.
     * A group the user renamed simply isn't in the running.
     */
    fun nextName(existing: List<TabGroup>): String {
        val taken = existing.mapNotNull { AUTO_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = 1
        while (n in taken) n++
        return "MR$n"
    }

    /**
     * The strip as one flat draggable list: each group's header followed by its tabs, groups in
     * their own order and tabs in [tabIds] order. Tabs whose group has gone missing are dropped
     * rather than orphaned — [TabsState] keeps membership in step, so this is belt and braces.
     */
    fun strip(groups: List<TabGroup>, tabIds: List<String>, groupOf: Map<String, Long>): List<StripItem> =
        buildList {
            for (group in groups) {
                add(StripItem.Header(group))
                for (id in tabIds) if (groupOf[id] == group.id) add(StripItem.Slot(id))
            }
        }

    /**
     * Where a dragged entry may come to rest: anywhere it would still be drawn, and never ahead of
     * the first header, because every tab has to belong to a group. Passed to
     * [GroupedStrip.dragStep] as its landing rule.
     */
    fun canLand(items: List<StripItem>, index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        return GroupedStrip.isVisible(items, index) && (index > 0 || item.isHeader)
    }

    /** The groups a (possibly dragged) strip describes, in strip order. */
    fun groups(items: List<StripItem>): List<TabGroup> =
        items.filterIsInstance<StripItem.Header>().map { it.group }

    /** The tab ids a strip describes, in strip order. */
    fun tabOrder(items: List<StripItem>): List<String> =
        items.filterIsInstance<StripItem.Slot>().map { it.tabId }

    /**
     * Which group each tab sits in after a drag: the header nearest to its left. Slots ahead of
     * every header have no group and are left out — [canLand] is what stops a drag producing them.
     */
    fun membership(items: List<StripItem>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        var current: Long? = null
        for (item in items) when (item) {
            is StripItem.Header -> current = item.group.id
            is StripItem.Slot -> current?.let { out[item.tabId] = it }
        }
        return out
    }

    /**
     * The group that should become active after [removed] is closed (from [groups] as it stood
     * *before* the removal). When the closed group wasn't the active one, [active] is returned
     * unchanged; when it was, the choice falls to the group that slides into its slot, else the new
     * last group. Null once nothing is left — a state [TabsState] never actually reaches, since it
     * always keeps one group around.
     */
    fun activeAfterRemove(groups: List<TabGroup>, removed: Long, active: Long): Long? {
        val idx = groups.indexOfFirst { it.id == removed }
        if (idx < 0 || active != removed) return active
        val remaining = groups.toMutableList().apply { removeAt(idx) }
        return (remaining.getOrNull(idx) ?: remaining.lastOrNull())?.id
    }
}
