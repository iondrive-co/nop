package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

/**
 * The handoff: an event log in, something the next provider can carry on from out.
 *
 * This is the part the whole feature exists for, and the thing it has to get right is *truthfulness*
 * — a summary that reads as though the work finished, when in fact a test was failing and a turn was
 * cut off, is worse than no summary at all, because the incoming agent will build on it.
 */
class HandoffTest {

    private var clock = 1_700_000_000_000L
    private fun at() = clock++

    private fun log(): MutableList<AgentEvent> = mutableListOf(
        AgentEvent.SessionStarted("/project", at()),
        AgentEvent.RunStarted(provider = "anthropic", account = "claude-main", at = at()),
        AgentEvent.UserMessage("Add AWS support to the provisioning script", at()),
        AgentEvent.AssistantMessage(
            blocks = listOf(
                Block.Thinking("Only the Vultr path is wired up."),
                Block.Text("I'll look at the existing setup first."),
                Block.ToolCall("c1", "Read", mapOf("file_path" to "/project/main.tf")),
            ),
            stopReason = "tool_use", model = "claude-opus-5", at = at(),
        ),
        AgentEvent.ToolStarted("c1", "Read", mapOf("file_path" to "/project/main.tf"), at()),
        AgentEvent.ToolFinished("c1", "resource \"vultr_instance\"", at = at()),
        AgentEvent.ToolStarted("c2", "Write", mapOf("file_path" to "/project/aws.tf"), at()),
        AgentEvent.ToolFinished("c2", "written", at = at()),
        AgentEvent.ToolStarted("c3", "Edit", mapOf("file_path" to "/project/main.tf"), at()),
        AgentEvent.ToolFinished("c3", "edited", at = at()),
        AgentEvent.ToolStarted("c4", "Bash", mapOf("command" to "make test"), at()),
        AgentEvent.ToolFinished("c4", "2 failed", isError = true, exitCode = 1, at = at()),
        AgentEvent.AssistantMessage(
            blocks = listOf(Block.Text("The AWS module is in place but two tests still fail.")),
            stopReason = "end_turn", model = "claude-opus-5", at = at(),
        ),
    )

    private fun summary(target: Provider = Provider.OpenAI, events: List<AgentEvent> = log()) =
        Handoff.summary(events, target)

    @Test
    fun `the task is the first thing the user typed`() {
        assertTrue("Add AWS support to the provisioning script" in summary())
    }

    @Test
    fun `a session with no user message still states a task`() {
        val text = Handoff.summary(
            listOf(AgentEvent.SessionStarted("/project", at())),
            Provider.Anthropic,
        )

        assertTrue("## Original Task" in text)
        assertTrue("Continue the work already in progress" in text)
    }

    @Test
    fun `created and modified files are listed, and a created file is not also modified`() {
        val text = summary()

        assertTrue("- Created: `/project/aws.tf`" in text)
        assertTrue("- Modified: `/project/main.tf`" in text)
        assertFalse("- Modified: `/project/aws.tf`" in text)
    }

    @Test
    fun `a file that was only read is not listed as changed`() {
        assertFalse("Modified: `/project/main.tf`" in summary(events = log().filterNot {
            it is AgentEvent.ToolStarted && it.tool == "Edit"
        }))
    }

    /**
     * The single most useful sentence in the whole summary. "Ran the tests" and "ran the tests and
     * they failed" are different pieces of news, and an incoming agent told only the first will
     * happily declare the work done.
     */
    @Test
    fun `a command that failed says so, with its exit code`() {
        val text = summary()

        assertTrue("`make test`" in text)
        assertTrue("failed (exit 1)" in text, "a failing command read as a passing one:\n$text")
    }

    @Test
    fun `a successful command is marked as such`() {
        val events = log().map {
            if (it is AgentEvent.ToolFinished && it.callId == "c4") {
                it.copy(isError = false, exitCode = 0, summary = "all green")
            } else {
                it
            }
        }

        assertTrue("succeeded" in summary(events = events))
    }

    @Test
    fun `an incidental command is not listed`() {
        val events = log() + listOf(
            AgentEvent.ToolStarted("c9", "Bash", mapOf("command" to "ls -la"), at()),
            AgentEvent.ToolFinished("c9", "total 96", exitCode = 0, at = at()),
        )

        assertFalse("`ls -la`" in summary(events = events), "a directory listing says nothing")
    }

    @Test
    fun `the remaining work leads with the previous agent's own last words`() {
        val text = summary()

        assertTrue("## Remaining Work" in text)
        assertTrue("The AWS module is in place but two tests still fail." in text)
        assertTrue("rather than starting discovery again" in text)
    }

    @Test
    fun `what did not succeed is called out in the remaining work`() {
        val remaining = summary().substringAfter("## Remaining Work")

        assertTrue("2 failed" in remaining, "the failing command never reached the remaining work")
    }

    @Test
    fun `a turn that was interrupted is reported as interrupted`() {
        val events = log() + AgentEvent.AssistantMessage(
            blocks = emptyList(), stopReason = "aborted:interrupted", at = at(),
        )

        assertTrue(
            "interrupted" in summary(events = events),
            "an incoming agent must not treat a half-finished change as a finished one",
        )
    }

    // -- Shaped for whoever is reading it --

    @Test
    fun `Codex is given the previous reasoning, which it uses well`() {
        assertTrue("[Reasoning]: Only the Vultr path is wired up." in summary(Provider.OpenAI))
    }

    /**
     * Claude regenerates its own reasoning from scratch, so a previous model's thinking is not just
     * redundant to it — it reads as settled conclusions that were never actually checked.
     */
    @Test
    fun `Claude is not given another model's thinking`() {
        val text = summary(Provider.Anthropic)

        assertFalse("[Reasoning]" in text)
        assertFalse("Only the Vultr path is wired up." in text)
        assertTrue("I'll look at the existing setup first." in text, "the actual answer was dropped too")
    }

    @Test
    fun `both providers get the tool calls, described rather than dumped`() {
        for (target in Provider.entries) {
            val text = summary(target)
            assertTrue("[Tool: Read] /project/main.tf" in text, "for ${target.id}:\n$text")
        }
    }

    @Test
    fun `a very long conversation is cut to its tail, and says that it was`() {
        val chatty = log() + (1..4000).map {
            AgentEvent.AssistantMessage(listOf(Block.Text("turn $it of a very long session")), at = at())
        }

        val text = summary(events = chatty)

        assertTrue("(earlier turns omitted)" in text)
        assertTrue("turn 4000 of a very long session" in text, "the tail is where the work got to")
        assertTrue(text.length < 40_000, "summary was ${text.length} chars")
    }

    // -- The degraded path --

    private fun screenOnly() = listOf(
        AgentEvent.SessionStarted("/project", at()),
        AgentEvent.RunStarted(provider = "openai", account = "codex", at = at()),
        AgentEvent.ScreenTail("> fixing the parser\n", at()),
        AgentEvent.ScreenTail("ran the tests, 1 failing\n", at()),
    )

    @Test
    fun `with no transcript the summary is built from the screen, and admits it`() {
        val text = Handoff.summary(screenOnly(), Provider.Anthropic)

        assertTrue("## Agent Work Log" in text)
        assertTrue("ran the tests, 1 failing" in text)
        assertTrue(
            "could not be read" in text,
            "an incoming agent should know it is reading a worse record than usual",
        )
    }

    @Test
    fun `a session with a real transcript is not flagged as screen-only`() {
        assertTrue(Handoff.fromScreenOnly(screenOnly()))
        assertFalse(Handoff.fromScreenOnly(log()))
    }

    @Test
    fun `screen text is not used when there is a transcript to use instead`() {
        val both = log() + AgentEvent.ScreenTail("noise from a redraw", at())

        assertFalse("## Agent Work Log" in summary(events = both))
    }

    // -- Writing it, and what the new CLI is started with --

    @Test
    fun `the summary is written outside the project, so nothing lands in the repository`() {
        val written = Handoff.write("session-${System.nanoTime()}", log(), Provider.OpenAI)
        try {
            assertTrue(written.path.toFile().isFile)
            assertFalse(
                written.path.toAbsolutePath().startsWith(Path.of("/project")),
                "a summary in the repo is a file the user has to notice and gitignore",
            )
            assertEquals(written.text, written.path.toFile().readText())
        } finally {
            written.path.toFile().delete()
            written.path.parent.toFile().delete()
        }
    }

    @Test
    fun `a second handoff in one session does not overwrite the first`() {
        val id = "session-${System.nanoTime()}"
        val first = Handoff.write(id, log(), Provider.OpenAI)
        val second = Handoff.write(id, log(), Provider.Anthropic)
        try {
            assertFalse(first.path == second.path, "the earlier handoff was overwritten")
            assertTrue(first.path.toFile().isFile)
        } finally {
            listOf(first, second).forEach { it.path.toFile().delete() }
            first.path.parent.toFile().delete()
        }
    }

    @Test
    fun `a deleted earlier handoff does not make the next one overwrite a later one`() {
        val id = "session-${System.nanoTime()}"
        val first = Handoff.write(id, log(), Provider.OpenAI)
        val second = Handoff.write(id, log(), Provider.OpenAI)
        try {
            first.path.toFile().delete()

            val third = Handoff.write(id, log(), Provider.OpenAI)

            assertFalse(third.path == second.path, "numbering from a count clobbers handoff-2")
            assertTrue(second.path.toFile().isFile)
        } finally {
            listOf(first, second).forEach { it.path.toFile().delete() }
            first.path.parent.toFile().listFiles()?.forEach { it.delete() }
            first.path.parent.toFile().delete()
        }
    }

    /**
     * The prompt names the file rather than carrying the summary. Linux caps one argv element at
     * 128 KiB and a full session exceeds that easily — and a file left on disk is something to read
     * when a handoff goes wrong, which an argument to an exited process is not.
     */
    @Test
    fun `the seed prompt is one short line pointing at the file`() {
        val path = Path.of("/data/handoffs/s1/handoff-1.md")

        val seed = Handoff.seedPrompt(Provider.Anthropic, path)

        assertTrue(seed.length < 300, "the seed is meant to be a line, not the summary: $seed")
        assertTrue(path.toString() in seed)
        assertTrue("Claude Code" in seed, "the new agent should know who it is taking over from")
        assertTrue("Remaining Work" in seed, "the seed points at the section that says what is left")
    }

    @Test
    fun `the seed reaches the CLI as its prompt`() {
        val account = Account("codex", Provider.OpenAI, "/homes/codex")
        val seed = Handoff.seedPrompt(Provider.Anthropic, Path.of("/data/handoff-1.md"))

        val argv = Spawn.command(account, File("/project"), seed = seed).argv

        assertEquals(seed, argv.last())
    }
}
