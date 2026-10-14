package iondrive.nop.agent

import iondrive.nop.Log
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * How the agents in nop's tabs message each other: a Unix socket only this user can reach, and
 * `nop-msg`, the command they reach it with. What is done with a message is [AgentMessages].
 *
 * The same shape as Claude Code's messaging between its own sessions, which is a Unix socket per
 * session in a directory nobody else can open. That reaches only Claude sessions on the same
 * account (the same `CLAUDE_CONFIG_DIR`), so under nop it cannot reach a Codex or an Antigravity tab,
 * or a Claude tab on another account. nop holds every tab's terminal, so it can.
 *
 * Nothing listens on the network. Who may connect is settled three times before a byte is read: the
 * socket is in a directory only the user can enter (`$XDG_RUNTIME_DIR/nop`, else nop's data root),
 * the socket file is owner-only, and the kernel's peer credentials for the connection must be this
 * user. Which tab is calling is the ticket nop put in that tab's environment
 * ([AgentSession.agentTicket], as [TICKET_ENV]); a connection without a live tab's ticket is refused.
 * That ticket is inherited by every command the agent runs and readable by any process of the user's,
 * so it names a tab without proving the agent itself chose to send — which is why nothing sent here
 * is typed anywhere until the user has read it and pressed Deliver (see [AgentMessages]). The name
 * avoids `TOKEN` and `KEY` because Codex strips variables named like those from the commands its
 * agent runs.
 *
 * The request is lines of text, so the helper can be a few lines of shell, ended by the client
 * closing its writing half:
 *
 *     <ticket>
 *     list                        or    send                    or    remember | forget    or    status
 *                                       <id or title>                 project | all <category>    <message id>
 *                                       <message, to the end>         <entry, to the end>
 *
 * The answer is `ok` or `error` on its first line, then text. A send's text ends with the message's
 * own line, `message <id> <state>: <detail>`, and `status` answers with that line as it stands now —
 * see [AgentMessages.Status].
 *
 * A tab can also register a service for its project (`service-register`, then the service's name on
 * the next line), and is answered with the path of the registration [AgentServices] keeps. The
 * service — a program of the user's with no tab — then makes the same `list` and `send` requests
 * with the registration's ticket in place of a tab's, and reaches only that project's tabs. It can
 * also ask after its own messages (`status`), and do nothing else: no memory, and no registering of
 * other services.
 */
object AgentSocket {

    /** Where a run finds the socket. */
    const val SOCKET_ENV = "NOP_SOCKET"

    /** Where a run finds its tab's ticket. */
    const val TICKET_ENV = "NOP_AGENT_TICKET"

    private val random = SecureRandom()

    @Volatile private var socket: Path? = null
    private var failed = false

    /** A fresh unguessable ticket for a tab. */
    fun newTicket(): String = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Where `nop-msg` is written, for the instructions every run is given. */
    fun helperPath(): Path = Accounts.dataRoot().resolve("bin").resolve(HELPER_NAME)

    /**
     * What a run carrying [ticket] needs in its environment to message the other tabs: the socket,
     * the ticket, and `nop-msg` on its PATH. Starts the socket the first time. Empty when the socket
     * could not be started — the run is then started without it rather than not at all.
     */
    fun runEnv(ticket: String): Map<String, String> {
        val path = ensureStarted() ?: return emptyMap()
        val bin = helperPath().parent.toString()
        val inherited = System.getenv("PATH").orEmpty()
        return mapOf(
            SOCKET_ENV to path.toString(),
            TICKET_ENV to ticket,
            "PATH" to if (inherited.isEmpty()) bin else "$bin${File.pathSeparator}$inherited",
        )
    }

    @Synchronized
    private fun ensureStarted(): Path? {
        socket?.let { return it }
        if (failed) return null
        return runCatching { start() }.onFailure {
            failed = true
            Log.warn("agent messaging is off: could not open its socket: $it")
        }.getOrNull()
    }

    private fun start(): Path {
        val dir = socketDir() ?: error("no private directory to put the socket in")
        sweep(dir)
        val path = dir.resolve("${ProcessHandle.current().pid()}.sock")
        Files.deleteIfExists(path)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path))
        // The directory is what keeps everyone else out; the file's own mode is the second lock.
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        installHelper()
        // A registration that cannot be updated is no reason to have no messaging at all.
        runCatching { AgentServices.attachSocket(path) }.onFailure { Log.warn("agent services: could not point them at $path: $it") }
        val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "nop-agent-socket").apply { isDaemon = true } }
        val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nop-agent-socket-timeout").apply { isDaemon = true } }
        Thread({
            while (server.isOpen) {
                val client = runCatching { server.accept() }.getOrNull() ?: run {
                    runCatching { Thread.sleep(ACCEPT_BACKOFF_MS) }
                    null
                } ?: continue
                // A client that never finishes its request is cut off rather than holding a worker.
                val cut = watchdog.schedule({ runCatching { client.close() } }, CLIENT_TIMEOUT_S, TimeUnit.SECONDS)
                workers.execute {
                    runCatching { serve(client) }.onFailure { Log.warn("agent socket request failed: $it") }
                    cut.cancel(false)
                }
            }
        }, "nop-agent-socket-accept").apply { isDaemon = true; start() }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { Files.deleteIfExists(path) } })
        socket = path
        Log.info("agent messaging socket at $path")
        return path
    }

    private fun serve(client: SocketChannel) = client.use {
        if (!fromThisUser(client)) return@use
        val request = readRequest(client) ?: return@use reply(client, false, "the request is too large")
        val (ok, text) = handle(request, ::identify, LiveTools) { AgentServices.identify(it) }
        reply(client, ok, text)
    }

    /** The request as text, or null when it is longer than [MAX_REQUEST_BYTES]. */
    private fun readRequest(client: SocketChannel): String? {
        val input = Channels.newInputStream(client)
        val bytes = input.readNBytes(MAX_REQUEST_BYTES + 1)
        if (bytes.size > MAX_REQUEST_BYTES) return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun reply(client: SocketChannel, ok: Boolean, text: String) {
        val buffer = ByteBuffer.wrap("${if (ok) "ok" else "error"}\n$text\n".toByteArray(Charsets.UTF_8))
        while (buffer.hasRemaining()) client.write(buffer)
    }

    /**
     * Whether the process at the other end runs as this user, by the kernel's account of it.
     * Refused when that cannot be read, rather than taken on trust.
     */
    private fun fromThisUser(client: SocketChannel): Boolean = runCatching {
        client.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED).user().name == System.getProperty("user.name")
    }.getOrElse {
        Log.warn("agent socket: could not read the caller's credentials, refusing it: $it")
        false
    }

    /** What the tools do, so [handle] can be checked without a window. */
    internal interface Tools {
        fun list(caller: AgentSession): AgentMessages.Outcome
        fun send(caller: AgentSession, to: String, message: String): AgentMessages.Outcome
        fun memory(caller: AgentSession, forget: Boolean, scope: SharedMemory.Scope, text: String): AgentMessages.Outcome
        fun status(caller: AgentSession, id: String): AgentMessages.Outcome
        fun registerService(caller: AgentSession, name: String): AgentMessages.Outcome
        fun listService(caller: AgentServices.Registration): AgentMessages.Outcome
        fun sendService(caller: AgentServices.Registration, to: String, message: String): AgentMessages.Outcome
        fun statusService(caller: AgentServices.Registration, id: String): AgentMessages.Outcome
    }

    /** One request answered: whether it worked, and what to say. */
    internal fun handle(
        request: String,
        identify: (String) -> AgentSession?,
        tools: Tools,
        identifyService: (String) -> AgentServices.Registration? = { null },
    ): Pair<Boolean, String> {
        val lines = request.split('\n', limit = 4)
        val ticket = lines.getOrNull(0)?.trim().orEmpty()
        val refused = false to "this only works from inside one of nop's agent tabs, or with a registered service's ticket"
        if (ticket.isEmpty()) return refused
        val caller = identify(ticket) ?: return identifyService(ticket)?.let { handleService(it, lines, tools) } ?: refused
        val outcome = when (lines.getOrNull(1)?.trim()) {
            "list" -> tools.list(caller)
            "send" -> {
                val to = lines.getOrNull(2)?.trim().orEmpty()
                tools.send(caller, to, lines.getOrNull(3).orEmpty())
            }
            "status" -> tools.status(caller, lines.getOrNull(2)?.trim().orEmpty())
            "service-register" -> tools.registerService(caller, lines.getOrNull(2)?.trim().orEmpty())
            "remember", "forget" -> {
                val scope = SharedMemory.parseScope(lines.getOrNull(2)?.trim().orEmpty().split(' ').filter { it.isNotEmpty() })?.first
                    ?: return false to "say who it is for: `project`, or `all <${SharedMemory.Category.entries.joinToString("|") { it.id }}>`"
                tools.memory(caller, lines[1].trim() == "forget", scope, lines.getOrNull(3).orEmpty())
            }
            else -> return false to "unknown request; nop-msg takes `list`, `send`, `status`, `remember`, `forget` or `service-register`"
        }
        return !outcome.isError to outcome.text
    }

    /**
     * A service's request: only `list` and `send`, which [AgentMessages] keeps to the service's project,
     * and `status` for its own messages.
     */
    private fun handleService(service: AgentServices.Registration, lines: List<String>, tools: Tools): Pair<Boolean, String> {
        val outcome = when (lines.getOrNull(1)?.trim()) {
            "list" -> tools.listService(service)
            "send" -> tools.sendService(service, lines.getOrNull(2)?.trim().orEmpty(), lines.getOrNull(3).orEmpty())
            "status" -> tools.statusService(service, lines.getOrNull(2)?.trim().orEmpty())
            else -> return false to "a service can only `list`, `send` to the tabs of its own project, and ask the `status` of what it sent"
        }
        return !outcome.isError to outcome.text
    }

    /** The live tab whose ticket [ticket] is. */
    private fun identify(ticket: String): AgentSession? =
        onEdt { AgentMessages.sessions().firstOrNull { !it.ended && it.agentTicket == ticket } }

    private object LiveTools : Tools {
        override fun list(caller: AgentSession) =
            onEdt { AgentMessages.list(caller) } ?: AgentMessages.Outcome("nop did not answer in time", isError = true)

        override fun send(caller: AgentSession, to: String, message: String) =
            onEdt { AgentMessages.send(caller, to, message) } ?: AgentMessages.Outcome("nop did not answer in time", isError = true)

        override fun status(caller: AgentSession, id: String) =
            onEdt { AgentMessages.status(caller, id) } ?: AgentMessages.Outcome("nop did not answer in time", isError = true)

        // As memory: the project is read on the EDT, the registration written here.
        override fun registerService(caller: AgentSession, name: String): AgentMessages.Outcome {
            val project = onEdt { caller.projectDir } ?: return AgentMessages.Outcome("nop did not answer in time", isError = true)
            val endpoint = socket ?: return AgentMessages.Outcome("nop's socket is not open", isError = true)
            return runCatching { AgentMessages.Outcome(AgentServices.register(name, project, endpoint).toAbsolutePath().toString()) }
                .getOrElse { AgentMessages.Outcome("could not register the service: ${it.message ?: it}", isError = true) }
        }

        override fun listService(caller: AgentServices.Registration) =
            onEdt { AgentMessages.listService(caller) } ?: AgentMessages.Outcome("nop did not answer in time", isError = true)

        override fun sendService(caller: AgentServices.Registration, to: String, message: String) =
            onEdt { AgentMessages.sendService(caller, to, message) } ?: AgentMessages.Outcome("nop did not answer in time", isError = true)

        override fun statusService(caller: AgentServices.Registration, id: String) =
            onEdt { AgentMessages.statusService(caller, id) } ?: AgentMessages.Outcome("nop did not answer in time", isError = true)

        // Only the project is read on the EDT; the files are written on the socket's own thread.
        override fun memory(caller: AgentSession, forget: Boolean, scope: SharedMemory.Scope, text: String): AgentMessages.Outcome {
            val project = onEdt { caller.projectDir.toPath() } ?: return AgentMessages.Outcome("nop did not answer in time", isError = true)
            return if (forget) SharedMemory.forget(project, scope, text) else SharedMemory.remember(project, scope, text)
        }
    }

    /** [block]'s value, computed on the EDT; null if the EDT did not get to it within a few seconds. */
    private fun <T> onEdt(block: () -> T): T? {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(block)
        SwingUtilities.invokeLater(task)
        return runCatching { task.get(EDT_TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull()
    }

    /**
     * `$XDG_RUNTIME_DIR/nop` when the runtime directory is the user's own and private, else a
     * directory under nop's data root. Null when neither turns out private — see [isPrivate].
     */
    internal fun socketDir(): Path? {
        val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?.takeIf { it.isAbsolute && isPrivate(it) }
        val dir = runtime?.resolve("nop") ?: Accounts.dataRoot().resolve("run")
        OwnerOnly.directory(dir)
        return dir.takeIf { isPrivate(it) } ?: run {
            Log.warn("agent socket: $dir is not a private directory of this user's")
            null
        }
    }

    /**
     * Whether [dir] is a real directory, not a link to one, owned by this user, that nobody else can
     * read, write or enter. A directory that fails this was not made by nop and is left as it is.
     */
    internal fun isPrivate(dir: Path): Boolean = runCatching {
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false
        val owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).name
        val open = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).filter { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }
        owner == System.getProperty("user.name") && open.isEmpty()
    }.getOrDefault(false)

    /** Removes the sockets of nops that have gone without cleaning up. */
    private fun sweep(dir: Path) = runCatching {
        Files.list(dir).use { files ->
            files.forEach { f ->
                val pid = STALE.matchEntire(f.fileName.toString())?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return@forEach
                runCatching { Files.deleteIfExists(f) }
            }
        }
    }

    /** Writes `nop-msg`, owner-only and executable. Rewritten at every start, so it matches this build. */
    private fun installHelper() {
        val target = helperPath()
        OwnerOnly.directory(target.parent)
        val tmp = Files.createTempFile(target.parent, ".nop-msg", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.writeString(tmp, HELPER)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private const val HELPER_NAME = "nop-msg"
    private const val CLIENT_TIMEOUT_S = 10L
    private const val ACCEPT_BACKOFF_MS = 200L
    private const val EDT_TIMEOUT_S = 5L
    private const val MAX_REQUEST_BYTES = 256 * 1024
    private val STALE = Regex("""(\d+)\.sock""")

    /**
     * The helper. Plain sh, and it needs something that speaks Unix sockets: `python3` first, since
     * the `nc` a Debian machine has by default (netcat-traditional) has no `-U`.
     */
    internal val HELPER = """
        |#!/bin/sh
        |# nop-msg: message another agent tab in nop, whatever CLI runs it, or add to the agents' memory.
        |# Written by nop at each start (agent/AgentSocket.kt); edits here are overwritten.
        |#
        |#   nop-msg list                            the agent tabs open in nop, with their ids
        |#   nop-msg send <id or title> <message...>  type the message into that tab's prompt
        |#   nop-msg send <id or title> < file        the same, with the message on stdin
        |#   nop-msg status <message id>              where a message you sent has got to
        |#   nop-msg remember project <text...>       add a line to this project's memory
        |#   nop-msg remember all <category> <text...> add a line to every project's (user|hardware|software)
        |#   nop-msg forget project|all <category> <text...>  take that line out again
        |#   nop-msg service-register <name>          let a program message this project's tabs as <name>
        |usage() { sed -n '5,12s/^# *//p' "${'$'}0" >&2; exit 2; }
        |if [ -z "${'$'}NOP_SOCKET" ] || [ -z "${'$'}NOP_AGENT_TICKET" ]; then
        |    echo "nop-msg: this only works inside one of nop's agent tabs" >&2
        |    exit 2
        |fi
        |case "${'$'}1" in
        |    list)
        |        [ ${'$'}# -eq 1 ] || usage
        |        request() { printf '%s\nlist\n' "${'$'}NOP_AGENT_TICKET"; } ;;
        |    send)
        |        [ ${'$'}# -ge 2 ] || usage
        |        to=${'$'}2
        |        shift 2
        |        case "${'$'}to" in *'
        |'*) echo "nop-msg: a tab's id or title has no newline in it" >&2; exit 2 ;; esac
        |        if [ ${'$'}# -gt 0 ]; then
        |            msg=${'$'}*
        |            request() { printf '%s\nsend\n%s\n%s' "${'$'}NOP_AGENT_TICKET" "${'$'}to" "${'$'}msg"; }
        |        else
        |            request() { printf '%s\nsend\n%s\n' "${'$'}NOP_AGENT_TICKET" "${'$'}to"; cat; }
        |        fi ;;
        |    status)
        |        [ ${'$'}# -eq 2 ] || usage
        |        id=${'$'}2
        |        case "${'$'}id" in *'
        |'*) echo "nop-msg: a message id has no newline in it" >&2; exit 2 ;; esac
        |        request() { printf '%s\nstatus\n%s\n' "${'$'}NOP_AGENT_TICKET" "${'$'}id"; } ;;
        |    service-register)
        |        [ ${'$'}# -eq 2 ] || usage
        |        service=${'$'}2
        |        case "${'$'}service" in *'
        |'*) echo "nop-msg: a service name has no newline in it" >&2; exit 2 ;; esac
        |        request() { printf '%s\nservice-register\n%s\n' "${'$'}NOP_AGENT_TICKET" "${'$'}service"; } ;;
        |    remember|forget)
        |        verb=${'$'}1
        |        shift
        |        case "${'$'}1" in
        |            project) scope=project; shift ;;
        |            all) [ ${'$'}# -ge 2 ] || usage; scope="all ${'$'}2"; shift 2 ;;
        |            *) echo "nop-msg: say who it is for: project, or all <user|hardware|software>" >&2; exit 2 ;;
        |        esac
        |        if [ ${'$'}# -gt 0 ]; then
        |            msg=${'$'}*
        |            request() { printf '%s\n%s\n%s\n%s' "${'$'}NOP_AGENT_TICKET" "${'$'}verb" "${'$'}scope" "${'$'}msg"; }
        |        else
        |            request() { printf '%s\n%s\n%s\n' "${'$'}NOP_AGENT_TICKET" "${'$'}verb" "${'$'}scope"; cat; }
        |        fi ;;
        |    *) usage ;;
        |esac
        |connect() {
        |    if command -v python3 >/dev/null 2>&1; then
        |        python3 -c 'import socket, sys
        |s = socket.socket(socket.AF_UNIX)
        |s.connect(sys.argv[1])
        |s.sendall(sys.stdin.buffer.read())
        |s.shutdown(socket.SHUT_WR)
        |while True:
        |    b = s.recv(65536)
        |    if not b:
        |        break
        |    sys.stdout.buffer.write(b)' "${'$'}NOP_SOCKET"
        |    elif command -v socat >/dev/null 2>&1; then
        |        socat -t 10 - "UNIX-CONNECT:${'$'}NOP_SOCKET"
        |    elif nc -h 2>&1 | grep -q -- ' -U'; then
        |        nc -N -U "${'$'}NOP_SOCKET"
        |    else
        |        echo "nop-msg: needs python3, socat, or an nc that has -U" >&2
        |        return 2
        |    fi
        |}
        |reply=${'$'}(request | connect) || { echo "nop-msg: could not reach nop at ${'$'}NOP_SOCKET" >&2; exit 1; }
        |status=${'$'}(printf '%s\n' "${'$'}reply" | head -n 1)
        |body=${'$'}(printf '%s\n' "${'$'}reply" | tail -n +2)
        |if [ "${'$'}status" = ok ]; then
        |    printf '%s\n' "${'$'}body"
        |else
        |    printf 'nop-msg: %s\n' "${'$'}{body:-no answer from nop}" >&2
        |    exit 1
        |fi
        |""".trimMargin()
}
