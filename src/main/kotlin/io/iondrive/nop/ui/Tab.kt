package io.iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.iondrive.nop.git.FileChange
import io.iondrive.nop.launchers.LauncherRun
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

    fun open(tab: Tab) {
        if (_tabs.none { it.id == tab.id }) {
            _tabs.add(tab)
        }
        selectedId = tab.id
    }

    fun close(id: String) {
        val idx = _tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        _tabs.removeAt(idx)
        if (selectedId == id) {
            selectedId = _tabs.getOrNull(idx)?.id ?: _tabs.getOrNull(idx - 1)?.id
        }
    }

    fun select(id: String) {
        if (_tabs.any { it.id == id }) selectedId = id
    }

    val selectedTab: Tab? get() = _tabs.firstOrNull { it.id == selectedId }
}
