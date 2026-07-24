package iondrive.nop.ui

import iondrive.nop.git.FileChange

/** A named column of related changes shown side by side in the commit panel. */
data class ChangeGroup(
    val title: String,
    val changes: List<FileChange>,
) {
    /** Directory prefix (with trailing '/') shared by every member; "" when members span roots. */
    val commonPrefix: String = commonDirPrefix(changes.map { it.path })

    /** Column heading: the group title and its member count, e.g. "ui · 3". */
    val header: String get() = "$title · ${changes.size}"

    /** Row label: the path with the group's shared directory prefix stripped. */
    fun labelFor(path: String): String =
        if (commonPrefix.isNotEmpty() && path.startsWith(commonPrefix)) path.removePrefix(commonPrefix) else path
}

/**
 * Splits a flat change list into related groups so a review reads by concern rather than as one
 * long list: tests, config, and docs are pulled into their own groups, and the remaining source
 * files are grouped by the directory that contains them. Purely heuristic — no per-file state.
 */
object ChangeGrouping {

    /** Upper bound on groups so side-by-side columns stay wide enough to read. */
    const val MAX_GROUPS = 6

    const val OTHER_TITLE = "other"

    fun group(changes: List<FileChange>): List<ChangeGroup> {
        if (changes.isEmpty()) return emptyList()
        val tests = mutableListOf<FileChange>()
        val config = mutableListOf<FileChange>()
        val docs = mutableListOf<FileChange>()
        val byDir = LinkedHashMap<String, MutableList<FileChange>>()
        for (change in changes) {
            when (classify(change.path)) {
                Category.TESTS -> tests += change
                Category.CONFIG -> config += change
                Category.DOCS -> docs += change
                Category.SOURCE -> byDir.getOrPut(sourceTitle(change.path)) { mutableListOf() } += change
            }
        }

        // Source groups lead (largest first — the core of the change), with the catch-all last;
        // tests/config/docs trail as supporting material.
        var source = byDir.map { (title, members) -> ChangeGroup(title, members) }
            .sortedWith(compareByDescending<ChangeGroup> { it.changes.size }.thenBy { it.title })
        source = source.filter { it.title != OTHER_TITLE } + source.filter { it.title == OTHER_TITLE }

        val specials = listOfNotNull(
            tests.takeIf { it.isNotEmpty() }?.let { ChangeGroup("tests", it) },
            config.takeIf { it.isNotEmpty() }?.let { ChangeGroup("config", it) },
            docs.takeIf { it.isNotEmpty() }?.let { ChangeGroup("docs", it) },
        )

        val maxSource = (MAX_GROUPS - specials.size).coerceAtLeast(1)
        if (source.size > maxSource) {
            val kept = source.take(maxSource - 1)
            val merged = source.drop(maxSource - 1).flatMap { it.changes }
            source = kept + ChangeGroup(OTHER_TITLE, merged)
        }

        // A source directory literally named "tests"/"config"/"docs" would otherwise produce two
        // columns with the same heading — fold such duplicates together.
        val out = LinkedHashMap<String, MutableList<FileChange>>()
        for (group in source + specials) {
            out.getOrPut(group.title) { mutableListOf() } += group.changes
        }
        return out.map { (title, members) -> ChangeGroup(title, members) }
    }

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
