package iondrive.nop.terminal

/**
 * The variables an agent's session leaves in the environment of every command it runs, for each of
 * the CLIs nop runs, and nop's own per-run ones beside them.
 *
 * These markers identify launches from an agent's session for [iondrive.nop.AgentLaunchGuard] and
 * are removed from inherited child environments. Inherited Claude Code markers can make a fresh
 * agent behave as a nested session and suppress its transcript, preventing resume and handover.
 *
 * Each supported CLI contributes markers for its own child processes:
 *
 * - nop's own. An agent run gets fresh ones from its environment; inherited, they would make a plain
 *   terminal call `nop-msg` as some other nop's tab.
 * - Claude Code's. Under `CLAUDECODE` or `CLAUDE_CODE_CHILD_SESSION` a `claude` saves no transcript;
 *   the rest name the parent's session, pid, effort, entrypoint and messaging socket.
 * - Codex's. The session and thread ids of the run whose shell it was, the version, `CODEX_CI`, and
 *   the sandbox's own two, which tell a command it is sandboxed and offline.
 * - Antigravity's. The conversation and trajectory ids, the language server's address and CSRF token,
 *   and the data directory of the account that ran it, which would reach past the `HOME` that keeps
 *   one account's `agy` out of another's.
 *
 * A variable the user sets to configure a CLI (`CLAUDE_CODE_USE_BEDROCK`, `CODEX_HOME`) is not a
 * marker, and passes through.
 */
object InheritedEnvironment {
    val SESSION_VARS: Set<String> = linkedSetOf(
        "NOP_AGENT_SESSION",
        "NOP_AGENT_TICKET",
        "NOP_SOCKET",

        "CLAUDECODE",
        "CLAUDE_CODE_CHILD_SESSION",
        "CLAUDE_CODE_SESSION_ID",
        "CLAUDE_CODE_SESSION_ATTENDED",
        "CLAUDE_CODE_ENTRYPOINT",
        "CLAUDE_CODE_EXECPATH",
        "CLAUDE_CODE_MESSAGING_SOCKET",
        "CLAUDE_CODE_MESSAGING_TOKEN",
        "CLAUDE_CODE_TUI_JUST_SWITCHED",
        "CLAUDE_PID",
        "CLAUDE_EFFORT",
        "AI_AGENT",

        "CODEX_SESSION_ID",
        "CODEX_THREAD_ID",
        "CODEX_VERSION",
        "CODEX_CI",
        "CODEX_SANDBOX",
        "CODEX_SANDBOX_NETWORK_DISABLED",

        "ANTIGRAVITY_AGENT",
        "ANTIGRAVITY_AGENTAPI_EXE",
        "ANTIGRAVITY_APP_DATA_DIR",
        "ANTIGRAVITY_CONVERSATION_ID",
        "ANTIGRAVITY_CSRF_TOKEN",
        "ANTIGRAVITY_LS_ADDRESS",
        "ANTIGRAVITY_LS_VERSION",
        "ANTIGRAVITY_PROJECT_ID",
        "ANTIGRAVITY_SOURCE_METADATA",
        "ANTIGRAVITY_TRAJECTORY_ID",
    )

    /** [inherited] without [SESSION_VARS]: what every child's environment starts from. */
    fun of(inherited: Map<String, String>): HashMap<String, String> =
        HashMap(inherited).apply { keys.removeAll(SESSION_VARS) }

    /**
     * The first of [SESSION_VARS] that is set in [env], which says the process holding it runs inside
     * an agent's session; null when none is. Set at all, even to nothing: it is the agent that put it
     * there, never the user.
     */
    fun sessionMarker(env: Map<String, String>): String? = SESSION_VARS.firstOrNull { it in env }
}
