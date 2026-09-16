package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.agent.transcript.ClaudeTailer
import iondrive.nop.agent.transcript.CodexTailer
import iondrive.nop.agent.transcript.LiveTranscripts
import iondrive.nop.agent.transcript.RunContext
import iondrive.nop.agent.transcript.Tailer
import iondrive.nop.agent.transcript.TranscriptFollower
import iondrive.nop.terminal.TerminalSession
import iondrive.nop.ui.TerminalTab
import java.io.File
import java.util.UUID

/** Why a run stopped. Only [Exited] is the user simply quitting the TUI. */
enum class EndReason { Exited, Quota, Switched, Killed }

/**
 * One run of one account's CLI inside a session: the argv and environment it was started with, the
 * PTY behind it, and the native session id its transcript is filed under.
 *
 * A run is not a session. Handing the work from Claude to Codex ends one run and starts another
 * inside the same [AgentSession], which is what keeps one event log, one history entry and one tab
 * across a provider switch.
 */
class AgentRun(
    val account: Account,
    val command: AgentCommand,
    val session: TerminalSession,
    /** Set when this run was started from a handoff summary rather than from scratch. */
    val seededFromHandoff: Boolean,
) {
    /**
     * The native session id, once it is known. Claude's is minted before the spawn; Codex's only
     * exists after its first rollout line, so the tailer fills this in when it finds it.
     *
     * Compose state rather than a plain `@Volatile`, and written from the tailer thread the way
     * [AgentSession.endedAt] is written from the watcher thread. Two things read it and both are
     * wrong a moment after the spawn without it: the post-exit panel, which can only offer to resume
     * a session the vendor has an id for, and the state file, whose row for a Codex session cannot
     * be written until the CLI has named it.
     */
    var nativeSessionId: String? by mutableStateOf(command.nativeSessionId)

    /** Why the run ended, set when it does. Null while it is still going. */
    @Volatile
    var endReason: EndReason? = null

    /** Follows this run's transcript. Null when the provider has no tailer nop can use. */
    var follower: TranscriptFollower? = null

    /** What the vendor said as it ran out, when that is why the run ended. */
    @Volatile
    var quota: QuotaHit? = null
}

/**
 * One agent session in one project: a tab in the tool strip, the run currently in it, and the
 * account behind that run.
 *
 * The session outlives its runs. That is the whole point of it — "switch to Codex" kills the Claude
 * TUI and opens a Codex one in the same tab, carrying the same session id, so the history entry and
 * the event log both stay continuous across the switch rather than splitting into two unrelated
 * halves.
 *
 * Nothing here parses the terminal. The TUI is drawn by JediTerm exactly as it would be in a shell;
 * what nop knows about what happened inside it comes from the CLI's own transcript on disk, read by
 * a tailer on a separate channel that this session owns but never renders.
 */
class AgentSession(
    val projectDir: File,
    account: Account,
    seed: String? = null,
    resumeId: String? = null,
    /**
     * nop's own id for the session, distinct from any provider's. Names the event log file.
     *
     * Given rather than minted when a tab is being put back from the state file: the log the session
     * already has is the one it should carry on writing, so a restart continues one piece of work
     * rather than starting a second session that happens to resume the same conversation.
     */
    val sessionId: String = UUID.randomUUID().toString(),
    /** What the tab was called when it was written down. Null for a session nobody has named yet. */
    restoredTitle: String? = null,
    /** Whether [restoredTitle] is a name the user typed. See [titleIsUsers]. */
    titleByUser: Boolean = false,
    /**
     * Where the repository stood when this session began — HEAD's sha at the moment the tab was
     * opened, or null for a session nop cannot place (no repository, or a tab restored from a state
     * file written before this was recorded).
     *
     * It is what the Diff tab's "session" base means: everything this agent has done, whether or
     * not it has committed any of it. Recorded once and never moved, which is the whole point —
     * a base that followed HEAD would empty the panel at the first commit, which is the moment the
     * user most wants to see what changed.
     */
    val baselineSha: String? = null,
) : TerminalTab {

    /**
     * What happened in this session, in a vocabulary neither vendor uses — which is what makes a
     * handoff between them possible at all. Written by the tailer, and by the terminal tap while a
     * run has produced no transcript event yet.
     */
    val log: EventLog = EventLog.open(sessionId).also {
        it.append(
            AgentEvent.SessionStarted(
                projectPath = projectDir.absolutePath,
                at = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Reads the vendor's terminal output looking for the moment it refuses to continue.
     *
     * One per session rather than per run, and [QuotaWatcher.reset] on each new run, so the tail
     * left over from an account that ran out cannot fire again the instant its replacement starts.
     *
     * Declared before [run], and it has to be: property initialisers run in declaration order, and
     * initialising `run` starts a run that installs this as its output tap. Below `run` it is still
     * null at that moment. The lambda's own reference back to `run` is safe the other way round —
     * nothing reaches it until the PTY starts, which is when the panel first asks for a widget.
     */
    private val quotaWatcher: QuotaWatcher = QuotaWatcher { hit ->
        val current = run
        if (current.endReason != null) return@QuotaWatcher
        Log.info("agent quota wall on ${current.account.name}: ${hit.line}")
        current.quota = hit
        endRun(EndReason.Quota)
        // Killed rather than left sitting at its own error — the session is over either way. Killed
        // and not disposed, so the dead TUI keeps its last frame under the panel, which is usually
        // the thing that says whether switching is the right call.
        current.session.kill()
    }

    /**
     * Bumped for each run so the terminal card panel — which files widgets under [id] — sees a
     * genuinely new card when a switch replaces the TUI, rather than re-showing the dead one.
     */
    private var runIndex by mutableStateOf(0)

    var run: AgentRun by mutableStateOf(start(account, seed, resumeId, seededFromHandoff = false))
        private set

    override val id: String get() = "agent:$sessionId:$runIndex"
    override val session: TerminalSession get() = run.session

    /** The account whose CLI is running now — which a switch changes. */
    val account: Account get() = run.account

    /**
     * The tab's label.
     *
     * Every session starts under the same name, for the reason every terminal is called "Term":
     * a label nop guesses at is a label the user has to read past. The account name was the guess,
     * and it answers the wrong question — which quota is being spent, not which piece of work the
     * tab is doing — while the settings dialog and the picker both already say it.
     *
     * Two better names replace it. The CLI writes a title into its own transcript a turn or two in
     * ("AWS support"), and the user can right-click the tab and say so themselves. A tab put back
     * from the state file starts under whichever of those it had earned by the time nop exited.
     */
    var title: String by mutableStateOf(restoredTitle?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE)
        private set

    /**
     * Whether the user named this tab themselves. Once they have, nothing else writes to [title] —
     * not a switch, not the CLI's own idea of what the session is about. A name you typed being
     * quietly replaced a minute later is worse than no rename at all — including a minute later on
     * the other side of a restart, which is why it is written to the state file beside the name.
     */
    private var titleIsUsers by mutableStateOf(titleByUser)

    /** Renames the tab. A blank name is ignored: the user who cleared the field meant to cancel. */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        title = trimmed
        titleIsUsers = true
    }

    /** A title the CLI gave the session. Yields to a name the user chose. */
    fun titleFromTranscript(name: String) {
        if (titleIsUsers) return
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) title = trimmed
    }

    /** True once the current run has ended and the post-exit choices belong on screen. */
    val ended: Boolean get() = endedAt > 0

    /**
     * When the current run ended, or 0 while it is still going.
     *
     * The session's own record of the ending rather than the terminal's, because the two disagree
     * in the case that matters: a run killed at a quota wall never reports an exit code, so waiting
     * for one would mean the panel offering to hand the work over was the one thing that never
     * appeared when it was needed. It is also the piece of Compose state that makes the panel show
     * at all — the end is noticed on a watcher thread, and [run] itself does not change.
     */
    var endedAt: Long by mutableStateOf(0L)
        private set

    /**
     * Kills the current run and starts [account]'s CLI in its place, optionally seeded with a
     * prompt or resuming a native session. The session id, tab and history entry all stay put.
     */
    fun switchTo(
        account: Account,
        seed: String? = null,
        resumeId: String? = null,
        reason: EndReason = EndReason.Switched,
        seededFromHandoff: Boolean = false,
        handoffPath: String? = null,
    ) {
        val from = run.account.provider.id
        endRun(reason)
        run.session.dispose()
        log.append(
            AgentEvent.ProviderSwitched(
                from = from,
                to = account.provider.id,
                reason = reason,
                handoffPath = handoffPath,
                at = System.currentTimeMillis(),
            ),
        )
        runIndex += 1
        run = start(account, seed, resumeId, seededFromHandoff)
        endedAt = 0
    }

    /**
     * Records why the current run stopped, drains its tailer and logs the end.
     *
     * The drain is not tidiness. A CLI writes its last records as it exits — the final answer, and
     * the stop reason that says whether it reached one — and those are exactly what a handoff
     * summary is built out of, so stopping the tailer without one last read loses the most useful
     * part of the session.
     */
    fun endRun(reason: EndReason) {
        if (run.endReason != null) return
        run.endReason = reason
        run.follower?.stop()
        run.follower = null
        // Let go of the transcript before the next run looks for one. A dead run still holding its
        // id would make its own session look like somebody else's to the tab that resumes it.
        LiveTranscripts.release(run.nativeSessionId)
        log.append(AgentEvent.RunEnded(run.session.exitCode, reason, System.currentTimeMillis()))
        endedAt = System.currentTimeMillis()
    }

    /**
     * Hands the work to [target]: builds a summary of what has happened, writes it beside the log,
     * and opens the new provider pointed at it.
     *
     * The summary is written before the new CLI starts, and the prompt is one line naming the file,
     * so a handoff that reads badly leaves something on disk to look at rather than only a process
     * that has already exited.
     */
    fun handOver(target: Account, reason: EndReason = EndReason.Switched): Handoff.Written {
        endRun(reason)
        val written = Handoff.write(sessionId, log.events(), target.provider)
        switchTo(
            account = target,
            seed = Handoff.seedPrompt(run.account.provider, written.path),
            reason = reason,
            seededFromHandoff = true,
            handoffPath = written.path.toString(),
        )
        return written
    }

    /** Starts the same account again in this tab, resuming the vendor's own session where it left off. */
    fun reopen(resumeId: String? = run.nativeSessionId) {
        switchTo(account = run.account, resumeId = resumeId, reason = EndReason.Switched)
    }

    /**
     * Starts the same account again in this tab with nothing carried over: no resume, no handoff —
     * the CLI as it would come up from a fresh shell.
     *
     * The sibling [reopen] needs, and for a while was the only thing offered. Resuming is usually
     * right, but not always: a session can end because the model has wedged itself, or because the
     * work in it is finished and the next piece is unrelated, and in both of those landing back in
     * the old conversation is the one thing the user did not want. Switching provider was the only
     * escape, which made "start again on the same account" the one obvious choice nop couldn't make.
     */
    fun startFresh() {
        switchTo(account = run.account, resumeId = null, reason = EndReason.Switched)
    }

    /**
     * This session as a row for the state file, or null for one that should not come back.
     *
     * Two kinds decline. A session whose run has [ended] is one the user quit: the tab is still in
     * the strip so its last frame can be read, but starting nop again is not a reason to start that
     * CLI again — the picker lists it among the past sessions, which is where a deliberate return to
     * it belongs. And a session with no [AgentRun.nativeSessionId] is one nop has no way back into;
     * restoring it would be a tab in the right place with the wrong conversation behind it.
     */
    fun asOpenAgent(): Settings.OpenAgent? {
        if (ended) return null
        val native = run.nativeSessionId ?: return null
        return Settings.OpenAgent(
            sessionId = sessionId,
            provider = account.provider.id,
            account = account.name,
            nativeSessionId = native,
            title = title,
            titleIsUsers = titleIsUsers,
            baselineSha = baselineSha.orEmpty(),
        )
    }

    /**
     * The shell command that lands back in this session outside nop, or null while the vendor has
     * not named it yet.
     *
     * It exists because nop cannot make a session visible to a bare `claude` and should not pretend
     * otherwise. Both CLIs keep a session's transcript in the same directory as the credentials for
     * the account that wrote it — `$CLAUDE_CONFIG_DIR/projects/<slug>/` for one, `$CODEX_HOME/
     * sessions/` for the other — with no setting that separates the two. So running several
     * accounts side by side, which is the point of the picker, splits the transcripts as a side
     * effect: a session nop ran under `claude-work` is not in the store a plain `claude` reads, and
     * no amount of work on nop's side changes where that CLI looks.
     *
     * What nop can do is say where it put it. One environment variable in front of the ordinary
     * command is the whole difference, and it is the same variable nop itself launches with.
     */
    fun resumeCommand(): String? {
        val native = run.nativeSessionId ?: return null
        val home = account.home
        return when (account.provider) {
            Provider.Anthropic -> "CLAUDE_CONFIG_DIR=$home claude --resume $native"
            Provider.OpenAI -> "CODEX_HOME=$home/.codex codex resume $native"
        }
    }

    /** Kills the PTY and everything under it. Idempotent; called when the tab or project closes. */
    fun dispose() {
        endRun(EndReason.Killed)
        run.session.dispose()
        log.close()
    }

    private fun start(
        account: Account,
        seed: String?,
        resumeId: String?,
        seededFromHandoff: Boolean,
    ): AgentRun {
        val command = Spawn.command(account, projectDir, seed, resumeId)
        // The vendor's TUI decides whether to run its first-run flow from its own config, not from
        // whether it has a token — so an account inherited with a perfectly good login would be
        // asked to sign in again. See VendorConfig.
        VendorConfig.prepareForInteractive(account)
        Log.info("agent run ${account.provider.id}/${account.name} in ${projectDir.name}")
        quotaWatcher.reset()
        val newRun: AgentRun
        val terminal = TerminalSession.agent(
            command = command.argv,
            env = command.env,
            dir = projectDir,
            title = account.name,
            // Two jobs, one copy of the output, and neither of them touches what is drawn. The
            // quota watcher is how nop learns the CLI has hit a wall — it announces that in its own
            // UI and nowhere else — and the screen tail is the fallback a handoff is built from
            // when the provider's transcript cannot be read.
            outputTap = { text ->
                quotaWatcher.feed(text)
                log.appendScreenTail(QuotaWatcher.stripAnsi(text))
            },
        )
        newRun = AgentRun(account, command, terminal, seededFromHandoff)
        // Claimed here rather than when the transcript turns up, because the gap between the two is
        // exactly when a tab opened beside this one would mistake this session's file for a `/clear`
        // of its own. Claude's id is known before the spawn; Codex's is claimed in [onLocated]
        // below, as soon as the CLI has named it.
        LiveTranscripts.claim(command.nativeSessionId)
        val startedAt = System.currentTimeMillis()
        log.beginRun()
        log.append(
            AgentEvent.RunStarted(
                provider = account.provider.id,
                account = account.name,
                model = account.model,
                reasoning = account.reasoning,
                nativeSessionId = command.nativeSessionId,
                argv = command.argv,
                seededFromHandoff = seededFromHandoff,
                at = startedAt,
            ),
        )

        val context = RunContext(
            projectDir = projectDir.toPath(),
            home = account.homePath,
            nativeSessionId = command.nativeSessionId,
            startedAt = startedAt,
            // Everything nop is following *except* this run. It is what stops the tailer adopting
            // the transcript of another agent tab on the same project — see [LiveTranscripts].
            foreign = { id -> id != newRun.nativeSessionId && LiveTranscripts.isLive(id) },
        )
        val tailer = tailerFor(account)
        newRun.follower = TranscriptFollower(
            tailer = tailer,
            run = context,
            log = log,
            onEvent = { event ->
                log.append(event)
                // The CLI names its own session a turn or two in. That name says far more about
                // which of three open tabs this is than the account does.
                if (event is AgentEvent.SessionTitled) titleFromTranscript(event.title)
            },
            // A second RunStarted, logged the moment the transcript is found rather than at spawn.
            // Deliberate, not a duplicate: the log is append-only, and the path, the offset and —
            // for a provider that names its own session — the id simply do not exist yet when the
            // CLI is launched. Everything that reads runs takes the last one.
            onLocated = { path, offset ->
                val previous = newRun.nativeSessionId
                newRun.nativeSessionId = tailer.nativeSessionId() ?: previous
                // The id can change under a run twice: a Codex session is named only once its first
                // rollout line lands, and a `/clear` typed in either TUI moves the run to a new one.
                // Both have to move the claim, or the session nop is now following is one the tab
                // next door is free to adopt.
                if (newRun.nativeSessionId != previous) {
                    LiveTranscripts.release(previous)
                    LiveTranscripts.claim(newRun.nativeSessionId)
                }
                log.append(
                    AgentEvent.RunStarted(
                        provider = account.provider.id,
                        account = account.name,
                        model = account.model,
                        reasoning = account.reasoning,
                        nativeSessionId = newRun.nativeSessionId,
                        transcriptPath = path.toString(),
                        transcriptOffset = offset,
                        argv = command.argv,
                        seededFromHandoff = seededFromHandoff,
                        at = System.currentTimeMillis(),
                    ),
                )
            },
        ).also { it.start() }

        // The tailer has to be stopped from the thread that notices the exit, not from whoever
        // happens to look at the session next: the last records are written on the way out. Guarded
        // on still being the current run, so a dying TUI a switch has already replaced cannot end
        // the one that took its place.
        terminal.onExit = { if (run === newRun) endRun(EndReason.Exited) }
        return newRun
    }

    private fun tailerFor(account: Account): Tailer = when (account.provider) {
        Provider.Anthropic -> ClaudeTailer(account.homePath)
        Provider.OpenAI -> CodexTailer(account.homePath)
    }

    companion object {
        /** What an agent tab is called before anything better is known. See [title]. */
        const val DEFAULT_TITLE: String = "Agent"
    }
}
