package iondrive.nop.agent

import iondrive.nop.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AccountsTest {
    private val originalRoot: Path = Settings.configRoot

    @AfterEach
    fun restoreRoot() {
        Settings.configRoot = originalRoot
    }

    @Test
    fun `an account round-trips through the config file`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val config = AgentConfig(
            accounts = listOf(
                Account("claude-main", Provider.Anthropic, "/homes/cw", model = "claude-opus-5", reasoning = "high"),
                Account("codex-main", Provider.OpenAI, "/homes/cx"),
            ),
        )

        Accounts.save(config)

        assertEquals(config, Accounts.load())
    }

    @Test
    fun `the account nominated to take over round-trips through the config file`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        val config = AgentConfig(
            accounts = listOf(
                Account("claude-main", Provider.Anthropic, "/homes/cw", handoverTo = "codex-main"),
                Account("codex-main", Provider.OpenAI, "/homes/cx"),
            ),
        )

        Accounts.save(config)

        assertEquals("codex-main", Accounts.load().accounts.first().handoverTo)
    }

    /**
     * A config written before this setting existed has no field for it, and must still read as a
     * perfectly good list of accounts that nobody has nominated anything for.
     */
    @Test
    fun `a config from before the setting existed reads as nobody nominated`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Files.createDirectories(Accounts.configFile.parent)
        Files.writeString(
            Accounts.configFile,
            """{"accounts": [{"name": "a", "provider": "anthropic", "home": "/h"}]}""",
        )

        assertNull(Accounts.load().accounts.single().handoverTo)
    }

    @Test
    fun `a nomination resolves to the account it names`() {
        val claude = Account("claude-main", Provider.Anthropic, "/h", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/h2")

        assertEquals(codex, listOf(claude, codex).handoverTarget(claude))
    }

    @Test
    fun `an account nobody nominated anything for hands over to nobody`() {
        val claude = Account("claude-main", Provider.Anthropic, "/h")

        assertNull(listOf(claude).handoverTarget(claude))
    }

    /**
     * The settings dialog clears a nomination when its target is removed, so this is the belt behind
     * that brace: an `agent.json` edited by hand must not be able to arm a handover that cannot
     * happen. Null here puts the choice back in front of the user, where it started.
     */
    @Test
    fun `a nomination naming an account that is gone resolves to nobody`() {
        val claude = Account("claude-main", Provider.Anthropic, "/h", handoverTo = "deleted")

        assertNull(listOf(claude).handoverTarget(claude))
    }

    /** Starting the exhausted account again is the one move that certainly does not help. */
    @Test
    fun `an account cannot hand its work to itself`() {
        val claude = Account("claude-main", Provider.Anthropic, "/h", handoverTo = "claude-main")

        assertNull(listOf(claude).handoverTarget(claude))
    }

    @Test
    fun `a nomination cleared in the active accounts list resolves to nobody even if the running account had one`() {
        val running = Account("claude-main", Provider.Anthropic, "/h", handoverTo = "codex")
        val currentClaude = Account("claude-main", Provider.Anthropic, "/h", handoverTo = null)
        val codex = Account("codex", Provider.OpenAI, "/h2")

        assertNull(listOf(currentClaude, codex).handoverTarget(running))
    }

    @Test
    fun `a nomination set in the active accounts list resolves even if the running account had none`() {
        val running = Account("claude-main", Provider.Anthropic, "/h", handoverTo = null)
        val currentClaude = Account("claude-main", Provider.Anthropic, "/h", handoverTo = "codex")
        val codex = Account("codex", Provider.OpenAI, "/h2")

        assertEquals(codex, listOf(currentClaude, codex).handoverTarget(running))
    }

    @Test
    fun `change listeners are notified on save`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        var notified: AgentConfig? = null
        val unsubscribe = Accounts.addChangeListener { notified = it }

        val config = AgentConfig(listOf(Account("a", Provider.Anthropic, "/h")))
        Accounts.save(config)

        assertEquals(config, notified)
        unsubscribe()

        Accounts.save(AgentConfig())
        assertEquals(config, notified, "unsubscribed listener should not be notified")
    }

    @Test
    fun `providers are stored under stable names`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Accounts.save(AgentConfig(listOf(Account("a", Provider.Anthropic, "/h"))))

        val text = Files.readString(Accounts.configFile)

        assertTrue("\"anthropic\"" in text, "expected the provider id in the file, got: $text")
    }

    @Test
    fun `an unreadable config reads as empty rather than taking the window down`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Files.createDirectories(Accounts.configFile.parent)
        Files.writeString(Accounts.configFile, "{ this is not json")

        val loaded = Accounts.load()

        assertTrue(loaded.accounts.isEmpty())
    }

    @Test
    fun `a saved config replaces the discovered one, so a deleted account stays deleted`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Accounts.save(AgentConfig(accounts = emptyList()))

        assertTrue(
            Accounts.load().accounts.isEmpty(),
            "discovery must only seed the first run — after a save, the file is the list",
        )
    }

    @Test
    fun `saving leaves no temp files behind`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Accounts.save(AgentConfig(listOf(Account("a", Provider.Anthropic, "/h"))))
        Accounts.save(AgentConfig(listOf(Account("b", Provider.OpenAI, "/h2"))))

        val strays = Files.list(Accounts.configFile.parent).use { stream ->
            stream.map { it.fileName.toString() }.filter { it != "agent.json" }.toList()
        }
        assertTrue(strays.isEmpty(), "expected only agent.json, found $strays")
    }

    @Test
    fun `the credential file is the one each CLI actually writes`() {
        assertTrue(
            Account("a", Provider.Anthropic, "/h").credentialFile.endsWith("h/.credentials.json"),
        )
        assertTrue(
            Account("a", Provider.OpenAI, "/h").credentialFile.endsWith("h/.codex/auth.json"),
        )
    }

    /** An antigravity account's credential is the file `agy` reads. See [Antigravity]. */
    @Test
    fun `an antigravity account's credential is the file the CLI reads`(@TempDir tmp: Path) {
        val account = Account("google-one", Provider.Antigravity, tmp.toString())

        assertEquals(
            tmp.resolve(".gemini/antigravity-cli/antigravity-oauth-token"),
            account.credentialFile,
        )
    }

    @Test
    fun `the CLIs' own default logins are offered`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(tmp.resolve(".claude/.credentials.json"), "{}")
        Files.createDirectories(tmp.resolve(".codex"))
        Files.writeString(tmp.resolve(".codex/auth.json"), "{}")

        val found = withHome(tmp) { Accounts.discover() }

        assertEquals(setOf("claude", "codex"), found.map { it.name }.toSet())
    }

    @Test
    fun `a CLI that has never been signed in is not offered as an account`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".claude"))

        assertTrue(withHome(tmp) { Accounts.discover() }.isEmpty())
    }

    @Test
    fun `discovery of nothing is empty, not an error`(@TempDir tmp: Path) {
        assertTrue(withHome(tmp) { Accounts.discover() }.isEmpty())
    }

    @Test
    fun `an untouched config offers the discovered accounts`(@TempDir tmp: Path) {
        Settings.configRoot = tmp.resolve("config")
        Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(tmp.resolve(".claude/.credentials.json"), "{}")

        val loaded = withHome(tmp) { Accounts.load() }

        assertEquals(listOf("claude"), loaded.accounts.map { it.name })
        assertFalse(
            Files.exists(Accounts.configFile),
            "looking must not write: a user who wants none of these should not have to delete a file",
        )
    }

    /** Runs [body] with `user.home` pointed at [home], so discovery looks inside a temp directory. */
    private fun <T> withHome(home: Path, body: () -> T): T {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            return body()
        } finally {
            System.setProperty("user.home", original)
        }
    }
}
