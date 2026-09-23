package iondrive.nop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SettingsTest {
    private val originalRoot: Path = Settings.configRoot

    @AfterEach
    fun restoreRoot() {
        Settings.configRoot = originalRoot
    }

    @Test
    fun `loadOpenProjects returns empty when no state file exists`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertTrue(Settings.loadOpenProjects().isEmpty())
    }

    @Test
    fun `save then load round-trips a single open project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("some/project").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(project))

        val loaded = Settings.loadOpenProjects()
        assertEquals(listOf(project.toAbsolutePath().normalize()), loaded)
    }

    @Test
    fun `save then load preserves order across multiple open projects`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        val c = tmp.resolve("c").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(a, b, c))

        assertEquals(
            listOf(a, b, c).map { it.toAbsolutePath().normalize() },
            Settings.loadOpenProjects(),
        )
    }

    @Test
    fun `loadOpenProjects tolerates a blank state file`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "\n   \n")
        }
        assertTrue(Settings.loadOpenProjects().isEmpty(), "blank state should produce empty list — got file at $state")
    }

    @Test
    fun `legacy single-line state still resolves the project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "/home/u/old-format-project\n")
        }
        assertEquals(listOf(Paths.get("/home/u/old-format-project")), Settings.loadOpenProjects())
    }

    @Test
    fun `legacy project= key is migrated into the open list`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "project=/home/u/proj\ntheme=light\n")
        }
        assertEquals(listOf(Paths.get("/home/u/proj")), Settings.loadOpenProjects())

        // Saving a fresh list should drop the legacy `project=` key.
        val replacement = tmp.resolve("replacement").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(replacement))
        val raw = Files.readString(state)
        val keys = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue("project" !in keys, "legacy project= key should be dropped after a save, got:\n$raw")
        assertTrue(raw.contains("open.0="), "should write open.0= entry, got:\n$raw")
    }

    /** A window on [projects], its tabs numbered from [firstTab], showing the one at [active]. */
    private fun window(
        id: Long,
        name: String,
        projects: List<Path>,
        firstTab: Long = 0,
        active: Int? = 0,
        geometry: WindowGeometry? = null,
        open: Boolean = true,
        closedAt: Long? = null,
    ): Workspace {
        val tabs = ProjectTabs.of(projects, firstTab)
        return Workspace(id, name, tabs, active?.let { tabs[it].id }, geometry, open, closedAt)
    }

    @Test
    fun `workspaces round-trip name, tabs, active tab and geometry`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        val windows = listOf(
            window(0, "work", listOf(a), geometry = WindowGeometry(900, 700, -5, 40)),
            window(1, "games", listOf(b), firstTab = 1, open = false),
        )
        Settings.saveWorkspaces(windows)

        assertEquals(windows, Settings.loadWorkspaces())
    }

    @Test
    fun `two tabs on one project round-trip as two tabs, with the right one in front`(
        @TempDir tmp: Path,
    ) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        // The second tab on `a` is the one in front — the case a saved path could not tell apart
        // from the first, which is why the active tab is saved as a position.
        val windows = listOf(window(0, "work", listOf(a, b, a), active = 2))
        Settings.saveWorkspaces(windows)

        val loaded = Settings.loadWorkspaces()
        assertEquals(listOf(a, b, a), loaded.single().projects)
        assertEquals(2, loaded.single().tabs.indexOfFirst { it.id == loaded.single().active })
        assertEquals(windows, loaded)
    }

    @Test
    fun `a renamed tab round-trips its name, and an unnamed one writes no row`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val window = window(0, "work", listOf(a, a))
        val named = window.copy(tabs = listOf(window.tabs[0].copy(name = "a — release"), window.tabs[1]))
        Settings.saveWorkspaces(listOf(named))

        assertEquals(listOf("a — release", "a"), Settings.loadWorkspaces().single().tabs.map { it.label })
        val raw = Files.readString(tmp.resolve("nop/state"))
        assertTrue("ws.0.tabname.0=a — release" in raw, "the renamed tab should be saved, got:\n$raw")
        assertTrue("ws.0.tabname.1" !in raw, "a tab with no name of its own should write none, got:\n$raw")
    }

    @Test
    fun `an active tab saved as a path by an older build still comes back`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also { Files.createDirectories(it.parent) }
        Files.writeString(
            state,
            """
            ws.0.name=work
            ws.0.open=true
            ws.0.active=/p/b
            ws.0.project.0=/p/a
            ws.0.project.1=/p/b
            """.trimIndent(),
        )

        val loaded = Settings.loadWorkspaces().single()
        assertEquals(Paths.get("/p/b"), loaded.activeTab?.path)
    }

    @Test
    fun `a window whose saved active tab is gone opens on its first tab`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also { Files.createDirectories(it.parent) }
        Files.writeString(
            state,
            """
            ws.0.name=work
            ws.0.open=true
            ws.0.active=7
            ws.0.project.0=/p/a
            """.trimIndent(),
        )

        assertEquals(Paths.get("/p/a"), Settings.loadWorkspaces().single().activeTab?.path)
    }

    @Test
    fun `tab ids are unique across the whole window list`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        Settings.saveWorkspaces(
            listOf(window(0, "work", listOf(a, a)), window(1, "games", listOf(a), firstTab = 2)),
        )

        val ids = Settings.loadWorkspaces().flatMap { it.tabs }.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `a parked window round-trips the time it was closed`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val windows = listOf(window(0, "games", listOf(a), open = false, closedAt = 1_700_000_000_000))

        Settings.saveWorkspaces(windows)

        assertEquals(windows, Settings.loadWorkspaces())
    }

    @Test
    fun `an unnamed window with no geometry round-trips`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val windows = listOf(window(0, "", listOf(a)))
        Settings.saveWorkspaces(windows)

        assertEquals(windows, Settings.loadWorkspaces())
    }

    @Test
    fun `saveWorkspaces mirrors every window's projects into the open list for backward compat`(
        @TempDir tmp: Path,
    ) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.saveWorkspaces(
            listOf(window(0, "work", listOf(a)), window(1, "games", listOf(b), firstTab = 1, open = false)),
        )

        assertEquals(listOf(a, b).map { it.toAbsolutePath().normalize() }, Settings.loadOpenProjects())
    }

    @Test
    fun `loadWorkspaces upgrades a rail layout into one window per group`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also { Files.createDirectories(it.parent) }
        Files.writeString(
            state,
            """
            active=/p/c
            rail.0=sep:work
            rail.1=project:/p/a
            rail.2=project:/p/b
            rail.3=sepc:games
            rail.4=project:/p/c
            """.trimIndent(),
        )

        val loaded = Settings.loadWorkspaces()
        assertEquals(listOf("work", "games"), loaded.map { it.name })
        assertEquals(listOf(Paths.get("/p/a"), Paths.get("/p/b")), loaded[0].projects)
        assertEquals(listOf(Paths.get("/p/c")), loaded[1].projects)
        // Both groups open, the collapsed one included — a fold in the old bar didn't mean the
        // user was done with those projects.
        assertTrue(loaded.all { it.open })
        // Each window opens on its own first tab, bar the one holding the saved active project.
        assertEquals(Paths.get("/p/a"), loaded[0].activeTab?.path)
        assertEquals(Paths.get("/p/c"), loaded[1].activeTab?.path)
    }

    @Test
    fun `loadWorkspaces upgrades a project-only state into a single unnamed window`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(a))

        val loaded = Settings.loadWorkspaces()
        assertEquals(1, loaded.size)
        assertEquals("", loaded[0].name)
        assertEquals(listOf(a.toAbsolutePath().normalize()), loaded[0].projects)
        assertTrue(loaded[0].open)
    }

    @Test
    fun `saving workspaces drops the retired rail rows it upgraded from`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also { Files.createDirectories(it.parent) }
        Files.writeString(
            state,
            """
            rail.0=sep:work
            rail.1=project:/p/a
            theme=light
            """.trimIndent(),
        )

        Settings.saveWorkspaces(Settings.loadWorkspaces())

        val raw = Files.readString(state)
        assertTrue("rail." !in raw, "rail rows should be gone once upgraded, got:\n$raw")
        // Unrelated settings are left alone.
        assertTrue(!Settings.loadDarkMode())
    }

    @Test
    fun `the geometry an older single-window build saved seeds the windows upgraded from it`(
        @TempDir tmp: Path,
    ) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also { Files.createDirectories(it.parent) }
        Files.writeString(
            state,
            "window.width=1024\nwindow.height=768\nwindow.x=50\nwindow.y=100\nrail.0=project:/p/a\n",
        )

        assertEquals(WindowGeometry(1024, 768, 50, 100), Settings.loadWindowGeometry())
        assertEquals(WindowGeometry(1024, 768, 50, 100), Settings.loadWorkspaces().single().geometry)
    }

    @Test
    fun `loadWindowGeometry returns null when size missing`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Settings.saveOpenProjects(listOf(tmp.resolve("project").also { Files.createDirectories(it) }))
        assertNull(Settings.loadWindowGeometry())
    }

    @Test
    fun `recent projects round-trip in saved order`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.saveRecentProjects(listOf(a, b))
        assertEquals(
            listOf(a, b).map { it.toAbsolutePath().normalize() },
            Settings.loadRecentProjects(),
        )
    }

    @Test
    fun `addRecentProject prepends and dedupes`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.addRecentProject(a)
        Settings.addRecentProject(b)
        // Re-adding `a` should move it to the front, not duplicate.
        Settings.addRecentProject(a)
        assertEquals(
            listOf(a, b).map { it.toAbsolutePath().normalize() },
            Settings.loadRecentProjects(),
        )
    }

    @Test
    fun `addRecentProject caps the list at ten`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val dirs = (0 until 15).map { i ->
            tmp.resolve("p$i").also { Files.createDirectories(it) }
        }
        for (d in dirs) Settings.addRecentProject(d)

        val loaded = Settings.loadRecentProjects()
        assertEquals(10, loaded.size, "should cap at 10, got ${loaded.size}")
        // Most-recently-added is first; earliest entries fell off.
        assertEquals(dirs.last().toAbsolutePath().normalize(), loaded.first())
        assertEquals(dirs[5].toAbsolutePath().normalize(), loaded.last())
    }

    @Test
    fun `loadSplitRatios returns nulls when nothing is saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val r = Settings.loadSplitRatios()
        assertNull(r.horizontal)
        assertNull(r.tools)
        assertNull(r.diff)
        assertNull(r.session)
    }

    @Test
    fun `split ratios round-trip`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Settings.saveSplitRatios(horizontal = 0.31f, tools = 0.42f, diff = 0.63f, session = 0.55f)
        val r = Settings.loadSplitRatios()
        assertEquals(0.31f, r.horizontal)
        assertEquals(0.42f, r.tools)
        assertEquals(0.63f, r.diff)
        assertEquals(0.55f, r.session)
    }

    @Test
    fun `loadSplitRatios rejects out-of-range values`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val state = tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "split.h=1.5\nsplit.tools=-0.2\nsplit.diff=2.0\n")
        }
        val r = Settings.loadSplitRatios()
        assertNull(r.horizontal, "h=1.5 should be rejected")
        assertNull(r.tools, "tools=-0.2 should be rejected")
        assertNull(r.diff, "diff=2.0 should be rejected")
    }

    @Test
    fun `loadSplitRatios ignores the retired bottom-panel key`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "split.v=0.55\n")
        }
        assertNull(Settings.loadSplitRatios().tools, "split.v held a height fraction; it must not seed the width split")
    }

    @Test
    fun `a state file from before the preview moved still loads`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        tmp.resolve("nop/state").also {
            Files.createDirectories(it.parent)
            // "split.preview" sized the markdown editor/preview divider, which no longer exists —
            // the preview is a tool-panel tab now. The line must be inert, not fatal.
            Files.writeString(it, "split.h=0.3\nsplit.preview=0.24\n")
        }
        assertEquals(0.3f, Settings.loadSplitRatios().horizontal)
    }

    @Test
    fun `commit message height returns null when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        assertNull(Settings.loadCommitMessageHeight(project))
    }

    @Test
    fun `commit message height round-trips per project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.saveCommitMessageHeight(a, 142.5f)
        Settings.saveCommitMessageHeight(b, 200.0f)
        assertEquals(142.5f, Settings.loadCommitMessageHeight(a))
        assertEquals(200.0f, Settings.loadCommitMessageHeight(b))
    }

    @Test
    fun `commit message height rejects out-of-range values`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val heightFile = Settings.projectDataDir(project).resolve("commit-height")
        Files.createDirectories(heightFile.parent)
        Files.writeString(heightFile, "-50")
        assertNull(Settings.loadCommitMessageHeight(project), "negative height should be rejected")
        Files.writeString(heightFile, "9001")
        assertNull(Settings.loadCommitMessageHeight(project), "absurdly large height should be rejected")
        Files.writeString(heightFile, "garbage")
        assertNull(Settings.loadCommitMessageHeight(project), "non-numeric should be rejected")
    }

    @Test
    fun `recent commit messages return empty when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        assertTrue(Settings.loadRecentCommitMessages(project).isEmpty())
    }

    @Test
    fun `recent commit messages round-trip per project preserving order and multi-line bodies`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        // Messages with embedded spaces and newlines must survive the NUL-delimited round-trip intact.
        val messagesA = listOf("Fix the bug", "Add feature\n\nWith a longer body line", "Tweak things")
        Settings.saveRecentCommitMessages(a, messagesA)
        Settings.saveRecentCommitMessages(b, listOf("Other project message"))

        assertEquals(messagesA, Settings.loadRecentCommitMessages(a))
        assertEquals(listOf("Other project message"), Settings.loadRecentCommitMessages(b))
    }

    @Test
    fun `commit message draft returns empty when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        assertEquals("", Settings.loadCommitMessageDraft(project))
    }

    @Test
    fun `commit message draft round-trips per project and in-memory cache`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val b = tmp.resolve("b").also { Files.createDirectories(it) }
        Settings.saveCommitMessageDraft(a, "draft for a\nwith newline")
        Settings.saveCommitMessageDraft(b, "draft for b")

        assertEquals("draft for a\nwith newline", Settings.loadCommitMessageDraft(a))
        assertEquals("draft for b", Settings.loadCommitMessageDraft(b))

        // Survives in-memory cache clear (reads back from disk)
        Settings.clearCommitDraftsForTest()
        assertEquals("draft for a\nwith newline", Settings.loadCommitMessageDraft(a))
        assertEquals("draft for b", Settings.loadCommitMessageDraft(b))

        // Saving empty string clears draft and removes file
        Settings.saveCommitMessageDraft(a, "")
        assertEquals("", Settings.loadCommitMessageDraft(a))
        val draftFileA = Settings.projectDataDir(a).resolve("commit-draft")
        assertFalse(Files.exists(draftFileA))
    }

    @Test
    fun `active project returns null when nothing saved`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertNull(Settings.loadActiveProject())
    }

    @Test
    fun `active project round-trips and clears`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        Settings.saveActiveProject(proj)
        assertEquals(proj.toAbsolutePath().normalize(), Settings.loadActiveProject())

        // Saving null clears the key so the next launch falls back to the first tab.
        Settings.saveActiveProject(null)
        assertNull(Settings.loadActiveProject())
    }

    @Test
    fun `saving the active project does not clobber the open list`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(proj))
        Settings.saveActiveProject(proj)
        assertEquals(listOf(proj.toAbsolutePath().normalize()), Settings.loadOpenProjects())
    }

    @Test
    fun `word wrap defaults to off and round-trips once set`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        // Nothing saved yet: lines run off the edge, which is what the diffs' line grid assumes.
        assertTrue(!Settings.loadWrapLines())

        Settings.saveWrapLines(true)
        assertTrue(Settings.loadWrapLines())

        Settings.saveWrapLines(false)
        assertTrue(!Settings.loadWrapLines())
    }

    @Test
    fun `saving word wrap leaves the rest of the state alone`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        Settings.saveOpenProjects(listOf(proj))
        Settings.saveDarkMode(false)

        Settings.saveWrapLines(true)

        assertEquals(listOf(proj.toAbsolutePath().normalize()), Settings.loadOpenProjects())
        assertTrue(!Settings.loadDarkMode())
        assertTrue(Settings.loadWrapLines())
    }

    @Test
    fun `saving recent projects does not clobber the windows`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val proj = tmp.resolve("project").also { Files.createDirectories(it) }
        val a = tmp.resolve("a").also { Files.createDirectories(it) }
        val windows = listOf(window(0, "work", listOf(proj), geometry = WindowGeometry(800, 600, 0, 0)))
        Settings.saveWorkspaces(windows)
        Settings.addRecentProject(a)

        assertEquals(windows, Settings.loadWorkspaces())
        assertEquals(listOf(a.toAbsolutePath().normalize()), Settings.loadRecentProjects())
    }

    // The Run tabs a project had open. Stored per project under the config root, so every test
    // below points configRoot at a temp directory and the real one is never touched.

    @Test
    fun `loadOpenRuns returns empty for a project that has never had one`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertTrue(Settings.loadOpenRuns(tmp.resolve("project")).isEmpty())
    }

    @Test
    fun `open runs round-trip in strip order, carrying a renamed tab's name`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        val runs = listOf(
            Settings.OpenRun("release", "./scripts/release.sh", "release"),
            Settings.OpenRun("test", "./gradlew test", "the slow one"),
        )
        Settings.saveOpenRuns(project, runs)
        assertEquals(runs, Settings.loadOpenRuns(project))
    }

    /** Two checkouts with the same directory name must not read each other's tabs. */
    @Test
    fun `open runs are kept per project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val one = tmp.resolve("one/frontend")
        val two = tmp.resolve("two/frontend")
        Settings.saveOpenRuns(one, listOf(Settings.OpenRun("dev", "npm run dev", "dev")))

        assertEquals(1, Settings.loadOpenRuns(one).size)
        assertTrue(Settings.loadOpenRuns(two).isEmpty())
    }

    @Test
    fun `saving an empty list clears the project's runs`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        Settings.saveOpenRuns(project, listOf(Settings.OpenRun("dev", "npm run dev", "dev")))
        Settings.saveOpenRuns(project, emptyList())
        assertTrue(Settings.loadOpenRuns(project).isEmpty())
    }

    /**
     * A tab or newline in a name would split the row it is written on, so both are flattened to a
     * space rather than allowed to turn one run into two — or into none.
     */
    @Test
    fun `a title carrying a separator survives as one row`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        Settings.saveOpenRuns(project, listOf(Settings.OpenRun("dev", "npm run dev", "a\tb\nc")))

        assertEquals(
            listOf(Settings.OpenRun("dev", "npm run dev", "a b c")),
            Settings.loadOpenRuns(project),
        )
    }

    /** A half-written file must cost the rows it truncated and nothing more. */
    @Test
    fun `a malformed row is skipped rather than fatal`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        val file = Settings.projectDataDir(project).resolve("runs")
        Files.createDirectories(file.parent)
        Files.writeString(file, "dev\tnpm run dev\tdev\ntruncated\n\ttest\n")

        assertEquals(
            listOf(Settings.OpenRun("dev", "npm run dev", "dev")),
            Settings.loadOpenRuns(project),
        )
    }

    // The agent tabs a project had open. Stored per project beside the runs above.

    @Test
    fun `loadOpenAgents returns empty for a project that has never had one`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        assertTrue(Settings.loadOpenAgents(tmp.resolve("project")).isEmpty())
    }

    @Test
    fun `open agents round-trip in strip order, keeping who named the tab`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        val agents = listOf(
            Settings.OpenAgent(
                sessionId = "nop-1",
                provider = "anthropic",
                account = "claude-main",
                nativeSessionId = "11111111-2222-3333-4444-555555555555",
                title = "parser rewrite",
                titleIsUsers = true,
                baselineSha = "0123456789abcdef0123456789abcdef01234567",
            ),
            Settings.OpenAgent(
                sessionId = "nop-2",
                provider = "openai",
                account = "codex",
                nativeSessionId = "rollout-2026",
                title = "AWS support",
                titleIsUsers = false,
            ),
        )
        Settings.saveOpenAgents(project, agents)
        assertEquals(agents, Settings.loadOpenAgents(project))
    }

    /** Two checkouts with the same directory name must not resume each other's conversations. */
    @Test
    fun `open agents are kept per project`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val one = tmp.resolve("one/frontend")
        val two = tmp.resolve("two/frontend")
        Settings.saveOpenAgents(one, listOf(agentRow()))

        assertEquals(1, Settings.loadOpenAgents(one).size)
        assertTrue(Settings.loadOpenAgents(two).isEmpty())
    }

    @Test
    fun `saving an empty list clears the project's agents`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        Settings.saveOpenAgents(project, listOf(agentRow()))
        Settings.saveOpenAgents(project, emptyList())
        assertTrue(Settings.loadOpenAgents(project).isEmpty())
    }

    /**
     * The baseline sha arrived after the format was in use, as a seventh field. A six-field row is
     * an older nop's, and the tab it comes back as simply has no baseline — dropping it would cost
     * the user the session rather than the feature.
     */
    @Test
    fun `a six-field agent row restores with no baseline`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        val file = Settings.projectDataDir(project).resolve("agents")
        Files.createDirectories(file.parent)
        Files.writeString(file, "nop-1\tanthropic\tclaude-main\tvendor-1\told tab\t1\n")

        assertEquals(
            listOf(
                Settings.OpenAgent(
                    sessionId = "nop-1",
                    provider = "anthropic",
                    account = "claude-main",
                    nativeSessionId = "vendor-1",
                    title = "old tab",
                    titleIsUsers = true,
                    baselineSha = "",
                ),
            ),
            Settings.loadOpenAgents(project),
        )
    }

    @Test
    fun `the collapsed tool panel is remembered`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        // Nothing saved reads as "folded away", so a first run gives the whole region to the agent
        // and a tool panel is something the user asks for.
        assertEquals(true, Settings.loadToolsCollapsed())
        Settings.saveToolsCollapsed(false)
        assertEquals(false, Settings.loadToolsCollapsed(), "showing has to survive a restart too")
        Settings.saveToolsCollapsed(true)
        assertEquals(true, Settings.loadToolsCollapsed())
    }

    /** A tab or a newline in a name would split the row it is written on. */
    @Test
    fun `an agent title carrying a separator survives as one row`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        Settings.saveOpenAgents(project, listOf(agentRow(title = "a\tb\nc")))

        assertEquals(listOf(agentRow(title = "a b c")), Settings.loadOpenAgents(project))
    }

    /**
     * The three fields a row cannot be guessed at without: nop's own id names the log to carry on
     * writing, the account says whose CLI to run, and the vendor's id is the conversation itself.
     */
    @Test
    fun `a row missing an id, an account or a conversation is skipped`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        val file = Settings.projectDataDir(project).resolve("agents")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            |	anthropic	claude-main	vendor-1	no id	0
            |nop-2	anthropic		vendor-2	no account	0
            |nop-3	anthropic	claude-main		no conversation	0
            |nop-4	anthropic	claude-main	vendor-4	fine	0
            |truncated
            """.trimMargin() + "\n",
        )

        assertEquals(
            listOf(agentRow(sessionId = "nop-4", nativeSessionId = "vendor-4", title = "fine")),
            Settings.loadOpenAgents(project),
        )
    }

    /** A tab nop never found a name for is written blank, and stays blank for the session to name. */
    @Test
    fun `a nameless agent tab round-trips as a nameless one`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val project = tmp.resolve("project")
        Settings.saveOpenAgents(project, listOf(agentRow(title = "")))

        assertEquals("", Settings.loadOpenAgents(project).single().title)
    }

    private fun agentRow(
        sessionId: String = "nop-1",
        nativeSessionId: String = "vendor-1",
        title: String = "parser rewrite",
    ) = Settings.OpenAgent(
        sessionId = sessionId,
        provider = "anthropic",
        account = "claude-main",
        nativeSessionId = nativeSessionId,
        title = title,
        titleIsUsers = false,
    )
}
