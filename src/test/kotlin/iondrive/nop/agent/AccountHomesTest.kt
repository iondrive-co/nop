package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AccountHomesTest {

    private fun account(home: Path) = Account("claude-alpha", Provider.Anthropic, home.toString())

    @Test
    fun `only a home outside nop's folder that is not a CLI default is offered a move`(@TempDir tmp: Path) {
        val homes = tmp.resolve("data/homes")
        val user = tmp.resolve("user")
        assertTrue(AccountHomes.offersMove(account(tmp.resolve("elsewhere/claude-alpha")), homes, user))
        assertFalse(AccountHomes.offersMove(account(homes.resolve("claude-alpha")), homes, user))
        // Where a plain `claude` and a plain `codex` keep the user's own login.
        assertFalse(AccountHomes.offersMove(account(user.resolve(".claude")), homes, user))
        assertFalse(AccountHomes.offersMove(account(user), homes, user))
    }

    @Test
    fun `a move takes the home's contents and leaves a link behind for whatever still uses it`(@TempDir tmp: Path) {
        val old = tmp.resolve("elsewhere/claude-alpha")
        Files.createDirectories(old.resolve("projects"))
        Files.writeString(old.resolve("projects/conv.jsonl"), "{}\n")
        val homes = tmp.resolve("data/homes")

        val moved = AccountHomes.moveIntoNop(account(old), homes)

        val to = homes.resolve("claude-alpha")
        assertEquals(to.toString(), moved.home)
        assertEquals("{}\n", Files.readString(to.resolve("projects/conv.jsonl")))
        assertTrue(Files.isSymbolicLink(old))
        assertEquals("{}\n", Files.readString(old.resolve("projects/conv.jsonl")), "the old path still reaches it")
    }

    @Test
    fun `a home whose folder is gone gets a fresh one`(@TempDir tmp: Path) {
        val homes = tmp.resolve("data/homes")
        val moved = AccountHomes.moveIntoNop(account(tmp.resolve("deleted/claude-alpha")), homes)
        assertEquals(homes.resolve("claude-alpha").toString(), moved.home)
        assertTrue(Files.isDirectory(homes.resolve("claude-alpha")))
    }

    @Test
    fun `a move never lands on a home that is already there`(@TempDir tmp: Path) {
        val old = Files.createDirectories(tmp.resolve("elsewhere/claude-alpha"))
        val homes = tmp.resolve("data/homes")
        Files.createDirectories(homes.resolve("claude-alpha"))
        assertThrows(IllegalStateException::class.java) { AccountHomes.moveIntoNop(account(old), homes) }
        assertTrue(Files.isDirectory(old) && !Files.isSymbolicLink(old), "nothing was touched")
    }
}
