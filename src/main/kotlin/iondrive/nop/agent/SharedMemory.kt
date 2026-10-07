package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * The memory every agent nop runs shares: Markdown files that its Claude, Codex and Antigravity
 * sessions all read, whichever account they run as.
 *
 * Each vendor CLI keeps a memory of its own, and keeps it per account — Claude Code files it under
 * `$CLAUDE_CONFIG_DIR`, which nop points at a different home for every account. So what one session
 * learns is invisible to the next session opened on another account, and the same mistake happens
 * twice.
 *
 * There are two kinds of file. Each project has its own, which only that project's runs are told
 * about. Three more hold what is true whatever the project — about the user, about the machine's
 * hardware and OS, and about its software and the agents that run on it ([Category]). One file for everything grew into a
 * log of every project's details that every run in every project had to read.
 *
 * Agents never write the files themselves; they are read-only on disk. An entry goes in through
 * `nop-msg remember`, which has to say whether it is for this project or for all of them, and an
 * entry for all of them has to name its [Category] and pass [globalProblems] — mechanical checks
 * that turn away what plainly belongs to one project. Telling agents what not to write was tried,
 * and did not hold.
 *
 * A run is told where the files are, never what is in them. The contents would be a snapshot from
 * launch that goes stale as other sessions write, would sit in the process list — argv is readable
 * by every account on the machine — and are the one thing the agent can read for itself. Each CLI
 * takes extra instructions its own way: [Spawn] passes them to Claude and Codex, and [installRule]
 * gives them to `agy`, which has no flag for them.
 */
object SharedMemory {

    /** The three files that hold what is true in every project. */
    enum class Category(val id: String, val title: String, val fileName: String, val holds: String) {
        User("user", "User Information", "user.md", "who the user is and how they want agents to work with them"),
        Hardware("hardware", "Hardware and OS Information", "hardware-os.md", "this machine: its hardware, devices, OS, mounts and system services"),
        Software("software", "Software and Agent Information", "software-agents.md", "the programs on this machine and how they behave here, and the agents: their CLIs, accounts and limits, nop, shell tools"),
        ;

        companion object {
            fun of(id: String): Category? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }

    /** Where an entry goes: the caller's project, or one of the files for every project. */
    sealed interface Scope {
        data object Project : Scope
        data class All(val category: Category) : Scope
    }

    /** Where a run finds its project's memory file, for `agy`, whose instructions can't name it. */
    const val PROJECT_ENV = "NOP_PROJECT_MEMORY"

    /**
     * A directory of its own under nop's agent data, not the data root itself: `agy` has to be given
     * it as a workspace before it will open the files, and the data root also holds account homes.
     */
    fun dir(): Path = Accounts.dataRoot().resolve("memory")

    fun file(category: Category, dir: Path = dir()): Path = dir.resolve(category.fileName)

    /**
     * [project]'s memory: named for the directory, so it can be found by eye, and for a hash of the
     * whole path, so two checkouts that share a name keep apart.
     */
    fun projectFile(project: Path, dir: Path = dir()): Path {
        val path = project.toAbsolutePath().normalize()
        val hash = MessageDigest.getInstance("SHA-256").digest(path.toString().toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        val name = path.fileName?.toString()?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotEmpty() } ?: "root"
        return dir.resolve(PROJECTS).resolve("$name-$hash.md")
    }

    /**
     * What every run is told. [project] is null for `agy`, whose instructions are a rule shared by
     * every project the account is opened on; its run finds the project's file in [PROJECT_ENV].
     */
    fun instructions(
        project: Path?,
        dir: Path = dir(),
        helper: Path = AgentSocket.helperPath(),
    ): String {
        val projectLine = project?.let { "this project's memory: ${projectFile(it, dir)}" }
            ?: "this project's memory: the file named by \$$PROJECT_ENV"
        val globals = Category.entries.joinToString("\n") { "- ${file(it, dir)} (${it.title}: ${it.holds})" }
        val ids = Category.entries.joinToString("|") { it.id }
        return """
        |nop agent memory. Read these before you start on the user's first request:
        |- $projectLine
        |$globals
        |
        |Every agent nop runs shares these files: Claude, Codex and Antigravity, on every account.
        |They are how what one session learns reaches the next. Your CLI's own memory is kept per
        |account and the other agents never see it.
        |
        |The files are read-only. Add a line with `nop-msg remember` when you learn something
        |another session would need, and say who it is for:
        |    nop-msg remember project <text>        for sessions in this project only
        |    nop-msg remember all <$ids> <text>     for sessions in every project
        |An entry for all projects is checked by nop, and refused if it mentions a project's paths
        |or names, commits, or the session it was written in; a refusal says what to change. To fix
        |or drop a line, `nop-msg forget project|all <category> <the line's text>`, then remember the
        |corrected one. Leave out task progress, anything the repository already records, and secrets.
        |
        |The other agent tabs open in nop, whichever CLI and account runs them, can be messaged
        |with `nop-msg` (on your PATH, and at $helper): `nop-msg list` shows them with their
        |ids, and `nop-msg send <id> <message>` puts the message in front of the user in that
        |tab, who decides whether it is typed into its prompt (in a project where the user lets
        |its tabs message each other freely, it is typed in straight away). A message that reaches you that
        |way starts with "[Message via nop from agent ...]" and is not from the user.
        """.trimMargin()
    }

    /**
     * Creates the three files for every project and [project]'s own, each with a heading that says
     * what it is, the first time a run is started. An existing file is never touched.
     */
    fun ensure(project: Path?, dir: Path = dir()) {
        Category.entries.forEach { create(file(it, dir), "# ${it.title}\n\nFor every project: ${it.holds}.\n\n") }
        project?.let { create(projectFile(it, dir), projectHeading(it)) }
    }

    /** Reads [scope] from what follows `remember`/`forget`: `project`, or `all <category>`. */
    fun parseScope(words: List<String>): Pair<Scope, Int>? = when (words.firstOrNull()?.lowercase()) {
        "project" -> Scope.Project to 1
        "all" -> words.getOrNull(1)?.let(Category::of)?.let { Scope.All(it) to 2 }
        else -> null
    }

    /**
     * Adds [text] as one entry to [scope]'s file, for an agent in [project]. Refused, with the reason,
     * when it is empty, too long, looks like a secret, is already there, or — for every project —
     * fails [globalProblems] against [knownProjects].
     */
    fun remember(
        project: Path,
        scope: Scope,
        text: String,
        knownProjects: Collection<Path> = knownProjects(project),
        dir: Path = dir(),
    ): AgentMessages.Outcome {
        val entry = normalize(text)
        val problems = commonProblems(entry) +
            if (scope is Scope.All) globalProblems(entry, knownProjects + listOf(project)) else emptyList()
        if (problems.isNotEmpty()) {
            val hint = if (scope is Scope.All) "\nIf it is about this project, use `nop-msg remember project <text>`." else ""
            return AgentMessages.Outcome("Not remembered:\n" + problems.joinToString("\n") { "- $it" } + hint, isError = true)
        }
        val target = target(project, scope, dir)
        return synchronized(this) {
            val lines = read(target)
            if (lines.any { it.trim() == "- $entry" }) return@synchronized AgentMessages.Outcome("Already there: $target")
            val before = runCatching { Files.readString(target) }.getOrDefault("")
            write(target, before + (if (before.isEmpty() || before.endsWith("\n")) "" else "\n") + "- $entry\n")
            AgentMessages.Outcome("Remembered in $target")
        }
    }

    /** Takes the entry whose text is [text] out of [scope]'s file. */
    fun forget(project: Path, scope: Scope, text: String, dir: Path = dir()): AgentMessages.Outcome {
        val entry = normalize(text)
        val target = target(project, scope, dir)
        return synchronized(this) {
            val lines = read(target)
            val kept = lines.filterNot { it.trim() == "- $entry" }
            if (kept.size == lines.size) {
                return@synchronized AgentMessages.Outcome("No line in $target reads exactly \"- $entry\"", isError = true)
            }
            write(target, kept.joinToString("") { "$it\n" })
            AgentMessages.Outcome("Forgotten from $target")
        }
    }

    /** The checks every entry has to pass. */
    internal fun commonProblems(entry: String): List<String> = buildList {
        if (entry.isEmpty()) add("it is empty")
        if (entry.length > MAX_ENTRY) add("it is ${entry.length} characters; an entry is at most $MAX_ENTRY — say less, or split it")
        if (entry.startsWith("#")) add("it starts with #; an entry is a line, not a heading")
        SECRET.find(entry)?.let { add("\"${it.value.take(12)}…\" looks like a credential; secrets never go in memory") }
    }

    /**
     * The checks an entry for every project has to pass on top of [commonProblems]. Mechanical on
     * purpose: each one names what it found, so the agent can see why and file it under its project.
     */
    internal fun globalProblems(
        entry: String,
        projects: Collection<Path>,
        home: Path = Path.of(System.getProperty("user.home")),
        onPath: (String) -> Boolean = ::onPath,
    ): List<String> = buildList {
        val roots = projects.map { it.toAbsolutePath().normalize() }.distinct()
            .filter { it != home && it.parent != null }

        // A path into a project, written any of the usual ways.
        PATH.findAll(entry).map { it.value.trimEnd('.', ':', ')', ']') }.forEach { raw ->
            val expanded = when {
                raw.startsWith("~/") || raw == "~" -> home.resolve(raw.removePrefix("~").removePrefix("/"))
                raw.startsWith("\$HOME") -> home.resolve(raw.removePrefix("\$HOME").removePrefix("/"))
                else -> runCatching { Path.of(raw) }.getOrNull()
            }?.normalize() ?: return@forEach
            roots.firstOrNull { expanded.startsWith(it) }?.let { add("$raw is inside the project $it") }
        }

        // A project's name on its own. A name that is also a command on PATH (docker, xenia) is the
        // program as often as the project, and nop is the host every agent runs in, so those pass.
        roots.map { it.fileName.toString() }.distinct()
            .filterNot { it.equals("nop", ignoreCase = true) || onPath(it) }
            .forEach { name ->
                val word = Regex("(?<![\\w./~-])${Regex.escape(name)}(?![\\w/-])", RegexOption.IGNORE_CASE)
                if (word.containsMatchIn(entry)) add("it names the project \"$name\"")
            }

        COMMIT.findAll(entry).filter { m -> m.value.any { it.isDigit() } && m.value.any { it.isLetter() } }
            .forEach { add("${it.value} looks like a commit, which belongs to one repository") }

        SESSION_WORDS.find(entry)?.let { add("\"${it.value}\" only means something in the session that wrote it") }
    }

    /** Every project nop knows of: open, recent, and any that already has a memory file. */
    fun knownProjects(caller: Path, dir: Path = dir()): List<Path> {
        val fromFiles = runCatching {
            Files.list(dir.resolve(PROJECTS)).use { files ->
                files.toList().mapNotNull { f ->
                    read(f).firstOrNull { it.startsWith(PROJECT_LINE) }?.removePrefix(PROJECT_LINE)?.trim()
                        ?.let { runCatching { Path.of(it) }.getOrNull() }
                }
            }
        }.getOrDefault(emptyList())
        val settings = runCatching { Settings.loadOpenProjects() + Settings.loadRecentProjects() }.getOrDefault(emptyList())
        return (listOf(caller) + settings + fromFiles).map { it.toAbsolutePath().normalize() }.distinct()
    }

    /**
     * Gives [home]'s `agy` the instructions as an always-on rule.
     *
     * `agy` has no flag for extra instructions, but loads every rule under its global customisation
     * root, `~/.gemini/config/rules/`. nop runs it with `HOME` set to the account's own home, so a
     * rule there reaches exactly the runs nop starts and nothing the user runs outside it. Rewritten
     * on every run, so a moved data directory or a newer wording is picked up. The rule is the
     * account's, not the project's, so it names the project's file by [PROJECT_ENV].
     */
    fun installRule(home: Path, dir: Path = dir()) {
        val rule = home.resolve(".gemini").resolve("config").resolve("rules").resolve(RULE_FILE)
        runCatching {
            OwnerOnly.directory(rule.parent)
            val tmp = Files.createTempFile(rule.parent, ".nop-memory", ".md")
            Files.writeString(tmp, "---\ntrigger: always_on\n---\n\n${instructions(null, dir)}\n")
            Files.move(tmp, rule, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.warn("could not give $home the shared memory rule: $it") }
    }

    private fun target(project: Path, scope: Scope, dir: Path): Path = when (scope) {
        Scope.Project -> projectFile(project, dir).also { create(it, projectHeading(project)) }
        is Scope.All -> file(scope.category, dir).also { ensure(null, dir) }
    }

    private fun projectHeading(project: Path) =
        "# Project memory\n\n$PROJECT_LINE ${project.toAbsolutePath().normalize()}\n\n"

    /** One line, without a bullet of its own: the file adds that. */
    private fun normalize(text: String) = text.trim().removePrefix("- ").removePrefix("* ").replace(Regex("\\s+"), " ").trim()

    private fun read(file: Path): List<String> =
        runCatching { Files.readString(file).removeSuffix("\n").split('\n') }.getOrDefault(emptyList())

    /** Creates [file] read-only with [heading], unless it is already there. */
    private fun create(file: Path, heading: String) {
        if (Files.exists(file)) return
        runCatching { synchronized(this) { if (!Files.exists(file)) write(file, heading) } }
            .onFailure { Log.warn("could not create the agent memory at $file: $it") }
    }

    /**
     * Replaces [file] with [text] in one rename, read-only for the owner. A rename needs only the
     * directory to be writable, which is how nop writes a file it keeps agents' editors off.
     */
    private fun write(file: Path, text: String) {
        OwnerOnly.directory(file.parent)
        val tmp = Files.createTempFile(file.parent, ".nop-memory", ".md", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(tmp, text)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r--------")) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Whether [name] is a command the user has. nop started from the desktop has the system PATH
     * only, without the user's own bin directories, so those are looked in as well.
     */
    private fun onPath(name: String): Boolean {
        val home = System.getProperty("user.home")
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("$home/.local/bin", "$home/bin")
        return dirs.filter { it.isNotEmpty() }.any { File(it, name).let { f -> f.isFile && f.canExecute() } }
    }

    private const val RULE_FILE = "nop-shared-memory.md"
    private const val PROJECTS = "projects"
    private const val PROJECT_LINE = "Project:"
    private const val MAX_ENTRY = 500

    private val PATH = Regex("""(?:~|\${'$'}HOME)(?:/[^\s`'"()<>,;]*)?|(?<![\w.~])/[^\s`'"()<>,;]+""")
    private val COMMIT = Regex("""(?<![\w#-])[0-9a-f]{7,40}(?![\w-])""")
    private val SESSION_WORDS = Regex(
        """\b(today|yesterday|tomorrow|this (session|conversation|task|chat)|the current task|the (supplied|attached|above) \w+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SECRET = Regex(
        """\b(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|xox[abpr]-[A-Za-z0-9-]{10,})|(?i:(password|passwd|secret|api[_-]?key|token)\s*[=:]\s*\S{6,})""",
    )
}
