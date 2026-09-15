package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LoginTest {

    private fun account(provider: Provider, home: Path) =
        Account("acct", provider, home.toString())

    @Test
    fun `the login runs in the account's own home, not the machine owner's`(@TempDir tmp: Path) {
        assertEquals(
            mapOf("CLAUDE_CONFIG_DIR" to tmp.toString()),
            Login.env(account(Provider.Anthropic, tmp)),
        )
        assertEquals(
            mapOf("HOME" to tmp.toString()),
            Login.env(account(Provider.OpenAI, tmp)),
        )
    }

    @Test
    fun `claude signs in through its auth subcommand, not by starting a session`(@TempDir tmp: Path) {
        val command = Login.command(account(Provider.Anthropic, tmp))

        // Bare `claude` starts coding as whoever the stale token in the config dir belonged to.
        assertEquals(listOf("auth", "login"), command.drop(1))
    }

    @Test
    fun `codex logs out first, so a half-finished session can't make the login a no-op`(@TempDir tmp: Path) {
        val command = Login.command(account(Provider.OpenAI, tmp))

        val script = command.last()
        assertTrue("logout" in script, "expected a logout before the login: $script")
        assertTrue(script.indexOf("logout") < script.indexOf("login"), script)
    }

    @Test
    fun `starting a login creates the directory the credentials will land in`(@TempDir tmp: Path) {
        val home = tmp.resolve("never-used")
        val account = account(Provider.OpenAI, home)
        assertFalse(Files.exists(home))

        // Both CLIs fail outright on a home that isn't there; an account that has never been
        // signed in doesn't have one yet.
        Login.session(account).dispose()

        assertTrue(Files.isDirectory(account.credentialFile.parent))
    }

    @Test
    fun `signing out deletes only that account's credential file`(@TempDir tmp: Path) {
        val mine = account(Provider.Anthropic, tmp.resolve("mine"))
        val theirs = account(Provider.Anthropic, tmp.resolve("theirs"))
        for (a in listOf(mine, theirs)) {
            Files.createDirectories(a.credentialFile.parent)
            Files.writeString(a.credentialFile, "{}")
        }

        Login.logOut(mine)

        assertFalse(Login.hasCredentials(mine))
        assertTrue(Login.hasCredentials(theirs))
    }

    @Test
    fun `signing out an account that was never signed in is not an error`(@TempDir tmp: Path) {
        Login.logOut(account(Provider.OpenAI, tmp.resolve("nothing-here")))
    }
}
