package iondrive.nop.ipc

import java.io.BufferedReader
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
 *
 * Server replies `OK\n` for handled lines, `ERR <reason>\n` otherwise, then closes the socket.
 * The token guards against random localhost processes triggering arbitrary file opens.
 */
object SingleInstance {
    private const val SIDECAR_RELATIVE = "nop/instance"
    private const val LOOPBACK = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 500
    private const val READ_TIMEOUT_MS = 1500

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
     * Binds an ephemeral loopback port and writes the sidecar so subsequent launchers can find
     * us. Returns null on bind failure (rare on 127.0.0.1, but possible when an unrelated
     * service squats on the chosen port between our pick and our use). The accept thread is a
     * daemon so it doesn't keep the JVM alive on shutdown.
     */
    fun bind(
        configRoot: Path,
        onOpen: (Path) -> Unit,
        onFocus: () -> Unit,
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
                "port=${server.localPort}\ntoken=$token\npid=${ProcessHandle.current().pid()}\n",
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
                handleConnection(client, token, onOpen, onFocus)
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
    ) = handleConnection(client, token, onOpen, onFocus)

    private fun handleConnection(
        client: Socket,
        token: String,
        onOpen: (Path) -> Unit,
        onFocus: () -> Unit,
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
                        else -> writer.println("ERR bad verb")
                    }
                }
            } catch (_: IOException) {
                // Client disconnected mid-request — nothing useful to recover.
            }
        }
    }

    private data class SidecarInfo(val port: Int, val token: String)

    private fun readSidecar(sidecar: Path): SidecarInfo? {
        if (!Files.isRegularFile(sidecar)) return null
        val text = runCatching { Files.readString(sidecar) }.getOrNull() ?: return null
        var port: Int? = null
        var token: String? = null
        for (line in text.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            when (k) {
                "port" -> port = v.toIntOrNull()
                "token" -> token = v
            }
        }
        if (port == null || port !in 1..65535 || token.isNullOrBlank()) return null
        return SidecarInfo(port, token)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
