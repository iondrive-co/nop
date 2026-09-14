package iondrive.nop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * One nop window: a name, the ordered project tabs it shows along its top bar, and which of those
 * is the project the workspace beneath the bar is currently showing.
 *
 * A window is the unit the user organises projects into — what named separators in a single bar used
 * to do, a whole window now does, so "games" is a window rather than a fold in a very long strip.
 *
 * Closing a window parks it rather than discarding it: [open] goes false, [closedAt] records when,
 * and the workspace stays on the list with its tabs, waiting in the window picker for the user to
 * pick it up again. A curated set of tabs can't be lost to a stray click on the title bar's close
 * button — only [Workspaces.discard] throws one away, and only when the user says so.
 *
 * [id] is a runtime identity for keying windows and addressing one for a rename or a move; it isn't
 * persisted, since nothing outside a single run refers to a workspace by anything but its position.
 * [geometry] is this window's own size and place on screen, remembered per window so reopening one
 * puts it back where it was.
 */
data class Workspace(
    val id: Long,
    val name: String,
    val projects: List<Path>,
    val active: Path? = null,
    val geometry: WindowGeometry? = null,
    val open: Boolean = true,
    /** When this window was parked, in epoch millis; null while it is open, or was never closed. */
    val closedAt: Long? = null,
) {
    /**
     * What the title bar and the window list call this window. A named workspace goes by its name;
     * an unnamed one — every window on a fresh install, until the user names it — falls back to the
     * project it is showing, which is what the title used to say after the old "nop — " prefix.
     */
    val title: String
        get() = name.ifBlank {
            (active ?: projects.firstOrNull())?.fileName?.toString() ?: "nop"
        }
}

/**
 * Pure helpers over the window list — creation, naming, moving projects between windows, and the
 * one-time upgrade from the old single-window rail — kept out of the Compose and IO layers so they
 * can be unit-tested directly.
 */
object Workspaces {
    // Prefixes of the retired rail format, still read by [migrateRail] to upgrade an existing
    // install: a project tab, and a separator — one prefix for each of the two fold states it had.
    private const val RAIL_PROJECT = "project:"
    private const val RAIL_SEPARATOR = "sep:"
    private const val RAIL_SEPARATOR_COLLAPSED = "sepc:"

    /** An id no workspace on [list] is using, for a window about to be created. */
    fun nextId(list: List<Workspace>): Long = (list.maxOfOrNull { it.id } ?: -1L) + 1L

    fun byId(list: List<Workspace>, id: Long): Workspace? = list.firstOrNull { it.id == id }

    /**
     * The windows waiting to be picked up, most recently closed first: the parked ones that still
     * hold tabs. An empty parked window is not offered — there would be nothing to come back to —
     * which is why [park] drops one rather than keeping it.
     */
    fun parked(list: List<Workspace>): List<Workspace> =
        list.filter { !it.open && it.projects.isNotEmpty() }
            .sortedByDescending { it.closedAt ?: Long.MIN_VALUE }

    /**
     * Puts the window with [id] away, keeping its tabs for the picker and noting the time so the
     * picker can say how long ago it was. A window with no tabs left in it is dropped instead: it
     * would be an empty row offering to reopen nothing.
     */
    fun park(list: List<Workspace>, id: Long, nowMs: Long): List<Workspace> {
        val window = byId(list, id) ?: return list
        if (window.projects.isEmpty()) return list.filter { it.id != id }
        return update(list, id) { it.copy(open = false, closedAt = nowMs) }
    }

    /** Throws a window away for good, tabs and all. Only ever from the picker's discard. */
    fun discard(list: List<Workspace>, id: Long): List<Workspace> = list.filter { it.id != id }

    /** The first window holding [path] as one of its tabs, or null when no window has it open. */
    fun containing(list: List<Workspace>, path: Path): Workspace? {
        val norm = path.toAbsolutePath().normalize()
        return list.firstOrNull { ws -> ws.projects.any { it == norm } }
    }

    /** Every project open in any window, in window order, deduped. */
    fun allProjects(list: List<Workspace>): List<Path> = list.flatMap { it.projects }.distinct()

    /** The active project of each window that is currently showing — the tabs nobody should poll. */
    fun activeProjects(list: List<Workspace>): Set<Path> =
        list.filter { it.open }.mapNotNull { it.active }.toSet()

    /**
     * Replaces the workspace with [id] using [transform]. Anything else is left as it stands, and an
     * id that isn't on the list is a no-op, so callers can wire this straight to a UI callback whose
     * window may already be gone.
     */
    fun update(list: List<Workspace>, id: Long, transform: (Workspace) -> Workspace): List<Workspace> =
        list.map { if (it.id == id) transform(it) else it }

    /**
     * Moves the project at [from] to index [to] within one window's tabs, shifting the rest.
     * Out-of-range indices (or from == to) return the list unchanged, so a stale mid-drag index is
     * harmless.
     */
    fun move(projects: List<Path>, from: Int, to: Int): List<Path> {
        if (from == to || from !in projects.indices || to !in projects.indices) return projects
        val out = projects.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /**
     * Hands [path] to the window with id [toId] and takes it off whichever window held it, so a
     * project sits in exactly one window. The receiving window makes it its active tab (that is what
     * the user just asked for by moving it there); the donor picks its next active tab the same way
     * closing that tab would. Moving a project to the window it is already in is a no-op.
     */
    fun moveProject(list: List<Workspace>, path: Path, toId: Long): List<Workspace> {
        val norm = path.toAbsolutePath().normalize()
        val target = byId(list, toId) ?: return list
        if (norm in target.projects) return list
        return list.map { ws ->
            when {
                // `+ listOf(norm)` rather than `+ norm`: a Path is itself an Iterable of its name
                // elements, so the bare form appends the segments of the path instead of the path.
                ws.id == toId -> ws.copy(projects = ws.projects + listOf(norm), active = norm)
                norm in ws.projects -> ws.copy(
                    projects = ws.projects.filter { it != norm },
                    active = ProjectTabs.activeAfterClose(ws.projects, norm, ws.active),
                )
                else -> ws
            }
        }
    }

    /**
     * [base] adjusted so it doesn't collide with a name already [taken] — "games" becomes "games 2",
     * then "games 3". Window names are the only handle the user has on a window in the "+" menu, so
     * two windows sharing one would leave them indistinguishable.
     */
    fun uniqueName(base: String, taken: Collection<String>): String {
        val trimmed = base.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed !in taken) return trimmed
        var n = 2
        while ("$trimmed $n" in taken) n++
        return "$trimmed $n"
    }

    /**
     * The one-time upgrade from the old layout: a single bar of project tabs interleaved with named
     * separators, each heading the run of tabs after it. Every separator becomes a window carrying
     * that run, in bar order, and any tabs ahead of the first separator become an unnamed window at
     * the front — they belonged to no group, and inventing a name for them would be inventing one the
     * user never chose.
     *
     * Every group opens, whether or not it was collapsed at the time. A fold in one bar and a window
     * that isn't showing are not the same thing: the groups are what the user built, and an upgrade
     * that left most of them off screen would read as having lost them.
     *
     * The saved [active] project decides which window holds the tab that was in front; the rest keep
     * their first tab. [geometry] is the one window's saved size and position, handed to each new
     * window with a small cascade so they don't all land exactly on top of each other.
     *
     * [encoded] is the raw `rail.N` values in order; anything unparseable is skipped rather than
     * aborting the upgrade.
     */
    fun migrateRail(encoded: List<String>, active: Path?, geometry: WindowGeometry?): List<Workspace> {
        val out = mutableListOf<Workspace>()
        // The window being filled: null until the first tab or separator decides what it is. Leading
        // tabs open an unnamed one; a separator starts a named one and ends whatever came before.
        var name: String? = null
        var projects = mutableListOf<Path>()

        fun flush() {
            if (name == null && projects.isEmpty()) return
            out.add(Workspace(id = out.size.toLong(), name = name ?: "", projects = projects.toList()))
        }

        for (value in encoded) {
            when {
                value.startsWith(RAIL_PROJECT) -> {
                    val path = runCatching { Paths.get(value.removePrefix(RAIL_PROJECT)) }.getOrNull() ?: continue
                    projects.add(path.toAbsolutePath().normalize())
                }
                value.startsWith(RAIL_SEPARATOR_COLLAPSED) || value.startsWith(RAIL_SEPARATOR) -> {
                    flush()
                    // Collapsed or not, the group is a window; the two prefixes differ only in a
                    // fold state that no longer has anything to describe. Exactly one is stripped,
                    // so a group named "sep:something" keeps its name.
                    name = if (value.startsWith(RAIL_SEPARATOR_COLLAPSED)) {
                        value.removePrefix(RAIL_SEPARATOR_COLLAPSED)
                    } else {
                        value.removePrefix(RAIL_SEPARATOR)
                    }
                    projects = mutableListOf()
                }
            }
        }
        flush()

        val normActive = active?.toAbsolutePath()?.normalize()
        return out.mapIndexed { idx, ws ->
            ws.copy(
                active = ProjectTabs.initialActive(ws.projects, normActive),
                geometry = cascade(geometry, idx),
            )
        }
    }

    /**
     * [geometry] stepped [steps] windows down the cascade, so a window created from another — or a
     * row of them made at once by the upgrade — doesn't land exactly on top of the one before it.
     */
    fun cascade(geometry: WindowGeometry?, steps: Int): WindowGeometry? {
        if (geometry == null || steps == 0 || geometry.x == null || geometry.y == null) return geometry
        return geometry.copy(x = geometry.x + CASCADE_STEP * steps, y = geometry.y + CASCADE_STEP * steps)
    }

    /**
     * The windows to show on launch, given what was saved. Normally that is exactly the ones marked
     * open, but a state where none are — every window closed one at a time, or an upgrade from a
     * layout whose groups were all folded away — would start nop with nothing on screen, so the
     * window holding the last active project (else the first) is opened instead.
     */
    fun opened(list: List<Workspace>, active: Path?): List<Workspace> {
        if (list.isEmpty() || list.any { it.open }) return list
        val wanted = active?.let { containing(list, it) } ?: list.first()
        return update(list, wanted.id) { it.copy(open = true, closedAt = null) }
    }
}

/**
 * How much bigger a window reports itself than the size it was actually given.
 *
 * On some window managers the two disagree — the frame decoration is counted once when the window is
 * sized and again when it is measured — so a size read straight back off a window and saved would
 * reopen it that much bigger, and again the launch after that. Measured here on this machine: a
 * window asked for 600px tall reports 604, and reopening it walks the saved height up 4px a launch.
 *
 * The gap is a property of the decoration, not of the size, so it is measured once per window from
 * the first size it reports against the size it was asked for, and taken back off everything saved
 * from then on. A genuine resize keeps its full effect: the same constant comes off every reading.
 */
data class WindowOverhead(val width: Int, val height: Int) {
    /** [geometry] as it should be persisted: the size the window was really given. */
    fun applyTo(geometry: WindowGeometry): WindowGeometry = geometry.copy(
        width = (geometry.width - width).coerceAtLeast(MIN_SIZE),
        height = (geometry.height - height).coerceAtLeast(MIN_SIZE),
    )

    companion object {
        val NONE = WindowOverhead(0, 0)

        /** Below this a window is unusable, and a correction that would cross it is not one. */
        private const val MIN_SIZE = 200

        /**
         * Past this, the difference is the user having resized the window rather than a decoration
         * being double-counted, and correcting by it would shrink the window on every launch.
         */
        private const val MAX_OVERHEAD = 80

        /**
         * The gap between the size a window was [asked] for and the first size it [reported]. Zero
         * in either direction whenever that gap can't be a decoration: a window reporting itself
         * *smaller* than requested is not overpaying for a frame, and one off by more than
         * [MAX_OVERHEAD] has been resized.
         */
        fun measure(asked: WindowGeometry, reported: WindowGeometry): WindowOverhead = WindowOverhead(
            width = (reported.width - asked.width).takeIf { it in 0..MAX_OVERHEAD } ?: 0,
            height = (reported.height - asked.height).takeIf { it in 0..MAX_OVERHEAD } ?: 0,
        )
    }
}

/** How far down and across each window in a cascade sits from the one before it, in dp. */
private const val CASCADE_STEP = 32
