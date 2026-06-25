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
}
