package iondrive.nop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

data class WindowGeometry(
    val width: Int,
    val height: Int,
    val x: Int?,
    val y: Int?,
)

data class SplitRatios(val horizontal: Float?, val tools: Float?, val diff: Float?)

/**
 * Tiny persistent settings stored at $XDG_CONFIG_HOME/nop/state (default ~/.config/nop/state)
 * as a key=value file.
 *
 * Open projects are stored as `open.0=…`, `open.1=…`, etc. Backward compatible with:
 *   - the original single-line "just a project path" format
 *   - the previous single-project `project=…` key
 * Both fall back into the open-projects list when no `open.N` entries are present.
 */
object Settings {
    /** Overridable for tests; defaults to XDG_CONFIG_HOME (or ~/.config). */
    var configRoot: Path = defaultConfigRoot()

    private fun defaultConfigRoot(): Path {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        return if (xdg.isNullOrBlank()) {
            Paths.get(System.getProperty("user.home"), ".config")
        } else {
            Paths.get(xdg)
        }
    }

    private val configFile: Path
        get() = configRoot.resolve("nop").resolve("state")

    private fun load(): MutableMap<String, String> {
        val f = configFile
        if (!Files.isRegularFile(f)) return mutableMapOf()
        val text = runCatching { Files.readString(f) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = t.indexOf('=')
            if (eq <= 0) {
                // Legacy single-line format: a bare project path. Honour it.
                if (!map.containsKey("project")) map["project"] = t
                continue
            }
            map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
        return map
    }

    private fun save(map: Map<String, String>) {
        val f = configFile
        runCatching {
            Files.createDirectories(f.parent)
            val body = map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
            Files.writeString(f, body)
        }
    }

    fun loadOpenProjects(): List<Path> {
        val map = load()
        // Prefer the new numbered open.N keys, preserving order.
        val numbered = map.entries
            .mapNotNull { (k, v) ->
                val idx = k.removePrefix("open.").toIntOrNull()?.takeIf { k.startsWith("open.") }
                if (idx == null) null else idx to v
            }
            .filter { it.second.isNotBlank() }
            .sortedBy { it.first }
            .mapNotNull { runCatching { Paths.get(it.second) }.getOrNull() }

        if (numbered.isNotEmpty()) return numbered

        // Fall back to the legacy single-project key.
        val legacy = map["project"]?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOfNotNull(runCatching { Paths.get(legacy) }.getOrNull())
    }

    fun saveOpenProjects(paths: List<Path>) {
        val map = load()
        // Drop any pre-existing open.* and the legacy project key, then write the new list.
        map.keys.filter { it.startsWith("open.") }.toList().forEach(map::remove)
        map.remove("project")
        paths.forEachIndexed { idx, p ->
            map["open.$idx"] = p.toAbsolutePath().normalize().toString()
        }
        save(map)
    }

    /**
     * Every window the user has, in order: its name, its project tabs (with any name the user gave
     * one), which of them was active, where the window sat on screen, and whether it was showing when
     * nop last exited. Stored as `ws.0.name`, `ws.0.project.0`, `ws.0.tabname.0`, … one numbered
     * block per window.
     *
     * When no `ws.N` block exists the state is from a build that had a single window and grouped its
     * projects with named separators in one bar, so the layout is upgraded on the spot — see
     * [Workspaces.migrateRail]. Older still (no rail either) is one unnamed window holding whatever
     * `open.N`/`project` recorded. Neither is written back until something changes, so nothing is
     * lost by looking.
     */
    fun loadWorkspaces(): List<Workspace> {
        val map = load()
        val indices = map.keys
            .filter { it.startsWith(WORKSPACE_PREFIX) }
            .mapNotNull { it.removePrefix(WORKSPACE_PREFIX).substringBefore('.').toIntOrNull() }
            .distinct()
            .sorted()
        if (indices.isEmpty()) return migratedWorkspaces(map)
        // Tab ids are handed out across the whole file, not per window, so a tab moved to another
        // window can't collide with one already there.
        var nextTab = 0L
        return indices.mapIndexed { seq, i ->
            val prefix = "$WORKSPACE_PREFIX$i."
            // A tab's own name, where it has been given one; blank for the rest, which go by their
            // project's directory name.
            val tabs = ProjectTabs.of(numbered(map, prefix + "project."), nextTab)
                .mapIndexed { j, tab -> tab.copy(name = map["${prefix}tabname.$j"].orEmpty()) }
            nextTab += tabs.size
            Workspace(
                // Ids are handed out by position: nothing outside one run refers to a window by id.
                id = seq.toLong(),
                name = map[prefix + "name"].orEmpty(),
                tabs = tabs,
                // A window with tabs always has one in front — a saved position that no longer
                // exists (or a file that never wrote one) falls back to the first tab rather than
                // opening the window on nothing.
                active = ProjectTabs.initialActive(tabs, savedActiveIndex(map[prefix + "active"], tabs)),
                geometry = decodeGeometry(map[prefix + "geom"]),
                // Absent means open: only a window the user actually closed writes `open=false`.
                open = map[prefix + "open"] != "false",
                closedAt = map[prefix + "closed"]?.toLongOrNull(),
            )
        }
    }

    /**
     * Which tab a window's saved `active` value points at, as a position in [tabs]. It holds that
     * position, since a project may now have more than one tab and a path would no longer say which
     * of them was in front; a value from a build before that is the active project's path, and is
     * resolved to the first tab on it.
     */
    private fun savedActiveIndex(value: String?, tabs: List<ProjectTab>): Int? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        raw.toIntOrNull()?.let { return it }
        val path = parsePath(raw)?.toAbsolutePath()?.normalize() ?: return null
        return tabs.indexOfFirst { it.path == path }.takeIf { it >= 0 }
    }

    /**
     * Persists the window list. Also mirrors every project path into `open.N` (in window order) so
     * [loadOpenProjects] — and a build from before windows existed — still sees the open set. The
     * retired `rail.N` rows are dropped as they are superseded, so the upgrade happens once.
     */
    fun saveWorkspaces(list: List<Workspace>) {
        val map = load()
        map.keys
            .filter { it.startsWith(WORKSPACE_PREFIX) || it.startsWith("open.") || it.startsWith("rail.") }
            .toList()
            .forEach(map::remove)
        map.remove("project")
        list.forEachIndexed { i, ws ->
            val prefix = "$WORKSPACE_PREFIX$i."
            // Newlines in a name would split the line format, so they are flattened on the way out.
            map[prefix + "name"] = ws.name.replace('\n', ' ').replace('\r', ' ')
            map[prefix + "open"] = ws.open.toString()
            // The tab in front by position, not by path: two tabs may be on the same project.
            ws.tabs.indexOfFirst { it.id == ws.active }.takeIf { it >= 0 }
                ?.let { map[prefix + "active"] = it.toString() }
            ws.closedAt?.let { map[prefix + "closed"] = it.toString() }
            ws.geometry?.let { map[prefix + "geom"] = encodeGeometry(it) }
            ws.tabs.forEachIndexed { j, tab ->
                map["${prefix}project.$j"] = tab.path.toAbsolutePath().normalize().toString()
                // Only a renamed tab writes a name; the rest are named by their directory, and a row
                // saying so would be a row to keep in step for nothing.
                if (tab.name.isNotBlank()) {
                    map["${prefix}tabname.$j"] = tab.name.replace('\n', ' ').replace('\r', ' ')
                }
            }
        }
        Workspaces.allProjects(list).forEachIndexed { idx, p ->
            map["open.$idx"] = p.toAbsolutePath().normalize().toString()
        }
        save(map)
    }

    private const val WORKSPACE_PREFIX = "ws."

    /** The one-time read of a pre-windows state file. Empty when there is nothing to upgrade. */
    private fun migratedWorkspaces(map: Map<String, String>): List<Workspace> {
        val rail = map.entries
            .mapNotNull { (k, v) ->
                val idx = if (k.startsWith("rail.")) k.removePrefix("rail.").toIntOrNull() else null
                if (idx == null || v.isBlank()) null else idx to v
            }
            .sortedBy { it.first }
            .map { it.second }
        val active = loadActiveProject()?.toAbsolutePath()?.normalize()
        val geometry = loadWindowGeometry()
        if (rail.isNotEmpty()) return Workspaces.migrateRail(rail, active, geometry)
        val projects = loadOpenProjects().map { it.toAbsolutePath().normalize() }
        if (projects.isEmpty()) return emptyList()
        val tabs = ProjectTabs.of(projects)
        return listOf(
            Workspace(
                id = 0,
                name = "",
                tabs = tabs,
                active = ProjectTabs.initialActive(tabs, tabs.indexOfFirst { it.path == active }.takeIf { it >= 0 }),
                geometry = geometry,
            ),
        )
    }

    /** Values of the `<prefix>0`, `<prefix>1`, … keys as paths, in index order. */
    private fun numbered(map: Map<String, String>, prefix: String): List<Path> =
        map.entries
            .mapNotNull { (k, v) ->
                val idx = if (k.startsWith(prefix)) k.removePrefix(prefix).toIntOrNull() else null
                if (idx == null || v.isBlank()) null else idx to v
            }
            .sortedBy { it.first }
            .mapNotNull { parsePath(it.second) }
            .map { it.toAbsolutePath().normalize() }

    private fun parsePath(value: String): Path? = runCatching { Paths.get(value) }.getOrNull()

    /** `width,height` for a window that has never been moved, `width,height,x,y` for one that has. */
    private fun encodeGeometry(g: WindowGeometry): String =
        if (g.x == null || g.y == null) "${g.width},${g.height}" else "${g.width},${g.height},${g.x},${g.y}"

    private fun decodeGeometry(value: String?): WindowGeometry? {
        val parts = value?.split(',')?.map { it.trim() } ?: return null
        if (parts.size < 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return WindowGeometry(w, h, parts.getOrNull(2)?.toIntOrNull(), parts.getOrNull(3)?.toIntOrNull())
    }

    /** Most-recently-opened first. */
    fun loadRecentProjects(): List<Path> {
        val map = load()
        return map.entries
            .mapNotNull { (k, v) ->
                val idx = if (k.startsWith("recent.")) k.removePrefix("recent.").toIntOrNull() else null
                if (idx == null) null else idx to v
            }
            .filter { it.second.isNotBlank() }
            .sortedBy { it.first }
            .mapNotNull { runCatching { Paths.get(it.second) }.getOrNull() }
    }

    fun saveRecentProjects(paths: List<Path>) {
        val map = load()
        map.keys.filter { it.startsWith("recent.") }.toList().forEach(map::remove)
        paths.take(RECENT_PROJECTS_CAP).forEachIndexed { idx, p ->
            map["recent.$idx"] = p.toAbsolutePath().normalize().toString()
        }
        save(map)
    }

    /** Bumps [path] to the top of the recents list, deduping by absolute/normalized path. */
    fun addRecentProject(path: Path) {
        val norm = path.toAbsolutePath().normalize()
        val current = loadRecentProjects().map { it.toAbsolutePath().normalize() }
        val next = (listOf(norm) + current.filter { it != norm }).take(RECENT_PROJECTS_CAP)
        saveRecentProjects(next)
    }

    private const val RECENT_PROJECTS_CAP = 10

    /**
     * The project tab that was active when nop last ran, so reopening restores the same view.
     * Null when nothing was saved or no project was active.
     */
    fun loadActiveProject(): Path? {
        val v = load()["active"]?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Paths.get(v) }.getOrNull()
    }

    fun saveActiveProject(path: Path?) {
        val map = load()
        if (path == null) map.remove("active") else map["active"] = path.toAbsolutePath().normalize().toString()
        save(map)
    }

    /**
     * The size and position of the single window builds before this one had. Windows carry their own
     * geometry now (see [loadWorkspaces]); this is read only to seed the ones an upgrade creates, and
     * the first window of a fresh install.
     */
    fun loadWindowGeometry(): WindowGeometry? {
        val map = load()
        val w = map["window.width"]?.toIntOrNull() ?: return null
        val h = map["window.height"]?.toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return WindowGeometry(
            width = w,
            height = h,
            x = map["window.x"]?.toIntOrNull(),
            y = map["window.y"]?.toIntOrNull(),
        )
    }

    /**
     * Loads the persisted divider ratios for the app's two splits and the one between a diff's two
     * halves. Null entries mean the user hasn't dragged that divider yet, so callers should fall
     * back to a sensible default.
     */
    fun loadSplitRatios(): SplitRatios {
        val map = load()
        return SplitRatios(
            horizontal = map["split.h"]?.toFloatOrNull()?.takeIf { it in 0f..1f },
            // "split.tools" replaced "split.v" when the tool panel moved from the bottom to the
            // right edge — a saved height fraction must not be reused as a width fraction.
            tools = map["split.tools"]?.toFloatOrNull()?.takeIf { it in 0f..1f },
            diff = map["split.diff"]?.toFloatOrNull()?.takeIf { it in 0f..1f },
        )
    }

    fun saveSplitRatios(horizontal: Float, tools: Float, diff: Float) {
        val map = load()
        map["split.h"] = horizontal.toString()
        map["split.tools"] = tools.toString()
        map["split.diff"] = diff.toString()
        // "split.preview" was the markdown editor/preview divider. The preview moved into the tool
        // panel, which is sized by "split.tools", so the key is no longer read or written; a state
        // file left over from an older build simply carries a line nothing looks at.
        save(map)
    }

    /** Returns the persisted theme, defaulting to dark when nothing is saved or the value is unknown. */
    fun loadDarkMode(): Boolean = when (load()["theme"]?.lowercase()) {
        "light" -> false
        else -> true
    }

    fun saveDarkMode(dark: Boolean) {
        val map = load()
        map["theme"] = if (dark) "dark" else "light"
        save(map)
    }

    /**
     * Whether long lines soft-wrap in the editor and in diffs. Global rather than per-project, like
     * the theme: it's a preference about how the user reads code, not about a particular repo.
     * Defaults to off (long lines run off the edge behind a horizontal scrollbar), which is what a
     * diff has always done and what the line-numbered views are laid out for.
     */
    fun loadWrapLines(): Boolean = load()["wrap"]?.lowercase() == "true"

    fun saveWrapLines(wrap: Boolean) {
        val map = load()
        map["wrap"] = wrap.toString()
        save(map)
    }

    /**
     * Height of the commit-message text area in dp, persisted per-project. Returns null when
     * the user hasn't dragged the handle yet so the caller can fall back to a sensible default.
     */
    fun loadCommitMessageHeight(projectPath: Path): Float? {
        val f = projectDataDir(projectPath).resolve("commit-height")
        if (!Files.isRegularFile(f)) return null
        return runCatching { Files.readString(f).trim().toFloat() }.getOrNull()
            ?.takeIf { it in 24f..2000f }
    }

    fun saveCommitMessageHeight(projectPath: Path, heightDp: Float) {
        val f = projectDataDir(projectPath).resolve("commit-height")
        runCatching {
            Files.createDirectories(f.parent)
            Files.writeString(f, heightDp.toString())
        }
    }

    /**
     * Recently used commit messages for this project, newest first, for the reuse dropdown.
     * Persisted NUL-separated rather than line-delimited so multi-line messages survive a
     * round-trip intact.
     */
    fun loadRecentCommitMessages(projectPath: Path): List<String> {
        val f = projectDataDir(projectPath).resolve("commit-messages")
        if (!Files.isRegularFile(f)) return emptyList()
        return runCatching { Files.readString(f) }.getOrNull()
            ?.split('\u0000')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    fun saveRecentCommitMessages(projectPath: Path, messages: List<String>) {
        val f = projectDataDir(projectPath).resolve("commit-messages")
        runCatching {
            Files.createDirectories(f.parent)
            Files.writeString(f, messages.joinToString("\u0000"))
        }
    }

    /**
     * A launcher run that had a tab in the Run strip: which launcher it was, and whatever the tab
     * was called by the time nop closed.
     *
     * The command is stored beside the name rather than looked up again from `.nop/launchers.txt`,
     * so a tab survives its launcher being renamed or deleted between one run of nop and the next.
     * A tab that pointed at nothing would be a label with no button under it.
     */
    data class OpenRun(val name: String, val command: String, val title: String)

    /**
     * The Run tabs this project had open when nop last exited, in strip order.
     *
     * Per project and outside it, like the commit-message history: which scripts someone had on
     * screen is about how they were working, not about the repository, and a file in the working
     * tree would be one more thing to gitignore.
     */
    fun loadOpenRuns(projectPath: Path): List<OpenRun> {
        val f = projectDataDir(projectPath).resolve("runs")
        if (!Files.isRegularFile(f)) return emptyList()
        val text = runCatching { Files.readString(f) }.getOrNull() ?: return emptyList()
        return text.lineSequence()
            .mapNotNull { line ->
                // Name, command, title — the launcher file's own format with the tab name added.
                // A line short of three fields is from a half-written file and is simply skipped.
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val name = parts[0].trim()
                val command = parts[1].trim()
                if (name.isEmpty() || command.isEmpty()) null
                else OpenRun(name, command, parts[2].trim().ifEmpty { name })
            }
            .toList()
    }

    fun saveOpenRuns(projectPath: Path, runs: List<OpenRun>) {
        val f = projectDataDir(projectPath).resolve("runs")
        runCatching {
            Files.createDirectories(f.parent)
            // Tabs and newlines are the record separators, so a title carrying one is flattened on
            // the way out rather than splitting the row it belongs to.
            val body = runs.joinToString("\n") { run ->
                "${flatten(run.name)}\t${flatten(run.command)}\t${flatten(run.title)}"
            }
            Files.writeString(f, if (body.isEmpty()) body else body + "\n")
        }
    }

    /**
     * The paths whose git log had a tab in the tool strip when nop last exited, in strip order.
     *
     * Per project and outside it, like the Run tabs above: which logs someone had open is about how
     * they were working, not about the repository. One absolute path per line — a log needs nothing
     * else to be rebuilt, because it is re-read from git when its tab is composed.
     */
    fun loadOpenHistories(projectPath: Path): List<String> {
        val f = projectDataDir(projectPath).resolve("histories")
        if (!Files.isRegularFile(f)) return emptyList()
        val text = runCatching { Files.readString(f) }.getOrNull() ?: return emptyList()
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    fun saveOpenHistories(projectPath: Path, paths: List<String>) {
        val f = projectDataDir(projectPath).resolve("histories")
        runCatching {
            Files.createDirectories(f.parent)
            // Newlines are the record separator, so a path carrying one — legal on unix, and the
            // only way a row could split in two — is dropped rather than allowed to write a row
            // that would load back as two paths that never existed.
            val body = paths.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n")
            Files.writeString(f, if (body.isEmpty()) body else body + "\n")
        }
    }

    /**
     * An agent session that had a tab in the tool strip when nop last exited: nop's own id for it,
     * the account whose CLI was running, the vendor's session id to resume, and what the tab was
     * called.
     *
     * [nativeSessionId] is the row's reason for existing. It is what `claude --resume` and
     * `codex resume` take, so it is the difference between a tab that comes back into the
     * conversation it was in and a tab that comes back empty — and a session without one is left
     * out of the file entirely rather than restored as the latter.
     *
     * [sessionId] is nop's own, not the vendor's, and it is written down so a restored tab keeps the
     * event log it already had. Without it every restart would split one piece of work into a new
     * session in the picker and a new log on disk, which is the same continuity a provider switch
     * is careful to keep (see [iondrive.nop.agent.AgentSession]).
     *
     * [titleIsUsers] travels with [title] because the two mean different things to the CLI's own
     * idea of what the session is about: a title nop inferred may be replaced by a better one after
     * the resume, and a name the user typed may not.
     */
    data class OpenAgent(
        val sessionId: String,
        val provider: String,
        val account: String,
        val nativeSessionId: String,
        val title: String,
        val titleIsUsers: Boolean,
    )

    /**
     * The agent tabs this project had open when nop last exited, in strip order.
     *
     * Per project and outside it, like the Run tabs and the git logs above. Six tab-separated
     * fields; a row short of them, or missing one of the three that cannot be guessed at, is from a
     * half-written file and is skipped rather than restored as a tab pointing at nothing.
     */
    fun loadOpenAgents(projectPath: Path): List<OpenAgent> {
        val f = projectDataDir(projectPath).resolve("agents")
        if (!Files.isRegularFile(f)) return emptyList()
        val text = runCatching { Files.readString(f) }.getOrNull() ?: return emptyList()
        return text.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 6) return@mapNotNull null
                val sessionId = parts[0].trim()
                val provider = parts[1].trim()
                val account = parts[2].trim()
                val nativeSessionId = parts[3].trim()
                if (sessionId.isEmpty() || account.isEmpty() || nativeSessionId.isEmpty()) {
                    return@mapNotNull null
                }
                OpenAgent(
                    sessionId = sessionId,
                    provider = provider,
                    account = account,
                    nativeSessionId = nativeSessionId,
                    // Blank is a legal title here and means "nop never found a better name for it".
                    // What a nameless tab is called is the session's business, not this file's — see
                    // AgentSession.DEFAULT_TITLE.
                    title = parts[4].trim(),
                    titleIsUsers = parts[5].trim() == "1",
                )
            }
            .toList()
    }

    fun saveOpenAgents(projectPath: Path, agents: List<OpenAgent>) {
        val f = projectDataDir(projectPath).resolve("agents")
        runCatching {
            Files.createDirectories(f.parent)
            // Tabs and newlines are the record separators, so a title carrying one is flattened on
            // the way out rather than splitting the row it belongs to. The ids and the account name
            // go through [flatten] too — none of them can legally hold a separator, and a corrupted
            // one costs its own row instead of the row after it.
            val body = agents.joinToString("\n") { agent ->
                listOf(
                    flatten(agent.sessionId),
                    flatten(agent.provider),
                    flatten(agent.account),
                    flatten(agent.nativeSessionId),
                    flatten(agent.title),
                    if (agent.titleIsUsers) "1" else "0",
                ).joinToString("\t")
            }
            Files.writeString(f, if (body.isEmpty()) body else body + "\n")
        }
    }

    private fun flatten(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /**
     * Per-project scratch directory under the nop config root. Used for derived data we don't
     * want to spray into the project itself — currently just the symbol index. Two projects
     * with the same final path segment (e.g. two `frontend/` checkouts) get separate dirs
     * because the suffix is derived from the full absolute path.
     */
    fun projectDataDir(projectPath: Path): Path {
        val abs = projectPath.toAbsolutePath().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-1").digest(abs.toByteArray())
        val short = digest.joinToString("") { "%02x".format(it) }.take(10)
        val safeName = projectPath.fileName?.toString()
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?.takeIf { it.isNotEmpty() }
            ?: "project"
        return configRoot.resolve("nop").resolve("projects").resolve("$safeName-$short")
    }
}
