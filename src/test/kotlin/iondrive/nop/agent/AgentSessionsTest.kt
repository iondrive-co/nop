package iondrive.nop.agent

import iondrive.nop.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The collection behind the agent tabs, and the naming that makes a strip of them readable.
 *
 * No process is started here: a session spawns nothing until the panel asks it for a widget, so
 * constructing one is cheap and touches only its event log.
 */
class AgentSessionsTest {
    private val opened = mutableListOf<AgentSessions>()

    @AfterEach
    fun cleanUp() {
        opened.forEach { state ->
            state.sessions.forEach { session ->
                runCatching { Files.deleteIfExists(session.log.file) }
                // A handover writes its summary beside the log, under the session's own directory.
                runCatching {
                    val dir = Accounts.dataRoot().resolve("handoffs").resolve(session.sessionId)
                    Files.list(dir).use { it.forEach { file -> Files.deleteIfExists(file) } }
                    Files.deleteIfExists(dir)
                }
            }
            state.disposeAll()
        }
    }

    private fun sessions(): AgentSessions = AgentSessions().also { opened += it }

    private fun account(name: String, provider: Provider = Provider.Anthropic) =
        Account(name, provider, "/homes/$name")

    @Test
    fun `opening a session shows it, and the picker is how you get back`(@TempDir tmp: Path) {
        val state = sessions()

        val session = state.open(tmp.toFile(), account("claude-main"))

        assertEquals(session.sessionId, state.selectedId)
        state.showPicker()
        assertNull(state.selected, "nothing selected is the picker, not an empty panel")
        assertTrue(session in state.sessions, "showing the picker must not close what is running")
    }

    /**
     * The same reason every terminal is called "Term": which account is running answers a question
     * the settings dialog and the picker already answer, and it is not the one a tab label is for.
     */
    @Test
    fun `every tab starts under the same name, the way the terminals do`(@TempDir tmp: Path) {
        val claude = sessions().open(tmp.toFile(), account("claude-main"))
        val codex = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))

        assertEquals("Agent", claude.title)
        assertEquals("Agent", codex.title)
    }

    @Test
    fun `switching provider does not overwrite a name already earned`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))
        session.titleFromTranscript("AWS support")

        session.switchTo(account("codex", Provider.OpenAI))

        assertEquals("AWS support", session.title, "a handoff continues the work, so it keeps its name")
    }

    @Test
    fun `renaming a tab sticks`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        state.rename(session.sessionId, "  parser rewrite  ")

        assertEquals("parser rewrite", session.title, "the name should be trimmed, not padded")
    }

    @Test
    fun `a blank rename is a cancel, not an unlabelled tab`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        state.rename(session.sessionId, "   ")

        assertEquals("Agent", session.title)
    }

    @Test
    fun `moving a session reorders the sessions list and preserves selection`(@TempDir tmp: Path) {
        val state = sessions()
        val a = state.open(tmp.toFile(), account("claude-1"))
        val b = state.open(tmp.toFile(), account("claude-2"))
        val c = state.open(tmp.toFile(), account("claude-3"))
        state.select(b.sessionId)

        state.move(0, 2)
        assertEquals(listOf(b, c, a), state.sessions)
        assertEquals(b.sessionId, state.selectedId)

        state.move(2, 0)
        assertEquals(listOf(a, b, c), state.sessions)
        assertEquals(b.sessionId, state.selectedId)

        // Stale or out-of-bounds indices are no-ops
        state.move(-1, 1)
        assertEquals(listOf(a, b, c), state.sessions)

        state.move(0, 10)
        assertEquals(listOf(a, b, c), state.sessions)

        state.move(1, 1)
        assertEquals(listOf(a, b, c), state.sessions)
    }

    @Test
    fun `the title the CLI gives the session replaces the default`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))

        session.titleFromTranscript("AWS support")

        assertEquals("AWS support", session.title, "the work is a better label than a placeholder")
    }

    /**
     * A name the user typed being quietly replaced a minute later, when the CLI decides what the
     * session is about, is worse than no rename at all.
     */
    @Test
    fun `a name the user chose outlives the CLI's own`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        state.rename(session.sessionId, "parser rewrite")
        session.titleFromTranscript("AWS support")

        assertEquals("parser rewrite", session.title)
    }

    /**
     * Quitting nop kills every session it is running — that is what [AgentSessions.disposeAll] is
     * for, because a vendor CLI is a real process and leaving one per tab behind is worse. But the
     * state file is written from the same sessions, and a row that reads its own killing as "the
     * user is finished with this" takes the whole strip out with it on the way down. Which is the
     * difference between a restart that comes back where you left off and one that comes back
     * empty.
     */
    @Test
    fun `a session nop killed on its own way out still comes back`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))
        state.rename(session.sessionId, "plan 40")

        session.dispose()

        val row = session.asOpenAgent()
        assertNotNull(row, "nop ending the session is not the user ending it")
        assertEquals("plan 40", row?.title)
        assertEquals(session.sessionId, row?.sessionId, "the tab keeps the log it already has")
    }

    @Test
    fun `renaming a session that isn't there is not an error`(@TempDir tmp: Path) {
        sessions().rename("no-such-session", "whatever")
    }

    @Test
    fun `closing a session drops it and lands on the tab beside it`(@TempDir tmp: Path) {
        val state = sessions()
        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("codex", Provider.OpenAI))

        state.close(second.sessionId)

        assertEquals(listOf(first.sessionId), state.sessions.map { it.sessionId })
        assertEquals(
            first.sessionId,
            state.selectedId,
            "closing a tab is not a request for an empty one — the strip keeps what is left",
        )
    }

    @Test
    fun `each session gets its own id and its own log`(@TempDir tmp: Path) {
        val state = sessions()

        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("claude-main"))

        assertTrue(first.sessionId != second.sessionId)
        assertTrue(first.log.file != second.log.file)
    }

    /**
     * The point of nominating an account: the work carries on without anybody being at the keyboard
     * to press the button. The wall itself is a phrase in a vendor's terminal output, which no test
     * can arrange — [QuotaWatcher] has its own tests for the parsing that leads here, and this
     * drives what is decided once it has fired.
     */
    @Test
    fun `an account that runs out hands its work to the one it nominated`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        val session = state.open(tmp.toFile(), claude)

        session.onQuotaWall(QuotaHit("usage limit", "you have hit your usage limit"))

        assertEquals("codex", session.account.name)
        assertFalse(
            session.ended,
            "the tab carries on in the new account rather than stopping at the post-exit choices",
        )
        assertEquals("claude-main", session.autoHandover?.from, "the bar has to be able to own up to it")
        assertEquals("codex", session.autoHandover?.to)
    }

    @Test
    fun `with nobody nominated, running out leaves the choice where it has always been`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.onQuotaWall(QuotaHit("usage limit", "you have hit your usage limit"))

        assertTrue(session.ended, "the post-exit panel is what asks the user which account instead")
        assertEquals(EndReason.Quota, session.run.endReason)
        assertNull(session.autoHandover, "nothing happened by itself, so there is nothing to own up to")
    }

    /**
     * One of each provider, each nominating the other, is the arrangement this feature is for — and
     * the one that would ring. Without a memory of who has already been refused, the pair would trade
     * a dead session back and forth for as long as nop was open.
     */
    @Test
    fun `an account already refused once is not handed the same work again`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex", handoverTo = "claude-main")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        val session = state.open(tmp.toFile(), claude)

        session.onQuotaWall(QuotaHit("usage limit", "claude-main is out"))
        assertEquals("codex", session.account.name)

        session.onQuotaWall(QuotaHit("usage limit", "codex is out"))

        assertEquals("codex", session.account.name, "claude-main ran out already; it is not asked twice")
        assertTrue(session.ended, "with everybody spent, the run ends and the user is asked")
    }

    /** A wall reported for a run that is already over is news about nothing. */
    @Test
    fun `a wall hit after the run has ended changes nothing`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        val session = state.open(tmp.toFile(), claude)
        session.endRun(EndReason.Exited)

        session.onQuotaWall(QuotaHit("usage limit", "you have hit your usage limit"))

        assertEquals("claude-main", session.account.name, "the user quit; nop does not restart them elsewhere")
        assertNull(session.autoHandover)
    }

    /**
     * The bug this guard exists for, and it is worth spelling out because the symptom was nothing
     * like the cause.
     *
     * A session editing nop's own quota code printed the phrases those tests are built from, the
     * watcher read its own fixtures off the screen, and the run was killed mid-turn. Resuming made
     * it worse rather than better: the text is in the conversation, so every resume replayed it and
     * was killed again within seconds, and the session became one nop could not get back into at
     * all — the user had to copy the resume command out to a terminal. Output is not evidence about
     * an account; the provider's own number is, and it gets to say no.
     */
    @Test
    fun `a limit phrase the agent merely printed does not kill a session with quota left`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { false }
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.onQuotaWall(QuotaHit("usage limit", """fire("You've hit your usage limit")"""))

        assertFalse(session.ended, "the account had quota; the phrase was something it was showing")
        assertNull(session.run.quota)
        assertNull(session.run.endReason)
    }

    @Test
    fun `a limit phrase is still acted on when the account really has run out`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { true }
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your usage limit"))

        assertTrue(session.ended)
        assertEquals(EndReason.Quota, session.run.endReason)
    }

    /**
     * Not knowing must behave exactly as it did before there was anything to know — a Codex reading
     * is days old and a Claude one may not have arrived yet, and neither is a reason to sit on a
     * wall the CLI has plainly hit.
     */
    @Test
    fun `with no usable reading the wall is believed, as it always was`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { null }
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your usage limit"))

        assertTrue(session.ended)
        assertEquals(EndReason.Quota, session.run.endReason)
    }

    /** An ignored phrase must not leave the watcher spent: the run has hours left to go wrong in. */
    @Test
    fun `ignoring a phrase leaves the watcher armed for the rest of the run`(@TempDir tmp: Path) {
        val state = sessions()
        var spent = false
        state.hasRunOut = { spent }
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.onQuotaWall(QuotaHit("usage limit", "a diff the agent was reading"))
        assertFalse(session.ended)

        spent = true
        session.onQuotaWall(QuotaHit("usage limit", "You've hit your usage limit"))

        assertTrue(session.ended, "the same run must still be able to hit a real wall afterwards")
    }

    /** A nomination is no reason to act on a phrase the account's own numbers contradict. */
    @Test
    fun `a session with quota left is not handed over on a phrase either`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        state.hasRunOut = { false }
        val session = state.open(tmp.toFile(), claude)

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your usage limit"))

        assertEquals("claude-main", session.account.name, "nothing ran out, so nothing changes hands")
        assertNull(session.autoHandover)
    }

    /**
     * The same protection without asking the vendor anything, which is what Codex — and whatever is
     * added after it — needs. Only one provider offers a usage reading worth contradicting a screen
     * with; every provider nop can run writes a transcript.
     */
    @Test
    fun `a phrase echoed from the session's own transcript does not kill it, whatever the provider`(
        @TempDir tmp: Path,
    ) {
        val state = sessions()
        // No usage reading at all — this is the guard that has to stand on its own.
        state.hasRunOut = { null }
        val session = state.open(tmp.toFile(), account("codex", Provider.OpenAI))
        session.run.transcriptPath = tmp.resolve("rollout.jsonl").also {
            Files.writeString(it, """{"text":"wrote fire(\"You've hit your usage limit\") to the test"}""")
        }

        session.onQuotaWall(
            QuotaHit("usage limit", "173 + fire(...)", matched = "You've hit your usage limit"),
        )

        assertFalse(session.ended, "the phrase is in the conversation, so the agent put it on screen")
        assertNull(session.run.quota)
    }

    @Test
    fun `a phrase the conversation never mentions is the vendor's, and still ends the run`(
        @TempDir tmp: Path,
    ) {
        val state = sessions()
        state.hasRunOut = { null }
        val session = state.open(tmp.toFile(), account("codex", Provider.OpenAI))
        session.run.transcriptPath = tmp.resolve("rollout.jsonl").also {
            Files.writeString(it, """{"text":"refactor the stash path"}""")
        }

        session.onQuotaWall(
            QuotaHit("usage limit", "You've hit your usage limit", matched = "You've hit your usage limit"),
        )

        assertTrue(session.ended)
        assertEquals(EndReason.Quota, session.run.endReason)
    }

    /**
     * The whole reason the session became unreachable: resuming replays the conversation, so the text
     * that triggered the first kill is on screen again within seconds of every restart.
     */
    @Test
    fun `a resumed run is not killed again by the text that killed the first one`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { null }
        val session = state.open(tmp.toFile(), account("claude-main"))
        val replayed = QuotaHit(
            "usage limit",
            "173 + fire(...)",
            matched = "You've hit your usage limit",
        )
        val transcript = tmp.resolve("session.jsonl").also {
            Files.writeString(it, """{"text":"fire(\"You've hit your usage limit\")"}""")
        }
        session.run.transcriptPath = transcript

        repeat(3) {
            session.onQuotaWall(replayed)
            assertFalse(session.ended, "resume must not walk back into the same kill")
            session.reopen(resumeId = "native-1")
            session.run.transcriptPath = transcript
        }

        assertFalse(session.ended)
    }

    @Test
    fun `resuming a session on a spent account does not hand over until a prompt is submitted`(@TempDir tmp: Path) {
        val aloancloud = Account("claude-aloancloud", Provider.Anthropic, "/homes/claude-aloancloud", handoverTo = "claude-iondrive")
        val iondrive = Account("claude-iondrive", Provider.Anthropic, "/homes/claude-iondrive")
        val state = sessions()
        state.handoverTarget = { from -> listOf(aloancloud, iondrive).handoverTarget(from) }
        state.hasRunOut = { it == aloancloud }

        val session = state.open(tmp.toFile(), aloancloud, resumeId = "native-1")
        assertTrue(session.run.isResume)
        assertFalse(session.run.userPromptSubmitted)

        val replayed = QuotaHit("usage limit", "You've hit your usage limit")
        session.onQuotaWall(replayed)

        assertFalse(session.ended, "merely viewing a resumed session must not end it")
        assertNull(session.autoHandover, "merely viewing a resumed session must not trigger auto-handover")
        assertEquals(aloancloud.name, session.account.name, "session must remain on original account while inspecting")

        // Now user enters a question
        session.run.userPromptSubmitted = true
        session.onQuotaWall(replayed)

        assertEquals(iondrive.name, session.account.name, "work must have handed over to iondrive after prompt")
    }

    /**
     * The 19:48 wall. A session nop has handed over reads a handoff quoting the wall that ended the
     * last run, so the phrase is in its conversation from its first turn — and its own wall, when it
     * came, was vetoed as an echo of that one. The refusal the CLI filed for it says otherwise.
     */
    @Test
    fun `a handed-over session hands over again at its own wall`(@TempDir tmp: Path) {
        val iondrive = Account(
            "claude-iondrive",
            Provider.Anthropic,
            "/homes/claude-iondrive",
            handoverTo = "claude-aloancloud",
        )
        val aloancloud = Account("claude-aloancloud", Provider.Anthropic, "/homes/claude-aloancloud")
        val state = sessions()
        state.handoverTarget = { from -> listOf(iondrive, aloancloud).handoverTarget(from) }
        state.hasRunOut = { null }
        val session = state.open(tmp.toFile(), iondrive)
        val wall = "You've hit your session limit"
        session.run.transcriptPath = tmp.resolve("session.jsonl").also {
            Files.writeString(
                it,
                listOf(
                    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1",""" +
                        """"content":"The previous agent's last message was: $wall · resets 8:10pm"}]}}""",
                    """{"type":"assistant","isApiErrorMessage":true,"timestamp":"${session.run.startedAt}",""" +
                        """"message":{"content":[{"type":"text","text":"$wall · resets 11pm"}]}}""",
                ).joinToString("\n"),
            )
        }

        session.onQuotaWall(QuotaHit("usage limit", "$wall · resets 11pm", matched = wall))

        assertEquals("claude-aloancloud", session.account.name)
        assertEquals("claude-iondrive", session.autoHandover?.from)
    }

    /**
     * When the account's own number says it is spent, the screen and the provider agree, and the
     * conversation quoting a wall is no reason to sit at one — a user pasting a wall to ask about
     * it puts the phrase there as surely as a handoff does.
     */
    @Test
    fun `a spent reading is not argued with by the conversation`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { true }
        val session = state.open(tmp.toFile(), account("claude-main"))
        val wall = "You've hit your session limit"
        session.run.transcriptPath = tmp.resolve("session.jsonl").also {
            Files.writeString(it, """{"type":"user","message":{"content":"pasted: $wall · resets 8:10pm"}}""")
        }

        session.onQuotaWall(QuotaHit("usage limit", wall, matched = wall))

        assertTrue(session.ended)
        assertEquals(EndReason.Quota, session.run.endReason)
    }

    /**
     * A resume redraws the wall that ended the last run. With no reading to say whether the account
     * has reset since, that is history on a screen, and the run is left to hit a wall of its own —
     * which, if the account is still out, it does the moment it is asked for anything.
     */
    @Test
    fun `a resume is not ended by redrawing the wall that ended the last run`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { null }
        val session = state.open(tmp.toFile(), account("claude-main"))
        val wall = "You've hit your session limit"
        val lastRun = session.run.startedAt.minusSeconds(3600)
        session.run.transcriptPath = tmp.resolve("session.jsonl").also {
            Files.writeString(
                it,
                """{"type":"assistant","isApiErrorMessage":true,"timestamp":"$lastRun",""" +
                    """"message":{"content":[{"type":"text","text":"$wall · resets 8:10pm"}]}}""",
            )
        }

        session.onQuotaWall(QuotaHit("usage limit", "$wall · resets 8:10pm", matched = wall))

        assertFalse(session.ended)
        assertNull(session.run.quota)
    }

    /**
     * The 18:59 handover in hermes, as it happened: an account reading 99%, a session that had just
     * finished answering a question about an exchange's 429 "too many requests", and a nominated
     * account ready to take over. The reading makes a wall believable; the agent's own reply, twelve
     * seconds old, says this was not one.
     */
    @Test
    fun `a session that has just talked about a rate limit is not handed over, spent or not`(
        @TempDir tmp: Path,
    ) {
        val aloancloud = Account(
            "claude-aloancloud",
            Provider.Anthropic,
            "/homes/claude-aloancloud",
            handoverTo = "claude-iondrive",
        )
        val iondrive = Account("claude-iondrive", Provider.Anthropic, "/homes/claude-iondrive")
        val state = sessions()
        state.handoverTarget = { from -> listOf(aloancloud, iondrive).handoverTarget(from) }
        state.hasRunOut = { true }
        val session = state.open(tmp.toFile(), aloancloud)
        // Written during this run, as the reply was: that run had been going for eighteen minutes.
        val said = java.time.Instant.now()
        session.run.transcriptPath = tmp.resolve("session.jsonl").also {
            Files.writeString(
                it,
                """{"type":"assistant","timestamp":"$said","message":{"content":[{"type":"text",""" +
                    """"text":"02:10Z: Hyperliquid refused a request (a 429 \"too many requests\" reply)."}]}}""",
            )
        }

        session.onQuotaWall(
            QuotaHit("rate limit", "429 \"too many requests\" reply", matched = "too many requests"),
        )

        assertEquals("claude-aloancloud", session.account.name)
        assertFalse(session.ended)
        assertNull(session.autoHandover)
    }

    /**
     * The same afternoon's other half: the window reset thirty seconds after the handover. A wall
     * that is about to lift is waited out — neither handed over nor ended.
     */
    @Test
    fun `a wall that lifts in a few minutes is waited out rather than handed over`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        state.hasRunOut = { true }
        state.spentUntil = { java.time.Instant.now().plusSeconds(30) }
        val session = state.open(tmp.toFile(), claude)

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your session limit"))

        assertEquals("claude-main", session.account.name)
        assertFalse(session.ended, "a wall that lifts by itself is not a reason to end the run either")
        assertNull(session.autoHandover)
    }

    @Test
    fun `a wall hours from lifting is still handed over`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        state.hasRunOut = { true }
        state.spentUntil = { java.time.Instant.now().plus(AgentSession.WAIT_FOR_RESET).plusSeconds(60) }
        val session = state.open(tmp.toFile(), claude)

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your session limit"))

        assertEquals("codex", session.account.name)
    }

    /**
     * The 14:11 handover in hermes on 2026-09-24. nop waited out the 14:10 reset; Claude Code carried
     * on at 14:11:07, and its redraw put the same wall back in front of the re-armed watcher while the
     * usage reading still read spent. Five tabs went to Codex seconds after they had started working.
     */
    @Test
    fun `a wall already waited out is not handed over when the CLI redraws it`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        state.hasRunOut = { true }
        var resetsAt = java.time.Instant.now().plusSeconds(30)
        state.spentUntil = { resetsAt }
        val session = state.open(tmp.toFile(), claude)
        val wall = "You've hit your session limit"
        val filedAt = session.run.startedAt
        val transcript = tmp.resolve("session.jsonl").also {
            Files.writeString(
                it,
                """{"type":"assistant","isApiErrorMessage":true,"timestamp":"$filedAt",""" +
                    """"message":{"content":[{"type":"text","text":"$wall · resets 2:10pm"}]}}""" + "\n",
            )
        }
        session.run.transcriptPath = transcript
        val hit = QuotaHit("usage limit", "$wall · resets 2:10pm", matched = wall)

        session.onQuotaWall(hit, now = filedAt.plusSeconds(3))
        assertEquals("claude-main", session.account.name, "a wall that lifts in 30s is waited out")

        // Past the reset, and the reading has not caught up: still spent, and the next window hours away.
        resetsAt = java.time.Instant.now().plus(Duration.ofHours(5))
        session.onQuotaWall(hit, now = filedAt.plusSeconds(90))

        assertEquals("claude-main", session.account.name, "the wall on screen is the one already waited out")
        assertFalse(session.ended)
        assertNull(session.autoHandover)
    }

    /** The watcher re-arms after a wait so that a wall still to come is caught; this is one. */
    @Test
    fun `a new wall after one waited out is still acted on`(@TempDir tmp: Path) {
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        state.handoverTarget = { from -> listOf(claude, codex).handoverTarget(from) }
        state.hasRunOut = { true }
        var resetsAt = java.time.Instant.now().plusSeconds(30)
        state.spentUntil = { resetsAt }
        val session = state.open(tmp.toFile(), claude)
        val wall = "You've hit your session limit"
        val filedAt = session.run.startedAt
        fun refusal(at: java.time.Instant) =
            """{"type":"assistant","isApiErrorMessage":true,"timestamp":"$at",""" +
                """"message":{"content":[{"type":"text","text":"$wall · resets 2:10pm"}]}}""" + "\n"
        val transcript = tmp.resolve("session.jsonl").also { Files.writeString(it, refusal(filedAt)) }
        session.run.transcriptPath = transcript
        val hit = QuotaHit("usage limit", "$wall · resets 2:10pm", matched = wall)

        session.onQuotaWall(hit, now = filedAt.plusSeconds(3))
        assertEquals("claude-main", session.account.name)

        resetsAt = java.time.Instant.now().plus(Duration.ofHours(5))
        Files.writeString(transcript, refusal(filedAt.plusSeconds(10)), java.nio.file.StandardOpenOption.APPEND)
        session.onQuotaWall(hit, now = filedAt.plusSeconds(12))

        assertEquals("codex", session.account.name, "the CLI filed a refusal after the wait, so this wall is new")
    }

    /**
     * The other half of the 14:11 handover. The first tab to hand over let go of its Claude session
     * as its run ended, and two sibling tabs on the same project, still running, adopted it as a
     * `/clear` of their own. Their handoffs were built from its conversation, so Codex did its work
     * twice.
     */
    @Test
    fun `a session stays its tab's after the run following it ends`(@TempDir tmp: Path) {
        val state = sessions()
        val first = state.open(tmp.toFile(), account("claude-main"))
        val sibling = state.open(tmp.toFile(), account("claude-main"))
        val id = first.run.nativeSessionId!!

        first.endRun(EndReason.Quota)

        assertTrue(
            iondrive.nop.agent.transcript.LiveTranscripts.isForeign(id, sibling.sessionId),
            "a sibling tab must not adopt a session because the tab that ran it has moved on",
        )
        assertFalse(iondrive.nop.agent.transcript.LiveTranscripts.isForeign(id, first.sessionId))

        // Reopening it from another tab is a deliberate claim, and moves it.
        sibling.reopen(resumeId = id)
        assertFalse(iondrive.nop.agent.transcript.LiveTranscripts.isForeign(id, sibling.sessionId))
        assertTrue(iondrive.nop.agent.transcript.LiveTranscripts.isForeign(id, first.sessionId))
    }

    @Test
    fun `changing handover nomination mid-session to ask me prevents auto-handover`(@TempDir tmp: Path) {
        val claudeStarted = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/homes/codex")
        val state = sessions()
        // Nomination cleared mid-session in the active accounts list:
        val claudeUpdated = Account("claude-main", Provider.Anthropic, "/homes/claude-main", handoverTo = null)
        state.handoverTarget = { from -> listOf(claudeUpdated, codex).handoverTarget(from) }
        state.hasRunOut = { true }
        state.spentUntil = { java.time.Instant.now().plus(AgentSession.WAIT_FOR_RESET).plusSeconds(60) }
        val session = state.open(tmp.toFile(), claudeStarted)

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your session limit"))

        assertNull(session.autoHandover, "clearing nomination mid-session should prevent auto-handover")
        assertEquals("claude-main", session.account.name)
    }

    /**
     * The `agy` wall in hermes on 2026-09-20, which nop watched happen twice and did nothing about.
     *
     * "Individual quota reached ... Resets in 7m31s" is a limit `agy`'s own `/usage` never mentions:
     * both of the windows it does report had hours left, so the account's reading said there was
     * quota and was telling the truth about something else. A vendor that has just said when it
     * will serve again outranks a number that is not about the allowance it refused against.
     */
    @Test
    fun `a wall the vendor timed itself is acted on though the reading has room`(@TempDir tmp: Path) {
        val google = Account(
            "google-aloancloud",
            Provider.Antigravity,
            "/homes/google-aloancloud",
            handoverTo = "codex-iondrive",
        )
        val codex = Account("codex-iondrive", Provider.OpenAI, "/homes/codex-iondrive")
        val state = sessions()
        state.handoverTarget = { from -> listOf(google, codex).handoverTarget(from) }
        state.hasRunOut = { false }
        val session = state.open(tmp.toFile(), google)

        session.onQuotaWall(
            QuotaHit(
                "usage limit",
                "⚠ Individual quota reached. Please upgrade your subscription to increase your " +
                    "limits. Resets in 7m31s.",
                matched = "quota reached",
                resetsIn = Duration.ofMinutes(7).plusSeconds(31),
            ),
        )

        assertEquals("codex-iondrive", session.account.name)
        assertEquals("google-aloancloud", session.autoHandover?.from)
    }

    /**
     * The other half of that: the reading only stands aside for a wall that timed itself. A phrase
     * with no countdown on it, on an account with quota, is the false positive this all exists to
     * stop — see the test above about a session editing nop's own quota code.
     */
    @Test
    fun `a phrase with no countdown is still nothing on an account with quota`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { false }
        val session = state.open(tmp.toFile(), account("google-aloancloud", Provider.Antigravity))

        session.onQuotaWall(QuotaHit("usage limit", """the log said "quota reached" at 17:03"""))

        assertFalse(session.ended)
        assertNull(session.run.quota)
    }

    @Test
    fun `a fresh refusal hands over even when poller says the account still has quota`(@TempDir tmp: Path) {
        val codex = Account("codex-iondrive", Provider.OpenAI, "/homes/codex-iondrive", handoverTo = "claude-main")
        val claude = Account("claude-main", Provider.Anthropic, "/homes/claude-main")
        val state = sessions()
        state.handoverTarget = { from -> listOf(codex, claude).handoverTarget(from) }
        state.hasRunOut = { false } // Poller reading claims account still has quota
        val session = state.open(tmp.toFile(), codex)

        val wall = "You've hit your usage limit"
        val now = java.time.Instant.now()
        session.run.transcriptPath = tmp.resolve("session.jsonl").also {
            Files.writeString(
                it,
                """{"timestamp":"$now","type":"event_msg","payload":{"type":"task_complete","error":{"message":"$wall. Upgrade to Pro... or try again at 4:14 PM.","codex_error_info":"usage_limit_exceeded"}}}""",
            )
        }

        session.onQuotaWall(QuotaHit("usage limit", "$wall. Upgrade to Pro... or try again at 4:14 PM.", matched = wall))

        assertEquals("claude-main", session.account.name, "CLI refusal must override stale poller reading and trigger handover")
    }

    /**
     * `agy` does not sit out a window and carry on the way Claude Code does: at the 2026-09-20 wall
     * it retried for three minutes, filed an executor error and sat idle at its prompt with four
     * minutes left to run. Waiting on that is not patience, it is a tab nobody comes back to.
     */
    @Test
    fun `a CLI that gives up at a wall is handed over rather than waited for`(@TempDir tmp: Path) {
        val google = Account(
            "google-aloancloud",
            Provider.Antigravity,
            "/homes/google-aloancloud",
            handoverTo = "codex-iondrive",
        )
        val codex = Account("codex-iondrive", Provider.OpenAI, "/homes/codex-iondrive")
        val state = sessions()
        state.handoverTarget = { from -> listOf(google, codex).handoverTarget(from) }
        state.hasRunOut = { true }
        state.spentUntil = { java.time.Instant.now().plusSeconds(30) }
        val session = state.open(tmp.toFile(), google)

        session.onQuotaWall(QuotaHit("usage limit", "Individual quota reached", matched = "quota reached"))

        assertEquals("codex-iondrive", session.account.name, "nothing was going to resume that session")
    }

    /**
     * The 16:49 wall in hermes. claude-aloancloud ran out at 13:09 and handed the work to
     * claude-iondrive; three and a half hours later iondrive ran out in turn, and the account that
     * had taken its place was by then rested and reading 0% used — and was skipped anyway, because
     * running out once had struck it off for the rest of the session. The run ended at the post-exit
     * panel with the right answer sitting in it as a button to press.
     */
    @Test
    fun `an account that has rested since its own wall is handed the work again`(@TempDir tmp: Path) {
        val aloancloud = Account(
            "claude-aloancloud",
            Provider.Anthropic,
            "/homes/claude-aloancloud",
            handoverTo = "claude-iondrive",
        )
        val iondrive = Account(
            "claude-iondrive",
            Provider.Anthropic,
            "/homes/claude-iondrive",
            handoverTo = "claude-aloancloud",
        )
        val state = sessions()
        state.handoverTarget = { from -> listOf(aloancloud, iondrive).handoverTarget(from) }
        var rested = false
        state.hasRunOut = { who -> if (who.name == "claude-aloancloud") !rested else true }
        val session = state.open(tmp.toFile(), aloancloud)

        val firstWall = java.time.Instant.now().minus(Duration.ofHours(3)).minusSeconds(40 * 60)
        session.onQuotaWall(QuotaHit("usage limit", "You've hit your session limit"), now = firstWall)
        assertEquals("claude-iondrive", session.account.name)
        rested = true

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your session limit"))

        assertEquals("claude-aloancloud", session.account.name, "its window rolled over hours ago")
        assertFalse(session.ended)
    }

    /**
     * And not straight back. The provider's number is not always about the allowance that refused —
     * an `agy` account walls on one `/usage` never mentions and reads as having room the whole time
     * — so a reading alone would let a pair trade the session as fast as two CLIs can start.
     */
    @Test
    fun `an account is not handed the work back moments after its own wall`(@TempDir tmp: Path) {
        val aloancloud = Account(
            "claude-aloancloud",
            Provider.Anthropic,
            "/homes/claude-aloancloud",
            handoverTo = "claude-iondrive",
        )
        val iondrive = Account(
            "claude-iondrive",
            Provider.Anthropic,
            "/homes/claude-iondrive",
            handoverTo = "claude-aloancloud",
        )
        val state = sessions()
        state.handoverTarget = { from -> listOf(aloancloud, iondrive).handoverTarget(from) }
        // Both read as having room throughout, which is what makes this the dangerous shape.
        state.hasRunOut = { false }
        val session = state.open(tmp.toFile(), aloancloud)
        val timed = QuotaHit(
            "usage limit",
            "Individual quota reached. Resets in 5m7s.",
            matched = "quota reached",
            resetsIn = Duration.ofMinutes(5).plusSeconds(7),
        )

        session.onQuotaWall(timed)
        assertEquals("claude-iondrive", session.account.name)

        session.onQuotaWall(timed)

        assertEquals("claude-iondrive", session.account.name, "aloancloud walled seconds ago")
        assertTrue(session.ended, "with nowhere rested to go, the user is asked")
    }

    /**
     * The CLI that takes over names its conversation after the handoff it was given. The tab was
     * already named after the work, and "Handoff from Claude Code" is what hid it from the user.
     */
    @Test
    fun `a handover keeps the tab's name when the new CLI names the handoff`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-aloancloud"))
        session.titleFromTranscript("LIQFADE rule health")

        session.handOver(account("claude-iondrive"))
        session.titleFromTranscript("Handoff from Claude Code")

        assertEquals("LIQFADE rule health", session.title)
        assertEquals(listOf("LIQFADE rule health"), session.log.events().filterIsInstance<AgentEvent.SessionTitled>().map { it.title })
    }

    @Test
    fun `a handover keeps the default tab name when the new CLI names the handoff`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))
        assertEquals("Agent", session.title)

        session.handOver(account("claude-iondrive"))
        session.titleFromTranscript("Handoff from Codex continuation")

        assertEquals("Agent", session.title)
        assertTrue(session.log.events().filterIsInstance<AgentEvent.SessionTitled>().isEmpty())
    }

    @Test
    fun `a handover keeps a user-chosen tab name when the new CLI names the handoff`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-aloancloud"))
        session.rename("Custom investigation")

        session.handOver(account("claude-iondrive"))
        session.titleFromTranscript("Handoff from Claude Code")

        assertEquals("Custom investigation", session.title)
        assertEquals(listOf("Custom investigation"), session.log.events().filterIsInstance<AgentEvent.SessionTitled>().map { it.title })
    }

    @Test
    fun `a handover starts the new run with the chosen model`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-aloancloud"))
        session.handOver(account("claude-iondrive").copy(model = "claude-haiku-4-5"))

        assertEquals("claude-haiku-4-5", session.run.account.model)
    }

    @Test
    fun `a handover eagerly starts the new terminal run if the previous run was started`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-aloancloud"))
        assertFalse(session.run.session.isStarted, "freshly opened session in test is not started until widget requested")

        // Simulate that the session was focused and started in the UI
        val customBg = java.awt.Color(10, 20, 30)
        val customFg = java.awt.Color(200, 210, 220)
        val customLink = java.awt.Color(50, 100, 150)
        session.run.session.getOrCreateWidget(customBg, customFg, customLink)
        assertTrue(session.run.session.isStarted)

        // Handover happens (e.g. while the user is focused on another project or tab)
        session.handOver(account("claude-iondrive"))

        // The new session must be started immediately without waiting for focus!
        assertTrue(session.run.session.isStarted, "new run after handover must start eagerly so work continues in background")
        assertEquals(customBg, session.run.session.themeColors?.first, "new run should inherit previous run's theme colors")
    }

    @Test
    fun `a handover of an unstarted run does not eagerly start the terminal`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-aloancloud"))
        assertFalse(session.run.session.isStarted)

        session.handOver(account("claude-iondrive"))
        assertFalse(session.run.session.isStarted, "an unstarted run should remain unstarted across handover in tests")
    }

    /**
     * The picker's tab closes the way a terminal's does: it goes out of the strip. There is no
     * process behind it to kill — closing the last terminal leaves the strip with only its "+", and
     * this is the same gesture with the same result.
     */
    @Test
    fun `closing the picker tab takes it out of the strip, and the + puts one back`(@TempDir tmp: Path) {
        val state = sessions()
        assertTrue(state.pickerTabVisible)

        state.hidePickerTab()
        assertFalse(state.pickerTabVisible)

        state.showPicker()
        assertTrue(state.pickerTabVisible, "the + has to be able to bring it back")
    }

    /**
     * The picker is a tab with no session in it yet, so the session it starts belongs in that tab.
     * Leaving it there as well put two tabs in the strip for one press — the picker on one side of
     * the new session and the "+" that opened it on the other.
     */
    @Test
    fun `starting a session uses up the picker's tab rather than adding one beside it`(@TempDir tmp: Path) {
        val state = sessions()
        assertTrue(state.pickerTabVisible)

        val session = state.open(tmp.toFile(), account("claude-main"))

        assertFalse(state.pickerTabVisible, "the picker's tab is the session's tab now")
        assertEquals(session.sessionId, state.selectedId)
    }

    /** While sessions are running, the selector tab is not added to the strip; the picker is accessed via + */
    @Test
    fun `the + opens the picker without adding a selector tab when sessions are running`(@TempDir tmp: Path) {
        val state = sessions()
        state.open(tmp.toFile(), account("claude-main"))

        state.showPicker()
        state.showPicker()

        assertFalse(state.pickerTabVisible, "the selector tab only shows if there are no sessions running")
        assertNull(state.selectedId, "the picker is what is on screen after the +")
    }

    /**
     * The selector tab for the sessions only shows if there are no sessions running.
     * When the last session is closed, the selector tab returns to the strip.
     */
    @Test
    fun `closing the last session restores the selector tab`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))
        assertFalse(state.pickerTabVisible)

        state.close(session.sessionId)

        assertTrue(state.pickerTabVisible, "the selector tab shows when no sessions are running")
        assertNull(state.selectedId, "with nothing left to show, the pane holds the picker")
    }

    /** With something left, closing the tab on screen lands on its neighbour, as a terminal's does. */
    @Test
    fun `closing the session on screen falls to a neighbour`(@TempDir tmp: Path) {
        val state = sessions()
        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("codex", Provider.OpenAI))
        state.select(second.sessionId)

        state.close(second.sessionId)

        assertEquals(first.sessionId, state.selectedId)
        assertFalse(state.pickerTabVisible)
    }

    /**
     * Typing `/exit` is the user saying the work in that tab is over. Leaving the dead TUI in the
     * strip made them say it a second time, to nop.
     */
    @Test
    fun `quitting the CLI closes the tab`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.run.session.onExit?.invoke(0)
        javax.swing.SwingUtilities.invokeAndWait { }

        assertTrue(state.sessions.isEmpty(), "the CLI exited cleanly, so the tab has done its job")
        assertTrue(state.pickerTabVisible, "with no sessions running, the selector tab shows")
    }

    @Test
    fun `restoring sessions hides the selector tab`(@TempDir tmp: Path) {
        val state = sessions()
        assertTrue(state.pickerTabVisible)

        val row = Settings.OpenAgent("s-1", Provider.Anthropic.id, "claude-main", "conv-1", "Title", titleIsUsers = false)
        val accounts = listOf(Account("claude-main", Provider.Anthropic, tmp.toString()))
        state.restore(listOf(row), tmp.toFile(), accounts)

        assertEquals(1, state.sessions.size)
        assertFalse(state.pickerTabVisible, "selector tab should hide when sessions are restored")
    }

    /**
     * A CLI that fell over is the one case where the dead frame is worth keeping: it is where the
     * reason is, and the post-exit choices are how the run is picked back up.
     */
    @Test
    fun `a CLI that exits badly keeps its tab`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.run.session.onExit?.invoke(1)
        javax.swing.SwingUtilities.invokeAndWait { }

        assertEquals(listOf(session), state.sessions)
        assertTrue(session.ended, "the run is over either way")
    }

    /**
     * And a wall nobody was nominated for ends the run by killing the CLI, which exits like any
     * other dying process. The choice of where the work goes next is the whole point of the tab
     * staying, so that exit must not take it away.
     */
    @Test
    fun `a session ended at a quota wall keeps its tab`(@TempDir tmp: Path) {
        val state = sessions()
        state.hasRunOut = { true }
        val session = state.open(tmp.toFile(), account("claude-main"))

        session.onQuotaWall(QuotaHit("usage limit", "You've hit your session limit"))
        session.run.session.onExit?.invoke(0)
        javax.swing.SwingUtilities.invokeAndWait { }

        assertEquals(listOf(session), state.sessions)
        assertEquals(EndReason.Quota, session.run.endReason)
    }

    /** Closing a tab the user is not looking at moves nothing: the strip is theirs to arrange. */
    @Test
    fun `closing a session in the background leaves the strip alone`(@TempDir tmp: Path) {
        val state = sessions()
        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("codex", Provider.OpenAI))

        state.close(first.sessionId)

        assertEquals(second.sessionId, state.selectedId)
        assertFalse(state.pickerTabVisible, "an empty tab nobody asked for is still a tab")
    }

    /**
     * nop cannot put a session into the store a bare `claude` reads — the vendor keeps a transcript
     * beside the credentials that wrote it — so it says where it put it instead. One environment
     * variable in front of the ordinary command is the whole difference.
     */
    @Test
    fun `a session can say how to resume it from a shell`(@TempDir tmp: Path) {
        val claude = sessions().open(tmp.toFile(), account("claude-main"))
        val native = claude.run.nativeSessionId
        assertEquals(
            "CLAUDE_CONFIG_DIR=/homes/claude-main claude --resume $native",
            claude.resumeCommand(),
        )

        val codex = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))
        assertNull(
            codex.resumeCommand(),
            "Codex names its own session, so there is nothing to resume until it has",
        )
    }

    @Test
    fun `a session records where it was started`(@TempDir tmp: Path) {
        val project: File = tmp.toFile()
        val session = sessions().open(project, account("claude-main"))

        val started = session.log.events().filterIsInstance<AgentEvent.SessionStarted>().single()
        assertEquals(project.absolutePath, started.projectPath)
    }

    // Tabs put back from the state file at the next start. Nothing is spawned here either: a restored
    // session is a session, and a session starts no PTY until a panel asks it for a widget.

    @Test
    fun `restored agents come back in order, named as they were left`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(title = "parser rewrite"), row(sessionId = "nop-2", title = "AWS support")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        assertEquals(listOf("parser rewrite", "AWS support"), state.sessions.map { it.title })
    }

    /** The log the session already had is the one it carries on writing — see AgentSession.sessionId. */
    @Test
    fun `a restored agent keeps nop's own id, so its history stays one session`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(listOf(row(sessionId = "nop-1")), tmp.toFile(), listOf(account("claude-main")))

        assertEquals("nop-1", state.sessions.single().sessionId)
    }

    /** Putting the strip back is one claim; deciding what the user wants to look at is another. */
    @Test
    fun `restoring selects nothing`(@TempDir tmp: Path) {
        val state = sessions()
        state.restore(listOf(row()), tmp.toFile(), listOf(account("claude-main")))
        assertNull(state.selected)
    }

    @Test
    fun `a restored agent resumes the conversation it was in`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(nativeSessionId = "11111111-2222-3333-4444-555555555555")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        val argv = state.sessions.single().run.command.argv
        assertTrue(
            argv.windowed(2).any { it == listOf("--resume", "11111111-2222-3333-4444-555555555555") },
            "expected a --resume in $argv",
        )
    }

    /**
     * This collection outlives the composition that draws it, so the restore has to be once per nop
     * run rather than once per look at the project.
     */
    @Test
    fun `restoring twice does not double the strip`(@TempDir tmp: Path) {
        val state = sessions()
        val rows = listOf(row())

        state.restore(rows, tmp.toFile(), listOf(account("claude-main")))
        state.restore(rows, tmp.toFile(), listOf(account("claude-main")))

        assertEquals(1, state.sessions.size)
    }

    /** The row names which quota to spend. An account that has since been deleted is not nop's to pick. */
    @Test
    fun `a row whose account is gone is skipped rather than run on another`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(account = "deleted"), row(sessionId = "nop-2", title = "kept")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        assertEquals(listOf("kept"), state.sessions.map { it.title })
    }

    /** An account name is not enough on its own: the row has to name the provider it belongs to. */
    @Test
    fun `a row is not restored onto an account of the other provider`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(provider = "openai")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        assertTrue(state.sessions.isEmpty())
    }

    // What gets written down. A session describes itself, or declines to.

    @Test
    fun `a session describes itself for the state file`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))
        state.rename(session.sessionId, "parser rewrite")

        assertEquals(
            Settings.OpenAgent(
                sessionId = session.sessionId,
                provider = "anthropic",
                account = "claude-main",
                nativeSessionId = session.run.nativeSessionId!!,
                title = "parser rewrite",
                titleIsUsers = true,
            ),
            session.asOpenAgent(),
        )
    }

    /** A name the CLI gave the session may be replaced by a better one after the resume. */
    @Test
    fun `a title the CLI chose is written down as the CLI's`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))
        session.titleFromTranscript("AWS support")

        val row = session.asOpenAgent()!!
        assertEquals("AWS support", row.title)
        assertFalse(row.titleIsUsers)
    }

    /**
     * The tab stays in the strip after the run ends so its last frame can be read, but starting nop
     * again is not a reason to start that CLI again — the picker is where a deliberate return lives.
     */
    @Test
    fun `a session the user quit is not written down`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))

        session.endRun(EndReason.Exited)

        assertNull(session.asOpenAgent())
    }

    /** Nothing to resume means nothing to restore: the tab would come back with a blank CLI in it. */
    @Test
    fun `a session with no conversation behind it is not written down`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))

        assertNull(session.run.nativeSessionId, "codex names its own session, a moment after it starts")
        assertNull(session.asOpenAgent())
    }

    private fun row(
        sessionId: String = "nop-1",
        provider: String = "anthropic",
        account: String = "claude-main",
        nativeSessionId: String = "vendor-1",
        title: String = "parser rewrite",
        titleIsUsers: Boolean = false,
    ) = Settings.OpenAgent(sessionId, provider, account, nativeSessionId, title, titleIsUsers)
}
