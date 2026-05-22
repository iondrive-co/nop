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
