package iondrive.nop

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class AgentLaunchGuardTest {

    private val users = Paths.get("/home/dev/.config")
    private val own = Paths.get("/tmp/shot/config")
    private val shell = mapOf("PATH" to "/usr/bin", "HOME" to "/home/dev")

    @Test
    fun `the user's own launch is never refused`() {
        assertNull(AgentLaunchGuard.refusal(shell, users, users) { true })
        assertNull(AgentLaunchGuard.refusal(shell, users, users) { false })
    }

    @Test
    fun `Claude markers identify an agent without nop's markers`() {
        val claudeShell = shell + mapOf("CLAUDECODE" to "1", "CLAUDE_CODE_CHILD_SESSION" to "1")

        val refusal = AgentLaunchGuard.refusal(claudeShell, users, users) { true }

        assertNotNull(refusal)
        assertTrue("CLAUDECODE" in refusal!!, refusal)
        assertTrue("nop --restart" in refusal, "the refusal says what to do instead: $refusal")
    }

    @Test
    fun `an agent of any CLI is refused the user's config even with no nop running`() {
        for (marker in listOf("NOP_AGENT_SESSION", "CODEX_THREAD_ID", "ANTIGRAVITY_CONVERSATION_ID")) {
            assertNotNull(AgentLaunchGuard.refusal(shell + (marker to "x"), users, users) { false }, marker)
        }
    }

    @Test
    fun `an agent may start a nop of its own, but not reach one running there`() {
        val agent = shell + ("CODEX_THREAD_ID" to "t")
        assertNull(AgentLaunchGuard.refusal(agent, own, users) { false }, "what scripts/screenshot.sh starts")
        assertNotNull(AgentLaunchGuard.refusal(agent, own, users) { true })
    }
}
