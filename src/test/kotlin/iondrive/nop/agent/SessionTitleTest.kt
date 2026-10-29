package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionTitleTest {
    private val instructions = """
        # AGENTS.md instructions for /workspace/editor

        <INSTRUCTIONS>
        Use the existing code style.
        </INSTRUCTIONS>
    """.trimIndent()

    @Test
    fun `instructions and environment alone supply no title`() {
        assertNull(SessionTitle.fromPrompt(instructions))
        assertNull(SessionTitle.fromPrompt("$instructions\n<environment_context>\n</environment_context>"))
        assertNull(SessionTitle.fromPrompt("# AGENTS.md instructions for /workspace/editor\n<INSTRUCTIONS>"))
    }

    @Test
    fun `a request after instructions environment and attachments supplies the title`() {
        val prompt = """
            $instructions
            <environment_context><shell>sh</shell></environment_context>
            <image name=[Image #1] path="/tmp/example.png">
            </image>
            <image>attachment</image>

            Fix the toolbar spacing
            Keep the buttons aligned.
        """.trimIndent()

        assertEquals("Fix the toolbar spacing", SessionTitle.fromPrompt(prompt))
    }

    @Test
    fun `incomplete attachment context supplies no title`() {
        assertNull(SessionTitle.fromPrompt("<image name=[Image #1] path=\"/tmp/example.png\">"))
    }
}
