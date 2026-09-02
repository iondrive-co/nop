package iondrive.nop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class GitOpTest {
    @Test
    fun `runGitOp returns null on success`() = runBlocking {
        assertNull(runGitOp("Commit failed") { /* succeeds */ })
    }

    @Test
    fun `runGitOp maps a failure to a GitOpError instead of throwing`() = runBlocking {
        val err = runGitOp("Commit failed") { throw RuntimeException("object too large to add") }
        assertEquals(GitOpError("Commit failed", "object too large to add"), err)
    }

    @Test
    fun `runGitOp rethrows CancellationException so cancellation still propagates`() {
        // A cancelled op (e.g. switching projects mid-commit) must not be reported as a failure.
        assertThrows(CancellationException::class.java) {
            runBlocking { runGitOp("Commit failed") { throw CancellationException("switched project") } }
        }
    }

    @Test
    fun `userMessage joins the cause chain and dedups`() {
        val wrapped = RuntimeException("could not add file", IOException("disk is full"))
        assertEquals("could not add file\ndisk is full", wrapped.userMessage())
    }

    @Test
    fun `userMessage falls back to the class name when no message is present`() {
        assertEquals("IllegalStateException", IllegalStateException().userMessage())
    }

    @Test
    fun `changeListMovedNotice names the count and lists the paths that turned up`() {
        val notice = changeListMovedNotice(setOf("src/b.kt", "src/a.kt"))
        assertEquals("Nothing committed — the change list moved", notice.title)
        assertTrue(notice.detail.startsWith("2 changes appeared"), "actual: ${notice.detail}")
        assertTrue(notice.detail.endsWith("src/a.kt\nsrc/b.kt"), "sorted paths last: ${notice.detail}")
    }

    @Test
    fun `changeListMovedNotice says how many paths it left out`() {
        val notice = changeListMovedNotice((1..20).map { "f$it.txt" })
        assertEquals(12, notice.detail.lines().count { it.endsWith(".txt") }, "the list is capped")
        assertTrue(notice.detail.endsWith("…and 8 more"), "actual: ${notice.detail}")
    }

    @Test
    fun `changeListMovedNotice speaks of one change in the singular`() {
        assertTrue(changeListMovedNotice(listOf("only.txt")).detail.startsWith("1 change appeared"))
    }
}
