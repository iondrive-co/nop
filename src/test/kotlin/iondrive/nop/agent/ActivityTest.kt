package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Telling a tab that is working from one that is waiting on its user.
 *
 * A session that has asked a question and one that is working look alike from outside, however
 * long the question waits. The titles here are the shapes the CLIs really write.
 */
class ActivityTest {
    @Test
    fun `a queued question is seen however the read cuts its last word`() {
        for (text in listOf("alt + ↑ to answer", "• Queued follow-up inputs")) {
            for (cut in 1 until text.length) {
                val tracker = ActivityTracker()
                // Either read may be the one to complete it: "input" is whole before its "s" arrives.
                val first = tracker.onOutput(text.substring(0, cut))
                assertTrue(first || tracker.onOutput(text.substring(cut)), "missed at $cut")
            }
        }
    }

    @Test
    fun `a permission prompt split across reads is seen while a call is open, and only then`() {
        val prompt = "Do you want to proceed?\n ❯ 1. Yes"
        for (cut in 1 until prompt.length) {
            val tracker = ActivityTracker()
            tracker.onEvent(AgentEvent.ToolStarted("c1", "Bash", emptyMap(), 0))
            tracker.onOutput(prompt.substring(0, cut))
            assertTrue(tracker.onOutput(prompt.substring(cut)), "missed at $cut")
        }
        assertFalse(ActivityTracker().onOutput(prompt))
    }

    @Test
    fun `a prompt already on screen when its call opens is read at the next output`() {
        val tracker = ActivityTracker()
        tracker.onOutput("Do you want to proceed?\n ❯ 1. Yes")
        tracker.onEvent(AgentEvent.ToolStarted("c1", "Bash", emptyMap(), 0))
        assertTrue(tracker.onOutput("✻"))
    }

    private val esc = "\u001b"
    private val bel = "\u0007"

    private fun osc(title: String) = "$esc]0;$title$bel"

    // ---- reading titles off the PTY ----

    @Test
    fun `a title is read out of the output around it`() {
        val reader = TitleReader()
        assertEquals("✳ Claude Code", reader.feed("${esc}[?2026h${osc("✳ Claude Code")}${esc}[2J hello"))
    }

    @Test
    fun `the last title in a read is the one that counts`() {
        val reader = TitleReader()
        assertEquals("◑ Task 36", reader.feed(osc("◐ Task 36") + "x" + osc("◑ Task 36")))
    }

    @Test
    fun `a title split across two reads is put back together`() {
        val reader = TitleReader()
        assertNull(reader.feed("before $esc]0;◐ Task"))
        assertEquals("◐ Task 36 review", reader.feed(" 36 review$bel after"))
    }

    @Test
    fun `the two-character terminator is found even split between reads`() {
        val reader = TitleReader()
        assertNull(reader.feed("$esc]2;✳ Done$esc"))
        assertEquals("✳ Done", reader.feed("\\rest"))
    }

    @Test
    fun `hyperlinks and colour queries are not titles`() {
        val reader = TitleReader()
        assertNull(reader.feed("$esc]8;id=jk1;file:///home/dev/shop/$esc\\link$esc]8;;$esc\\ $esc]11;?$bel"))
    }

    @Test
    fun `an empty title is still a title`() {
        // Antigravity writes exactly this, and it must not be mistaken for nothing having been said.
        assertEquals("", TitleReader().feed("$esc]0;$bel"))
    }

    @Test
    fun `a title that never ends is dropped rather than held forever`() {
        val reader = TitleReader()
        reader.feed("$esc]0;")
        repeat(10) { reader.feed("x".repeat(1000)) }
        assertEquals("✳ ok", reader.feed(osc("✳ ok")))
    }

    // ---- what a title says ----

    @Test
    fun `Claude's spinner is working and its star is stopped`() {
        assertEquals(TitleSignal.Says.Working, TitleSignal.read("◐ Claude Code"))
        assertEquals(TitleSignal.Says.Working, TitleSignal.read("◓ Task 36 review and implementation"))
        assertEquals(TitleSignal.Says.Stopped, TitleSignal.read("✳ Claude Code"))
    }

    @Test
    fun `Codex's braille is working, bracketed or not, and it says when it is blocked`() {
        assertEquals(TitleSignal.Says.Working, TitleSignal.read("⠼ ops"))
        assertEquals(TitleSignal.Says.Working, TitleSignal.read("[ ⠹ ] Working | ops"))
        assertEquals(TitleSignal.Says.Blocked, TitleSignal.read("[ ! ] Action Required | ops"))
        assertEquals(TitleSignal.Says.Blocked, TitleSignal.read("[ . ] Action Required | ops"))
    }

    @Test
    fun `a title with no known glyph says nothing by itself`() {
        assertNull(TitleSignal.read("ops"))
        assertNull(TitleSignal.read(""))
    }

    // ---- the tracker ----

    private fun started(id: String, tool: String) = AgentEvent.ToolStarted(id, tool, at = 0)
    private fun finished(id: String) = AgentEvent.ToolFinished(id, at = 0)

    @Test
    fun `a tab nobody has heard from is only running`() {
        assertEquals(Activity.Running, ActivityTracker().activity(now = 0))
    }

    @Test
    fun `a spinner is working and the star after it is idle`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Task 36", at = 0)
        assertEquals(Activity.Working, tracker.activity(now = 100))
        tracker.onTitle("✳ Task 36", at = 200)
        assertEquals(Activity.Idle, tracker.activity(now = 200))
    }

    /** A question left open: an AskUserQuestion call still waiting, and nothing else moving. */
    @Test
    fun `an open question is asking whatever the title says`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Task 36", at = 0)
        tracker.onEvent(started("toolu_013f", "AskUserQuestion"))
        assertEquals(Activity.Asking, tracker.activity(now = 10))
        tracker.onTitle("✳ Task 36", at = 20)
        assertEquals(Activity.Asking, tracker.activity(now = 42_333_190))
        tracker.onEvent(finished("toolu_013f"))
        tracker.onTitle("◐ Task 36", at = 42_333_300)
        assertEquals(Activity.Working, tracker.activity(now = 42_333_300))
    }

    @Test
    fun `a plan waiting for approval is a question too`() {
        val tracker = ActivityTracker()
        tracker.onEvent(started("p", "ExitPlanMode"))
        assertEquals(Activity.Asking, tracker.activity(now = 0))
    }

    @Test
    fun `a CLI that stops with a call still open is waiting on the user`() {
        // Permissions are bypassed for every CLI nop starts, so a stopped CLI holding an unanswered
        // call has nothing left to wait for but a person.
        val tracker = ActivityTracker()
        tracker.onEvent(started("b", "Bash"))
        tracker.onTitle("◐ x", at = 0)
        assertEquals(Activity.Working, tracker.activity(now = 0))
        tracker.onTitle("✳ x", at = 10)
        assertEquals(Activity.Asking, tracker.activity(now = 10))
    }

    @Test
    fun `answered calls leave a stopped CLI idle`() {
        val tracker = ActivityTracker()
        tracker.onEvent(started("b", "Bash"))
        tracker.onEvent(finished("b"))
        tracker.onTitle("✳ x", at = 0)
        assertEquals(Activity.Idle, tracker.activity(now = 0))
    }

    @Test
    fun `a new prompt forgets calls the last turn left open`() {
        val tracker = ActivityTracker()
        tracker.onEvent(started("lost", "Bash"))
        tracker.onEvent(AgentEvent.UserMessage("next", at = 0))
        tracker.onTitle("✳ x", at = 0)
        assertEquals(Activity.Idle, tracker.activity(now = 0))
    }

    @Test
    fun `a completed turn releases a permission prompt without masking the next question`() {
        val tracker = ActivityTracker()
        tracker.onEvent(started("command", "Bash"))
        tracker.onOutput("Do you want to proceed?\n❯ 1. Yes\n  2. No\n")
        tracker.onTitle("[ ! ] Action Required | sample", at = 0)
        tracker.onEvent(AgentEvent.AssistantMessage(emptyList(), stopReason = "tool_use", at = 1))
        assertEquals(Activity.Asking, tracker.activity(now = 1))

        tracker.onEvent(AgentEvent.AssistantMessage(emptyList(), stopReason = "end_turn", at = 2))
        assertEquals(Activity.Idle, tracker.activity(now = 2))
        tracker.onEvent(AgentEvent.UserMessage("Continue", at = 3))
        tracker.onEvent(started("question", "AskUserQuestion"))
        assertEquals(Activity.Asking, tracker.activity(now = 4))
    }

    @Test
    fun `Codex saying Action Required is asking`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ! ] Action Required | ops", at = 0)
        assertEquals(Activity.Asking, tracker.activity(now = 0))
    }

    @Test
    fun `a queued question makes a working tab asking`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ⠹ ] Working | ops", at = 0)
        assertEquals(Activity.Working, tracker.activity(now = 0))

        val changed = tracker.onOutput(
            "Working (31m 54s • esc to interrupt) · 1 background terminal running\n" +
                "• Queued follow-up inputs\n" +
                "  ? 1 question\n" +
                "    alt + ↑ to answer\n",
        )
        assertTrue(changed)
        assertEquals(Activity.Asking, tracker.activity(now = 10))
    }

    @Test
    fun `a Codex async question stays asking through the calls after it until the user answers`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ⠹ ] Working | hermes", at = 0)
        tracker.onEvent(started("q", "request_user_input_async"))
        tracker.onEvent(finished("q"))                       // returns {"accepted":true} at once
        assertEquals(Activity.Asking, tracker.activity(now = 0))
        tracker.onEvent(started("b", "Bash"))
        tracker.onEvent(finished("b"))
        tracker.onTitle("[ ] Idle | hermes", at = 10)
        assertEquals(Activity.Asking, tracker.activity(now = 10_000))
        assertTrue(tracker.onUserInput())
        assertEquals(Activity.Idle, tracker.activity(now = 10_000))
    }

    @Test
    fun `words about a queued question on screen do not hold a tab asking`() {
        // A Claude tab whose reply quotes Codex's prompt is not asking once its turn's calls are done.
        val tracker = ActivityTracker()
        tracker.onEvent(started("b", "Bash"))
        tracker.onOutput("Codex shows \"? 1 question · alt + ↑ to answer\"\n")
        tracker.onEvent(finished("b"))
        tracker.onTitle("✳ x", at = 0)
        assertEquals(Activity.Idle, tracker.activity(now = 0))
    }

    @Test
    fun `a Codex async question survives a nop restart`() {
        val tracker = ActivityTracker()
        tracker.onEvent(started("q", "request_user_input_async"))
        tracker.onEvent(finished("q"))
        val next = ActivityTracker()
        next.restore(tracker.snapshot(), now = 0)
        next.onTitle("[ ] Idle | hermes", at = 0)
        assertEquals(Activity.Asking, next.activity(now = 10_000))
        next.onEvent(AgentEvent.UserMessage("my answer", at = 0))
        assertEquals(Activity.Idle, next.activity(now = 10_000))
    }

    @Test
    fun `an answered permission prompt still clears with its call`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ⠹ ] Working | ops", at = 0)
        tracker.onEvent(started("b", "Bash"))
        tracker.onOutput("Do you want to proceed?\n❯ 1. Yes\n  2. No\n")
        val asked = tracker.activity(now = 0)
        tracker.onEvent(finished("b"))
        assertEquals(Activity.Working, tracker.activity(now = 0))
        assertEquals(Activity.Asking, asked)
    }

    @Test
    fun `user input clears the queued question state`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ⠹ ] Working | ops", at = 0)
        tracker.onOutput("• Queued follow-up inputs\n  alt + ↑ to answer")
        assertEquals(Activity.Asking, tracker.activity(now = 0))

        val changed = tracker.onUserInput()
        assertTrue(changed)
        assertEquals(Activity.Working, tracker.activity(now = 0))
    }

    @Test
    fun `a new user message clears the queued question state`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ⠹ ] Working | ops", at = 0)
        tracker.onOutput("• Queued follow-up inputs\n  alt + ↑ to answer")
        assertEquals(Activity.Asking, tracker.activity(now = 0))

        tracker.onEvent(AgentEvent.UserMessage("my answer", at = 0))
        assertEquals(Activity.Working, tracker.activity(now = 0))
    }

    @Test
    fun `queued question with ansi escapes is detected`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ⠹ ] Working | ops", at = 0)
        val esc = "\u001b"
        tracker.onOutput("${esc}[32m• Queued follow-up inputs${esc}[0m\n  alt + ↑ to answer")
        assertEquals(Activity.Asking, tracker.activity(now = 0))
    }

    @Test
    fun `Claude Code permission prompt makes a working tab asking`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Handoff continuation from Claude", at = 0)
        tracker.onEvent(started("toolu_01FGqtmWj4TbY9GFpDcC8NDm", "mcp__ainun-ssh__shell"))
        assertEquals(Activity.Working, tracker.activity(now = 0))

        val changed = tracker.onOutput(
            "Hook PreToolUse: mcp__ainun-ssh__shell requires confirmation for this command:\n" +
                "First ainun-ssh call against \"dev\" environment in the last 4 hours.\n" +
                "Do you want to proceed? ❯ 1. Yes  2. No\n" +
                "Esc to cancel · Tab to amend\n",
        )
        assertTrue(changed)
        assertEquals(Activity.Asking, tracker.activity(now = 10))
    }

    @Test
    fun `Claude Code permission prompt with stripped ANSI escapes is detected`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Claude Code", at = 0)
        tracker.onEvent(started("toolu_123", "Bash"))
        assertEquals(Activity.Working, tracker.activity(now = 0))

        val changed = tracker.onOutput(
            "HookPreToolUse:Bashrequiresconfirmationforthiscommand:\n" +
                "Hookasksforconfirmationsettings.jsontoupdatehooksDoyouwanttoproceed? ❯ 1. Yes  2.NoEscto cancel",
        )
        assertTrue(changed)
        assertEquals(Activity.Asking, tracker.activity(now = 10))
    }

    /**
     * The prompt can be on screen before the transcript says which call it holds, since the tailer
     * reads the call on a later pass. What is already on screen counts once the call is known.
     */
    @Test
    fun `a permission prompt drawn before its call is known counts once it is`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Claude Code", at = 0)
        assertFalse(tracker.onOutput("Do you want to proceed? ❯ 1. Yes  2. No\n"))

        tracker.onEvent(started("toolu_321", "Bash"))

        assertTrue(tracker.onOutput("✻"))
        assertEquals(Activity.Asking, tracker.activity(now = 0))
    }

    @Test
    fun `a queued question is found however much output came before it`() {
        val tracker = ActivityTracker()
        repeat(200) { tracker.onOutput("✻ Thinking… (${it}s)\n") }

        assertFalse(tracker.onOutput("? 1 question · alt + "))
        assertTrue(tracker.onOutput("↑ to answer"))
    }

    @Test
    fun `answering a permission prompt returns the tab to working`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Claude Code", at = 0)
        tracker.onEvent(started("toolu_123", "Bash"))
        tracker.onOutput("Do you want to proceed? ❯ 1. Yes  2. No")
        assertEquals(Activity.Asking, tracker.activity(now = 0))

        val changed = tracker.onUserInput()
        assertTrue(changed)
        assertEquals(Activity.Working, tracker.activity(now = 0))
    }

    @Test
    fun `tool finishing clears the permission prompt state`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Claude Code", at = 0)
        tracker.onEvent(started("toolu_123", "Bash"))
        tracker.onOutput("Do you want to proceed? ❯ 1. Yes  2. No")
        assertEquals(Activity.Asking, tracker.activity(now = 0))

        tracker.onEvent(finished("toolu_123"))
        assertEquals(Activity.Working, tracker.activity(now = 0))
    }

    @Test
    fun `real session screen tail permission prompt is detected`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Kafka Shadow", at = 0)
        tracker.onEvent(started("toolu_456", "mcp__ainun-ssh__shell"))
        val changed = tracker.onOutput(
            "pen theirwindowsfor30minutes(eachapproved│callagainstahostrefreshesitswindow,upto4hoursfromnow, " +
                "│ after which you are asked again). The server-side allowlist still │ " +
                "applies.settings.json to update hooks Do you want to proceed? ❯1. Yes   2. NoEsc to cancel · Tab to amend",
        )
        assertTrue(changed)
        assertEquals(Activity.Asking, tracker.activity(now = 0))
    }

    /** An agent describing a permission prompt in its reply is not showing one. */
    @Test
    fun `a reply that quotes a permission prompt is not a question`() {
        val tracker = ActivityTracker()
        tracker.onTitle("✳ Claude Code", at = 0)

        val changed = tracker.onOutput("It turns orange on text like \"Do you want to proceed? ❯ 1. Yes\".")

        assertFalse(changed)
        assertEquals(Activity.Idle, tracker.activity(now = 10_000))
    }

    @Test
    fun `tool output that only mentions confirmation is not a question`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Claude Code", at = 0)
        tracker.onEvent(started("toolu_789", "Bash"))

        assertFalse(tracker.onOutput("deploy.sh requires confirmation; Do you want to proceed? (y/n) is printed by it"))
        assertEquals(Activity.Working, tracker.activity(now = 0))
    }

    /**
     * Codex's stopped title carries no glyph, so it means nothing until it has stood still — and
     * until then the tab is still what it was, not "running", which would be a change from working
     * that never happened.
     */
    @Test
    fun `a plain title holds the last answer until it has settled`() {
        val tracker = ActivityTracker()
        tracker.onTitle("⠼ ops", at = 0)
        tracker.onTitle("ops", at = 500)
        assertEquals(Activity.Working, tracker.activity(now = 600))
        assertEquals(500 + TitleSignal.STILL_MS, tracker.settlesAt())
        assertEquals(Activity.Idle, tracker.activity(now = 500 + TitleSignal.STILL_MS))
    }

    @Test
    fun `the same title again does not restart the wait`() {
        val tracker = ActivityTracker()
        tracker.onTitle("ops", at = 0)
        tracker.onTitle("ops", at = 1000)
        assertEquals(Activity.Idle, tracker.activity(now = TitleSignal.STILL_MS))
    }

    @Test
    fun `a title that says what it means has nothing to settle`() {
        val tracker = ActivityTracker()
        tracker.onTitle("✳ x", at = 0)
        assertNull(tracker.settlesAt())
    }

    @Test
    fun `a blank title does not overwrite transcript or hook activity`() {
        val tracker = ActivityTracker()
        tracker.onEvent(AgentEvent.UserMessage("check the build", at = 100))
        assertEquals(Activity.Working, tracker.activity(now = 100))

        tracker.onTitle("", at = 200)
        assertEquals(Activity.Working, tracker.activity(now = 10_000))
        assertNull(tracker.settlesAt())
    }

    @Test
    fun `a title-less CLI tracks working, asking and idle from its transcript events`() {
        val tracker = ActivityTracker()
        assertEquals(Activity.Running, tracker.activity(now = 0))

        tracker.onEvent(AgentEvent.UserMessage("inspect the config", at = 1_000))
        assertEquals(Activity.Working, tracker.activity(now = 1_000))

        tracker.onEvent(AgentEvent.ToolStarted("c1", "Bash", emptyMap(), at = 2_000))
        assertEquals(Activity.Working, tracker.activity(now = 2_000))
        tracker.onEvent(AgentEvent.ToolFinished("c1", at = 3_000))
        assertEquals(Activity.Working, tracker.activity(now = 3_000))

        tracker.onEvent(AgentEvent.ToolStarted("q1", "ask_question", emptyMap(), at = 4_000))
        assertEquals(Activity.Asking, tracker.activity(now = 4_000))
        tracker.onEvent(AgentEvent.ToolFinished("q1", at = 5_000))
        assertEquals(Activity.Working, tracker.activity(now = 5_000))

        tracker.onEvent(AgentEvent.AssistantMessage(listOf(Block.Text("All set.")), stopReason = "end_turn", at = 6_000))
        assertEquals(Activity.Idle, tracker.activity(now = 6_000))
    }

    @Test
    fun `a stop hook clears an open question and ignores trailing transcript lines written before it`() {
        val tracker = ActivityTracker()
        tracker.onTurnWorking()
        tracker.onEvent(AgentEvent.ToolStarted("q1", "ask_question", emptyMap(), at = 10_000))
        assertEquals(Activity.Asking, tracker.activity(now = 10_000))

        tracker.onTurnStopped(at = 10_500)
        assertEquals(Activity.Idle, tracker.activity(now = 10_500))

        // Trailing lines read by the polling tailer moments after Stop must not flip the tab back.
        tracker.onEvent(AgentEvent.UserMessage("canceled prompt", at = 10_000))
        tracker.onEvent(AgentEvent.ToolStarted("q2", "ask_question", emptyMap(), at = 10_000))
        assertEquals(Activity.Idle, tracker.activity(now = 11_000))

        // A fresh PreInvocation immediately resumes working, and a much later transcript turn also recovers.
        tracker.onTurnWorking()
        assertEquals(Activity.Working, tracker.activity(now = 11_100))
        tracker.onTurnStopped(at = 12_000)
        tracker.onEvent(AgentEvent.UserMessage("later turn without hook", at = 20_000))
        assertEquals(Activity.Working, tracker.activity(now = 20_000))
    }

    @Test
    fun `Antigravity tool confirmation prompts mark an open call as asking, even split across reads`() {
        for (prompt in listOf(
            "Yes, allow tool call\nNo, deny permission",
            "Yes,allowaccess  No,denypermission",
            "Run this command?\n  Yes, allow once\n  No, deny",
        )) {
            for (cut in 1 until prompt.length) {
                val tracker = ActivityTracker()
                tracker.onEvent(AgentEvent.ToolStarted("c1", "Bash", emptyMap(), at = 1_000))
                val first = tracker.onOutput(prompt.substring(0, cut))
                assertTrue(first || tracker.onOutput(prompt.substring(cut)), "missed at $cut for $prompt")
                assertEquals(Activity.Asking, tracker.activity(now = 1_000))
            }
            // Outside an open tool call, prose quoting the same options is not a confirmation dialog.
            assertFalse(ActivityTracker().onOutput(prompt))
        }
    }

    @Test
    fun `a title-less CLI's working and idle states survive snapshot and restore`() {
        val working = ActivityTracker().apply { onTurnWorking() }
        val restoredWorking = ActivityTracker().apply { restore(working.snapshot(), now = 1_000) }
        assertEquals(Activity.Working, restoredWorking.activity(now = 1_000))

        val idle = ActivityTracker().apply {
            onTurnWorking()
            onTurnStopped(at = 2_000)
        }
        val restoredIdle = ActivityTracker().apply { restore(idle.snapshot(), now = 3_000) }
        assertEquals(Activity.Idle, restoredIdle.activity(now = 3_000))
    }
}

/**
 * The unseen flag: whether a tab has stopped since the user last looked at it.
 *
 * No process is started, as in [AgentSessionsTest]: the tracker is fed directly and read back with
 * [AgentSession.refreshActivity], which is what the PTY tap and the tailer do between them.
 */
class AgentActivityTest {
    private val opened = mutableListOf<AgentSessions>()

    @AfterEach
    fun cleanUp() {
        opened.forEach { state ->
            state.sessions.forEach { runCatching { Files.deleteIfExists(it.log.file) } }
            state.disposeAll()
        }
    }

    private fun open(dir: Path): AgentSession =
        AgentSessions().also { opened += it }.open(dir.toFile(), Account("claude-main", Provider.Anthropic, "/homes/c"))

    private fun AgentSession.title(text: String, at: Long) {
        run.tracker.onTitle(text, at)
        refreshActivity(run.tracker, now = at)
    }

    private fun AgentSession.event(event: AgentEvent, at: Long) {
        run.tracker.onEvent(event)
        refreshActivity(run.tracker, now = at)
    }

    @Test
    fun `a tab whose CLI has not been started is asleep`(@TempDir tmp: Path) {
        assertEquals(Activity.Asleep, open(tmp).activity)
    }

    @Test
    fun `finishing behind another tab is unseen`(@TempDir tmp: Path) {
        val session = open(tmp)
        session.title("◐ x", at = 1000)
        assertFalse(session.unseen)
        session.title("✳ x", at = 2000)
        assertTrue(session.unseen)
        assertEquals(2000, session.activitySince)
    }

    @Test
    fun `finishing in front of the user is not`(@TempDir tmp: Path) {
        val session = open(tmp)
        session.watch()
        session.title("◐ x", at = 1000)
        session.title("✳ x", at = 2000)
        assertFalse(session.unseen)
    }

    @Test
    fun `looking at the tab clears it, and looking away does not bring it back`(@TempDir tmp: Path) {
        val session = open(tmp)
        session.title("◐ x", at = 1000)
        session.title("✳ x", at = 2000)
        session.watch()
        assertFalse(session.unseen)
        session.unwatch()
        assertFalse(session.unseen)
    }

    @Test
    fun `a CLI coming up idle has nothing to announce`(@TempDir tmp: Path) {
        // Every tab restored at start comes up on "✳ Claude Code". None of them has done anything.
        val session = open(tmp)
        session.title("✳ Claude Code", at = 1000)
        assertFalse(session.unseen)
    }

    @Test
    fun `a question is news whatever came before it`(@TempDir tmp: Path) {
        val session = open(tmp)
        session.event(AgentEvent.ToolStarted("q", "AskUserQuestion", at = 0), at = 1000)
        assertTrue(session.unseen)
    }

    @Test
    fun `going back to work by itself takes the flag away`(@TempDir tmp: Path) {
        val session = open(tmp)
        session.title("◐ x", at = 1000)
        session.title("✳ x", at = 2000)
        session.title("◐ x", at = 3000)
        assertFalse(session.unseen)
    }

    @Test
    fun `a run that ends while working behind another tab is unseen`(@TempDir tmp: Path) {
        val session = open(tmp)
        session.title("◐ x", at = 1000)
        session.endRun(EndReason.Exited)
        assertEquals(Activity.Ended, session.activity)
        assertTrue(session.unseen)
    }

    @Test
    fun `a title from a run a switch replaced changes nothing`(@TempDir tmp: Path) {
        val session = open(tmp)
        val old = session.run.tracker
        session.switchTo(Account("claude-side", Provider.Anthropic, "/homes/s"))
        old.onTitle("◐ x", 1000)
        session.refreshActivity(old, now = 1000)
        old.onTitle("✳ x", 2000)
        session.refreshActivity(old, now = 2000)
        assertFalse(session.unseen)
    }

    @Test
    fun `an Antigravity turn that finishes behind another tab marks unseen, even within one refresh`(@TempDir tmp: Path) {
        val session = AgentSessions().also { opened += it }
            .open(tmp.toFile(), Account("agy-alpha", Provider.Antigravity, "/homes/agy-alpha"))
        session.run.session.running = true

        // Both UserMessage and end_turn arrive before the UI thread refreshes.
        session.run.tracker.onEvent(AgentEvent.UserMessage("summarise the diff", at = 1_000))
        session.run.tracker.onEvent(AgentEvent.AssistantMessage(listOf(Block.Text("Two files changed.")), stopReason = "end_turn", at = 1_500))
        session.refreshActivity(session.run.tracker, now = 1_500)

        assertEquals(Activity.Idle, session.activity)
        assertTrue(session.hasWorked)
        assertTrue(session.unseen)
        assertEquals(1_500L, session.activitySince)

        // Reading the tab clears unseen; a second fast turn behind another tab marks it again.
        session.watch()
        assertFalse(session.unseen)
        session.unwatch()

        session.run.tracker.onEvent(AgentEvent.UserMessage("check tests too", at = 3_000))
        session.run.tracker.onEvent(AgentEvent.AssistantMessage(listOf(Block.Text("All green.")), stopReason = "end_turn", at = 3_500))
        session.refreshActivity(session.run.tracker, now = 3_500)

        assertEquals(Activity.Idle, session.activity)
        assertTrue(session.unseen)
        assertEquals(3_500L, session.activitySince)
    }

    @Test
    fun `an Antigravity question marks unseen and clearing it returns to working before idle`(@TempDir tmp: Path) {
        val session = AgentSessions().also { opened += it }
            .open(tmp.toFile(), Account("agy-alpha", Provider.Antigravity, "/homes/agy-alpha"))
        session.run.session.running = true

        session.event(AgentEvent.UserMessage("update the schema", at = 1_000), at = 1_000)
        assertEquals(Activity.Working, session.activity)
        assertFalse(session.unseen)

        session.event(AgentEvent.ToolStarted("q1", "ask_question", emptyMap(), at = 2_000), at = 2_000)
        assertEquals(Activity.Asking, session.activity)
        assertTrue(session.unseen)

        session.watch()
        assertFalse(session.unseen)
        session.event(AgentEvent.ToolFinished("q1", at = 3_000), at = 3_000)
        assertEquals(Activity.Working, session.activity)
        session.unwatch()

        session.event(AgentEvent.AssistantMessage(listOf(Block.Text("Updated.")), stopReason = "end_turn", at = 4_000), at = 4_000)
        assertEquals(Activity.Idle, session.activity)
        assertTrue(session.unseen)
    }

    @Test
    fun `reopening a past Antigravity session ignores replayed history and tracks subsequent live turns`(@TempDir tmp: Path) {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")))
        val home = tmp.resolve("home")
        val lock = Antigravity.presenceDir(home).resolve("conv-old.lock")
        Files.createDirectories(lock.parent)
        Files.createFile(lock)
        val transcript = Antigravity.conversationTranscript(home, "conv-old")
        Files.createDirectories(transcript.parent)
        Files.writeString(
            transcript,
            """{"step_index":0,"type":"USER_INPUT","status":"DONE","created_at":"2020-01-01T00:00:00Z","content":"<USER_REQUEST>\nold task\n</USER_REQUEST>"}""" + "\n" +
                """{"step_index":1,"type":"PLANNER_RESPONSE","status":"DONE","created_at":"2020-01-01T00:00:05Z","content":"Old reply."}""" + "\n",
        )
        val process = com.pty4j.PtyProcessBuilder(arrayOf("sh", "-c", "exec 9<'$lock'; echo ready; exec sleep 30"))
            .setEnvironment(System.getenv()).start()
        try {
            process.inputStream.bufferedReader().readLine()
            val session = AgentSessions().also { opened += it }
                .open(tmp.toFile(), Account("agy-alpha", Provider.Antigravity, home.toString()), resumeId = "conv-old")
            session.run.session.javaClass.getDeclaredField("process").apply { isAccessible = true }.set(session.run.session, process)
            session.run.session.running = true

            fun waitUntil(message: String, predicate: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline && !predicate()) Thread.sleep(50)
                javax.swing.SwingUtilities.invokeAndWait { }
                assertTrue(predicate(), message)
            }

            waitUntil("history should still be logged") {
                session.log.events().any { it is AgentEvent.AssistantMessage }
            }
            assertEquals(Activity.Running, session.activity)
            assertFalse(session.hasWorked)
            assertFalse(session.unseen)

            // Live turn: prompt -> question -> answer -> final reply, all behind another tab.
            fun append(line: String) = Files.writeString(transcript, line + "\n", java.nio.file.StandardOpenOption.APPEND)
            val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            append("""{"step_index":2,"type":"USER_INPUT","status":"DONE","created_at":"$now","content":"<USER_REQUEST>\ncheck status\n</USER_REQUEST>"}""")
            waitUntil("live prompt starts working") { session.activity == Activity.Working }
            assertTrue(session.hasWorked)
            assertFalse(session.unseen)

            append("""{"step_index":3,"type":"PLANNER_RESPONSE","status":"DONE","created_at":"${now.plusSeconds(1)}","tool_calls":[{"name":"ask_question","args":{"questions":"[]"}}]}""")
            waitUntil("ask_question marks tab asking") { session.activity == Activity.Asking }
            assertTrue(session.unseen)

            append("""{"step_index":4,"type":"GENERIC","status":"DONE","created_at":"${now.plusSeconds(2)}","content":"A1: yes"}""")
            waitUntil("answering returns to working") { session.activity == Activity.Working }
            assertFalse(session.unseen)

            append("""{"step_index":5,"type":"PLANNER_RESPONSE","status":"DONE","created_at":"${now.plusSeconds(3)}","content":"Finished."}""")
            waitUntil("final reply marks tab idle with unread response") { session.activity == Activity.Idle }
            assertTrue(session.unseen)

            session.watch()
            assertFalse(session.unseen)
            session.unwatch()
        } finally {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
