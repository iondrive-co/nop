package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.git.CommitFile
import iondrive.nop.git.FileChange
import iondrive.nop.terminal.TerminalSession
import java.io.File

sealed class Tab {
    abstract val id: String
    abstract val title: String

    data class FileView(val file: File) : Tab() {
        override val id: String get() = "file:${file.absolutePath}"
        override val title: String get() = file.name
    }

    data class Diff(val change: FileChange, val repoRoot: File) : Tab() {
        override val id: String get() = "diff:${change.path}"
        override val title: String get() = File(change.path).name
    }

    /** Git log restricted to [file] (which may be a directory). */
    data class History(val file: File, val repoRoot: File) : Tab() {
        override val id: String get() = "history:${file.absolutePath}"
        override val title: String get() = "⎇ ${file.name}"
        var expandedSha: String? by mutableStateOf(null)
    }

    /** Diff of a single file within a historic commit (parent vs commit). */
    data class CommitDiff(val sha: String, val shortSha: String, val file: CommitFile, val repoRoot: File) : Tab() {
        override val id: String get() = "commitdiff:$sha:${file.path}"
        override val title: String get() = "$shortSha ${File(file.path).name}"
    }

    /**
     * nop's own record of what [file] has contained — every version it saved, plus the ones outside
     * writers left behind while the file was open. The git-independent half of [History], and the
     * only history a file that has never been committed has.
     */
    data class LocalHistory(val file: File) : Tab() {
        override val id: String get() = "localhistory:${file.absolutePath}"
        override val title: String get() = "⟲ ${file.name}"
    }

    /**
     * Diff of [file] as it stood at [sha] against what the working tree holds now — the
     * "compare with revision" gesture, with the revision picked out of the file's git log. The
     * git-side twin of [LocalDiff]: same shape, same read-only view, but the old side comes out
     * of a commit rather than nop's own record.
     */
    data class RevisionDiff(val file: File, val sha: String, val shortSha: String, val repoRoot: File) : Tab() {
        override val id: String get() = "revisiondiff:$sha:${file.absolutePath}"
        override val title: String get() = "⇄ $shortSha ${file.name}"
    }

    /** Diff of one local-history revision of [file] against what the file holds now. */
    data class LocalDiff(val file: File, val timestampMillis: Long) : Tab() {
        override val id: String get() = "localdiff:${file.absolutePath}:$timestampMillis"
        override val title: String get() = "${LocalHistoryFormat.time(timestampMillis)} ${file.name}"
    }

    /**
     * A live PTY-backed terminal — a launcher run or a plain shell. Each invocation is its own
     * tab (the nanoTime suffix keeps re-runs distinct), so it never collapses onto an existing one.
     */
    class Terminal(val session: TerminalSession) : Tab() {
        override val id: String = "terminal:${session.title}:${System.nanoTime()}"
        override val title: String get() = session.title
        override fun equals(other: Any?): Boolean = other is Terminal && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }
}

class TabsState {
    // Held in strip order: every group's tabs contiguous, groups in [_groups] order. The strip reads
    // straight off this, and a drag writes a whole new order back through [applyStrip].
    private val _tabs = mutableStateListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

    // The strip's groups, left to right. Never empty — every tab has to live in one, so a session
    // starts with the default and closing the last group leaves a fresh one behind rather than a
    // strip with nowhere to put the next file.
    private val _groups = mutableStateListOf(TabGroup(id = 0L, name = TabGroups.DEFAULT_NAME))
    val groups: List<TabGroup> get() = _groups

    // Which group each open tab sits in, keyed by tab id.
    private val tabGroups = mutableStateMapOf<String, Long>()

    // Ids are handed out per state (i.e. per project) and never reused, so a group keeps its identity
    // across renames, collapses and drags even when two groups share a name.
    private var nextGroupId = 1L

    /** The group [open] puts new tabs in — the one the user last picked in the strip. */
    var activeGroupId: Long by mutableStateOf(0L)
        private set

    var selectedId: String? by mutableStateOf(null)
        private set

    // 1-based line numbers to scroll to the next time a tab is composed. Cleared by the consumer
    // via [consumeJumpLine]. We keep this off [Tab.FileView] so the tab identity stays stable —
    // jumping to a different line in an already-open file shouldn't open a second tab.
    private val pendingJumpLines = mutableStateMapOf<String, Int>()

    // Query to seed the tab's in-file find bar with the next time it's composed — set when a global
    // "Find in files" result is opened so the editor lights up the same matches a manual find would.
    // Kept off [Tab.FileView] for the same reason as [pendingJumpLines]: it must not change tab identity.
    private val pendingSearchQueries = mutableStateMapOf<String, String>()

    // How many times each tab has been asked to re-read its content from disk. A diff caches both
    // sides when it opens, so without this a commit, pull or agent edit leaves it showing the state
    // of the world when the tab appeared. Kept off the tab itself so a reload never looks like a
    // different tab.
    private val reloadCounts = mutableStateMapOf<String, Int>()

    /**
     * Invoked with the file each time a [Tab.FileView] is opened as a genuine user action (tree
     * click, search jump, double-shift pick, …) so callers can track access frequency. Session
     * restore passes `record = false`, so reopening last run's tabs doesn't inflate the counts.
     */
    var onFileOpened: ((File) -> Unit)? = null

    /**
     * Shows [tab], adding it to the end of the active group if it isn't open yet. Opening a tab
     * that's *already* open also requests a reload: clicking the same change in the commit panel a
     * second time is the natural "show me this again" gesture, and the user means the file as it is
     * now, not the copy the tab captured when it first opened.
     *
     * Either way the tab ends up selected, so the group holding it is expanded if it was folded
     * away — a file the user just asked for must not open somewhere they can't see it.
     */
    fun open(tab: Tab, record: Boolean = true) {
        // Breadcrumb: the last tab opened is the single most useful piece of context when nop dies
        // while rendering something, so it goes in the log before the render is attempted.
        Log.info("open tab ${tab.id}")
        val existing = _tabs.indexOfFirst { it.id == tab.id }
        if (existing < 0) {
            tabGroups[tab.id] = activeGroupId
            _tabs.add(insertionIndex(activeGroupId), tab)
        } else {
            if (_tabs[existing] != tab) _tabs[existing] = tab
            requestReload(tab.id)
        }
        tabGroups[tab.id]?.let { setCollapsed(it, collapsed = false) }
        selectedId = tab.id
        if (record && tab is Tab.FileView) onFileOpened?.invoke(tab.file)
    }

    /**
     * Swaps the open tab sharing [tab]'s id for [tab], leaving the selection, the strip order and
     * the group alone. For a tab whose identity is a path but whose data moves underneath it — a
     * working-tree diff whose change goes from untracked to added — where [open] would also drag
     * the selection onto a tab the user never asked to look at. Ignores an id that isn't open.
     */
    fun replace(tab: Tab) {
        val idx = _tabs.indexOfFirst { it.id == tab.id }
        if (idx < 0 || _tabs[idx] == tab) return
        _tabs[idx] = tab
    }

    /**
     * Re-points the tab currently open as [oldId] at [tab], whose id has changed because its file
     * moved — the tree's rename. [replace] can't do this (it matches on the id that just changed)
     * and close-then-[open] would drop the tab to the end of the *active* group, so a rename would
     * shuffle the strip and could move the tab to a group the user wasn't even looking at. Everything
     * keyed on the old id comes across with it, and the selection follows.
     *
     * No-op for an id that isn't open. If the new id is somehow already open, the stale tab is just
     * closed — two tabs on one file is the one outcome worse than either.
     */
    fun rekey(oldId: String, tab: Tab) {
        val idx = _tabs.indexOfFirst { it.id == oldId }
        if (idx < 0 || oldId == tab.id) return
        if (_tabs.any { it.id == tab.id }) {
            close(oldId)
            return
        }
        _tabs[idx] = tab
        tabGroups.remove(oldId)?.let { tabGroups[tab.id] = it }
        pendingJumpLines.remove(oldId)?.let { pendingJumpLines[tab.id] = it }
        pendingSearchQueries.remove(oldId)?.let { pendingSearchQueries[tab.id] = it }
        reloadCounts.remove(oldId)?.let { reloadCounts[tab.id] = it }
        if (selectedId == oldId) selectedId = tab.id
    }

    /** Where a tab joining [groupId] goes in [_tabs]: after that group's run, ahead of the next one's. */
    private fun insertionIndex(groupId: Long): Int {
        val rank = _groups.indexOfFirst { it.id == groupId }
        if (rank < 0) return _tabs.size
        val after = _tabs.indexOfFirst { rankOf(it.id) > rank }
        return if (after < 0) _tabs.size else after
    }

    /** Position in [_groups] of the group holding [tabId]; -1 when it has none (or none is known). */
    private fun rankOf(tabId: String): Int = _groups.indexOfFirst { it.id == tabGroups[tabId] }

    /**
     * Counter for [tabId]'s reload requests. Observable, so a view keying its loader on this
     * re-reads from disk each time [requestReload] fires.
     */
    fun reloadKey(tabId: String): Int = reloadCounts[tabId] ?: 0

    /** Asks the view behind [tabId] to re-read its content. No-op for a tab that isn't open. */
    fun requestReload(tabId: String) {
        if (_tabs.none { it.id == tabId }) return
        reloadCounts[tabId] = (reloadCounts[tabId] ?: 0) + 1
    }

    /**
     * Opens [tab] and queues a one-shot scroll to [line] (1-based) once the editor is laid out.
     * When [searchQuery] is non-empty it's also queued so the tab seeds its in-file find bar with
     * it — used by global "Find in files" so the opened editor highlights the matched text.
     */
    fun openAt(tab: Tab, line: Int, record: Boolean = true, searchQuery: String? = null) {
        open(tab, record)
        pendingJumpLines[tab.id] = line.coerceAtLeast(1)
        if (searchQuery.isNullOrEmpty()) pendingSearchQueries.remove(tab.id)
        else pendingSearchQueries[tab.id] = searchQuery
    }

    /**
     * Pending 1-based line number to scroll to for [tabId], or null. Reads off observable state
     * so a Compose call site re-runs when [openAt] queues a new jump for an already-open tab.
     */
    fun pendingJumpLine(tabId: String): Int? = pendingJumpLines[tabId]

    /** Marks the pending jump for [tabId] as handled. Call after the scroll/select has run. */
    fun clearJumpLine(tabId: String) {
        pendingJumpLines.remove(tabId)
    }

    /**
     * Query to seed [tabId]'s in-file find bar with, or null. Observable, so a Compose call site
     * re-runs when [openAt] queues a new search for an already-open tab.
     */
    fun pendingSearchQuery(tabId: String): String? = pendingSearchQueries[tabId]

    /** Marks the pending search seed for [tabId] as handled. Call after the find bar is seeded. */
    fun clearSearchQuery(tabId: String) {
        pendingSearchQueries.remove(tabId)
    }

    fun close(id: String) {
        val idx = _tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        _tabs.removeAt(idx)
        forget(id)
        if (selectedId == id) selectedId = nearestShown(idx)
    }

    /**
     * Closes every tab except [keepId], which becomes the selected tab. Returns the removed tabs so
     * the caller can run per-tab cleanup (flush edits, stop launcher processes). No-op returning an
     * empty list if [keepId] isn't currently open. Groups are left alone — the emptied ones stay as
     * buckets to open into, the same way a project-rail separator outlives the tabs beneath it.
     */
    fun closeOthers(keepId: String): List<Tab> {
        val keep = _tabs.firstOrNull { it.id == keepId } ?: return emptyList()
        val removed = _tabs.filter { it.id != keepId }
        _tabs.clear()
        _tabs.add(keep)
        removed.forEach { forget(it.id) }
        selectedId = keep.id
        return removed
    }

    /** Drops everything keyed on a tab that has just left the strip. */
    private fun forget(id: String) {
        tabGroups.remove(id)
        pendingJumpLines.remove(id)
        pendingSearchQueries.remove(id)
        reloadCounts.remove(id)
    }

    /**
     * Where the selection lands when the tab at [idx] leaves: the tab that slid into its slot, else
     * the nearest one before it — skipping anything folded away inside a collapsed group, since
     * selecting a tab the strip isn't drawing would leave the editor showing a file with no visible
     * tab. Falls back to a hidden neighbour only when nothing at all is on show.
     */
    private fun nearestShown(idx: Int): String? {
        for (i in idx until _tabs.size) if (isShown(_tabs[i].id)) return _tabs[i].id
        for (i in idx - 1 downTo 0) if (isShown(_tabs[i].id)) return _tabs[i].id
        return _tabs.getOrNull(idx)?.id ?: _tabs.getOrNull(idx - 1)?.id
    }

    /** Whether [tabId]'s group is expanded, i.e. whether the strip is drawing a tab for it. */
    private fun isShown(tabId: String): Boolean =
        _groups.firstOrNull { it.id == tabGroups[tabId] }?.collapsed != true

    fun select(id: String) {
        if (_tabs.any { it.id == id }) selectedId = id
    }

    val selectedTab: Tab? get() = _tabs.firstOrNull { it.id == selectedId }

    /** The group [tabId] belongs to, or null when no such tab is open. */
    fun groupOf(tabId: String): Long? = tabGroups[tabId]

    /** The tabs in [groupId], in strip order. */
    fun tabsIn(groupId: Long): List<Tab> = _tabs.filter { tabGroups[it.id] == groupId }

    /** The whole strip as one flat draggable list — group headers with their tabs after them. */
    val strip: List<StripItem> get() = TabGroups.strip(_groups, _tabs.map { it.id }, tabGroups)

    /**
     * Adds a group at the end of the strip and makes it the one new tabs open into, which is the
     * point of adding one. [name] defaults to the next free MR<n>.
     */
    fun addGroup(name: String? = null): TabGroup {
        val label = name?.trim()?.takeIf { it.isNotEmpty() } ?: TabGroups.nextName(_groups)
        val group = TabGroup(id = nextGroupId++, name = label)
        _groups.add(group)
        activeGroupId = group.id
        return group
    }

    /** Renames [id]. A blank name is ignored rather than leaving an unlabelled group behind. */
    fun renameGroup(id: Long, name: String) {
        val label = name.trim().takeIf { it.isNotEmpty() } ?: return
        val idx = _groups.indexOfFirst { it.id == id }
        if (idx >= 0) _groups[idx] = _groups[idx].copy(name = label)
    }

    /** Makes [id] the group new tabs open into. Unknown ids are ignored. */
    fun selectGroup(id: Long) {
        if (_groups.any { it.id == id }) activeGroupId = id
    }

    /**
     * Folds [id]'s tabs away, or unfolds them. Collapsing the group the selected tab lives in moves
     * the selection to the nearest tab still on show, so the editor never ends up on a file whose
     * tab isn't drawn anywhere.
     */
    fun setCollapsed(id: Long, collapsed: Boolean) {
        val idx = _groups.indexOfFirst { it.id == id }
        if (idx < 0 || _groups[idx].collapsed == collapsed) return
        _groups[idx] = _groups[idx].copy(collapsed = collapsed)
        if (collapsed) revealSelection()
    }

    fun toggleCollapse(id: Long) {
        val group = _groups.firstOrNull { it.id == id } ?: return
        setCollapsed(id, !group.collapsed)
    }

    /** Moves the selection off a tab that has just been folded away, onto the nearest one still drawn. */
    private fun revealSelection() {
        val current = selectedId ?: return
        val idx = _tabs.indexOfFirst { it.id == current }
        if (idx < 0 || isShown(current)) return
        selectedId = nearestShown(idx)
    }

    /**
     * Removes [id] along with the tabs in it, returning them so the caller can run per-tab cleanup
     * (flush edits, stop launcher processes). The strip always needs somewhere to put the next file,
     * so closing the only group leaves a fresh default in its place rather than no group at all.
     */
    fun removeGroup(id: Long): List<Tab> {
        val idx = _groups.indexOfFirst { it.id == id }
        if (idx < 0) return emptyList()
        val removed = _tabs.filter { tabGroups[it.id] == id }
        _tabs.removeAll(removed)
        removed.forEach { forget(it.id) }
        val nextActive = TabGroups.activeAfterRemove(_groups, id, activeGroupId)
        _groups.removeAt(idx)
        if (_groups.isEmpty()) _groups.add(TabGroup(id = nextGroupId++, name = TabGroups.DEFAULT_NAME))
        activeGroupId = nextActive?.takeIf { next -> _groups.any { it.id == next } } ?: _groups.first().id
        if (_tabs.none { it.id == selectedId }) {
            selectedId = _tabs.firstOrNull { isShown(it.id) }?.id ?: _tabs.firstOrNull()?.id
        }
        return removed
    }

    /**
     * Moves [tabId] to the end of [groupId] — where a drag dropping it on that group would leave it
     * — and unfolds the group so the tab is visible where it landed.
     */
    fun moveTabToGroup(tabId: String, groupId: Long) {
        val idx = _tabs.indexOfFirst { it.id == tabId }
        if (idx < 0 || tabGroups[tabId] == groupId || _groups.none { it.id == groupId }) return
        val tab = _tabs.removeAt(idx)
        tabGroups[tabId] = groupId
        _tabs.add(insertionIndex(groupId), tab)
        setCollapsed(groupId, collapsed = false)
    }

    /**
     * Adopts the order a drag produced: group order, membership and tab order all come from [items].
     * A list that isn't a permutation of the current [strip] — a stale mid-drag snapshot — is
     * ignored rather than applied halfway.
     */
    fun applyStrip(items: List<StripItem>) {
        val groups = TabGroups.groups(items)
        val order = TabGroups.tabOrder(items)
        if (groups.size != _groups.size || order.size != _tabs.size) return
        val byId = _tabs.associateBy { it.id }
        val reordered = order.mapNotNull { byId[it] }
        if (reordered.size != _tabs.size) return
        _groups.clear()
        _groups.addAll(groups)
        tabGroups.clear()
        tabGroups.putAll(TabGroups.membership(items))
        _tabs.clear()
        _tabs.addAll(reordered)
        // A drag can fold the selected tab away by dropping a collapsed group's header over it.
        revealSelection()
    }

    /**
     * Rebuilds the groups from a restored session, replacing the default the state started with.
     * Returns them in order so the caller can open each saved tab into the right one. Restored
     * groups always start expanded — [open] would unfold them again as it restores their tabs — so
     * the caller applies the saved collapsed state afterwards.
     */
    fun replaceGroups(names: List<String>): List<TabGroup> {
        _groups.clear()
        val restored = names.map { TabGroup(id = nextGroupId++, name = it) }
        _groups.addAll(restored.ifEmpty { listOf(TabGroup(id = nextGroupId++, name = TabGroups.DEFAULT_NAME)) })
        activeGroupId = _groups.first().id
        return _groups.toList()
    }

    /** Immutable copy of everything the strip persists, taken on the UI thread and written off it. */
    fun snapshot(): TabsSnapshot =
        TabsSnapshot(_groups.toList(), _tabs.toList(), tabGroups.toMap(), selectedId, activeGroupId)
}

/**
 * The working file behind a diff tab — where F4 ("jump to source") lands. Every diff surface
 * resolves to a path in the working tree: a [Tab.Diff] shows changes against it directly, and a
 * [Tab.CommitDiff], [Tab.RevisionDiff] or [Tab.LocalDiff] shows an earlier revision *of* it. Null for every other tab
 * kind, and for any diff whose file isn't in the working tree any more (reverted, deleted, renamed
 * away, or removed by the very commit being read) — there's nothing to open in that case.
 */
internal fun jumpToSourceTarget(tab: Tab?): File? {
    val file = when (tab) {
        is Tab.Diff -> File(tab.repoRoot, tab.change.path)
        is Tab.CommitDiff -> File(tab.repoRoot, tab.file.path)
        is Tab.RevisionDiff -> tab.file
        is Tab.LocalDiff -> tab.file
        else -> null
    }
    return file?.takeIf { it.isFile }
}
