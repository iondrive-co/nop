package iondrive.nop.ipc

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Coordinates one logical nop process per user. The first launcher to start opens a localhost
 * ServerSocket and writes a sidecar file under the config root recording its port + a random
 * token; subsequent launchers read the sidecar, connect, hand over their project paths, and
 * exit. The primary's accept loop drains the requests onto the EDT via the [bind] callbacks.
 *
 * Protocol is one line per request:
 *
 *   `<token> OPEN <abs-path>`   primary opens (or focuses) the project at `<abs-path>`
 *   `<token> FOCUS`             primary just brings a window to the foreground
 *   `<token> QUIT`              primary shuts down (used when a newer build is taking over)
 *   `<token> RESTART [<asker>]` primary asks the user, and if they agree starts its successor and
 *                               quits; `<asker>` is the agent tab that asked (`nop --restart`)
 *
 * Server replies `OK\n` for handled lines, `ERR <reason>\n` otherwise, then closes the socket.
 * The token guards against random localhost processes triggering arbitrary file opens.
 *
 * The sidecar also records a [buildStamp] of the binary the primary launched from. A launcher
 * whose own build differs (the user rebuilt and relaunched) does NOT forward — it asks the stale
 * primary to QUIT and becomes the new primary itself, so a fresh build never silently hands control
 * to the old running code.
 */
object SingleInstance {
    /**
     * Set in the environment of every agent CLI nop runs, to that tab's session id. A nop started
     * with it set was started by an agent, which [iondrive.nop.AgentLaunchGuard] refuses along with
     * every other agent's marker; `nop --restart` sends it along to say which tab asked.
     */
    const val INSIDE_AGENT_ENV = "NOP_AGENT_SESSION"

    private const val SIDECAR_RELATIVE = "nop/instance"
    private const val LOOPBACK = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 500
    private const val READ_TIMEOUT_MS = 1500

    /** How long to wait for a stale primary to exit after we ask it to QUIT, before taking over anyway. */
    private const val QUIT_WAIT_MS = 5_000L

    /** Handle returned by [bind]; close it to stop the accept loop and remove the sidecar. */
    class Handle internal constructor(
        private val server: ServerSocket,
        private val sidecar: Path,
        private val thread: Thread,
    ) : AutoCloseable {
        @Volatile private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            runCatching { server.close() }
            runCatching { Files.deleteIfExists(sidecar) }
            thread.interrupt()
        }
    }

    /**
     * Attempts to hand the given [paths] (or a bare FOCUS when empty) to an already-running
     * primary. Returns true when the primary accepted the request and the caller should exit.
     * Stale sidecar files (primary crashed without cleanup) are removed so the next launcher
     * can become primary.
     */
    fun tryForward(paths: List<Path>, configRoot: Path): Boolean {
        val sidecar = configRoot.resolve(SIDECAR_RELATIVE)
        val info = readSidecar(sidecar) ?: return false
        // If the running primary was launched from a different build than this binary — the user
        // rebuilt and relaunched — forwarding would keep the stale code running forever. Ask it to
        // quit and return false so our caller becomes the new primary on the fresh build instead.
        if (info.build != buildStamp()) {
            takeOver(info, sidecar)
            return false
        }
        return try {
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(InetAddress.getByName(LOOPBACK), info.port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val out = PrintWriter(sock.getOutputStream().writer(StandardCharsets.UTF_8), true)
                val `in` = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                if (paths.isEmpty()) {
                    out.println("${info.token} FOCUS")
                    val reply = `in`.readLine() ?: ""
                    return@use reply.startsWith("OK")
                }
                var allOk = true
                for (p in paths) {
                    val abs = p.toAbsolutePath().normalize().toString()
                    out.println("${info.token} OPEN $abs")
                    val reply = `in`.readLine() ?: ""
                    if (!reply.startsWith("OK")) allOk = false
                }
                allOk
            }
        } catch (_: IOException) {
            // Primary went away without cleaning up its sidecar — purge it so our own bind can
            // succeed (and so the next launcher doesn't waste time trying to connect).
            runCatching { Files.deleteIfExists(sidecar) }
            false
        }
    }

    /**
     * Whether a primary is running for [configRoot]: its sidecar is there and names a live process.
     * Asks the primary nothing, so it cannot be mistaken for a launch.
     */
    fun isRunning(configRoot: Path): Boolean {
        val info = readSidecar(configRoot.resolve(SIDECAR_RELATIVE)) ?: return false
        val pid = info.pid ?: return true
        return runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(true)
    }

    /** What became of a `nop --restart`. */
    sealed interface RestartReply {
        /** The running nop has put the question to the user. Whether it restarts is up to them. */
        data object Asked : RestartReply

        /** No nop is running with this config, so there is nothing to restart. */
        data object NotRunning : RestartReply

        /** The running nop would not ask: [why] says why, for the command line. */
        data class Refused(val why: String) : RestartReply
    }

    /**
     * Asks the primary for [configRoot] to restart, on behalf of the agent tab [asker] when it is one.
     * Never takes over or starts anything itself, whatever build either side is: the restart, if the
     * user agrees to one, is the primary's to do.
     */
    fun requestRestart(configRoot: Path, asker: String?): RestartReply {
        if (!isRunning(configRoot)) return RestartReply.NotRunning
        val info = readSidecar(configRoot.resolve(SIDECAR_RELATIVE)) ?: return RestartReply.NotRunning
        val reply = try {
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(InetAddress.getByName(LOOPBACK), info.port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val out = PrintWriter(sock.getOutputStream().writer(StandardCharsets.UTF_8), true)
                val `in` = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                out.println(listOfNotNull(info.token, "RESTART", asker?.takeIf { it.isNotBlank() && ' ' !in it }).joinToString(" "))
                `in`.readLine().orEmpty()
            }
        } catch (_: IOException) {
            return RestartReply.NotRunning
        }
        return when {
            reply.startsWith("OK") -> RestartReply.Asked
            // What a nop built before this verb existed answers.
            reply == "ERR bad verb" -> RestartReply.Refused("the running nop is older than `nop --restart`; ask the user to restart it")
            else -> RestartReply.Refused(reply.removePrefix("ERR").trim().ifEmpty { "no answer from the running nop" })
        }
    }

    /**
     * Ask a running primary built from a different binary to quit, then wait for it to actually
     * exit so its socket is released and its sidecar removed before our caller binds a fresh one.
     * Best-effort: if it's already unreachable we purge the sidecar; if it won't die within
     * [QUIT_WAIT_MS] we take over anyway (a leftover orphan is no worse than the stale forward we
     * are avoiding, and our caller will own the sidecar).
     */
    private fun takeOver(info: SidecarInfo, sidecar: Path) {
        val asked = try {
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(InetAddress.getByName(LOOPBACK), info.port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val out = PrintWriter(sock.getOutputStream().writer(StandardCharsets.UTF_8), true)
                val `in` = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                out.println("${info.token} QUIT")
                `in`.readLine()?.startsWith("OK") == true
            }
        } catch (_: IOException) {
            false
        }
        if (!asked) {
            // Already dead / stale sidecar — purge so our bind() can take over cleanly.
            runCatching { Files.deleteIfExists(sidecar) }
            return
        }
        // Wait for the old process to fully terminate; once it's gone its shutdown (server close +
        // sidecar delete) has completed, so our bind() writes a clean sidecar with no race.
        val handle = info.pid?.let { runCatching { ProcessHandle.of(it).orElse(null) }.getOrNull() }
        if (handle != null) {
            val deadline = System.currentTimeMillis() + QUIT_WAIT_MS
            while (handle.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(50) }
            }
        }
    }

    /**
     * Identity of the binary this process was launched from (jar name + size + mtime). Any rebuild
     * changes at least one component, so a relaunch from a changed build is detected as different.
     * Empty when it can't be determined, in which case we degrade gracefully to plain forwarding.
     */
    private fun buildStamp(): String {
        val loc = runCatching { SingleInstance::class.java.protectionDomain?.codeSource?.location }.getOrNull() ?: return ""
        val file = runCatching { File(loc.toURI()) }.getOrNull() ?: return ""
        return runCatching { "${file.name}:${file.length()}:${file.lastModified()}" }.getOrDefault("")
    }

    /**
     * Binds an ephemeral loopback port and writes the sidecar so subsequent launchers can find
     * us. Returns null on bind failure (rare on 127.0.0.1, but possible when an unrelated
     * service squats on the chosen port between our pick and our use). The accept thread is a
     * daemon so it doesn't keep the JVM alive on shutdown.
     */
    fun bind(
        configRoot: Path,
        onOpen: (Path) -> Unit,
        onFocus: () -> Unit,
        onQuit: () -> Unit = {},
        onRestart: (asker: String?) -> String? = { "this nop cannot restart itself" },
    ): Handle? {
        val server = try {
            ServerSocket(0, 50, InetAddress.getByName(LOOPBACK))
        } catch (_: IOException) {
            return null
        }
        val token = generateToken()
        val sidecar = configRoot.resolve(SIDECAR_RELATIVE)
        runCatching {
            Files.createDirectories(sidecar.parent)
            Files.writeString(
                sidecar,
                "port=${server.localPort}\ntoken=$token\npid=${ProcessHandle.current().pid()}\nbuild=${buildStamp()}\n",
            )
        }.onFailure {
            runCatching { server.close() }
            return null
        }

        val thread = Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                handleConnection(client, token, onOpen, onFocus, onQuit, onRestart)
            }
        }, "nop-single-instance").apply {
            isDaemon = true
            start()
        }
        return Handle(server, sidecar, thread)
    }

    /** Exposed for tests so they can hand a fixed token in. */
    internal fun handleConnectionForTest(
        client: Socket,
        token: String,
        onOpen: (Path) -> Unit,
        onFocus: () -> Unit,
        onQuit: () -> Unit = {},
        onRestart: (asker: String?) -> String? = { "this nop cannot restart itself" },
    ) = handleConnection(client, token, onOpen, onFocus, onQuit, onRestart)

    private fun handleConnection(
        client: Socket,
        token: String,
        onOpen: (Path) -> Unit,
        onFocus: () -> Unit,
        onQuit: () -> Unit,
        onRestart: (asker: String?) -> String?,
    ) {
        client.use { sock ->
            sock.soTimeout = READ_TIMEOUT_MS
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                val writer = PrintWriter(sock.getOutputStream().writer(StandardCharsets.UTF_8), true)
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.split(' ', limit = 3)
                    if (parts.size < 2 || parts[0] != token) {
                        writer.println("ERR auth")
                        break
                    }
                    when (parts[1]) {
                        "OPEN" -> {
                            val path = parts.getOrNull(2)
                            if (path.isNullOrBlank()) {
                                writer.println("ERR missing path")
                            } else {
                                onOpen(java.nio.file.Paths.get(path))
                                writer.println("OK")
                            }
                        }
                        "FOCUS" -> {
                            onFocus()
                            writer.println("OK")
                        }
                        "QUIT" -> {
                            // A newer build is taking over. Ack first so it can watch us exit, then
                            // hand off to the app's shutdown.
                            writer.println("OK")
                            onQuit()
                        }
                        "RESTART" -> {
                            // Null is "the user is being asked"; anything else is why they won't be.
                            val refused = onRestart(parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
                            writer.println(if (refused == null) "OK" else "ERR $refused")
                        }
                        else -> writer.println("ERR bad verb")
                    }
                }
            } catch (_: IOException) {
                // Client disconnected mid-request — nothing useful to recover.
            }
        }
    }

    private data class SidecarInfo(val port: Int, val token: String, val pid: Long?, val build: String?)

    private fun readSidecar(sidecar: Path): SidecarInfo? {
        if (!Files.isRegularFile(sidecar)) return null
        val text = runCatching { Files.readString(sidecar) }.getOrNull() ?: return null
        var port: Int? = null
        var token: String? = null
        var pid: Long? = null
        var build: String? = null
        for (line in text.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            when (k) {
                "port" -> port = v.toIntOrNull()
                "token" -> token = v
                "pid" -> pid = v.toLongOrNull()
                "build" -> build = v
            }
        }
        if (port == null || port !in 1..65535 || token.isNullOrBlank()) return null
        return SidecarInfo(port, token, pid, build)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
