package iondrive.nop

import java.nio.file.Path

/**
 * Pure helpers for the left-rail project tabs, kept out of the Compose layer so they can be
 * unit-tested without spinning up a UI.
 */
object ProjectTabs {
    /**
     * The tab that should become active after [closed] is removed from [projects] (the list as it
     * stood *before* removal). When the closed tab wasn't the active one, [active] is returned
     * unchanged. When it was, selection falls to the tab that slides into its slot, else the new
     * last tab, else null once nothing is left.
     */
    fun activeAfterClose(projects: List<Path>, closed: Path, active: Path?): Path? {
        val idx = projects.indexOf(closed)
        if (idx < 0 || active != closed) return active
        val remaining = projects.toMutableList().apply { removeAt(idx) }
        return remaining.getOrNull(idx) ?: remaining.lastOrNull()
    }

    /** The tab to show on launch: the saved active tab when it's still open, else the first tab. */
    fun initialActive(projects: List<Path>, saved: Path?): Path? =
        saved?.takeIf { it in projects } ?: projects.firstOrNull()

    /**
     * The entries the "+" dropdown should offer: [recent] projects with the currently-[open] ones
     * removed (they already have tabs), normalized and de-duplicated, newest first. Filesystem
     * existence filtering is left to the caller since it isn't pure.
     */
    fun recentMenu(recent: List<Path>, open: List<Path>): List<Path> {
        val openSet = open.map { it.toAbsolutePath().normalize() }.toSet()
        return recent
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .filter { it !in openSet }
    }
}
