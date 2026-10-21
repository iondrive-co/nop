package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.ipc.Handover
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.swing.Timer

/**
 * Messages between agent tabs: who can be written to, and a message's way from one tab to another.
 * [AgentSocket] is how a request arrives; this is what is done with it.
 *
 * Nothing an agent sends is typed anywhere on its say-so. A message is held in the recipient tab's
 * [AgentSession.inbox], shown there with who sent it and every word of it, and goes into that
 * agent's prompt only when the user presses Deliver ([deliver]). The reason is who can send: the
 * ticket that identifies a tab is in its environment (see [AgentSocket]), so every command the agent
 * runs — a project's own scripts included — can send as that tab, and any process of the user's can
 * read it out of `/proc`. Typing straight into agents that run with their permission prompts off on
 * that basis would let one project's code drive the agents in every other. The user reading it first
 * is the check that holds whoever sent it.
 *
 * The one exception is a project the user has opened up ([setAutoDeliver]): a message between two
 * tabs of that project goes in without waiting for Deliver. That gives the project's own code no
 * reach it did not have — its agents already read and run it — while a message from any other
 * project's tab is still held, so the risk above stays shut.
 *
 * A service ([AgentServices]) — a program of the user's with no tab, registered from one of a
 * project's tabs — sends the same way under its own name, but only to that project's tabs, so it is
 * held or let through exactly as a message between those tabs would be.
 *
 * Delivered, a message is typed into the prompt and submitted exactly as if the user had, headed
 * with its sender and how to answer ([envelope]). Its text is cleaned when it is held ([clean]), so
 * what the user reads is what is typed, and no byte of it can act as a key. A delivered message
 * still waits while the tab cannot safely take it: a CLI still starting, or holding a question (the
 * text would be taken as the answer).
 *
 * Every message has an id, and its sender is told where it has got to ([Status]): held for the user,
 * waiting and on what, being typed in, sent with Enter, or discarded. The answer to a send says so
 * as it stands once nop has tried to type it in, and `status` asks again later; the log records each
 * step, and what holds a message back whenever that changes.
 *
 * Everything here runs on the AWT EDT, where the keyboard's writes happen too.
 */
object AgentMessages {

    /** What the sender is told. */
    data class Outcome(val text: String, val isError: Boolean = false)

    /**
     * A message in a tab's inbox. The sender is written down as it was when it wrote, since its tab
     * may be closed or renamed before the user gets to this.
     */
    class Held(
        /** Who sent it, as the inbox names them: `tab` with its title, id, CLI/account and project, or `service` with its name and project. */
        val from: String,
        /** The sender's id, or a service's name, for the log. */
        val fromId: String,
        /** The sender's project, which decides whether [setAutoDeliver] lets it through. */
        val fromProject: File,
        /** What the user reads, and exactly what is typed after the header. */
        val body: String,
        /** What is typed: [body] under its header. */
        val typed: String,
        val at: Long,
        /** This message's own id: what its sender is told, and asks after it by ([status]). */
        val id: String = newId(),
        /**
         * Who may ask after it — `tab:<session id>` or `service:<project>:<name>` — so that no sender
         * can read where another's messages have got to. Empty for one nobody can ask after.
         */
        val sender: String = "",
    ) {
        /** The user has pressed Deliver; it goes in as soon as the tab can take it. */
        var approved: Boolean by mutableStateOf(false)
            internal set

        /** What the log last said holds it back, so the log speaks when that changes rather than at every pass. */
        internal var loggedBlocker: String? = null

        /** As the next nop is handed it across a restart. */
        fun record() = Handover.HeldRecord(from, fromId, fromProject.path, body, typed, at, approved, id, sender)

        companion object {
            /** A message the last nop handed over, back in an inbox as it was. */
            fun of(record: Handover.HeldRecord) = Held(
                record.from, record.fromId, File(record.fromProject), record.body, record.typed, record.at,
                id = record.id.ifEmpty { newId() }, sender = record.sender,
            ).also { it.approved = record.approved }
        }
    }

    /**
     * Where one message has got to, as its sender is told: [line] is one line a program can read,
     * `message <id> <state>: <detail>`, with the state one of [State]'s words.
     */
    data class Status(val id: String, val state: State, val detail: String) {
        enum class State(val word: String) {
            /** In the recipient's inbox until the user chooses Deliver. */
            Held("held"),

            /** Let through, and waiting until the tab can take it; the detail says on what. */
            Waiting("waiting"),

            /** Typed into the prompt, with the Enter that sends it a moment behind. */
            Submitting("submitting"),

            /** Typed in and sent with Enter. */
            Submitted("submitted"),

            /** Discarded by the user. */
            Discarded("discarded"),

            /** It cannot go in: the tab's CLI has ended, or stopped before the Enter. */
            Undeliverable("undeliverable"),
        }

        val line: String get() = "message $id ${state.word}: $detail"
    }

    /** The messages that have left an inbox, by id, with who sent each; the last [MAX_GONE], for [status]. */
    private val gone = object : LinkedHashMap<String, Pair<String, Status>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Status>>) = size > MAX_GONE
    }

    /** How [pump] types a message in, and hears whether its Enter reached a running CLI. Replaced in tests. */
    internal var typeInto: (AgentSession, String, (Boolean) -> Unit) -> Unit =
        { s, text, entered -> s.run.session.submit(text, entered) }

    /** When the pump last typed a message in, in any tab. Set in tests. */
    internal var lastSubmitAt = 0L

    private val pump = Timer(PUMP_MS) { pump(System.currentTimeMillis()) }.apply { isRepeats = true }

    /** Every agent tab in every project. Replaced in tests. */
    internal var sessions: () -> List<AgentSession> = { AgentSessionStore.all() }

    /** Read from [Settings] once per project; [autoDeliverRevision] is what Compose watches. */
    private val autoDeliverCache = HashMap<File, Boolean>()
    private var autoDeliverRevision by mutableStateOf(0)

    /** Whether messages between [project]'s own tabs go in without the user's Deliver. */
    fun autoDelivers(project: File): Boolean {
        autoDeliverRevision
        return autoDeliverCache.getOrPut(key(project)) { Settings.loadAgentAutoDeliver(key(project).toPath()) }
    }

    /**
     * The user's choice for [project]. Turned on, whatever its tabs already hold from each other is
     * delivered too; turned off, a message already let through is not called back.
     */
    fun setAutoDeliver(project: File, on: Boolean) {
        val dir = key(project)
        Settings.saveAgentAutoDeliver(dir.toPath(), on)
        autoDeliverCache[dir] = on
        autoDeliverRevision++
        Log.info("agent messages between tabs in $dir: ${if (on) "delivered without asking" else "held for the user"}")
        if (!on) return
        sessions().filter { key(it.projectDir) == dir && !it.ended }.forEach { s ->
            s.inbox.filter { key(it.fromProject) == dir }.forEach { it.approved = true }
        }
        startPump()
    }

    /** Whether a message from [from] to [to] goes in without the user's Deliver. */
    internal fun passesUnasked(from: AgentSession, to: AgentSession): Boolean =
        key(from.projectDir) == key(to.projectDir) && autoDelivers(to.projectDir)

    /** Forgets what was read from [Settings], for tests that point it somewhere else. */
    internal fun forgetAutoDeliver() = autoDeliverCache.clear()

    private fun key(project: File): File = project.absoluteFile.normalize()

    /** The tabs [caller] can write to, one line each, with the caller's own marked. */
    fun list(caller: AgentSession): Outcome {
        val rows = sessions().joinToString("\n") { s -> row(s, unasked = s !== caller && passesUnasked(caller, s), you = s === caller) }
        return Outcome("Agent tabs open in nop. Address one by its id (the first column) or its exact title.\n$rows")
    }

    /** As [list], for a service: only its project's tabs, the only ones it can write to. */
    internal fun listService(caller: AgentServices.Registration): Outcome {
        val rows = sessions().filter { key(it.projectDir) == key(caller.projectDir) }
            .joinToString("\n") { s -> row(s, unasked = autoDelivers(s.projectDir), you = false) }
        return Outcome("Agent tabs open in nop in ${caller.projectDir.name}. Address one by its id (the first column) or its exact title.\n$rows")
    }

    /**
     * One tab as [list] shows it. Its inbox is told apart: messages the user has not let through yet,
     * and ones that are let through and waiting for the tab, with what the first of those waits on.
     */
    private fun row(s: AgentSession, unasked: Boolean, you: Boolean, now: Long = System.currentTimeMillis()): String {
        val forUser = s.inbox.count { !it.approved }
        val waiting = forUser.takeIf { it > 0 }?.let { ", $it message(s) waiting for the user" }.orEmpty()
        val letThrough = s.inbox.filter { it.approved }
        val going = when {
            letThrough.isEmpty() -> ""
            s.ended -> ", ${letThrough.size} message(s) that cannot go in now its CLI has ended"
            else -> ", ${letThrough.size} message(s) waiting to be typed in once ${blocker(s, letThrough.first(), now) ?: NEXT_PASS}"
        }
        val freely = if (unasked) ", takes your messages without asking the user" else ""
        val mine = if (you) "  (you)" else ""
        return "- ${s.shortId}  \"${clean(s.title)}\"  ${s.account.provider.id}/${s.account.name}  in ${s.projectDir.name}  — " +
            "${describe(s)}$waiting$going$freely$mine"
    }

    /** Holds [message] in the inbox of the tab [to] names, for the user to deliver or discard. */
    fun send(caller: AgentSession, to: String, message: String): Outcome {
        val text = clean(message).trim()
        refuseText(text)?.let { return it }
        val target = when (val found = resolve(to, sessions())) {
            is Found.One -> found.session
            is Found.Error -> return Outcome(found.why, isError = true)
        }
        if (target === caller) return Outcome("that is your own tab", isError = true)
        refuseTarget(target)?.let { return it }
        val unasked = passesUnasked(caller, target)
        val held = Held(
            from = "tab \"${clean(caller.title)}\" (${caller.shortId}, ${caller.account.provider.id}/${caller.account.name}, in ${caller.projectDir.name})",
            fromId = caller.shortId,
            fromProject = caller.projectDir,
            body = text,
            typed = envelope(caller, text),
            at = System.currentTimeMillis(),
            sender = senderOf(caller),
        )
        return enqueue(target, held, unasked, shownAs = "your tab's name", freely = "tabs in ${target.projectDir.name} may message each other freely")
    }

    /**
     * As [send], from a service: only to a tab of its own project, and headed with the service's name
     * rather than any tab's. It goes in unasked exactly when a message between that project's own tabs
     * would.
     */
    internal fun sendService(caller: AgentServices.Registration, to: String, message: String): Outcome {
        val text = clean(message).trim()
        refuseText(text)?.let { return it }
        val target = when (val found = resolve(to, sessions().filter { key(it.projectDir) == key(caller.projectDir) })) {
            is Found.One -> found.session
            is Found.Error -> return Outcome(found.why, isError = true)
        }
        refuseTarget(target)?.let { return it }
        val unasked = autoDelivers(target.projectDir)
        val held = Held(
            from = "service \"${clean(caller.name)}\" (in ${caller.projectDir.name})",
            fromId = "service \"${clean(caller.name)}\"",
            fromProject = caller.projectDir,
            body = text,
            typed = serviceEnvelope(caller, text),
            at = System.currentTimeMillis(),
            sender = senderOf(caller),
        )
        return enqueue(
            target, held, unasked,
            shownAs = "your service's name",
            freely = "the user lets messages from within ${target.projectDir.name} go in freely",
        )
    }

    private fun refuseText(text: String): Outcome? = when {
        text.isEmpty() -> Outcome("the message is empty", isError = true)
        text.length > MAX_CHARS -> Outcome("the message is ${text.length} characters; the limit is $MAX_CHARS", isError = true)
        else -> null
    }

    private fun refuseTarget(target: AgentSession): Outcome? = when {
        target.ended -> Outcome("\"${clean(target.title)}\" has ended; nothing is running there to read it", isError = true)
        target.inbox.size >= MAX_HELD -> Outcome("\"${clean(target.title)}\" already has $MAX_HELD messages waiting in its inbox", isError = true)
        else -> null
    }

    /**
     * Puts [held] in [target]'s inbox, approved already when [unasked], and says what became of it:
     * the inbox shows it under [shownAs], and [freely] is why it went in without the user. A message
     * let through is offered to the pump at once, so the answer says what actually happened to it —
     * typed in, or waiting and on what — and ends with its [Status] line.
     */
    private fun enqueue(target: AgentSession, held: Held, unasked: Boolean, shownAs: String, freely: String): Outcome {
        target.inbox.add(held)
        val to = "\"${clean(target.title)}\" (${target.shortId})"
        if (unasked) {
            held.approved = true
            Log.info("agent message ${held.id} from ${held.fromId} to ${target.shortId}: delivered without asking, as ${target.projectDir.name} allows")
            startPump()
            val now = System.currentTimeMillis()
            val status = lookUp(held.id, held.sender, now) ?: statusOf(target, held, now)
            val what = when (status.state) {
                Status.State.Submitting, Status.State.Submitted -> "nop is typing it into that agent's prompt now."
                Status.State.Waiting -> "nop types it into that agent's prompt once ${blocker(target, held, now) ?: NEXT_PASS}."
                else -> "${status.detail}."
            }
            return Outcome("Delivered to $to without asking the user, since $freely. $what\n${status.line}")
        }
        Log.info("agent message ${held.id} from ${held.fromId} to ${target.shortId}: held for the user")
        return Outcome(
            "Held for the user in $to. nop shows it there with $shownAs, and nothing is typed into that agent until the " +
                "user chooses Deliver; they may discard it instead.\n${statusOf(target, held).line}",
        )
    }

    /** The user's Deliver: types [held] into [session] now, or as soon as the tab can take it. */
    fun deliver(session: AgentSession, held: Held) {
        if (held !in session.inbox) return
        held.approved = true
        Log.info("agent message ${held.id} from ${held.fromId} to ${session.shortId}: delivered by the user")
        startPump()
    }

    /** Starts typing in what is already approved: messages a restart carried across, waiting to go in. */
    internal fun startPump() {
        pump(System.currentTimeMillis())
        if (sessions().any { s -> !s.ended && s.inbox.any { it.approved } }) pump.start()
    }

    /** The user taking back a Deliver that has not gone in yet. */
    fun hold(held: Held) {
        held.approved = false
        held.loggedBlocker = null
        Log.info("agent message ${held.id} from ${held.fromId}: held back by the user")
    }

    /** The user's Discard. */
    fun discard(session: AgentSession, held: Held) {
        if (!session.inbox.remove(held)) return
        gone[held.id] = held.sender to Status(held.id, Status.State.Discarded, "discarded by the user at ${clock(System.currentTimeMillis())}")
        Log.info("agent message ${held.id} from ${held.fromId} to ${session.shortId}: discarded by the user")
    }

    /**
     * Types in whatever the user has delivered and can go now: one message per pass, so each one's
     * Enter lands first. Logs what holds each of the rest back, when that changes.
     */
    internal fun pump(now: Long) {
        val waiting = sessions().filter { s -> !s.ended && s.inbox.any { it.approved } }
        if (now - lastSubmitAt >= BETWEEN_SUBMITS_MS) {
            val ready = waiting.firstOrNull { waitReason(it, now) == null }
            if (ready != null) {
                val next = ready.inbox.first { it.approved }
                ready.inbox.remove(next)
                lastSubmitAt = now
                typeIn(ready, next, now)
            }
        }
        for (s in waiting) for (held in s.inbox.toList()) if (held.approved) noteBlocker(s, held, now)
        if (sessions().none { s -> !s.ended && s.inbox.any { it.approved } }) pump.stop()
    }

    /** Types [held], out of [s]'s inbox now, into its prompt, and keeps its [Status] as it goes. */
    private fun typeIn(s: AgentSession, held: Held, now: Long) {
        val to = "\"${clean(s.title)}\" (${s.shortId})"
        gone[held.id] = held.sender to Status(held.id, Status.State.Submitting, "typed into $to's prompt at ${clock(now)}; its Enter follows")
        Log.info("agent message ${held.id} from ${held.fromId} to ${s.shortId}: typing it in")
        typeInto(s, held.typed) { entered ->
            val at = System.currentTimeMillis()
            gone[held.id] = held.sender to if (entered) {
                Log.info("agent message ${held.id} from ${held.fromId} to ${s.shortId}: submitted")
                Status(held.id, Status.State.Submitted, "typed into $to's prompt and sent with Enter at ${clock(at)}")
            } else {
                Log.warn("agent message ${held.id} from ${held.fromId} to ${s.shortId}: its CLI stopped before the Enter that sends it")
                Status(held.id, Status.State.Undeliverable, "typed into $to's prompt, but its CLI stopped before the Enter that sends it")
            }
        }
    }

    /** Logs what holds [held] back from [s], if that is not what the log last said. */
    private fun noteBlocker(s: AgentSession, held: Held, now: Long) {
        val why = blocker(s, held, now) ?: return
        if (why == held.loggedBlocker) return
        held.loggedBlocker = why
        Log.info("agent message ${held.id} to ${s.shortId}: waiting until $why")
    }

    /** Why a delivered message cannot be typed into [s] at [now], or null when it can. */
    fun waitReason(s: AgentSession, now: Long = System.currentTimeMillis()): String? {
        val terminal = s.run.session
        return when {
            s.ended -> "its CLI has ended"
            !terminal.isStarted || terminal.deferred -> "the tab is opened and its CLI starts"
            !terminal.running -> "its CLI is running again"
            now - s.run.startedAt.toEpochMilli() < BOOT_MS -> "its CLI has finished starting"
            s.activity == Activity.Asking -> "the question it is asking has been answered"
            terminal.submitting -> "nop has finished typing another message into it"
            terminal.mayHoldDraft(now) -> "the draft in its prompt is sent or cleared"
            terminal.lastSubmitAt > 0L && now - terminal.lastSubmitAt < BETWEEN_SUBMITS_MS -> "the prompt has finished sending"
            else -> null
        }
    }

    /**
     * Why [held], let through, is not being typed into [s] at [now], or null when nop's next pass takes
     * it: [waitReason] for the tab, then what the pump itself waits on — the message ahead of it in the
     * same inbox, which goes first, and the pause nop leaves after typing any message in, so that each
     * Enter lands before the next text arrives.
     */
    internal fun blocker(s: AgentSession, held: Held, now: Long = System.currentTimeMillis()): String? {
        waitReason(s, now)?.let { return it }
        val first = s.inbox.firstOrNull { it.approved }
        if (first != null && first !== held) return "the message ahead of it has gone in"
        if (now - lastSubmitAt < BETWEEN_SUBMITS_MS) return "nop has paused after typing in another message"
        return null
    }

    /** Where [held], still in [s]'s inbox, stands at [now]. */
    internal fun statusOf(s: AgentSession, held: Held, now: Long = System.currentTimeMillis()): Status {
        val to = "\"${clean(s.title)}\" (${s.shortId})"
        return when {
            s.ended -> Status(held.id, Status.State.Undeliverable, "$to's CLI has ended; the user can only discard it")
            !held.approved -> Status(held.id, Status.State.Held, "in $to's inbox until the user chooses Deliver")
            else -> Status(held.id, Status.State.Waiting, "in $to's inbox; it goes in once ${blocker(s, held, now) ?: NEXT_PASS}")
        }
    }

    /** Where the message [id] that [sender] sent has got to, or null when nop has no record of it from them. */
    internal fun lookUp(id: String, sender: String, now: Long = System.currentTimeMillis()): Status? {
        for (s in sessions()) s.inbox.firstOrNull { it.id == id && it.sender == sender }?.let { return statusOf(s, it, now) }
        return gone[id]?.takeIf { it.first == sender }?.second
    }

    /** Where the message [id] that [caller] sent has got to. */
    fun status(caller: AgentSession, id: String): Outcome = statusFor(id, senderOf(caller))

    /** As [status], for a service's own messages. */
    internal fun statusService(caller: AgentServices.Registration, id: String): Outcome = statusFor(id, senderOf(caller))

    private fun statusFor(id: String, sender: String): Outcome {
        val wanted = id.trim()
        if (wanted.isEmpty()) return Outcome("say which message: the answer to a send ends with its id", isError = true)
        val found = lookUp(wanted, sender) ?: return Outcome(
            "nop has no record of a message ${clean(wanted)} from you. It keeps the last $MAX_GONE that have left an inbox " +
                "since it last started, and loses a message with the tab it was sent to when that tab is closed.",
            isError = true,
        )
        return Outcome(found.line)
    }

    private fun senderOf(tab: AgentSession) = "tab:${tab.sessionId}"

    private fun senderOf(service: AgentServices.Registration) = "service:${key(service.projectDir)}:${service.name}"

    private fun newId(): String = "m" + UUID.randomUUID().toString().replace("-", "").take(8)

    /** [ms] as a local time with its offset, to the millisecond, for status lines read beside other logs. */
    private fun clock(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).truncatedTo(ChronoUnit.MILLIS).toString()

    internal sealed interface Found {
        data class One(val session: AgentSession) : Found
        data class Error(val why: String) : Found
    }

    /**
     * The one tab [to] names: an id or the start of one (at least [MIN_ID_PREFIX] characters), or
     * failing that an exact title, ignoring case. More than one match is an error naming them all —
     * two tabs with one title is ordinary, and guessing would put the message in front of the wrong
     * agent.
     */
    internal fun resolve(to: String, all: List<AgentSession>): Found {
        val key = to.trim()
        if (key.isEmpty()) return Found.Error("say which tab: `nop-msg list` shows their ids")
        val byId = if (key.length >= MIN_ID_PREFIX) all.filter { it.sessionId.startsWith(key, ignoreCase = true) } else emptyList()
        val matches = byId.ifEmpty { all.filter { it.title.equals(key, ignoreCase = true) } }
        return when (matches.size) {
            1 -> Found.One(matches.single())
            0 -> Found.Error("no agent tab has the id or title \"${clean(key)}\"; `nop-msg list` shows the ones open")
            else -> Found.Error(
                "\"${clean(key)}\" matches ${matches.size} tabs; use an id instead:\n" +
                    matches.joinToString("\n") { "- ${it.shortId}  \"${clean(it.title)}\"  in ${it.projectDir.name}" },
            )
        }
    }

    /**
     * [text] as the recipient reads it: who it is from and how to answer, above the message. The
     * header is the whole of what tells an agent this did not come from the user — though the user
     * did choose to let it through, one message at a time or for the whole project.
     */
    internal fun envelope(from: AgentSession, text: String): String = clean(
        "${ENVELOPE_START}agent \"${from.title}\" (${from.shortId}, ${from.account.provider.id}/${from.account.name}, " +
            "in ${from.projectDir.name}), not the user. Reply: nop-msg send ${from.shortId} \"...\"]",
    ) + "\n\n" + clean(text)

    /**
     * As [envelope], from a service: its name, and no tab to reply to — a service reads no prompt, so
     * there is nobody at the other end of a `nop-msg send`. It only reaches its own project's tabs, so
     * the project goes unsaid.
     */
    internal fun serviceEnvelope(from: AgentServices.Registration, text: String): String = clean(
        "${ENVELOPE_START}service \"${from.name}\", not the user; no reply.]",
    ) + "\n\n" + clean(text)

    /**
     * [text] with nothing left in it that a terminal would take for anything but text: every control
     * character except newline and tab (ESC among them, so no escape sequence — a bracketed paste's
     * end marker included — survives), carriage returns made newlines, and the Unicode direction
     * overrides that could make what the user approves read differently from what is typed.
     */
    fun clean(text: String): String = buildString(text.length) {
        for (c in text.replace("\r\n", "\n").replace('\r', '\n')) {
            if (c == '\n' || c == '\t' || (!c.isISOControl() && c !in BIDI_CONTROLS)) append(c)
        }
    }

    private val BIDI_CONTROLS = setOf(
        '؜', '‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩',
    )

    private fun describe(s: AgentSession): String = when {
        s.ended -> "ended"
        !s.run.session.isStarted || s.run.session.deferred -> "asleep"
        else -> when (s.activity) {
            Activity.Asleep -> "asleep"
            Activity.Running -> "running"
            Activity.Working -> "working"
            Activity.Asking -> "waiting on the user's answer"
            Activity.Idle -> "idle"
            Activity.Ended -> "ended"
        }
    }

    /** How every message nop delivers begins, from an agent ([envelope]) or a service ([serviceEnvelope]). */
    internal const val ENVELOPE_START = "[Message via nop from "

    /** What a message waits on when nothing holds it back: the pump's next pass. */
    private const val NEXT_PASS = "nop's next pass, within half a second"
    private const val MAX_GONE = 500
    private const val PUMP_MS = 500
    internal const val BETWEEN_SUBMITS_MS = 1500L
    private const val BOOT_MS = 5000L
    private const val MAX_CHARS = 32_000
    private const val MAX_HELD = 20
    private const val MIN_ID_PREFIX = 4
}
