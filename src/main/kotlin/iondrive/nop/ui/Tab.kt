package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.git.CommitFile
import iondrive.nop.git.FileChange
import iondrive.nop.launchers.LauncherRun
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

    /** A live launcher invocation — output streams here while the process runs. */
    class LauncherOutput(val run: LauncherRun) : Tab() {
        override val id: String = "launcher:${run.launcher.name}:${System.nanoTime()}"
        override val title: String get() = "▶ ${run.launcher.name}"
        override fun equals(other: Any?): Boolean = other is LauncherOutput && other.id == id
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

    fun open(tab: Tab) {
        if (_tabs.none { it.id == tab.id }) {
            _tabs.add(tab)
        }
        selectedId = tab.id
    }

    /** Opens [tab] and queues a one-shot scroll to [line] (1-based) once the editor is laid out. */
    fun openAt(tab: Tab, line: Int) {
        open(tab)
        pendingJumpLines[tab.id] = line.coerceAtLeast(1)
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

    fun close(id: String) {
        val idx = _tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        _tabs.removeAt(idx)
        pendingJumpLines.remove(id)
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
        removed.forEach { pendingJumpLines.remove(it.id) }
        selectedId = keep.id
        return removed
    }

    fun select(id: String) {
        if (_tabs.any { it.id == id }) selectedId = id
    }

    val selectedTab: Tab? get() = _tabs.firstOrNull { it.id == selectedId }
}
