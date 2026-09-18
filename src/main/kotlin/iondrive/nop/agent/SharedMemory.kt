package iondrive.nop.agent

import iondrive.nop.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One memory for every agent nop runs: a Markdown file that its Claude, Codex and Antigravity
 * sessions all read and add to, whichever account they run as and whichever project they were
 * opened on.
 *
 * Each vendor CLI keeps a memory of its own, and keeps it per account — Claude Code files it under
 * `$CLAUDE_CONFIG_DIR`, which nop points at a different home for every account. So what one session
 * learned (how the user wants a fix delivered, a test that only fails under load, a command never to
 * run on this machine) was invisible to the next session opened on another account, and the user
 * found out by watching the same mistake happen twice.
 *
 * A run is told where the file is, never what is in it. The contents would be a snapshot from launch
 * that goes stale as other sessions write, would sit in the process list — argv is readable by every
 * account on the machine — and are the one thing the agent can read for itself. Each CLI takes extra
 * instructions its own way: [Spawn] passes them to Claude and Codex, and [installRule] gives them to
 * `agy`, which has no flag for them.
 */
object SharedMemory {

    /**
     * A directory of its own under nop's agent data, not the data root itself: `agy` has to be given
     * it as a workspace before it will open the file, and the data root also holds account homes.
     */
    fun dir(): Path = Accounts.dataRoot().resolve("memory")

    fun file(): Path = dir().resolve("memory.md")

    /** What every run is told about [file], whichever way its CLI takes it. */
    fun instructions(file: Path = file()): String = """
        |nop shared agent memory: $file
        |
        |Every agent nop runs reads and writes this one file: Claude, Codex and Antigravity, on
        |every account and in every project. It is how what one session learns reaches the next,
        |whichever account or tool that one runs as. Your CLI's own memory is kept per account and
        |the other agents never see it.
        |
        |- Read the file before you start on the user's first request.
        |- Add to it when you learn something another session would need: a correction or
        |  preference from the user, how they want work delivered, a fact about this machine, a
        |  trap in a project that took real work to find. Say which project an entry is about (its
        |  path) unless it applies everywhere.
        |- Leave out task progress, anything the repository already records, and secrets.
        |- Keep each entry to a line or two, and keep the file true: fix or remove an entry that
        |  turned out wrong instead of adding another beside it.
        |- Other agents write to it too. Read it again just before you change it, and change only
        |  the lines you mean to.
    """.trimMargin()

    /**
     * Creates [file] owner-only, with a heading that says what it is, the first time a run is
     * started. An existing file is never touched: by then it is the agents' to edit.
     */
    fun ensure(file: Path = file()) {
        if (Files.exists(file)) return
        runCatching {
            OwnerOnly.file(file)
            // Only the run that just created it writes the heading. Two tabs starting at once must
            // not have the second truncate what the first one's agent has already added.
            if (Files.size(file) == 0L) Files.writeString(file, HEADING)
        }.onFailure { Log.warn("could not create the shared agent memory at $file: $it") }
    }

    /**
     * Gives [home]'s `agy` the instructions as an always-on rule.
     *
     * `agy` has no flag for extra instructions, but loads every rule under its global customisation
     * root, `~/.gemini/config/rules/`. nop runs it with `HOME` set to the account's own home, so a
     * rule there reaches exactly the runs nop starts and nothing the user runs outside it. Rewritten
     * on every run, so a moved data directory or a newer wording is picked up.
     */
    fun installRule(home: Path, file: Path = file()) {
        val rule = home.resolve(".gemini").resolve("config").resolve("rules").resolve(RULE_FILE)
        runCatching {
            OwnerOnly.directory(rule.parent)
            val tmp = Files.createTempFile(rule.parent, ".nop-memory", ".md")
            Files.writeString(tmp, "---\ntrigger: always_on\n---\n\n${instructions(file)}\n")
            Files.move(tmp, rule, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.warn("could not give $home the shared memory rule: $it") }
    }

    private const val RULE_FILE = "nop-shared-memory.md"

    private val HEADING = """
        |# Shared agent memory
        |
        |Kept by nop. Every agent it runs reads this file, on any account and in any project, and
        |adds what the next session should know. An entry about one project names its path.
        |
    """.trimMargin()
}
