package iondrive.nop.index

import java.io.File
import java.nio.file.Path

/**
 * Walks a project tree and extracts cross-file definitions into a [SymbolIndex].
 *
 * Rules are language-shaped (Ansible, TypeScript, Kotlin), not project-shaped — the project's
 * own conventions are derived from filesystem layout (role directories, `templates/`, etc.) at
 * index time, so nothing here needs to know about a specific repo.
 */
object Indexer {
    private val IGNORED_DIR_NAMES = setOf(
        ".git", ".idea", ".gradle", ".vscode",
        "node_modules", "build", "out", "target", "dist", ".next", "__pycache__",
    )

    private val YAML_EXT = setOf("yml", "yaml")
    private val TS_EXT = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
    private val KOTLIN_EXT = setOf("kt", "kts", "java")

    fun build(projectRoot: Path): SymbolIndex {
        val rootFile = projectRoot.toAbsolutePath().normalize().toFile()
        if (!rootFile.isDirectory) return SymbolIndex()
        val out = mutableListOf<IndexEntry>()
        walk(rootFile, rootFile, out)
        return SymbolIndex(out)
    }

    /**
     * Cheap "did the tree change since the cache was written" probe so callers can skip the full
     * [build] (which reads + regexes every file) when nothing has moved. This walk only stats
     * entries — no file contents are read.
     *
     * Returns true (stale) as soon as it sees evidence of a change:
     *  - a file modified after [since] (an edit),
     *  - a directory modified after [since] (an add / remove / rename bumps the parent's mtime),
     *  - a file count that differs from [cachedFileCount] (a safety net for add/remove).
     *
     * [since] is epoch millis — pass the cache file's last-modified time. A pure deletion that
     * leaves mtimes untouched on a filesystem that doesn't update directory mtimes is the one
     * case this can miss; the manual Refresh and the next real change both recover from it.
     */
    fun isStale(projectRoot: Path, since: Long, cachedFileCount: Int): Boolean {
        val rootFile = projectRoot.toAbsolutePath().normalize().toFile()
        if (!rootFile.isDirectory) return true
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(rootFile)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.name in IGNORED_DIR_NAMES) continue
                if (f.isDirectory) {
                    if (f.lastModified() > since) return true
                    stack.addLast(f)
                } else if (f.isFile) {
                    if (f.lastModified() > since) return true
                    count++
                    if (count > cachedFileCount) return true
                }
            }
        }
        return count != cachedFileCount
    }

    private fun walk(root: File, dir: File, out: MutableList<IndexEntry>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name in IGNORED_DIR_NAMES) continue
            if (f.isDirectory) {
                indexDirectory(root, f, out)
                walk(root, f, out)
            } else if (f.isFile) {
                indexFile(root, f, out)
            }
        }
    }

    // ---- directory-shaped definitions ---------------------------------------------------------

    private fun indexDirectory(root: File, dir: File, out: MutableList<IndexEntry>) {
        // Ansible: any directory directly under a "roles" parent is a role. Jump target is the
        // role's tasks/main.yml when present, otherwise the role directory itself.
        if (dir.parentFile?.name == "roles") {
            val mainYml = File(dir, "tasks/main.yml")
            val target = if (mainYml.isFile) mainYml else dir
            out += IndexEntry(dir.name, relPath(root, target), 1, SymbolKind.ANSIBLE_ROLE)
        }
    }

    // ---- file-shaped definitions --------------------------------------------------------------

    private fun indexFile(root: File, file: File, out: MutableList<IndexEntry>) {
        val rel = relPath(root, file)
        val parts = rel.split('/')
        val parent = parts.getOrNull(parts.size - 2)
        val ext = file.extension.lowercase()
        val baseName = file.nameWithoutExtension

        // Ansible tasks/handlers: a YAML file inside tasks/ or handlers/ can be the target of
        // `import_tasks: foo.yml` or `include_tasks: foo.yml`, with the file basename.
        if ((parent == "tasks" || parent == "handlers") && ext in YAML_EXT) {
            out += IndexEntry(baseName, rel, 1, SymbolKind.ANSIBLE_TASKS)
        }

        // Ansible templates: keyed by full filename so `src: foo.j2` resolves; we also add the
        // bare basename so a cursor that lands inside `foo` (without the extension) still hits.
        if (parent == "templates") {
            out += IndexEntry(file.name, rel, 1, SymbolKind.ANSIBLE_TEMPLATE)
            if (baseName != file.name) {
                out += IndexEntry(baseName, rel, 1, SymbolKind.ANSIBLE_TEMPLATE)
            }
        }

        // Top-level playbook YAML files (e.g. `adn_api_server.yml` at the repo root) — make
        // them jumpable from `roles:` lists and from `import_playbook:` references.
        if (ext in YAML_EXT && parts.size <= 2) {
            out += IndexEntry(baseName, rel, 1, SymbolKind.ANSIBLE_TASKS)
        }

        // Ansible variables: top-level keys in group_vars/, host_vars/, role defaults/, role vars/.
        val isVarFile = ext in YAML_EXT && parts.any { it in setOf("group_vars", "host_vars", "defaults", "vars") }
        if (isVarFile) {
            extractYamlTopLevelKeys(file).forEach { (name, ln) ->
                out += IndexEntry(name, rel, ln, SymbolKind.ANSIBLE_VAR)
            }
        }

        // set_fact: declarations live in any tasks file; extract from all YAML.
        if (ext in YAML_EXT) {
            extractSetFacts(file).forEach { (name, ln) ->
                out += IndexEntry(name, rel, ln, SymbolKind.ANSIBLE_VAR)
            }
        }

        // TypeScript/JavaScript exports.
        if (ext in TS_EXT) {
            extractTsExports(file).forEach { (name, ln) ->
                out += IndexEntry(name, rel, ln, SymbolKind.TS_SYMBOL)
            }
        }

        // Kotlin / Java top-level declarations — handy in nop's own codebase even if the user's
        // target projects don't need them.
        if (ext in KOTLIN_EXT) {
            extractKotlinDefs(file).forEach { (name, ln) ->
                out += IndexEntry(name, rel, ln, SymbolKind.KOTLIN_SYMBOL)
            }
        }
    }

    // ---- extractors ---------------------------------------------------------------------------

    private val YAML_TOPLEVEL_KEY = Regex("""^([A-Za-z_][A-Za-z0-9_-]*)\s*:""")

    internal fun extractYamlTopLevelKeys(file: File): List<Pair<String, Int>> {
        val text = readSafely(file) ?: return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        for ((idx, line) in text.lines().withIndex()) {
            if (line.isEmpty() || line[0].isWhitespace() || line.startsWith("#") || line.startsWith("-")) continue
            val m = YAML_TOPLEVEL_KEY.find(line) ?: continue
            out += m.groupValues[1] to (idx + 1)
        }
        return out
    }

    private val SET_FACT_INLINE = Regex("""set_fact:\s*([A-Za-z_][A-Za-z0-9_]*)\s*=""")
    private val SET_FACT_KV = Regex("""^(\s+)([A-Za-z_][A-Za-z0-9_]*)\s*:""")

    internal fun extractSetFacts(file: File): List<Pair<String, Int>> {
        val text = readSafely(file) ?: return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        val lines = text.lines()
        var inBlock = false
        var blockIndent = -1
        for ((idx, line) in lines.withIndex()) {
            val inline = SET_FACT_INLINE.find(line)
            if (inline != null) {
                out += inline.groupValues[1] to (idx + 1)
                inBlock = false
                continue
            }
            val trimmed = line.trimEnd()
            if (trimmed.endsWith("set_fact:")) {
                inBlock = true
                blockIndent = trimmed.indexOfFirst { !it.isWhitespace() }
                continue
            }
            if (inBlock) {
                if (line.isBlank()) continue
                val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
                if (firstNonWs <= blockIndent) {
                    inBlock = false
                    continue
                }
                val m = SET_FACT_KV.find(line) ?: continue
                out += m.groupValues[2] to (idx + 1)
            }
        }
        return out
    }

    private val TS_EXPORT = Regex(
        """^\s*export\s+(?:default\s+)?(?:async\s+)?(?:abstract\s+)?(class|function\*?|const|let|var|interface|type|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)"""
    )

    internal fun extractTsExports(file: File): List<Pair<String, Int>> {
        val text = readSafely(file) ?: return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        for ((idx, line) in text.lines().withIndex()) {
            val m = TS_EXPORT.find(line) ?: continue
            out += m.groupValues[2] to (idx + 1)
        }
        return out
    }

    private val KOTLIN_DECL = Regex(
        """(?:^|\s)(?:class|object|interface|fun|val|var|enum\s+class|data\s+class|sealed\s+class|sealed\s+interface|annotation\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )

    internal fun extractKotlinDefs(file: File): List<Pair<String, Int>> {
        val text = readSafely(file) ?: return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        for ((idx, line) in text.lines().withIndex()) {
            // Match only one declaration per line — keeps things deterministic for tests
            // and matches typical Kotlin style.
            val m = KOTLIN_DECL.find(line) ?: continue
            out += m.groupValues[1] to (idx + 1)
        }
        return out
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun relPath(root: File, file: File): String {
        val rp = file.toPath().toAbsolutePath().normalize()
        val rr = root.toPath().toAbsolutePath().normalize()
        return rr.relativize(rp).toString().replace(File.separatorChar, '/')
    }

    private fun readSafely(file: File): String? {
        // Skip files that are clearly too big to be source; protects against binary blobs that
        // sneak past the extension filter. 2 MiB is generous for source.
        if (file.length() > 2L * 1024 * 1024) return null
        return runCatching { file.readText() }.getOrNull()
    }
}
