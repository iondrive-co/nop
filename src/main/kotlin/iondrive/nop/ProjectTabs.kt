package iondrive.nop

import java.nio.file.Path

/**
 * One tab along a window's project bar: the project directory it shows, and the [id] everything —
 * selection, closing, dragging, moving it to another window — addresses it by.
 *
 * The id is what lets one project have more than one tab. Two tabs on the same directory are two
 * places to work in it, the way two windows onto one folder are, so a tab can't be named by its path
 * any more: the bar would have two tabs answering to the same name and no way to tell which was
 * being clicked.
 *
 * Like a window's id, it is a runtime identity, handed out afresh on every launch — nothing outside
 * one run refers to a tab by anything but its position.
 *
 * [name] is what the user renamed this tab to, and blank on nearly all of them: a tab goes by its
 * project's directory name until someone says otherwise. Naming is what tells two tabs on one
 * project apart — "api" and "api — release" rather than two tabs both reading "api".
 */
data class ProjectTab(val id: Long, val path: Path, val name: String = "") {
    /** What the tab says on the bar: the name it was given, else the project directory's own. */
    val label: String get() = name.ifBlank { path.fileName?.toString() ?: path.toString() }
}

/**
 * Pure helpers for the project tabs along the top of a window, kept out of the Compose layer so they
 * can be unit-tested without spinning up a UI. Which window holds which tabs is [Workspaces].
 */
object ProjectTabs {
    /**
     * [paths] as a fresh row of tabs, ids running up from [firstId]. Used wherever a list of project
     * paths becomes tabs: restoring a saved window, upgrading an older layout, the first window of a
     * fresh install.
     */
    fun of(paths: List<Path>, firstId: Long = 0L): List<ProjectTab> =
        paths.mapIndexed { i, path -> ProjectTab(firstId + i, path.toAbsolutePath().normalize()) }

    /**
     * The tab that should become active after the tab with id [closed] is removed from [tabs] (that
     * window's tabs as they stood *before* removal). When the closed tab wasn't the active one,
     * [active] is returned unchanged. When it was, selection falls to the tab that slides into its
     * slot, else the new last tab, else null once nothing is left.
     */
    fun activeAfterClose(tabs: List<ProjectTab>, closed: Long, active: Long?): Long? {
        val idx = tabs.indexOfFirst { it.id == closed }
        if (idx < 0 || active != closed) return active
        val remaining = tabs.toMutableList().apply { removeAt(idx) }
        return (remaining.getOrNull(idx) ?: remaining.lastOrNull())?.id
    }

    /**
     * The tab a window shows on launch, given the [saved] position of the one that was in front:
     * that tab when the position still exists, else the first. A position rather than a path because
     * two tabs may be on the same project, and a path can no longer say which of them was showing.
     */
    fun initialActive(tabs: List<ProjectTab>, saved: Int?): Long? =
        (saved?.let { tabs.getOrNull(it) } ?: tabs.firstOrNull())?.id

    /**
     * The entries the project menu should offer: [recent] projects with the ones this window already
     * has a tab for ([open]) removed, normalized and de-duplicated, newest first. A project open in
     * *another* window is still offered — a tab here is a second place to work in it, which is the
     * same thing the "+" makes. Filesystem existence filtering is left to the caller since it isn't
     * pure.
     */
    fun recentMenu(recent: List<Path>, open: List<Path>): List<Path> {
        val openSet = open.map { it.toAbsolutePath().normalize() }.toSet()
        return recent
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .filter { it !in openSet }
    }
}
