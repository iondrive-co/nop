package iondrive.nop.ui

/**
 * A named column of related items, each sitting at a project-relative path. [paths] runs parallel
 * to [items] — the grouping keeps it so a column can size and label its rows without knowing what
 * kind of item it holds.
 */
data class PathGroup<T>(
    val title: String,
    val items: List<T>,
    val paths: List<String>,
) {
    /** Directory prefix (with trailing '/') shared by every member; "" when members span roots. */
    val commonPrefix: String = commonDirPrefix(paths)

    /** Column heading: the group title and its member count, e.g. "ui · 3". */
    val header: String get() = "$title · ${items.size}"

    /** Row label: the path with the group's shared directory prefix stripped. */
    fun labelFor(path: String): String =
        if (commonPrefix.isNotEmpty() && path.startsWith(commonPrefix)) path.removePrefix(commonPrefix) else path
}

/**
 * Splits a flat list of path-bearing items into related groups so a long list reads by concern
 * rather than as one mixed run: tests, config, and docs are pulled into their own groups, and the
 * remaining source files are grouped by the directory that contains them. Shared by the commit
 * panel's change columns and the find-in-files result columns, so both label their columns the same
 * way. Purely heuristic — no per-item state.
 *
 * Several items may share a path (a search turning up many matches in one file); they stay together
 * in that path's group, and a group's count reflects items, not files.
 */
object PathGrouping {

    /** Upper bound on groups so side-by-side columns stay wide enough to read. */
    const val MAX_GROUPS = 6

    const val OTHER_TITLE = "other"

    fun <T> group(items: List<T>, pathOf: (T) -> String): List<PathGroup<T>> {
        if (items.isEmpty()) return emptyList()
        val tests = mutableListOf<Member<T>>()
        val config = mutableListOf<Member<T>>()
        val docs = mutableListOf<Member<T>>()
        val byDir = LinkedHashMap<String, MutableList<Member<T>>>()
        for (item in items) {
            val member = Member(pathOf(item), item)
            when (classify(member.path)) {
                Category.TESTS -> tests += member
                Category.CONFIG -> config += member
                Category.DOCS -> docs += member
                Category.SOURCE -> byDir.getOrPut(sourceTitle(member.path)) { mutableListOf() } += member
            }
        }

        // Source groups lead (largest first — the core of the change), with the catch-all last;
        // tests/config/docs trail as supporting material.
        var source = byDir.map { (title, members) -> title to members.toList() }
            .sortedWith(
                compareByDescending<Pair<String, List<Member<T>>>> { it.second.size }.thenBy { it.first },
            )
        source = source.filter { it.first != OTHER_TITLE } + source.filter { it.first == OTHER_TITLE }

        val specials = listOfNotNull(
            tests.takeIf { it.isNotEmpty() }?.let { "tests" to it.toList() },
            config.takeIf { it.isNotEmpty() }?.let { "config" to it.toList() },
            docs.takeIf { it.isNotEmpty() }?.let { "docs" to it.toList() },
        )

        val maxSource = (MAX_GROUPS - specials.size).coerceAtLeast(1)
        if (source.size > maxSource) {
            val kept = source.take(maxSource - 1)
            val merged = source.drop(maxSource - 1).flatMap { it.second }
            source = kept + (OTHER_TITLE to merged)
        }

        // A source directory literally named "tests"/"config"/"docs" would otherwise produce two
        // columns with the same heading — fold such duplicates together.
        val out = LinkedHashMap<String, MutableList<Member<T>>>()
        for ((title, members) in source + specials) {
            out.getOrPut(title) { mutableListOf() } += members
        }
        return out.map { (title, members) ->
            PathGroup(title, members.map { it.item }, members.map { it.path })
        }
    }

    /** An item paired with its path, so [group]'s `pathOf` is called once per item. */
    private data class Member<T>(val path: String, val item: T)

    private enum class Category { TESTS, CONFIG, DOCS, SOURCE }

    private val TEST_DIRS = setOf("test", "tests", "__tests__", "spec", "specs", "testing")
    private val TEST_DIR_REGEX = Regex("(common|jvm|js|android|ios|native|integration|unit)tests?")

    private val CONFIG_FILENAMES = setOf(
        "dockerfile", "makefile", "jenkinsfile", "cmakelists.txt",
        "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        "cargo.lock", "gemfile", "gemfile.lock", "pom.xml",
        "requirements.txt", "pyproject.toml", "setup.cfg", "tsconfig.json",
        "gradlew", "gradlew.bat", "gradle.lockfile",
    )
    private val CONFIG_EXTENSIONS = setOf(
        "yml", "yaml", "toml", "properties", "ini", "cfg", "conf", "lock", "gradle",
    )
    private val DOC_EXTENSIONS = setOf("md", "markdown", "rst", "adoc", "txt")
    private val DOC_DIRS = setOf("doc", "docs")

    private fun classify(path: String): Category {
        val segments = path.split('/')
        val fileName = segments.last()
        val lowerName = fileName.lowercase()
        val dirs = segments.dropLast(1).map { it.lowercase() }

        if (dirs.any { it in TEST_DIRS || TEST_DIR_REGEX.matches(it) } || isTestFileName(fileName)) {
            return Category.TESTS
        }
        if (lowerName in CONFIG_FILENAMES || lowerName.endsWith(".gradle.kts")) return Category.CONFIG
        val ext = lowerName.substringAfterLast('.', "")
        if (ext in DOC_EXTENSIONS || dirs.firstOrNull() in DOC_DIRS) return Category.DOCS
        if (ext in CONFIG_EXTENSIONS || lowerName.startsWith(".") || dirs.firstOrNull()?.startsWith(".") == true) {
            return Category.CONFIG
        }
        return Category.SOURCE
    }

    private fun isTestFileName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (".test." in lower || ".spec." in lower || lower.startsWith("test_")) return true
        // Case-sensitive suffixes so "latest.kt" doesn't read as a test.
        val base = fileName.substringBeforeLast('.')
        return base.endsWith("Test") || base.endsWith("Tests") || base.endsWith("Spec") || base.endsWith("_test")
    }

    /** Title for a source file's group: the directory that contains it. */
    private fun sourceTitle(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isEmpty()) return OTHER_TITLE
        return dir.substringAfterLast('/')
    }
}

private fun commonDirPrefix(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    var prefix: List<String>? = null
    for (path in paths) {
        val dirs = path.split('/').dropLast(1)
        prefix = if (prefix == null) dirs else prefix.zip(dirs).takeWhile { (a, b) -> a == b }.map { it.first }
        if (prefix.isEmpty()) return ""
    }
    return prefix!!.joinToString("/", postfix = "/")
}
