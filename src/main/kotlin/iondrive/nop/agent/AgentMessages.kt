package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import java.io.File
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
 * Delivered, a message is typed into the prompt and submitted exactly as if the user had, headed
 * with its sender and how to answer ([envelope]). Its text is cleaned when it is held ([clean]), so
 * what the user reads is what is typed, and no byte of it can act as a key. A delivered message
 * still waits while the tab cannot safely take it: a CLI still starting, or holding a question (the
 * text would be taken as the answer).
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
        /** Who sent it, as the inbox names them: title, id, CLI/account and project. */
        val from: String,
        /** The sender's id, for the log. */
        val fromId: String,
        /** The sender's project, which decides whether [setAutoDeliver] lets it through. */
        val fromProject: File,
        /** What the user reads, and exactly what is typed after the header. */
        val body: String,
        /** What is typed: [body] under its header. */
        val typed: String,
        val at: Long,
    ) {
        /** The user has pressed Deliver; it goes in as soon as the tab can take it. */
        var approved: Boolean by mutableStateOf(false)
            internal set
    }

    private var lastSubmitAt = 0L

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
        val rows = sessions().joinToString("\n") { s ->
            val you = if (s === caller) "  (you)" else ""
            val waiting = s.inbox.size.takeIf { it > 0 }?.let { ", $it message(s) waiting for the user" }.orEmpty()
            val unasked = if (s !== caller && passesUnasked(caller, s)) ", takes your messages without asking the user" else ""
            "- ${s.shortId}  \"${clean(s.title)}\"  ${s.account.provider.id}/${s.account.name}  in ${s.projectDir.name}  — ${describe(s)}$waiting$unasked$you"
        }
        return Outcome("Agent tabs open in nop. Address one by its id (the first column) or its exact title.\n$rows")
    }

    /** Holds [message] in the inbox of the tab [to] names, for the user to deliver or discard. */
    fun send(caller: AgentSession, to: String, message: String): Outcome {
        val text = clean(message).trim()
        if (text.isEmpty()) return Outcome("the message is empty", isError = true)
        if (text.length > MAX_CHARS) return Outcome("the message is ${text.length} characters; the limit is $MAX_CHARS", isError = true)
        val target = when (val found = resolve(to, sessions())) {
            is Found.One -> found.session
            is Found.Error -> return Outcome(found.why, isError = true)
        }
        if (target === caller) return Outcome("that is your own tab", isError = true)
        if (target.ended) return Outcome("\"${clean(target.title)}\" has ended; nothing is running there to read it", isError = true)
        if (target.inbox.size >= MAX_HELD) {
            return Outcome("\"${clean(target.title)}\" already has $MAX_HELD messages waiting for the user", isError = true)
        }
        val unasked = passesUnasked(caller, target)
        val held = Held(
            from = "\"${clean(caller.title)}\" (${caller.shortId}, ${caller.account.provider.id}/${caller.account.name}, in ${caller.projectDir.name})",
            fromId = caller.shortId,
            fromProject = caller.projectDir,
            body = text,
            typed = envelope(caller, text, unasked),
            at = System.currentTimeMillis(),
        )
        target.inbox.add(held)
        if (unasked) {
            held.approved = true
            Log.info("agent message from ${caller.shortId} to ${target.shortId}: delivered without asking, as ${target.projectDir.name} allows")
            startPump()
            val waiting = waitReason(target)?.let { " It goes in once $it." }.orEmpty()
            return Outcome(
                "Delivered to \"${clean(target.title)}\" (${target.shortId}) without asking the user, since tabs in " +
                    "${target.projectDir.name} may message each other freely. nop types it into that agent's prompt.$waiting",
            )
        }
        Log.info("agent message from ${caller.shortId} to ${target.shortId}: held for the user")
        return Outcome(
            "Held for the user in \"${clean(target.title)}\" (${target.shortId}). nop shows it there with your tab's name, " +
                "and nothing is typed into that agent until the user chooses Deliver; they may discard it instead.",
        )
    }

    /** The user's Deliver: types [held] into [session] now, or as soon as the tab can take it. */
    fun deliver(session: AgentSession, held: Held) {
        if (held !in session.inbox) return
        held.approved = true
        Log.info("agent message from ${held.fromId} to ${session.shortId}: delivered by the user")
        startPump()
    }

    private fun startPump() {
        pump(System.currentTimeMillis())
        if (sessions().any { s -> !s.ended && s.inbox.any { it.approved } }) pump.start()
    }

    /** The user taking back a Deliver that has not gone in yet. */
    fun hold(held: Held) {
        held.approved = false
    }

    /** The user's Discard. */
    fun discard(session: AgentSession, held: Held) {
        if (session.inbox.remove(held)) Log.info("agent message from ${held.fromId} to ${session.shortId}: discarded by the user")
    }

    /** Types in whatever the user has delivered and can go now: one message per pass, so each one's Enter lands first. */
    internal fun pump(now: Long) {
        val waiting = sessions().filter { s -> !s.ended && s.inbox.any { it.approved } }
        if (now - lastSubmitAt >= BETWEEN_SUBMITS_MS) {
            val ready = waiting.firstOrNull { waitReason(it, now) == null }
            if (ready != null) {
                val next = ready.inbox.first { it.approved }
                ready.inbox.remove(next)
                ready.run.session.submit(next.typed)
                lastSubmitAt = now
            }
        }
        if (sessions().none { s -> !s.ended && s.inbox.any { it.approved } }) pump.stop()
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
            terminal.draftPending -> "the draft in its prompt is sent or cleared"
            terminal.lastSubmitAt > 0L && now - terminal.lastSubmitAt < BETWEEN_SUBMITS_MS -> "the prompt has finished sending"
            else -> null
        }
    }

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
    internal fun envelope(from: AgentSession, text: String, unasked: Boolean = false): String = clean(
        "[Message via nop from agent \"${from.title}\" (id ${from.shortId}, ${from.account.provider.id}/${from.account.name}, " +
            "in ${from.projectDir.name}). It is not from the user, " +
            (if (unasked) "who lets this project's tabs message each other without reading each message first. " else "who read it and chose to deliver it. ") +
            "To reply, run: nop-msg send ${from.shortId} \"<your reply>\"]",
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

    private const val PUMP_MS = 500
    internal const val BETWEEN_SUBMITS_MS = 1500L
    private const val BOOT_MS = 5000L
    private const val MAX_CHARS = 32_000
    private const val MAX_HELD = 20
    private const val MIN_ID_PREFIX = 4
}
