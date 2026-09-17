package iondrive.nop.agent

import java.io.File
import java.util.UUID

/**
 * A ready-to-run vendor CLI invocation: what to exec, what to put in its environment, and the
 * session id the transcript will be filed under when nop was able to choose one.
 */
data class AgentCommand(
    val argv: List<String>,
    val env: Map<String, String>,
    /**
     * The native session id, when nop minted it up front (Claude's `--session-id`) or is resuming
     * a known one. Null for a fresh Codex run, whose id only exists once the CLI has written its
     * first rollout line — [iondrive.nop.agent.transcript.CodexTailer] finds it there.
     */
    val nativeSessionId: String?,
)

/**
 * Builds the argv and environment that run one account's CLI.
 *
 * This is the file that makes per-account isolation real: every provider in scope keeps its login
 * in a file under a directory named by one environment variable, so setting that variable to the
 * account's own home is the whole mechanism. Get it wrong and three accounts quietly share one
 * login — which is why `SpawnTest` checks the environment against a capture taken from the Python
 * implementation this was ported from, rather than against its own idea of what it should be.
 *
 * Both YOLO flags are unconditional. This is a launcher for agents that are trusted to edit the
 * project they were opened on; a permission prompt the user cannot see the reason for, inside a
 * TUI nop does not parse, is worse than no prompt at all.
 */
object Spawn {

    fun command(
        account: Account,
        projectDir: File,
        seed: String? = null,
        resumeId: String? = null,
    ): AgentCommand = when (account.provider) {
        Provider.Anthropic -> claude(account, seed, resumeId)
        Provider.OpenAI -> codex(account, projectDir, seed, resumeId)
        Provider.Antigravity -> antigravity(account, projectDir, seed, resumeId)
    }

    private fun claude(account: Account, seed: String?, resumeId: String?): AgentCommand {
        val argv = mutableListOf(CliTools.resolve(Provider.Anthropic), "--permission-mode", "bypassPermissions")
        account.model?.takeIf { it != DEFAULT_CHOICE }?.let { argv += listOf("--model", it) }

        // A fresh run gets an id nop chooses, because knowing it before the CLI starts is what lets
        // the tailer name the transcript file it is about to watch — the alternative is guessing
        // which of the files in the slug directory is this run's.
        val sessionId = resumeId ?: UUID.randomUUID().toString()
        if (resumeId != null) argv += listOf("--resume", resumeId) else argv += listOf("--session-id", sessionId)

        // Positional, not piped: with a TTY on stdin the CLI reads the terminal, so anything
        // written to the PTY before the UI is up is lost. Long text goes in a file the seed points
        // at (see Handoff) — Linux caps one argv element at 128 KiB.
        seed?.takeIf { it.isNotBlank() }?.let { argv += it }

        val env = mutableMapOf("CLAUDE_CONFIG_DIR" to account.home)
        CLAUDE_THINKING_BUDGETS[account.reasoning]?.let { env["MAX_THINKING_TOKENS"] = it.toString() }
        return AgentCommand(argv, env, sessionId)
    }

    /**
     * `agy`, whose isolation is `HOME` exactly as Codex's is — the difference is that the file that
     * variable leads to is one the CLI only writes while it believes the keyring is unusable. See
     * [Antigravity], which is what keeps that belief true, and is called on the way to every run.
     *
     * `--add-dir` is not a nicety. Without it the CLI decides the directory is untrusted and works
     * in a scratch workspace of its own, which looks from the outside like an agent that reported
     * success having never touched the project.
     *
     * Like Codex and unlike Claude there is no id to mint up front: the conversation is named by
     * the CLI, and nop learns the name from
     * [the tailer][iondrive.nop.agent.transcript.AntigravityTailer].
     */
    private fun antigravity(account: Account, projectDir: File, seed: String?, resumeId: String?): AgentCommand {
        val argv = mutableListOf(
            CliTools.resolve(Provider.Antigravity),
            "--dangerously-skip-permissions",
            "--add-dir",
            projectDir.absolutePath,
        )

        // The model and the effort are one choice here, not two. `agy`'s model ids carry the effort
        // inside them — `gemini-3.1-pro-low` *is* the low-effort build of that model — and the CLI
        // refuses to start when `--effort` disagrees with the id it was given, or when the model has
        // no effort dimension at all:
        //
        //     error: invalid model selection (--model "gemini-3.1-pro-low" --effort "high"):
        //     --model gemini-3.1-pro-low conflicts with --effort=high
        //     error: ... --effort is not supported for model "claude-sonnet-4-6"
        //
        // A refused spawn is a tab that opens on an error, so the named model wins and its own
        // effort comes with it. The Thinking picker is then what sets the effort of the CLI's
        // default model, which is the only case where nop has an effort to choose at all.
        val model = account.model?.takeIf { it != DEFAULT_CHOICE }
        if (model != null) {
            argv += listOf("--model", model)
        } else {
            account.reasoning?.takeIf { it != DEFAULT_CHOICE }?.let { argv += listOf("--effort", it) }
        }
        if (resumeId != null) argv += listOf("--conversation", resumeId)

        // `-i` and not a positional: `--prompt-interactive` is the flag that submits a prompt and
        // *stays* in the TUI. Bare `--print` would answer once and exit, which is not a session.
        seed?.takeIf { it.isNotBlank() }?.let { argv += listOf("-i", it) }

        return AgentCommand(argv, mapOf("HOME" to account.home), resumeId)
    }

    private fun codex(account: Account, projectDir: File, seed: String?, resumeId: String?): AgentCommand {
        val argv = mutableListOf(
            CliTools.resolve(Provider.OpenAI),
            "--dangerously-bypass-approvals-and-sandbox",
            "-C",
            projectDir.absolutePath,
        )
        account.model?.takeIf { it != DEFAULT_CHOICE }?.let { argv += listOf("-m", it) }
        account.reasoning?.takeIf { it != DEFAULT_CHOICE }
            ?.let { argv += listOf("-c", "model_reasoning_effort=\"$it\"") }

        // `resume` is a subcommand, so it follows the options rather than joining them.
        if (resumeId != null) argv += listOf("resume", resumeId)
        seed?.takeIf { it.isNotBlank() }?.let { argv += it }

        return AgentCommand(argv, mapOf("HOME" to account.home), resumeId)
    }
}
