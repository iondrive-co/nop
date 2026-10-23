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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
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

    @Test fun `refreshing unchanged contents does not invalidate the tree`(@TempDir tmp: Path) {
        tmp.resolve("notes.txt").createFile()
        val dir = tmp.toFile()
        val reads = AtomicInteger()
        val refreshed = CountDownLatch(1)
        val listings = DirectoryListings(
            read = { d, previous -> listEntries(d, previous).also { reads.incrementAndGet() } },
            onUi = { publish -> ui.execute {
                publish()
                if (reads.get() >= 2) refreshed.countDown()
            } },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
        )
        onUi { listings.childrenOf(dir) }
        eventually("listed the directory") { listings.child(dir, "notes.txt") != null }
        val version = onUi { listings.version }

        onUi { listings.refresh(setOf(dir.absolutePath)) }
        assertTrue(refreshed.await(5, TimeUnit.SECONDS), "the refreshed listing was not published")
        assertEquals(version, onUi { listings.version })

        onUi { listings.refresh(emptySet()) }
        assertTrue(onUi { listings.version } > version)
        assertNull(onUi { listings.peek(dir) })
    }

    @Test fun `large directories back off without delaying small ones`(@TempDir tmp: Path) {
        val now = AtomicLong()
        val completed = AtomicInteger()
        val large = tmp.resolve("large").toFile()
        val small = tmp.resolve("small").toFile()
        val largeReads = AtomicInteger()
        val smallReads = AtomicInteger()
        val listings = DirectoryListings(
            read = { dir, _ ->
                if (dir == large) {
                    largeReads.incrementAndGet()
                    List(5_000) { TreeChild(File(dir, "item-$it.txt"), isDirectory = false) }
                } else {
                    smallReads.incrementAndGet()
                    emptyList()
                }
            },
            onUi = { publish -> ui.execute { publish(); completed.incrementAndGet() } },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
            nowMs = now::get,
        )
        val view = listings.view()
        onUi { view.show(setOf(large.absolutePath, small.absolutePath)); view.poll() }
        eventually("published both initial reads") { completed.get() == 2 }

        now.set(3_000)
        onUi { view.poll() }
        eventually("refreshed the small directory") { completed.get() == 3 }
        assertEquals(1, largeReads.get())
        assertEquals(2, smallReads.get())

        now.set(15_000)
        onUi { view.poll() }
        eventually("refreshed both after the larger interval") { completed.get() == 5 }
        assertEquals(2, largeReads.get())
        assertEquals(3, smallReads.get())
        onUi { view.poll(force = true) }
        eventually("honoured an explicit refresh before the next poll was due") { completed.get() == 7 }
        assertEquals(3, largeReads.get())
        assertEquals(4, smallReads.get())
        onUi { view.close() }
    }

    @Test fun `a slow directory backs off even when it has few entries`(@TempDir tmp: Path) {
        val now = AtomicLong()
        val completed = AtomicInteger()
        val secondRead = CountDownLatch(1)
        val reads = AtomicInteger()
        val listings = DirectoryListings(
            read = { _, _ ->
                if (reads.incrementAndGet() > 1) secondRead.countDown()
                now.addAndGet(200)
                emptyList()
            },
            onUi = { publish -> ui.execute { publish(); completed.incrementAndGet() } },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
            nowMs = now::get,
        )
        val view = listings.view()
        onUi { view.show(setOf(tmp.toString())); view.poll() }
        eventually("published the slow read") { completed.get() == 1 }

        now.set(3_200)
        onUi { view.poll() }
        assertFalse(secondRead.await(100, TimeUnit.MILLISECONDS), "a slow read repeated too soon")

        now.set(20_200)
        onUi { view.poll() }
        eventually("read again after the cost based delay") { completed.get() == 2 }
        onUi { view.close() }
    }

    @Test fun `hiding a view drops follow-up reads and link lookups`(@TempDir tmp: Path) {
        val started = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val reads = AtomicInteger()
        val looks = AtomicInteger()
        val listings = DirectoryListings(
            read = { dir, _ ->
                reads.incrementAndGet()
                started.countDown()
                answer.await()
                listOf(TreeChild(File(dir, "linked"), false, TreeChild.Link.Pending))
            },
            resolve = { looks.incrementAndGet(); true },
            onUi = { publish -> ui.execute { publish(); completed.countDown() } },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
        )
        val view = listings.view()
        onUi { view.show(setOf(tmp.toString())); view.poll() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        onUi { view.poll(); view.close() }
        answer.countDown()
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        onUi { view.poll() }

        assertEquals(1, reads.get())
        assertEquals(0, looks.get())
    }

    @Test fun `link results publish together without waiting for a stuck link`(@TempDir tmp: Path) {
        val completed = AtomicInteger()
        val batch = AtomicReference<(() -> Unit)?>()
        val listings = DirectoryListings(
            read = { dir, _ -> listOf("alpha", "bravo", "stuck").map {
                TreeChild(File(dir, it), false, TreeChild.Link.Pending)
            } },
            resolve = { file -> if (file.name == "stuck") never.await(); file.name == "bravo" },
            onUi = { publish -> ui.execute { publish(); completed.incrementAndGet() } },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
            scheduleLinkBatch = { batch.set(it) },
        )
        val dir = tmp.toFile()
        onUi { listings.childrenOf(dir) }
        eventually("collected both responsive links") { completed.get() == 3 && batch.get() != null }
        val version = onUi { listings.version }
        assertEquals(TreeChild.Link.Pending, listings.child(dir, "alpha")?.link)

        onUi { batch.getAndSet(null)!!.invoke() }
        eventually("published both responsive links") {
            listings.child(dir, "alpha")?.link == TreeChild.Link.Resolved &&
                listings.child(dir, "bravo")?.isDirectory == true
        }
        assertEquals(version + 1, onUi { listings.version })
        assertEquals(TreeChild.Link.Pending, listings.child(dir, "stuck")?.link)
    }

    @Test fun `a deferred link result cannot restore a removed entry`(@TempDir tmp: Path) {
        val contents = AtomicReference(listOf(TreeChild(tmp.resolve("link").toFile(), false, TreeChild.Link.Pending)))
        val completed = AtomicInteger()
        val batch = AtomicReference<(() -> Unit)?>()
        val listings = DirectoryListings(
            read = { _, _ -> contents.get() },
            resolve = { true },
            onUi = { publish -> ui.execute { publish(); completed.incrementAndGet() } },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
            scheduleLinkBatch = { batch.set(it) },
        )
        val dir = tmp.toFile()
        onUi { listings.childrenOf(dir) }
        eventually("queued the link result") { completed.get() == 2 && batch.get() != null }
        contents.set(listOf(TreeChild(tmp.resolve("new.txt").toFile(), false)))
        onUi { listings.refresh(setOf(dir.absolutePath)) }
        eventually("published the replacement listing") { completed.get() == 3 }
        onUi { batch.getAndSet(null)!!.invoke() }
        eventually("finished applying the deferred result") { completed.get() == 4 }

        assertEquals(listOf("new.txt"), onUi { listings.peek(dir) }!!.map { it.name })
    }

    @Test fun `a link batch cannot overwrite a newer directory scan`(@TempDir tmp: Path) {
        val link = TreeChild(tmp.resolve("link").toFile(), false, TreeChild.Link.Pending)
        val contents = AtomicReference(listOf(link))
        val callbacks = LinkedBlockingDeque<() -> Unit>()
        val batch = AtomicReference<(() -> Unit)?>()
        val listings = DirectoryListings(
            read = { _, _ -> contents.get() },
            resolve = { true },
            onUi = { callbacks.addLast(it) },
            loadingAfterMs = 60_000,
            notRespondingAfterMs = 60_000,
            scheduleLinkBatch = { batch.set(it) },
        )
        fun callback(): () -> Unit = callbacks.poll(5, TimeUnit.SECONDS) ?: fail("no worker result arrived")
        val dir = tmp.toFile()
        onUi { listings.childrenOf(dir) }
        val initialRead = callback()
        onUi { initialRead() }
        val resolved = callback()
        onUi { resolved(); batch.getAndSet(null)!!.invoke() }
        val staleBatch = callback()

        contents.set(listOf(link, TreeChild(tmp.resolve("added.txt").toFile(), false)))
        onUi { listings.refresh(setOf(dir.absolutePath)) }
        val freshRead = callback()
        onUi { freshRead(); staleBatch(); batch.getAndSet(null)!!.invoke() }
        val rebasedBatch = callback()
        onUi { rebasedBatch() }

        assertEquals(setOf("link", "added.txt"), onUi { listings.peek(dir) }!!.map { it.name }.toSet())
        assertEquals(true, listings.child(dir, "link")?.isDirectory)
        assertEquals(TreeChild.Link.Resolved, listings.child(dir, "link")?.link)
    }

    @Test fun `inactive listings are evicted by weight while active views stay pinned`(@TempDir tmp: Path) {
        val listings = DirectoryListings(
            read = { dir, _ -> listOf(TreeChild(File(dir, "item.txt"), false)) },
            onUi = { ui.execute(it) },
            maxInactiveChildren = 1,
            maxInactiveDirectories = 1,
        )
        val active = tmp.resolve("active").toFile()
        val older = tmp.resolve("older").toFile()
        val newer = tmp.resolve("newer").toFile()
        val firstView = onUi { listings.view().also { it.show(setOf(active.absolutePath)); it.poll() } }
        val secondView = onUi { listings.view().also { it.show(setOf(active.absolutePath)) } }
        eventually("listed the active directory") { onUi { listings.peek(active) } != null }
        onUi { listings.childrenOf(older) }
        eventually("listed the older directory") { onUi { listings.peek(older) } != null }
        onUi { listings.childrenOf(newer) }
        eventually("listed the newer directory") { onUi { listings.peek(newer) } != null }

        assertNull(onUi { listings.peek(older) })
        assertTrue(onUi { listings.peek(active) } != null)
        onUi { firstView.close() }
        assertTrue(onUi { listings.peek(active) } != null, "the second view still needs the listing")
        onUi { secondView.close() }
        assertNull(onUi { listings.peek(newer) })
        assertTrue(onUi { listings.peek(active) } != null)
    }

    @Test fun `large active listings can exceed the inactive budget but are released on close`(@TempDir tmp: Path) {
        val listings = DirectoryListings(
            read = { dir, _ -> List(3) { TreeChild(File(dir, "item-$it.txt"), false) } },
            onUi = { ui.execute(it) },
            maxInactiveChildren = 1,
        )
        val dir = tmp.toFile()
        val view = onUi { listings.view().also { it.show(setOf(dir.absolutePath)); it.poll() } }
        eventually("listed the active directory") { onUi { listings.peek(dir)?.size } == 3 }
        onUi { view.close() }
        assertNull(onUi { listings.peek(dir) })
    }

    @Test fun `retaining an ancestor protects its cache without polling it`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val child = tmp.resolve("child").toFile()
        val rootReads = AtomicInteger()
        val listings = DirectoryListings(
            read = { dir, _ ->
                if (dir == root) rootReads.incrementAndGet()
                listOf(TreeChild(File(dir, "item.txt"), false))
            },
            onUi = { ui.execute(it) },
            maxInactiveChildren = 0,
        )
        val view = onUi { listings.view().also {
            it.show(setOf(child.absolutePath), keep = setOf(root.absolutePath, child.absolutePath))
            listings.childrenOf(root)
            it.poll()
        } }
        eventually("listed the ancestor and visible child") {
            onUi { listings.peek(root) != null && listings.peek(child) != null }
        }
        onUi { view.poll(force = true) }
        assertEquals(1, rootReads.get())
        onUi { view.show(emptySet(), keep = setOf(root.absolutePath)) }
        assertTrue(onUi { listings.peek(root) } != null)
        // A read already in flight can finish, but its result is subject to the same inactive budget.
        eventually("released the inactive child") { onUi { listings.peek(child) } == null }
        onUi { view.close() }
        assertNull(onUi { listings.peek(root) })
    }

    @Test fun `project eviction preserves active views and reuses recent inactive projects`(@TempDir tmp: Path) {
        val projects = ProjectDirectoryListings(maxInactiveProjects = 1)
        val first = onUi { projects.forProject(tmp.resolve("first")) }
        val view = onUi { first.view() }
        val second = onUi { projects.forProject(tmp.resolve("second")) }
        assertSame(second, onUi { projects.forProject(tmp.resolve("second")) })
        onUi { projects.forProject(tmp.resolve("third")) }
        assertSame(first, onUi { projects.forProject(tmp.resolve("first")) })
        val reopenedSecond = onUi { projects.forProject(tmp.resolve("second")) }
        assertNotSame(second, reopenedSecond)
        onUi { view.close() }
        assertSame(first, onUi { projects.forProject(tmp.resolve("first")) })
        assertNotSame(reopenedSecond, onUi { projects.forProject(tmp.resolve("second")) })
    }
}
