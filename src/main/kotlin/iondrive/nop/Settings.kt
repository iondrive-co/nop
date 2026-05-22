package iondrive.nop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class WindowGeometry(
    val width: Int,
    val height: Int,
    val x: Int?,
    val y: Int?,
)

/**
 * Tiny persistent settings stored at $XDG_CONFIG_HOME/nop/state (default ~/.config/nop/state)
 * as a key=value file. Backward compatible with the original single-line "just a project path"
 * format — if the file has no '=' lines, the first non-blank line is treated as the project path.
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

    fun loadLastProject(): Path? {
        val raw = load()["project"]?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Paths.get(raw) }.getOrNull()
    }

    fun saveLastProject(path: Path) {
        val map = load()
        map["project"] = path.toAbsolutePath().normalize().toString()
        save(map)
    }

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
}
