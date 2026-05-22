package iondrive.nop.launchers

import java.nio.file.Files
import java.nio.file.Path

/**
 * A version-controlled launcher: a named shell command that runs in the project root.
 * Stored at `.nop/launchers.txt` so the file ships with the repo.
 */
data class Launcher(
    val name: String,
    val command: String,
) {
    init {
        require(name.isNotBlank()) { "launcher name is blank" }
        require(command.isNotBlank()) { "launcher command is blank" }
        require('\t' !in name && '\n' !in name) { "launcher name cannot contain tab or newline" }
        require('\n' !in command) { "launcher command cannot contain a newline" }
    }
}

/**
 * Reads and writes the launchers file for one project.
 *
 * The file format is one launcher per line, `name<TAB>command`. Blank lines and lines starting
 * with `#` are comments. The format is line-oriented so it diffs cleanly under version control
 * and is easy to edit by hand.
 */
class LauncherStore(private val projectRoot: Path) {
    val file: Path get() = projectRoot.resolve(".nop").resolve("launchers.txt")

    fun load(): List<Launcher> {
        val f = file
        if (!Files.isRegularFile(f)) return emptyList()
        val text = runCatching { Files.readString(f) }.getOrNull() ?: return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
            .toList()
    }

    fun save(launchers: List<Launcher>) {
        val f = file
        Files.createDirectories(f.parent)
        val body = buildString {
            appendLine("# nop launchers — one per line: name<TAB>command")
            for (l in launchers) {
                append(l.name).append('\t').append(l.command).append('\n')
            }
        }
        Files.writeString(f, body)
    }

    private fun parseLine(line: String): Launcher? {
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.length - 1) return null
        val name = line.substring(0, tab).trim()
        val command = line.substring(tab + 1).trim()
        if (name.isEmpty() || command.isEmpty()) return null
        return runCatching { Launcher(name, command) }.getOrNull()
    }
}
