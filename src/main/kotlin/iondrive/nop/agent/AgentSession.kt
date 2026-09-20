package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.agent.transcript.AntigravityTailer
import iondrive.nop.agent.transcript.ClaudeTailer
import iondrive.nop.agent.transcript.CodexTailer
import iondrive.nop.agent.transcript.LiveTranscripts
import iondrive.nop.agent.transcript.RunContext
import iondrive.nop.agent.transcript.Tailer
import iondrive.nop.agent.transcript.TranscriptFollower
import iondrive.nop.terminal.TerminalSession
import iondrive.nop.ui.TerminalTab
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** Why a run stopped. Only [Exited] is the user simply quitting the TUI. */
enum class EndReason { Exited, Quota, Switched, Killed }

/**
 * A handover nop made on its own, once it has been made: who ran out, who took over, and what the
 * vendor called the wall. Shown once and dismissed — see [AgentSession.autoHandover].
 */
data class AutoHandover(val from: String, val to: String, val kind: String)

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
    /**
     * What this run's CLI has said about whether it is working — see [ActivityTracker]. Per run,
     * because a title or an open tool call belongs to the CLI that wrote it: a question the last
     * provider was asking is not one the next is.
     */
    internal val tracker: ActivityTracker = ActivityTracker(),
) {
    /**
     * When nop started this run. What the CLI filed before it — the wall that ended the last run,
     * replayed by a resume — is history rather than something the vendor is saying now; see
     * [QuotaEcho.judge].
     */
    val startedAt: Instant = Instant.now()

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

    /**
     * The transcript the follower settled on, once it has found one.
     *
     * Kept because it is the other channel: what the CLI recorded, as against what it drew. Deciding
     * whether a limit phrase on the screen came from the vendor or from the agent's own output means
     * looking for it here — see [QuotaEcho].
     */
    @Volatile
    var transcriptPath: java.nio.file.Path? = null

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
    /**
     * Who the work goes to when the account running it says it will not continue, or null for
     * nobody — which leaves the choice on screen, where it has always been.
     *
     * A question asked at the wall rather than an answer captured at the start, because the
     * settings dialog is reachable the whole time a session is running: an account nominated ten
     * minutes into a session is the one the session should use.
     */
    private val handoverTarget: (Account) -> Account? = { _ -> null },
    /**
     * Whether the poller's last reading agrees that [account] has run out — true when it does,
     * false when it says there is quota left, null when it cannot say. See
     * [UsageReading.looksSpent], which is where the reasoning for this lives.
     *
     * It is here to be allowed to say no. Everything else about a quota wall is inferred from text
     * on a screen the agent itself is writing to, and that is not evidence about an account.
     */
    private val hasRunOut: (Account) -> Boolean? = { _ -> null },
    /**
     * When [account] can be served again, going by the poller's last reading, or null when it does
     * not say. See [UsageReading.spentUntil], and [onQuotaWall] for what a reset close at hand
     * changes.
     */
    private val spentUntil: (Account) -> Instant? = { _ -> null },
    /**
     * What to do when the CLI in this tab quits of its own accord and cleanly — `/exit`, `quit`,
     * Ctrl-D at its prompt. Wired by [AgentSessions] to closing the tab.
     *
     * Quitting the CLI is how the user says the work in that tab is over, and a dead TUI left in
     * the strip made them say it twice: once to the agent and once to nop. Every other way a run
     * can end keeps its tab, because each of those leaves something on screen worth reading — a
     * wall and a crash both do, and the post-exit choices are the answer to them (see
     * [iondrive.nop.ui.AgentExitPanel]). A non-zero exit is a crash as far as this can tell, so it
     * is not this.
     */
    private val onExited: (AgentSession) -> Unit = { },
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
     * null at that moment. What the lambda calls reaching back into the session is safe the other
     * way round — nothing reaches it until the PTY starts, which is when the panel first asks for a
     * widget, and later still now that the decision is taken on the UI thread.
     */
    private val quotaWatcher: QuotaWatcher = QuotaWatcher { hit ->
        // Off the PTY's reader thread before anything is decided. What happens next may be a
        // handover, and a handover disposes the very terminal whose output is calling us and starts
        // another in its place — which is the UI thread's work, and exactly what the Hand over
        // button already does there.
        //
        // And not at once. The screen can be ahead of the transcript — a reply is drawn as it
        // streams and filed when it ends — and the transcript is what tells the agent's words from
        // the vendor's (see [QuotaEcho]). A real wall has already ended the turn, so a moment's
        // wait costs it nothing.
        SwingUtilities.invokeLater {
            val firedOn = run
            javax.swing.Timer(QUOTA_SETTLE_MS) { if (run === firedOn) onQuotaWall(hit) }
                .apply { isRepeats = false }
                .start()
        }
    }

    /**
     * The accounts that have already hit a wall in this session.
     *
     * A handover chain has to be able to stop. Two accounts nominating each other is the obvious
     * arrangement for someone with one of each provider, and without this, the pair would trade a
     * dead session back and forth for as long as nop was open — each switch spawning a CLI that
     * reads the summary, asks for a turn, is refused, and hands on again. An account that has been
     * refused once in this session is not offered the same work twice.
     */
    private val spent: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /**
     * A title the CLI gave the session. Yields to a name the user chose, and to the name the work
     * already had when a handover started the run naming it ([fromHandoff]): the CLI that takes over
     * names its conversation after the handoff it was given ("Handoff from Claude Code"), which says
     * nothing about the work.
     */
    fun titleFromTranscript(name: String, fromHandoff: Boolean = false) {
        if (titleIsUsers) return
        if (fromHandoff && title != DEFAULT_TITLE) return
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
     * What the tab is doing — see [Activity].
     *
     * Two of the answers come from the terminal rather than the CLI. [Activity.Ended] is this
     * session's own record of the run stopping, and [Activity.Asleep] is a PTY that has never been
     * started: the state a restored tab stays in until somebody opens it.
     */
    val activity: Activity
        get() = when {
            ended -> Activity.Ended
            !run.session.running && run.session.exitCode == null -> Activity.Asleep
            else -> live
        }

    /** What the running CLI is doing, as its title and transcript say — see [ActivityTracker]. */
    private var live: Activity by mutableStateOf(Activity.Running)

    /** When [activity] last changed, in epoch millis — how long a question has been waiting. */
    var activitySince: Long by mutableStateOf(System.currentTimeMillis())
        private set

    /**
     * Whether the current run has done any work yet. An [Activity.Idle] CLI that has not is one that
     * has just come up — a fresh tab, or one resumed at start — and "finished its turn" would be
     * describing a turn that never happened.
     */
    var hasWorked: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the tab has stopped since anyone last looked at it: asked a question, finished its
     * turn, or ended, while it was not on screen.
     *
     * This is what the strip marks loudly, and it is the difference between a tab and a state. A tab
     * the user is watching finish needs no flag; one that finished behind another tab at 21:03 needs
     * to still say so at 08:48. Only a change *from* working counts — see [noteChange] — so a restart
     * does not bring the strip back with every tab announcing itself at once.
     */
    var unseen: Boolean by mutableStateOf(false)
        private set

    /** How many strips are showing this session's TUI now. See [watch]. */
    @Volatile
    private var watchers = 0

    /** Re-reads the activity once a title has had time to settle. Created on first use, on the EDT. */
    private var settleTimer: javax.swing.Timer? = null

    /**
     * Says the session's TUI is on screen, until the matching [unwatch]. While it is, nothing it does
     * is [unseen] — the user is looking at it happen.
     *
     * A count rather than a flag because two windows can hold tabs on one project, and a window that
     * stops showing the session must not unmark it for the one that still is.
     */
    fun watch() {
        watchers += 1
        unseen = false
    }

    /** The other half of [watch]. */
    fun unwatch() {
        watchers = (watchers - 1).coerceAtLeast(0)
    }

    /**
     * Reads what [tracker] says now into [activity], and arms a re-read for when a title that has not
     * said anything yet will have stood still long enough to. On the UI thread.
     *
     * A tracker that is not the current run's is ignored: a title arriving from a TUI that a switch
     * has just replaced is news about a run that no longer exists.
     */
    internal fun refreshActivity(tracker: ActivityTracker, now: Long = System.currentTimeMillis()) {
        if (tracker !== run.tracker || ended) return
        val (next, settles) = synchronized(tracker) { tracker.activity(now) to tracker.settlesAt() }
        val was = live
        if (next != was) {
            live = next
            activitySince = now
            if (next == Activity.Working) hasWorked = true
            noteChange(was, next)
        }
        if (settles != null && settles > now) {
            val timer = settleTimer ?: javax.swing.Timer(0) { refreshActivity(run.tracker) }
                .apply { isRepeats = false }
                .also { settleTimer = it }
            timer.initialDelay = (settles - now + SETTLE_SLACK_MS).toInt()
            timer.restart()
        }
    }

    /**
     * Whether a change of [activity] is something the user has not seen.
     *
     * Only a change away from work counts: a tab that was working and has now stopped — whether on a
     * question, at the end of its turn, or dead — is news. A tab starting up, or put back asleep, is
     * not. A question is news from anywhere, because nothing moves until it is answered.
     */
    private fun noteChange(was: Activity, next: Activity) {
        unseen = when {
            watchers > 0 -> false
            // Back at work by itself — a background task finishing, say — so whatever it stopped
            // for last time has been dealt with.
            next == Activity.Working -> false
            next == Activity.Asking -> true
            next == Activity.Idle || next == Activity.Ended ->
                unseen || was == Activity.Working || was == Activity.Asking
            else -> unseen
        }
    }

    /**
     * The handover this session made by itself, for the session bar to own up to. Null until one
     * happens, and again once the user has read it.
     *
     * A provider changing under a running tab is the one thing this feature does that the user did
     * not ask for at the moment it happens, so it says so. Without this the only evidence is the
     * account name in the bar quietly reading something else than it did a minute ago, which is
     * indistinguishable from having misremembered which tab you were in.
     */
    var autoHandover: AutoHandover? by mutableStateOf(null)
        private set

    /** Forgets the note above, once it has been read. */
    fun dismissAutoHandover() {
        autoHandover = null
    }

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
        // The new CLI has said nothing yet. What the last one was doing is not what this one is.
        live = Activity.Running
        activitySince = System.currentTimeMillis()
        hasWorked = false
        // Whatever the last run had to own up to belongs to that run. A switch the user made by
        // hand should not arrive carrying an explanation of one nop made ten minutes ago.
        autoHandover = null
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
        activitySince = endedAt
        noteChange(live, Activity.Ended)
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

    /**
     * What happens when the vendor says it will not continue.
     *
     * Either the work moves to the account this one nominated, or — with nobody nominated, or with
     * everyone nominated already refused in this session — the run ends and the post-exit panel puts
     * the choice in front of the user, which is what always used to happen.
     *
     * Internal rather than private so its own test can drive it. Reaching it for real means a PTY
     * printing a limit message, which is not something a test can arrange; the parsing that leads
     * here has [QuotaWatcher]'s tests, and what is decided here has its own.
     */
    internal fun onQuotaWall(hit: QuotaHit) {
        val current = run
        // Anything may have happened between the watcher firing and this reaching the UI thread —
        // the user quitting the TUI, or handing the work on themselves. A run that is already over
        // is not one to end again.
        if (current.endReason != null) return

        // The screen said the account has run out; the provider's own numbers get to disagree.
        //
        // This is the difference between a session that ends because the vendor stopped serving it
        // and one that ends because it was *reading about* a vendor that stopped serving somebody.
        // A diff, a log, a test fixture, a message being replayed on resume — each of those puts a
        // limit message on the screen while the CLI is working perfectly, and acting on it kills a
        // session mid-turn with no undo. Worse, it does not end there: the text that triggered it is
        // in the conversation, so every resume replays it and is killed again within seconds, and
        // the session becomes one nop cannot get back into at all.
        //
        // Only a reading that actively contradicts the screen stops this. Not knowing (null) is left
        // to the transcript below — see [UsageReading.looksSpent].
        val runOut = hasRunOut(current.account)
        if (runOut == false) {
            Log.warn(
                "ignoring a usage-limit phrase on ${current.account.name}: the account still has " +
                    "quota, so this is output the agent was showing rather than the vendor " +
                    "refusing — ${hit.line}",
            )
            // Armed again for the rest of the run, and with the tail it matched on dropped so the
            // same text cannot re-fire the moment the next byte lands.
            quotaWatcher.reset()
            return
        }

        // And the transcript gets its say when the reading has none, whoever the provider is.
        //
        // The usage reading above is the strongest answer there is, but only one provider offers one
        // worth having: Codex's is scavenged from its last session and is usually too old to mean
        // anything, and a provider added later may have none at all. This test needs nothing from the
        // vendor beyond the transcript nop already follows to build a handoff — a refusal the CLI
        // filed just now settles it, and failing that, a phrase that is in the conversation was
        // being shown rather than said. See [QuotaEcho] for why a miss here is the right way round to
        // be wrong.
        //
        // Mostly not heard when the reading says the account is spent. The screen and the provider's
        // own number then agree, and the transcript's "the agent could be showing this" is weaker
        // than either: every session nop hands over reads a handoff quoting the wall that ended the
        // last one, so it would veto the very next wall in exactly the sessions that need it.
        //
        // The exception is the agent's own reply from moments ago. A refused request writes no
        // reply, so a fresh one carrying the phrase is the agent talking — the hermes session handed
        // over at 18:59 had just finished answering a question about an exchange's 429 "too many
        // requests", on an account at 99% of a window that reset 30 seconds later.
        val verdict = QuotaEcho.judge(current.transcriptPath, hit.matched, since = current.startedAt)
        val why = when (verdict) {
            QuotaEcho.Verdict.Refused -> "the CLI filed the refusal"
            QuotaEcho.Verdict.Said -> {
                Log.warn(
                    "ignoring a usage-limit phrase on ${current.account.name}: \"${hit.matched}\" is " +
                        "in the agent's own reply from moments ago, and the CLI has filed no refusal",
                )
                quotaWatcher.reset()
                return
            }
            QuotaEcho.Verdict.Shown, QuotaEcho.Verdict.Unrecorded -> when {
                runOut == true -> "its usage reading is spent"
                verdict == QuotaEcho.Verdict.Unrecorded -> "nothing in the transcript disputes it"
                else -> {
                    Log.warn(
                        "ignoring a usage-limit phrase on ${current.account.name}: \"${hit.matched}\" " +
                            "is in this session's own transcript and the CLI has filed no refusal this " +
                            "run, so it is output the agent was showing rather than the vendor refusing",
                    )
                    quotaWatcher.reset()
                    return
                }
            }
        }

        // A wall that is about to lift is waited out, not acted on. Handing over costs the work its
        // conversation — the next account starts from a summary — and ending the run costs the user a
        // restart, both to save a few minutes; Claude Code carries on by itself once the window
        // rolls over. The watcher stays quiet until then, so the wall on screen is not re-judged at
        // every redraw, and is armed again afterwards for the walls still to come.
        val untilReset = spentUntil(current.account)?.let { Duration.between(Instant.now(), it) }
        if (untilReset != null && untilReset <= WAIT_FOR_RESET) {
            Log.info(
                "agent quota wall on ${current.account.name} ($why), but it resets in " +
                    "${untilReset.seconds}s; waiting for that rather than handing over: ${hit.line}",
            )
            javax.swing.Timer(untilReset.toMillis().toInt() + REARM_SLACK_MS) {
                if (run === current && current.endReason == null) quotaWatcher.reset()
            }.apply { isRepeats = false }.start()
            return
        }

        Log.info("agent quota wall on ${current.account.name} ($why): ${hit.line}")
        current.quota = hit
        spent += current.account.name

        val target = handoverTarget(current.account)?.takeIf { it.name !in spent }
        if (target == null) {
            endRun(EndReason.Quota)
            // Killed rather than left sitting at its own error — the session is over either way.
            // Killed and not disposed, so the dead TUI keeps its last frame under the panel, which
            // is usually the thing that says whether switching is the right call.
            current.session.kill()
            return
        }

        Log.info("${current.account.name} ran out; handing over to ${target.name}")
        handOver(target, EndReason.Quota)
        // After the switch, so the note describes a handover that has actually happened — and after
        // [switchTo] has cleared it, which is what stops a previous one hanging over this run.
        autoHandover = AutoHandover(
            from = current.account.name,
            to = target.name,
            kind = hit.kind,
        )
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
     * otherwise. Every one of them keeps a session's transcript in the same directory as the
     * credentials for the account that wrote it — `$CLAUDE_CONFIG_DIR/projects/<slug>/`,
     * `$CODEX_HOME/sessions/`, `$HOME/.gemini/antigravity-cli/conversations/` — with no setting
     * that separates the two. So running several
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
            Provider.Antigravity -> "HOME=$home agy --conversation $native"
        }
    }

    /** Kills the PTY and everything under it. Idempotent; called when the tab or project closes. */
    fun dispose() {
        endRun(EndReason.Killed)
        settleTimer?.stop()
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
        // Every run is told to read the shared memory first, so it has to be there to read.
        SharedMemory.ensure()
        Log.info("agent run ${account.provider.id}/${account.name} in ${projectDir.name}")
        quotaWatcher.reset()
        val newRun: AgentRun
        val tracker = ActivityTracker()
        // Only ever touched from the PTY's reader thread, which is the one thread the tap runs on.
        val titles = TitleReader()
        val terminal = TerminalSession.agent(
            command = command.argv,
            env = command.env,
            dir = projectDir,
            title = account.name,
            // Three jobs, one copy of the output, and none of them touches what is drawn. The
            // quota watcher is how nop learns the CLI has hit a wall — it announces that in its own
            // UI and nowhere else — and the screen tail is the fallback a handoff is built from
            // when the provider's transcript cannot be read. The title is where the CLI says
            // whether it is working, which is what the tab's mark shows — see [activity].
            outputTap = { text ->
                quotaWatcher.feed(text)
                log.appendScreenTail(QuotaWatcher.stripAnsi(text))
                titles.feed(text)?.let { title ->
                    synchronized(tracker) { tracker.onTitle(title, System.currentTimeMillis()) }
                    SwingUtilities.invokeLater { refreshActivity(tracker) }
                }
            },
        )
        newRun = AgentRun(account, command, terminal, seededFromHandoff, tracker)
        // Claimed here rather than when the transcript turns up, because the gap between the two is
        // exactly when a tab opened beside this one would mistake this session's file for a `/clear`
        // of its own. Claude's id is known before the spawn; Codex's is claimed in [onLocated]
        // below, as soon as the CLI has named it.
        LiveTranscripts.claim(command.nativeSessionId)
        val startedAt = newRun.startedAt.toEpochMilli()
        log.beginRun()
        log.append(
            AgentEvent.RunStarted(
                provider = account.provider.id,
                account = account.name,
                home = account.home,
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
                if (event is AgentEvent.SessionTitled) titleFromTranscript(event.title, seededFromHandoff)
                // A question is an open tool call, and a new prompt is a new turn — see
                // [ActivityTracker]. Nothing else in the transcript changes what the tab is doing.
                if (event is AgentEvent.ToolStarted || event is AgentEvent.ToolFinished ||
                    event is AgentEvent.UserMessage
                ) {
                    synchronized(tracker) { tracker.onEvent(event) }
                    SwingUtilities.invokeLater { refreshActivity(tracker) }
                }
            },
            // A second RunStarted, logged the moment the transcript is found rather than at spawn.
            // Deliberate, not a duplicate: the log is append-only, and the path, the offset and —
            // for a provider that names its own session — the id simply do not exist yet when the
            // CLI is launched. Everything that reads runs takes the last one.
            onLocated = { path, offset ->
                newRun.transcriptPath = path
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
                        home = account.home,
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
        terminal.onExit = { code ->
            if (run === newRun && newRun.endReason == null) {
                endRun(EndReason.Exited)
                // A clean exit is the user leaving; anything else is the CLI falling over, and the
                // tab is where the evidence of that is. Off this thread before the tab goes, for
                // the same reason the quota watcher hops threads: closing disposes the very
                // terminal whose watcher is calling us, which is the UI thread's work.
                if (code == 0) SwingUtilities.invokeLater { onExited(this) }
            }
        }
        return newRun
    }

    private fun tailerFor(account: Account): Tailer = when (account.provider) {
        Provider.Anthropic -> ClaudeTailer(account.homePath)
        Provider.OpenAI -> CodexTailer(account.homePath)
        Provider.Antigravity -> AntigravityTailer(account.homePath)
    }

    companion object {
        /** What an agent tab is called before anything better is known. See [title]. */
        const val DEFAULT_TITLE: String = "Agent"

        /** Past the moment a title settles, so the re-read lands after it rather than just before. */
        private const val SETTLE_SLACK_MS = 50L

        /** How long a limit phrase waits for the transcript to catch up. See [quotaWatcher]. */
        private const val QUOTA_SETTLE_MS = 3_000

        /**
         * How close a reset has to be for a wall to be waited out rather than handed over. A few
         * minutes idle is cheaper than a conversation traded for a summary of it.
         */
        internal val WAIT_FOR_RESET: Duration = Duration.ofMinutes(10)

        /** Past the reset before the watcher listens again, so the poller has had a chance to see it. */
        private const val REARM_SLACK_MS = 5_000
    }
}
