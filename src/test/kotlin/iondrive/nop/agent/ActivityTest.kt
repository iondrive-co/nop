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
 * The case this is for: a Claude session in hermes asked a question at 21:03 and was answered at
 * 08:48, and for the eleven and three-quarter hours between, its tab looked the same as a working
 * one. The titles here are the ones the CLIs really write, taken from nop's own session logs.
 */
class ActivityTest {
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
        assertEquals("◑ Plan 36", reader.feed(osc("◐ Plan 36") + "x" + osc("◑ Plan 36")))
    }

    @Test
    fun `a title split across two reads is put back together`() {
        val reader = TitleReader()
        assertNull(reader.feed("before $esc]0;◐ Plan"))
        assertEquals("◐ Plan 36 review", reader.feed(" 36 review$bel after"))
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
        assertNull(reader.feed("$esc]8;id=jk1;file:///home/miles/hermes/$esc\\link$esc]8;;$esc\\ $esc]11;?$bel"))
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
        assertEquals(TitleSignal.Says.Working, TitleSignal.read("◓ Plan 36 review and implementation"))
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
        tracker.onTitle("◐ Plan 36", at = 0)
        assertEquals(Activity.Working, tracker.activity(now = 100))
        tracker.onTitle("✳ Plan 36", at = 200)
        assertEquals(Activity.Idle, tracker.activity(now = 200))
    }

    /** The night itself: the question was an open AskUserQuestion call, and nothing else was moving. */
    @Test
    fun `an open question is asking whatever the title says`() {
        val tracker = ActivityTracker()
        tracker.onTitle("◐ Plan 36", at = 0)
        tracker.onEvent(started("toolu_013f", "AskUserQuestion"))
        assertEquals(Activity.Asking, tracker.activity(now = 10))
        tracker.onTitle("✳ Plan 36", at = 20)
        assertEquals(Activity.Asking, tracker.activity(now = 42_333_190))
        tracker.onEvent(finished("toolu_013f"))
        tracker.onTitle("◐ Plan 36", at = 42_333_300)
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
    fun `Codex saying Action Required is asking`() {
        val tracker = ActivityTracker()
        tracker.onTitle("[ ! ] Action Required | ops", at = 0)
        assertEquals(Activity.Asking, tracker.activity(now = 0))
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
}
