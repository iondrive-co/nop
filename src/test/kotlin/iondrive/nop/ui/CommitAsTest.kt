package iondrive.nop.ui

import iondrive.nop.git.CommitIdentity
import iondrive.nop.git.IdentityChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitAsTest {
    private val repoOwn = CommitIdentity("Dev", "dev@example.test")
    private val other = CommitIdentity("Dev", "dev@other.test")

    @Test
    fun `no pick commits as the repository's default`() {
        val choices = listOf(IdentityChoice(repoOwn, IdentityChoice.REPO), IdentityChoice(other, IdentityChoice.HISTORY))
        assertEquals(repoOwn, commitIdentity(choices, picked = null))
        assertEquals(other, commitIdentity(choices, picked = other))
        assertNull(commitIdentity(emptyList(), picked = null), "nothing loaded yet leaves it to git")
    }

    @Test
    fun `saving is offered unless the identity already is the repository's own`() {
        val saved = listOf(IdentityChoice(repoOwn, IdentityChoice.REPO), IdentityChoice(other, IdentityChoice.HISTORY))
        assertFalse(canSaveIdentity(saved, repoOwn))
        assertTrue(canSaveIdentity(saved, other))

        // A default found in the global config can be pinned to this repository.
        val global = listOf(IdentityChoice(repoOwn, IdentityChoice.GLOBAL))
        assertTrue(canSaveIdentity(global, repoOwn))
        assertFalse(canSaveIdentity(global, null))
    }

    @Test
    fun `a typed identity needs a name and an email git can store`() {
        assertNull(identityError("Dev", "dev@example.test"))
        assertNotNull(identityError("", "dev@example.test"))
        assertNotNull(identityError("Dev", ""))
        assertNotNull(identityError("Dev <x>", "dev@example.test"))
        assertNotNull(identityError("Dev", "dev@example.test\nextra"))
    }
}
