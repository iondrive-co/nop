package iondrive.nop.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DirectoryListingsTest {
    // Every change goes through one thread, as it goes through the UI thread in nop.
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }

    // Released at the end, so nothing a test left waiting outlives it.
    private val never = CountDownLatch(1)

    @AfterEach fun stop() {
        never.countDown()
        ui.shutdownNow()
    }

    private fun listings(
        read: (File, List<TreeChild>?) -> List<TreeChild> = ::listEntries,
        resolve: (File) -> Boolean? = ::linkTargetIsDirectory,
        loadingAfterMs: Long = 300,
        notRespondingAfterMs: Long = 2_000,
    ) = DirectoryListings(read, resolve, onUi = { ui.execute(it) }, loadingAfterMs, notRespondingAfterMs)

    private fun <T> onUi(block: () -> T): T = ui.submit(Callable(block)).get(5, TimeUnit.SECONDS)

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) {
            if (System.nanoTime() > deadline) fail("never $what")
            Thread.sleep(10)
        }
    }

    private fun DirectoryListings.child(dir: File, name: String): TreeChild? =
        onUi { peek(dir) }?.singleOrNull { it.file.name == name }

    @Test fun `a directory that does not answer is never waited on`(@TempDir tmp: Path) {
        tmp.resolve("a.txt").createFile()
        val dir = tmp.toFile()
        val answer = CountDownLatch(1)
        val listings = listings(
            read = { d, previous -> answer.await(); listEntries(d, previous) },
            loadingAfterMs = 20,
            notRespondingAfterMs = 60,
        )

        val started = System.nanoTime()
        assertNull(onUi { listings.childrenOf(dir) })
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1), "asking for a listing waited on the disk")

        eventually("said the directory is not responding") {
            onUi { listings.childrenOf(dir) } == DirectoryListings.Listing.Pending(notResponding = true)
        }
        answer.countDown()
        eventually("showed the directory once it answered") {
            (onUi { listings.childrenOf(dir) } as? DirectoryListings.Listing.Ready)?.children?.map { it.file.name } == listOf("a.txt")
        }
    }

    @Test fun `a symlink is listed without being followed and looked behind afterwards`(@TempDir tmp: Path) {
        tmp.resolve("real").createDirectories()
        Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("real"))
        val dir = tmp.toFile()
        val answer = CountDownLatch(1)
        val listings = listings(resolve = { answer.await(); linkTargetIsDirectory(it) })

        onUi { listings.childrenOf(dir) }
        eventually("listed the directory") { listings.child(dir, "link") != null }
        assertEquals(TreeChild(tmp.resolve("link").toFile(), isDirectory = false, link = TreeChild.Link.Pending), listings.child(dir, "link"))
        assertEquals(TreeChild(tmp.resolve("real").toFile(), isDirectory = true), listings.child(dir, "real"))

        answer.countDown()
        eventually("learned the link leads to a directory") {
            listings.child(dir, "link") == TreeChild(tmp.resolve("link").toFile(), isDirectory = true, link = TreeChild.Link.Resolved)
        }
    }

    @Test fun `a link to nothing is broken`(@TempDir tmp: Path) {
        Files.createSymbolicLink(tmp.resolve("gone"), tmp.resolve("not-there"))
        val dir = tmp.toFile()
        val listings = listings()

        onUi { listings.childrenOf(dir) }
        eventually("marked the link broken") { listings.child(dir, "gone")?.link == TreeChild.Link.Broken }
        assertEquals(false, listings.child(dir, "gone")?.isDirectory)
    }

    @Test fun `a link that never answers holds up only its own row`(@TempDir tmp: Path) {
        tmp.resolve("real").createDirectories()
        Files.createSymbolicLink(tmp.resolve("stuck"), tmp.resolve("real"))
        Files.createSymbolicLink(tmp.resolve("fine"), tmp.resolve("real"))
        val dir = tmp.toFile()
        val reads = AtomicInteger()
        val stuckLooks = AtomicInteger()
        val listings = listings(
            read = { d, previous -> reads.incrementAndGet(); listEntries(d, previous) },
            resolve = { link ->
                if (link.name == "stuck") {
                    stuckLooks.incrementAndGet()
                    never.await()
                }
                linkTargetIsDirectory(link)
            },
        )

        onUi { listings.childrenOf(dir) }
        eventually("resolved the link that answers") { listings.child(dir, "fine")?.link == TreeChild.Link.Resolved }

        // A refresh lists the directory again, and does not send a second worker after the stuck link.
        tmp.resolve("new.txt").createFile()
        onUi { listings.refresh(setOf(dir.absolutePath)) }
        eventually("listed the directory again") { reads.get() == 2 && listings.child(dir, "new.txt") != null }
        assertEquals(TreeChild.Link.Pending, listings.child(dir, "stuck")?.link)
        assertEquals(TreeChild.Link.Resolved, listings.child(dir, "fine")?.link)
        assertEquals(1, stuckLooks.get())
    }

    @Test fun `a directory that cannot be read says so`(@TempDir tmp: Path) {
        val notADirectory = tmp.resolve("file.txt").createFile().toFile()
        val listings = listings()

        onUi { listings.childrenOf(notADirectory) }
        eventually("gave up on it") { onUi { listings.childrenOf(notADirectory) } is DirectoryListings.Listing.Failed }
    }

    @Test fun `a refresh forgets the directories it is not told to keep`(@TempDir tmp: Path) {
        val kept = tmp.resolve("kept").createDirectories().toFile()
        val closed = tmp.resolve("closed").createDirectories().toFile()
        val listings = listings()

        onUi { listings.childrenOf(kept); listings.childrenOf(closed) }
        eventually("listed both") { onUi { listings.peek(kept) != null && listings.peek(closed) != null } }

        onUi { listings.refresh(setOf(kept.absolutePath)) }
        assertNull(onUi { listings.peek(closed) })
        assertIs<List<TreeChild>>(onUi { listings.peek(kept) })
    }
}
