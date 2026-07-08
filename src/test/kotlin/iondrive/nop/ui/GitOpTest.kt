package iondrive.nop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
}
