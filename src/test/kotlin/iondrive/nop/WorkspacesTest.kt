package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class WorkspacesTest {
    private fun p(s: String): Path = Paths.get(s)

    private fun ws(id: Long, name: String, vararg projects: String, open: Boolean = true) =
        Workspace(id, name, projects.map(::p), active = projects.firstOrNull()?.let(::p), open = open)

    @Test
    fun `title is the window's name`() {
        assertEquals("games", ws(0, "games", "/p/pog").title)
    }

    @Test
    fun `an unnamed window is titled after the project it is showing`() {
        assertEquals("pog", ws(0, "", "/p/pog").title)
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
    fun `activeProjects covers the showing windows only`() {
        val list = listOf(ws(0, "work", "/p/a"), ws(1, "games", "/p/c", open = false))
        assertEquals(setOf(p("/p/a")), Workspaces.activeProjects(list))
    }

    @Test
    fun `move slides a tab along the bar`() {
        val tabs = listOf(p("/a"), p("/b"), p("/c"))
        assertEquals(listOf(p("/b"), p("/c"), p("/a")), Workspaces.move(tabs, 0, 2))
        assertEquals(listOf(p("/c"), p("/a"), p("/b")), Workspaces.move(tabs, 2, 0))
    }

    @Test
    fun `move ignores an out-of-range or standstill index`() {
        val tabs = listOf(p("/a"), p("/b"))
        assertEquals(tabs, Workspaces.move(tabs, 1, 1))
        assertEquals(tabs, Workspaces.move(tabs, 0, 5))
        assertEquals(tabs, Workspaces.move(tabs, -1, 0))
    }

    @Test
    fun `moveProject hands a tab over and takes it off the window that had it`() {
        val list = listOf(ws(0, "work", "/p/a", "/p/b"), ws(1, "games", "/p/c"))
        val moved = Workspaces.moveProject(list, p("/p/b"), toId = 1)

        assertEquals(listOf(p("/p/a")), moved[0].projects)
        assertEquals(listOf(p("/p/c"), p("/p/b")), moved[1].projects)
        // The window it landed in shows it — that is what the user just asked for.
        assertEquals(p("/p/b"), moved[1].active)
    }

    @Test
    fun `moving the tab that was in front leaves the donor showing its neighbour`() {
        val list = listOf(
            Workspace(0, "work", listOf(p("/p/a"), p("/p/b")), active = p("/p/b")),
            ws(1, "games", "/p/c"),
        )
        val moved = Workspaces.moveProject(list, p("/p/b"), toId = 1)
        assertEquals(p("/p/a"), moved[0].active)
    }

    @Test
    fun `moving a project to the window it is already in changes nothing`() {
        val list = listOf(ws(0, "work", "/p/a"))
        assertEquals(list, Workspaces.moveProject(list, p("/p/a"), toId = 0))
    }

    @Test
    fun `moveProject ignores a window that isn't there`() {
        val list = listOf(ws(0, "work", "/p/a"))
        assertEquals(list, Workspaces.moveProject(list, p("/p/a"), toId = 7))
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
        assertEquals(p("/p/ops"), out[0].active)
        assertEquals(p("/p/paren"), out[1].active)
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
