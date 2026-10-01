package iondrive.nop.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SingleInstanceTest {

    @Test
    fun `tryForward returns false when no sidecar exists`(@TempDir tmp: Path) {
        assertFalse(SingleInstance.tryForward(listOf(Paths.get("/some/path")), tmp))
    }

    @Test
    fun `tryForward purges a stale sidecar pointing at a dead port`(@TempDir tmp: Path) {
        val sidecar = tmp.resolve("nop/instance").also {
            Files.createDirectories(it.parent)
            // Port 1 is reserved on Linux; connect will fail fast.
            Files.writeString(it, "port=1\ntoken=abc\npid=99999\n")
        }
        assertFalse(SingleInstance.tryForward(listOf(Paths.get("/p")), tmp))
        assertFalse(Files.exists(sidecar), "stale sidecar should be removed after a failed forward")
    }

    @Test
    fun `bind + tryForward round-trip delivers OPEN paths to the primary`(@TempDir tmp: Path) {
        val received = LinkedBlockingQueue<Path>()
        val handle = SingleInstance.bind(
            configRoot = tmp,
            onOpen = { received.add(it) },
            onFocus = {},
        ) ?: error("bind failed")
        try {
            val ok = SingleInstance.tryForward(
                listOf(Paths.get("/home/u/proj-a"), Paths.get("/home/u/proj-b")),
                tmp,
            )
            assertTrue(ok)
            val a = received.poll(2, TimeUnit.SECONDS)
            val b = received.poll(2, TimeUnit.SECONDS)
            assertEquals(Paths.get("/home/u/proj-a"), a)
            assertEquals(Paths.get("/home/u/proj-b"), b)
        } finally {
            handle.close()
        }
    }

    @Test
    fun `bind + tryForward with no paths triggers FOCUS`(@TempDir tmp: Path) {
        val focused = CountDownLatch(1)
        val handle = SingleInstance.bind(
            configRoot = tmp,
            onOpen = {},
            onFocus = { focused.countDown() },
        ) ?: error("bind failed")
        try {
            val ok = SingleInstance.tryForward(emptyList(), tmp)
            assertTrue(ok)
            assertTrue(focused.await(2, TimeUnit.SECONDS), "FOCUS callback should fire")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `tryForward takes over instead of forwarding when the running build differs`(@TempDir tmp: Path) {
        val quitAsked = CountDownLatch(1)
        val opened = LinkedBlockingQueue<Path>()
        val handle = SingleInstance.bind(
            configRoot = tmp,
            onOpen = { opened.add(it) },
            onFocus = {},
            onQuit = { quitAsked.countDown() },
        ) ?: error("bind failed")
        try {
            // Rewrite the sidecar so it advertises a build this binary will never produce (an older
            // running instance) and a dead pid (so the takeover doesn't wait on a live process).
            val sidecar = tmp.resolve("nop/instance")
            val real = Files.readString(sidecar).lines()
            val port = real.first { it.startsWith("port=") }.removePrefix("port=")
            val token = real.first { it.startsWith("token=") }.removePrefix("token=")
            Files.writeString(sidecar, "port=$port\ntoken=$token\npid=99999\nbuild=OTHER-BUILD\n")

            val forwarded = SingleInstance.tryForward(listOf(Paths.get("/x")), tmp)

            assertFalse(forwarded, "a different build must NOT forward — the caller becomes the new primary")
            assertTrue(quitAsked.await(2, TimeUnit.SECONDS), "the stale primary should be asked to QUIT")
            assertTrue(opened.isEmpty(), "takeover must not deliver OPEN to the old primary")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `tryForward forwards normally when the running build matches`(@TempDir tmp: Path) {
        // bind() and tryForward() run in the same process, so they share an identical build stamp —
        // the everyday "relaunch the same binary" case must still forward (focus the existing window).
        val received = LinkedBlockingQueue<Path>()
        val handle = SingleInstance.bind(tmp, onOpen = { received.add(it) }, onFocus = {}) ?: error("bind failed")
        try {
            assertTrue(SingleInstance.tryForward(listOf(Paths.get("/home/u/proj")), tmp))
            assertEquals(Paths.get("/home/u/proj"), received.poll(2, TimeUnit.SECONDS))
        } finally {
            handle.close()
        }
    }

    @Test
    fun `closing the handle removes the sidecar`(@TempDir tmp: Path) {
        val handle = SingleInstance.bind(tmp, onOpen = {}, onFocus = {}) ?: error("bind failed")
        val sidecar = tmp.resolve("nop/instance")
        assertTrue(Files.exists(sidecar), "bind should create sidecar")
        handle.close()
        assertFalse(Files.exists(sidecar), "close should delete sidecar")
    }

    @Test
    fun `forwarding with a wrong token is rejected`(@TempDir tmp: Path) {
        val received = LinkedBlockingQueue<Path>()
        val handle = SingleInstance.bind(
            configRoot = tmp,
            onOpen = { received.add(it) },
            onFocus = {},
        ) ?: error("bind failed")
        try {
            // Overwrite the sidecar with a wrong token. Primary's port is still correct, so we'll
            // connect, but the server will hang up after the bad-auth response.
            val sidecar = tmp.resolve("nop/instance")
            val real = Files.readString(sidecar)
            val port = real.lines().first { it.startsWith("port=") }.removePrefix("port=")
            Files.writeString(sidecar, "port=$port\ntoken=not-the-real-one\n")

            val ok = SingleInstance.tryForward(listOf(Paths.get("/x")), tmp)
            assertFalse(ok)
            assertTrue(received.isEmpty(), "primary should not have received the OPEN")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `isRunning is true for a live primary and false once it has gone`(@TempDir tmp: Path) {
        assertFalse(SingleInstance.isRunning(tmp), "no sidecar, nothing running")
        val handle = SingleInstance.bind(tmp, onOpen = {}, onFocus = {}) ?: error("bind failed")
        try {
            assertTrue(SingleInstance.isRunning(tmp))
        } finally {
            handle.close()
        }
        assertFalse(SingleInstance.isRunning(tmp))
    }

    @Test
    fun `a sidecar left by a dead primary does not count as running`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("nop"))
        Files.writeString(tmp.resolve("nop/instance"), "port=1\ntoken=abc\npid=2147483000\n")
        assertFalse(SingleInstance.isRunning(tmp))
    }

    @Test
    fun `a restart request reaches the primary with the tab that asked, and starts nothing`(@TempDir tmp: Path) {
        val askers = LinkedBlockingQueue<String>()
        val quit = CountDownLatch(1)
        val handle = SingleInstance.bind(
            configRoot = tmp,
            onOpen = {},
            onFocus = {},
            onQuit = { quit.countDown() },
            onRestart = { asker -> askers.add(asker ?: "(none)"); null },
        ) ?: error("bind failed")
        try {
            assertEquals(SingleInstance.RestartReply.Asked, SingleInstance.requestRestart(tmp, "tab-1"))
            assertEquals(SingleInstance.RestartReply.Asked, SingleInstance.requestRestart(tmp, null))
            assertEquals("tab-1", askers.poll(2, TimeUnit.SECONDS))
            assertEquals("(none)", askers.poll(2, TimeUnit.SECONDS))
            assertFalse(quit.await(200, TimeUnit.MILLISECONDS), "asking is not quitting: the user decides")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `a primary that cannot restart says why`(@TempDir tmp: Path) {
        val handle = SingleInstance.bind(tmp, onOpen = {}, onFocus = {}, onRestart = { "not from its launcher" })
            ?: error("bind failed")
        try {
            assertEquals(SingleInstance.RestartReply.Refused("not from its launcher"), SingleInstance.requestRestart(tmp, null))
        } finally {
            handle.close()
        }
    }

    @Test
    fun `a restart with no nop running is nothing to do`(@TempDir tmp: Path) {
        assertEquals(SingleInstance.RestartReply.NotRunning, SingleInstance.requestRestart(tmp, "tab-1"))
    }

    @Test
    fun `a primary from before restart existed is told apart from one that refused`(@TempDir tmp: Path) {
        // What an older nop answers any verb it does not know.
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                server.accept().use { sock ->
                    sock.getInputStream().bufferedReader().readLine()
                    sock.getOutputStream().write("ERR bad verb\n".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val sidecar = tmp.resolve("nop/instance")
            Files.createDirectories(sidecar.parent)
            Files.writeString(sidecar, "port=${server.localPort}\ntoken=t\npid=${ProcessHandle.current().pid()}\n")

            val reply = SingleInstance.requestRestart(tmp, null)

            assertTrue(reply is SingleInstance.RestartReply.Refused && "older" in reply.why, reply.toString())
        } finally {
            server.close()
            thread.join(2000)
        }
    }
}
