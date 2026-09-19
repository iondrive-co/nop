package iondrive.nop.agent

import iondrive.nop.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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

    @Test
    fun `renaming a session that isn't there is not an error`(@TempDir tmp: Path) {
        sessions().rename("no-such-session", "whatever")
    }

    @Test
    fun `closing a session drops it and falls back to the picker`(@TempDir tmp: Path) {
        val state = sessions()
        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("codex", Provider.OpenAI))

        state.close(second.sessionId)

        assertEquals(listOf(first.sessionId), state.sessions.map { it.sessionId })
        assertNull(
            state.selectedId,
            "after closing a session the useful next thing is starting another, which is the picker",
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

    /** Twice over: one press of the "+" is one new tab, however many sessions are already running. */
    @Test
    fun `the + makes one empty tab at a time`(@TempDir tmp: Path) {
        val state = sessions()
        state.open(tmp.toFile(), account("claude-main"))

        state.showPicker()
        state.showPicker()

        assertTrue(state.pickerTabVisible)
        assertNull(state.selectedId, "the empty tab is what is on screen after the +")
    }

    /**
     * The pane falls back to the picker when the session on screen is closed, so the strip has to
     * have a tab for it — a strip with nothing selected beside a panel showing the picker is a
     * strip that disagrees with the panel.
     */
    @Test
    fun `closing the session on screen brings the picker's tab back with it`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))
        assertFalse(state.pickerTabVisible)

        state.close(session.sessionId)

        assertTrue(state.pickerTabVisible)
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
