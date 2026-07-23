package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private val _tabs = mutableStateListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

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
     * Shows [tab], adding it to the strip if it isn't there yet. Opening a tab that's *already*
     * open also requests a reload: clicking the same change in the commit panel a second time is
     * the natural "show me this again" gesture, and the user means the file as it is now, not the
     * copy the tab captured when it first opened.
     */
    fun open(tab: Tab, record: Boolean = true) {
        val existing = _tabs.indexOfFirst { it.id == tab.id }
        if (existing < 0) {
            _tabs.add(tab)
        } else {
            if (_tabs[existing] != tab) _tabs[existing] = tab
            requestReload(tab.id)
        }
        selectedId = tab.id
        if (record && tab is Tab.FileView) onFileOpened?.invoke(tab.file)
    }

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
        pendingJumpLines.remove(id)
        pendingSearchQueries.remove(id)
        reloadCounts.remove(id)
        if (selectedId == id) {
            selectedId = _tabs.getOrNull(idx)?.id ?: _tabs.getOrNull(idx - 1)?.id
        }
    }

    /**
     * Closes every tab except [keepId], which becomes the selected tab. Returns the removed tabs so
     * the caller can run per-tab cleanup (flush edits, stop launcher processes). No-op returning an
     * empty list if [keepId] isn't currently open.
     */
    fun closeOthers(keepId: String): List<Tab> {
        val keep = _tabs.firstOrNull { it.id == keepId } ?: return emptyList()
        val removed = _tabs.filter { it.id != keepId }
        _tabs.clear()
        _tabs.add(keep)
        removed.forEach {
            pendingJumpLines.remove(it.id)
            pendingSearchQueries.remove(it.id)
            reloadCounts.remove(it.id)
        }
        selectedId = keep.id
        return removed
    }

    fun select(id: String) {
        if (_tabs.any { it.id == id }) selectedId = id
    }

    val selectedTab: Tab? get() = _tabs.firstOrNull { it.id == selectedId }
}
