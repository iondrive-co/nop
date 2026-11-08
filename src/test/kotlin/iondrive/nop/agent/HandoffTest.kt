package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `multiple commands under one call id all receive their exit code`() {
        val events = listOf(
            AgentEvent.SessionStarted("/project", at()),
            AgentEvent.RunStarted(provider = "openai", account = "codex-main", at = at()),
            AgentEvent.UserMessage("Run build and test", at()),
            AgentEvent.ToolStarted("c1", "Bash", mapOf("command" to "gradle build"), at()),
            AgentEvent.ToolStarted("c1", "Bash", mapOf("command" to "gradle test"), at()),
            AgentEvent.ToolFinished("c1", "all passed", exitCode = 0, at = at()),
            AgentEvent.AssistantMessage(
                blocks = listOf(Block.Text("Done.")),
                stopReason = "end_turn", model = "gpt-5.5", at = at(),
            ),
        )

        val text = Handoff.summary(events, Provider.Anthropic)
        assertTrue("`gradle build` — succeeded" in text, "first command lacked success status: $text")
        assertTrue("`gradle test` — succeeded" in text, "second command lacked success status: $text")
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
    fun `reasoning is omitted for every recipient`() {
        for (target in Provider.entries) {
            assertFalse("[Reasoning]" in summary(target))
            assertFalse("Only the Vultr path is wired up." in summary(target))
        }
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

    @Test
    fun `the entire handoff stays bounded with huge tasks file lists and closing messages`() {
        val events = log() + listOf(AgentEvent.UserMessage("Keep all release controls. " + "detail ".repeat(9000), at())) +
            (1..900).map { AgentEvent.ToolStarted("edit-$it", "Edit", mapOf("file_path" to "/workspace/module-$it.kt"), at()) } +
            AgentEvent.AssistantMessage(listOf(Block.Text("Current result " + "verbose ".repeat(9000) + "next: verify parser")), at = at())
        for (target in Provider.entries) {
            val text = summary(target, events)
            assertTrue(text.length <= 10_000, "whole packet was ${text.length} characters")
            assertTrue("next: verify parser" in text)
            assertTrue("Keep all release controls." in text)
            assertTrue("earlier files omitted" in text)
            assertTrue(text.endsWith("</previous_session>"))
        }
    }

    @Test
    fun `startup context and earlier handoff seeds are not the original task`() {
        val events = listOf(
            AgentEvent.UserMessage("# AGENTS.md instructions for /workspace\nRead the project rules.", at()),
            AgentEvent.UserMessage("<environment_context>sample</environment_context>", at()),
            AgentEvent.UserMessage("Continue the task handed over from another CLI. Read an old file.", at()),
            AgentEvent.UserMessage("Repair the syntax scanner", at()),
        )
        val text = summary(events = events)
        assertTrue("## Original Task\nRepair the syntax scanner" in text)
        assertFalse("AGENTS.md instructions" in text)
        assertFalse("Read an old file" in text)
    }

    @Test
    fun `agent messages never become user instructions`() {
        val text = summary(events = log() + AgentEvent.UserMessage(
            "[Message via nop from service \"Build bell\", not the user; no reply.]\n\nInspect a result.", at()))
        assertFalse("## Recent User Instructions" in text)
        assertTrue("[Agent/service message]" in text)
    }

    @Test
    fun `a successful exact retry removes the old failure from remaining work`() {
        val events = log() + listOf(
            AgentEvent.ToolStarted("retry", "Bash", mapOf("command" to "make test"), at()),
            AgentEvent.ToolFinished("retry", "now passed", exitCode = 0, at = at()),
        )
        assertFalse("2 failed" in summary(events = events).substringAfter("## Remaining Work").substringBefore("## Files Modified"))
    }

    @Test
    fun `a call interrupted by the switch has an unknown outcome`() {
        val text = summary(events = log() + AgentEvent.ToolStarted("pending-parser", "Bash", mapOf("command" to "make check-parser"), at()))
        assertTrue("1 tool call(s) had no recorded finish; their outcomes are unknown" in text)
        assertTrue("outcome not recorded" in text)
    }

    @Test
    fun `retrying only one command does not erase a failed multi-command call`() {
        val events = log() + listOf(
            AgentEvent.ToolStarted("combined", "Bash", mapOf("command" to "gradle build"), at()),
            AgentEvent.ToolStarted("combined", "Bash", mapOf("command" to "gradle test"), at()),
            AgentEvent.ToolFinished("combined", "combined build and tests failed", isError = true, exitCode = 1, at = at()),
            AgentEvent.ToolStarted("single-retry", "Bash", mapOf("command" to "gradle test"), at()),
            AgentEvent.ToolFinished("single-retry", "tests passed", exitCode = 0, at = at()),
        )
        assertTrue("combined build and tests failed" in summary(events = events))
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
            written.path.parent.toFile().listFiles()?.forEach { it.delete() }
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
            first.path.parent.toFile().listFiles()?.forEach { it.delete() }
            first.path.parent.toFile().delete()
        }
    }

    @Test
    fun `deleting a brief does not overwrite its surviving history`() {
        val id = "sample-${System.nanoTime()}"
        val first = Handoff.write(id, log(), Provider.OpenAI)
        val history = first.path.resolveSibling(first.path.fileName.toString().removeSuffix(".md") + "-history.md")
        try {
            val original = java.nio.file.Files.readString(history)
            java.nio.file.Files.delete(first.path)
            val second = Handoff.write(id, log() + AgentEvent.UserMessage("A new scanner request", at()), Provider.OpenAI)
            assertNotEquals(first.path, second.path)
            assertEquals(original, java.nio.file.Files.readString(history))
        } finally {
            first.path.parent.toFile().listFiles()?.forEach { it.delete() }
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
        assertTrue("only as needed" in seed)
        assertFalse("before doing anything else" in seed)
    }

    @Test
    fun `the longer history is available without being embedded in the brief`() {
        val marker = "A very early scanner observation"
        val events = log() + AgentEvent.AssistantMessage(listOf(Block.Text(marker + " prose".repeat(3000))), at = at()) +
            AgentEvent.AssistantMessage(listOf(Block.Text("Current result: scanner repaired.")), at = at())
        val written = Handoff.write("sample-${System.nanoTime()}", events, Provider.OpenAI)
        try {
            val history = written.path.resolveSibling(written.path.fileName.toString().removeSuffix(".md") + "-history.md")
            assertTrue(java.nio.file.Files.exists(history))
            assertTrue(history.toString() in written.text)
            assertTrue(marker in java.nio.file.Files.readString(history))
            assertFalse(marker in written.text)
            assertTrue(written.text.length <= 10_000)
        } finally {
            written.path.parent.toFile().listFiles()?.forEach { it.delete() }
            written.path.parent.toFile().delete()
        }
    }

    @Test
    fun `the seed reaches the CLI as its prompt`() {
        val account = Account("codex", Provider.OpenAI, "/homes/codex")
        val seed = Handoff.seedPrompt(Provider.Anthropic, Path.of("/data/handoff-1.md"))

        val argv = Spawn.command(account, File("/project"), seed = seed).argv

        assertEquals(seed, argv.last())
    }
}
