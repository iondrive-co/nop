package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * nop can be started from inside an agent's own shell, and what that shell's session put in the
 * environment must not reach the terminals and agents nop opens — whichever CLI the agent runs in.
 */
class InheritedEnvironmentTest {

    private val users = mapOf(
        "PATH" to "/usr/local/bin:/usr/bin",
        "HOME" to "/home/dev",
        "CLAUDE_CODE_USE_BEDROCK" to "1",
        "CODEX_HOME" to "/home/dev/.codex",
    )

    @Test
    fun `a Claude Code session's markers are dropped and the user's own settings kept`() {
        val inherited = users + mapOf(
            "CLAUDECODE" to "1",
            "CLAUDE_CODE_CHILD_SESSION" to "1",
            "CLAUDE_CODE_SESSION_ID" to "0000-parent",
            "CLAUDE_CODE_ENTRYPOINT" to "sdk-cli",
            "CLAUDE_CODE_MESSAGING_SOCKET" to "/run/user/1000/parent.sock",
            "CLAUDE_PID" to "4242",
            "AI_AGENT" to "parent-agent",
            "NOP_AGENT_SESSION" to "parent-tab",
            "NOP_AGENT_TICKET" to "parent-ticket",
            "NOP_SOCKET" to "/run/user/1000/nop/1.sock",
        )

        assertEquals(users, InheritedEnvironment.of(inherited))
    }

    @Test
    fun `a Codex session's markers are dropped`() {
        val inherited = users + mapOf(
            "CODEX_SESSION_ID" to "11111111-2222-4333-8444-555555555555",
            "CODEX_THREAD_ID" to "11111111-2222-4333-8444-555555555555",
            "CODEX_VERSION" to "0.0.0-fixture",
            "CODEX_CI" to "1",
            "CODEX_SANDBOX_NETWORK_DISABLED" to "1",
        )

        assertEquals(users, InheritedEnvironment.of(inherited))
    }

    @Test
    fun `an Antigravity session's markers are dropped`() {
        val inherited = users + listOf(
            "ANTIGRAVITY_AGENT", "ANTIGRAVITY_AGENTAPI_EXE", "ANTIGRAVITY_APP_DATA_DIR",
            "ANTIGRAVITY_CONVERSATION_ID", "ANTIGRAVITY_CSRF_TOKEN", "ANTIGRAVITY_LS_ADDRESS",
            "ANTIGRAVITY_LS_VERSION", "ANTIGRAVITY_PROJECT_ID", "ANTIGRAVITY_SOURCE_METADATA",
            "ANTIGRAVITY_TRAJECTORY_ID",
        ).associateWith { "x" }

        assertEquals(users, InheritedEnvironment.of(inherited))
    }

    @Test
    fun `any marker of any CLI says this is an agent's session, and the user's settings do not`() {
        assertNull(InheritedEnvironment.sessionMarker(users))
        assertEquals("CLAUDECODE", InheritedEnvironment.sessionMarker(users + ("CLAUDECODE" to "1")))
        assertEquals("CODEX_THREAD_ID", InheritedEnvironment.sessionMarker(users + ("CODEX_THREAD_ID" to "t")))
        assertEquals(
            "ANTIGRAVITY_CONVERSATION_ID",
            InheritedEnvironment.sessionMarker(users + ("ANTIGRAVITY_CONVERSATION_ID" to "c")),
        )
        // Set to nothing still counts: the user never sets these at all.
        assertEquals("NOP_SOCKET", InheritedEnvironment.sessionMarker(users + ("NOP_SOCKET" to "")))
    }
}
