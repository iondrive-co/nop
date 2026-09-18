package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class WorkspacesTest {
    private fun p(s: String): Path = Paths.get(s)

    /**
     * A window on [projects], its first tab in front. Tab ids are spaced out by window so that a
     * list built here has the same property the real one does: no two tabs anywhere share an id.
     */
    private fun ws(id: Long, name: String, vararg projects: String, open: Boolean = true): Workspace {
        val tabs = ProjectTabs.of(projects.map(::p), firstId = id * 100)
        return Workspace(id, name, tabs, active = tabs.firstOrNull()?.id, open = open)
    }

    private fun tabs(vararg projects: String) = ProjectTabs.of(projects.map(::p))

    @Test
    fun `title is the window's name`() {
        assertEquals("games", ws(0, "games", "/p/pog").title)
    }

    @Test
    fun `an unnamed window is titled after the project it is showing`() {
        assertEquals("pog", ws(0, "", "/p/pog").title)
    }

    @Test
    fun `an unnamed window showing a renamed tab is titled by that name`() {
        val work = ws(0, "", "/p/pog")
        val renamed = work.copy(tabs = listOf(work.tabs[0].copy(name = "pog — server")))
        assertEquals("pog — server", renamed.title)
    }

    @Test
    fun `an unnamed, empty window still has a title`() {
        assertEquals("nop", Workspace(0, "", emptyList()).title)
    }

    @Test
    fun `containing finds the window holding a project`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/b"))
        assertEquals(1L, Workspaces.containing(list, p("/p/b"))?.id)
        assertNull(Workspaces.containing(list, p("/p/nowhere")))
    }

    @Test
    fun `containing normalizes the path it is handed`() {
        val list = listOf(ws(0, "work", "/p/a"))
        assertEquals(0L, Workspaces.containing(list, p("/p/x/../a"))?.id)
    }

    @Test
    fun `allProjects reads every window in order, open or not`() {
        val list = listOf(ws(0, "work", "/p/a", "/p/b"), ws(1, "games", "/p/c", open = false))
        assertEquals(listOf(p("/p/a"), p("/p/b"), p("/p/c")), Workspaces.allProjects(list))
    }

    @Test
    fun `allProjects counts a project with two tabs once — it is one working tree`() {
        assertEquals(listOf(p("/p/a")), Workspaces.allProjects(listOf(ws(0, "work", "/p/a", "/p/a"))))
    }

    @Test
    fun `activeProjects covers the showing windows only`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/c", open = false))
        assertEquals(setOf(p("/p/a")), Workspaces.activeProjects(list))
    }

    @Test
    fun `move slides a tab along the bar`() {
        val row = tabs("/a", "/b", "/c")
        assertEquals(listOf(1L, 2L, 0L), Workspaces.move(row, 0, 2).map { it.id })
        assertEquals(listOf(2L, 0L, 1L), Workspaces.move(row, 2, 0).map { it.id })
    }

    @Test
    fun `move ignores an out-of-range or standstill index`() {
        val row = tabs("/a", "/b")
        assertEquals(row, Workspaces.move(row, 1, 1))
        assertEquals(row, Workspaces.move(row, 0, 5))
        assertEquals(row, Workspaces.move(row, -1, 0))
    }

    @Test
    fun `moveTab hands a tab over and takes it off the window that had it`() {
        val list = listOf(ws(0, "work", "/p/a", "/p/b"), ws(1, "games", "/p/c"))
        val moving = list[0].tabs[1]
        val moved = Workspaces.moveTab(list, moving.id, toId = 1)

        assertEquals(listOf(p("/p/a")), moved[0].projects)
        assertEquals(listOf(p("/p/c"), p("/p/b")), moved[1].projects)
        // The window it landed in shows it — that is what the user just asked for — and it is the
        // same tab that left, not a new one on the same project.
        assertEquals(moving.id, moved[1].active)
    }

    @Test
    fun `moving the tab that was in front leaves the donor showing its neighbour`() {
        val work = ws(0, "work", "/p/a", "/p/b")
        val list = listOf(work.copy(active = work.tabs[1].id), ws(1, "games", "/p/c"))
        val moved = Workspaces.moveTab(list, work.tabs[1].id, toId = 1)
        assertEquals(p("/p/a"), moved[0].activeTab?.path)
    }

    @Test
    fun `moving a tab to the window it is already in changes nothing`() {
        val list = listOf(ws(0, "work", "/p/a"))
        assertEquals(list, Workspaces.moveTab(list, list[0].tabs[0].id, toId = 0))
    }

    @Test
    fun `moveTab ignores a window that isn't there`() {
        val list = listOf(ws(0, "work", "/p/a"))
        assertEquals(list, Workspaces.moveTab(list, list[0].tabs[0].id, toId = 7))
    }

    @Test
    fun `moveTab ignores a tab that isn't there`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/b"))
        assertEquals(list, Workspaces.moveTab(list, tabId = 999, toId = 1))
    }

    @Test
    fun `moveTab takes one of a project's two tabs and leaves the other`() {
        val list = listOf(ws(0, "work", "/p/a", "/p/a"), ws(1, "games", "/p/b"))
        val moved = Workspaces.moveTab(list, list[0].tabs[0].id, toId = 1)

        assertEquals(listOf(p("/p/a")), moved[0].projects)
        assertEquals(listOf(p("/p/b"), p("/p/a")), moved[1].projects)
    }

    @Test
    fun `uniqueName numbers a name that is taken`() {
        assertEquals("games", Workspaces.uniqueName("games", listOf("work")))
        assertEquals("games 2", Workspaces.uniqueName("games", listOf("games")))
        assertEquals("games 3", Workspaces.uniqueName("games", listOf("games", "games 2")))
    }

    @Test
    fun `uniqueName leaves a blank name blank rather than numbering the unnamed windows`() {
        assertEquals("", Workspaces.uniqueName("   ", listOf("", "work")))
    }

    @Test
    fun `nextId clears every id in use`() {
        assertEquals(0L, Workspaces.nextId(emptyList()))
        assertEquals(8L, Workspaces.nextId(listOf(ws(3, "a"), ws(7, "b"))))
    }

    @Test
    fun `nextTabId clears every tab id in use, in every window`() {
        assertEquals(0L, Workspaces.nextTabId(emptyList()))
        // ws(1, …) numbers its tabs from 100, so the next id has to clear that window too.
        assertEquals(102L, Workspaces.nextTabId(listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/b", "/p/c"))))
    }

    @Test
    fun `holdingTab finds the window a tab is in`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/b"))
        assertEquals(1L, Workspaces.holdingTab(list, list[1].tabs[0].id)?.id)
        assertNull(Workspaces.holdingTab(list, 999))
    }

    // --- the upgrade from the single bar of separator-grouped tabs -------------------------------

    private val rail = listOf(
        "sep:work",
        "project:/p/ops",
        "project:/p/core",
        "sepc:games",
        "project:/p/pog",
        "project:/p/paren",
    )

    @Test
    fun `migrateRail gives each group its own window, named and in order`() {
        val out = Workspaces.migrateRail(rail, active = null, geometry = null)

        assertEquals(listOf("work", "games"), out.map { it.name })
        assertEquals(listOf(p("/p/ops"), p("/p/core")), out[0].projects)
        assertEquals(listOf(p("/p/pog"), p("/p/paren")), out[1].projects)
        assertEquals(listOf(0L, 1L), out.map { it.id })
    }

    @Test
    fun `every group opens, collapsed at the time or not`() {
        val out = Workspaces.migrateRail(rail, active = null, geometry = null)
        assertTrue(out.all { it.open })
    }

    @Test
    fun `a group named like a separator prefix keeps its name`() {
        val out = Workspaces.migrateRail(listOf("sepc:sep:odd"), active = null, geometry = null)
        assertEquals("sep:odd", out.single().name)
    }

    @Test
    fun `the saved active tab stays in front of its own window, others take their first tab`() {
        val out = Workspaces.migrateRail(rail, active = p("/p/paren"), geometry = null)
        assertEquals(p("/p/ops"), out[0].activeTab?.path)
        assertEquals(p("/p/paren"), out[1].activeTab?.path)
    }

    @Test
    fun `migrateRail hands out tab ids that no two windows share`() {
        val out = Workspaces.migrateRail(rail, active = null, geometry = null)
        val ids = out.flatMap { it.tabs }.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `tabs ahead of the first separator become an unnamed window at the front`() {
        val out = Workspaces.migrateRail(
            listOf("project:/p/loose", "sep:work", "project:/p/ops"),
            active = null,
            geometry = null,
        )
        assertEquals(listOf("", "work"), out.map { it.name })
        assertEquals(listOf(p("/p/loose")), out[0].projects)
    }

    @Test
    fun `a separator heading no tabs still becomes a window, empty`() {
        val out = Workspaces.migrateRail(listOf("sep:work", "sep:empty"), active = null, geometry = null)
        assertEquals(listOf("work", "empty"), out.map { it.name })
        assertEquals(emptyList<Path>(), out[1].projects)
    }

    @Test
    fun `the old window's geometry is handed to each new window, cascaded`() {
        val out = Workspaces.migrateRail(rail, active = null, geometry = WindowGeometry(900, 700, 10, 20))
        assertEquals(WindowGeometry(900, 700, 10, 20), out[0].geometry)
        assertEquals(WindowGeometry(900, 700, 42, 52), out[1].geometry)
    }

    @Test
    fun `an unpositioned geometry cascades to nowhere in particular`() {
        val out = Workspaces.migrateRail(rail, active = null, geometry = WindowGeometry(900, 700, null, null))
        assertEquals(WindowGeometry(900, 700, null, null), out[1].geometry)
    }

    @Test
    fun `migrateRail skips a row it can't read rather than dropping the layout`() {
        val out = Workspaces.migrateRail(
            listOf("sep:work", "garbage", "project:/p/ops"),
            active = null,
            geometry = null,
        )
        assertEquals(listOf(p("/p/ops")), out.single().projects)
    }

    @Test
    fun `an empty rail upgrades to no windows at all`() {
        assertEquals(emptyList<Workspace>(), Workspaces.migrateRail(emptyList(), null, null))
    }

    // --- what to show on launch ------------------------------------------------------------------

    @Test
    fun `opened leaves a layout that already has a window showing alone`() {
        val list = listOf(ws(0, "work", "/p/a", open = false), ws(1, "games", "/p/b"))
        assertEquals(list, Workspaces.opened(list, active = p("/p/a")))
    }

    @Test
    fun `opened brings back the window holding the last active project when none are showing`() {
        val list = listOf(ws(0, "work", "/p/a", open = false), ws(1, "games", "/p/b", open = false))
        val out = Workspaces.opened(list, active = p("/p/b"))
        assertTrue(!out[0].open)
        assertTrue(out[1].open)
    }

    @Test
    fun `opened falls back to the first window when the last active project is nobody's`() {
        val list = listOf(ws(0, "work", "/p/a", open = false), ws(1, "games", "/p/b", open = false))
        assertTrue(Workspaces.opened(list, active = p("/p/gone"))[0].open)
    }

    @Test
    fun `opened has nothing to open when there are no windows`() {
        assertEquals(emptyList<Workspace>(), Workspaces.opened(emptyList(), active = p("/p/a")))
    }

    // --- parking a window, and picking it back up ------------------------------------------------

    @Test
    fun `parking a window keeps its tabs and notes when it was put away`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/b"))
        val out = Workspaces.park(list, id = 1, nowMs = 5000)

        assertEquals(listOf(p("/p/b")), out[1].projects)
        assertTrue(!out[1].open)
        assertEquals(5000L, out[1].closedAt)
        assertEquals(list[0], out[0])
    }

    @Test
    fun `parking a window with no tabs drops it instead of keeping an empty row`() {
        val list = listOf(ws(0, "work", "/p/a"), Workspace(1, "scratch", emptyList()))
        assertEquals(listOf(0L), Workspaces.park(list, id = 1, nowMs = 5000).map { it.id })
    }

    @Test
    fun `parked lists the closed windows, most recently closed first`() {
        val list = listOf(
            ws(0, "work", "/p/a"),
            ws(1, "games", "/p/b", open = false).copy(closedAt = 100),
            ws(2, "media", "/p/c", open = false).copy(closedAt = 900),
        )
        assertEquals(listOf("media", "games"), Workspaces.parked(list).map { it.name })
    }

    @Test
    fun `a parked window with nothing in it is not offered`() {
        val list = listOf(Workspace(0, "empty", emptyList(), open = false, closedAt = 100))
        assertEquals(emptyList<Workspace>(), Workspaces.parked(list))
    }

    @Test
    fun `a window that never recorded a closing time still sorts, at the back`() {
        val list = listOf(
            ws(0, "old", "/p/a", open = false),
            ws(1, "new", "/p/b", open = false).copy(closedAt = 100),
        )
        assertEquals(listOf("new", "old"), Workspaces.parked(list).map { it.name })
    }

    // --- quitting by closing the windows one at a time -------------------------------------------

    @Test
    fun `windows closed one after another on the way to quitting open again with it`() {
        val list = listOf(
            ws(0, "work", "/p/a", open = false).copy(closedAt = 1_000),
            ws(1, "games", "/p/b", open = false).copy(closedAt = 4_000),
            ws(2, "media", "/p/c", open = false).copy(closedAt = 9_000),
            ws(3, "last", "/p/d"),
        )
        val out = Workspaces.quitting(list, nowMs = 12_000)

        assertTrue(out.all { it.open })
        assertTrue(out.all { it.closedAt == null })
        // Nothing else about them changes: tabs, names and places are what comes back.
        assertEquals(list.map { it.copy(open = true, closedAt = null) }, out)
    }

    @Test
    fun `the run is measured between closes, so a slow shutdown still counts as one`() {
        // Each close is under the gap from the next, though the first is well over it from the quit.
        val list = listOf(
            ws(0, "work", "/p/a", open = false).copy(closedAt = 0),
            ws(1, "games", "/p/b", open = false).copy(closedAt = 8_000),
            ws(2, "media", "/p/c", open = false).copy(closedAt = 16_000),
            ws(3, "last", "/p/d"),
        )
        assertTrue(Workspaces.quitting(list, nowMs = 24_000).all { it.open })
    }

    @Test
    fun `a window parked a while before the shutdown began stays parked`() {
        val list = listOf(
            ws(0, "parked", "/p/a", open = false).copy(closedAt = 1_000),
            ws(1, "games", "/p/b", open = false).copy(closedAt = 60_000),
            ws(2, "last", "/p/c"),
        )
        val out = Workspaces.quitting(list, nowMs = 62_000)

        assertEquals(listOf(false, true, true), out.map { it.open })
        assertEquals(1_000L, out[0].closedAt)
    }

    @Test
    fun `a gap in the run ends it, even with windows closed close together before the gap`() {
        val list = listOf(
            ws(0, "a", "/p/a", open = false).copy(closedAt = 1_000),
            ws(1, "b", "/p/b", open = false).copy(closedAt = 2_000),
            ws(2, "c", "/p/c", open = false).copy(closedAt = 50_000),
            ws(3, "last", "/p/d"),
        )
        assertEquals(listOf(false, false, true, true), Workspaces.quitting(list, nowMs = 51_000).map { it.open })
    }

    @Test
    fun `quitting long after the last park leaves every parked window where it is`() {
        val list = listOf(ws(0, "games", "/p/b", open = false).copy(closedAt = 1_000), ws(1, "last", "/p/c"))
        assertEquals(list, Workspaces.quitting(list, nowMs = 100_000))
    }

    @Test
    fun `a parked window with no closing time on record is never swept up in a quit`() {
        val list = listOf(ws(0, "old", "/p/a", open = false), ws(1, "last", "/p/b"))
        assertEquals(list, Workspaces.quitting(list, nowMs = 5_000))
    }

    @Test
    fun `discard throws a window away for good`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/b", open = false))
        assertEquals(listOf(0L), Workspaces.discard(list, 1).map { it.id })
    }

    @Test
    fun `opening a parked window clears the time it was closed at`() {
        val list = listOf(ws(0, "games", "/p/b", open = false).copy(closedAt = 100))
        assertNull(Workspaces.opened(list, active = null).single().closedAt)
    }

    // --- the size a window over-reports itself by ------------------------------------------------

    private fun geom(w: Int, h: Int) = WindowGeometry(w, h, 100, 200)

    @Test
    fun `the overhead is the gap between the size asked for and the size reported`() {
        assertEquals(WindowOverhead(0, 4), WindowOverhead.measure(geom(900, 600), geom(900, 604)))
    }

    @Test
    fun `taking the overhead off a reading gives back the size the window was given`() {
        val overhead = WindowOverhead.measure(geom(900, 600), geom(900, 604))
        // Which is the point: saved and restored, this reading reopens the window at 900x600, and
        // the launch after that saves 900x600 again rather than creeping another 4px.
        assertEquals(geom(900, 600), overhead.applyTo(geom(900, 604)))
    }

    @Test
    fun `a resize keeps its full effect once the overhead is known`() {
        val overhead = WindowOverhead.measure(geom(900, 600), geom(900, 604))
        assertEquals(geom(1200, 800), overhead.applyTo(geom(1200, 804)))
    }

    @Test
    fun `a window reporting itself smaller than it was asked for is not overpaying for a frame`() {
        assertEquals(WindowOverhead.NONE, WindowOverhead.measure(geom(900, 600), geom(880, 580)))
    }

    @Test
    fun `a gap too big to be a decoration is a resize, and is left alone`() {
        assertEquals(WindowOverhead.NONE, WindowOverhead.measure(geom(900, 600), geom(1600, 1000)))
    }

    @Test
    fun `a correction is never allowed to shrink a window out of usefulness`() {
        assertEquals(geom(200, 200), WindowOverhead(80, 80).applyTo(geom(210, 240)))
    }

    @Test
    fun `the position is passed through untouched`() {
        val corrected = WindowOverhead(0, 4).applyTo(WindowGeometry(900, 604, -5, 2159))
        assertEquals(-5, corrected.x)
        assertEquals(2159, corrected.y)
    }
}
