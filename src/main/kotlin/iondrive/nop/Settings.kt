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

data class SplitRatios(val horizontal: Float?, val vertical: Float?)

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

    fun saveWindowGeometry(g: WindowGeometry) {
        val map = load()
        map["window.width"] = g.width.toString()
        map["window.height"] = g.height.toString()
        if (g.x != null) map["window.x"] = g.x.toString() else map.remove("window.x")
        if (g.y != null) map["window.y"] = g.y.toString() else map.remove("window.y")
        save(map)
    }

    /**
     * Loads the persisted divider ratios for the two splits. Null entries mean the user hasn't
     * dragged that divider yet, so callers should fall back to a sensible default.
     */
    fun loadSplitRatios(): SplitRatios {
        val map = load()
        return SplitRatios(
            horizontal = map["split.h"]?.toFloatOrNull()?.takeIf { it in 0f..1f },
            vertical = map["split.v"]?.toFloatOrNull()?.takeIf { it in 0f..1f },
        )
    }

    fun saveSplitRatios(horizontal: Float, vertical: Float) {
        val map = load()
        map["split.h"] = horizontal.toString()
        map["split.v"] = vertical.toString()
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
