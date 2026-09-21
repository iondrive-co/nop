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
    fun `providers are stored under the names chad used`(@TempDir tmp: Path) {
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

    /**
     * The seed list is what the user curated, not what is lying around. Scanning the home
     * directories was the obvious approach and the wrong one: those hold every account ever made,
     * so the picker opened on fifteen entries of which four were real.
     */
    @Test
    fun `discovery takes the accounts chad had configured`(@TempDir tmp: Path) {
        chadConfig(
            tmp,
            """
            {"accounts": {
              "claude-main": {"provider": "anthropic", "key": "encrypted", "model": "default", "reasoning": "default"},
              "codex-main": {"provider": "openai", "key": "encrypted", "model": "gpt-5.5", "reasoning": "high"}
            }}
            """.trimIndent(),
        )
        // A home that exists but was never in the config — an abandoned experiment.
        Files.createDirectories(tmp.resolve(".chad/claude-configs/left-over"))
        Files.writeString(tmp.resolve(".chad/claude-configs/left-over/.credentials.json"), "{}")

        val found = withHome(tmp) { Accounts.discover() }

        assertEquals(listOf("claude-main", "codex-main"), found.map { it.name })
        assertEquals(
            tmp.resolve(".chad/claude-configs/claude-main").toString(),
            found.first { it.name == "claude-main" }.home,
        )
        assertEquals(
            tmp.resolve(".chad/codex-homes/codex-main").toString(),
            found.first { it.name == "codex-main" }.home,
        )
    }

    @Test
    fun `a configured model and reasoning come across, and the default choice means neither`(@TempDir tmp: Path) {
        chadConfig(
            tmp,
            """
            {"accounts": {
              "plain": {"provider": "anthropic", "model": "default", "reasoning": "default"},
              "tuned": {"provider": "openai", "model": "gpt-5.5", "reasoning": "high"}
            }}
            """.trimIndent(),
        )

        val found = withHome(tmp) { Accounts.discover() }.associateBy { it.name }

        assertNull(found["plain"]!!.model)
        assertNull(found["plain"]!!.reasoning)
        assertEquals("gpt-5.5", found["tuned"]!!.model)
        assertEquals("high", found["tuned"]!!.reasoning)
    }

    /**
     * chad configured providers nop does not launch — qwen, kimi, and a local llama-server. Listing
     * one and then refusing to launch it would be worse than leaving it out.
     */
    @Test
    fun `an account for a provider nop cannot run is left out`(@TempDir tmp: Path) {
        chadConfig(
            tmp,
            """
            {"accounts": {
              "qwen-one": {"provider": "qwen"},
              "claude-one": {"provider": "anthropic"}
            }}
            """.trimIndent(),
        )

        assertEquals(listOf("claude-one"), withHome(tmp) { Accounts.discover() }.map { it.name })
    }

    /**
     * Antigravity was one of those until `agy` moved its login out of the shared OS-keyring slot and
     * into a file under its own home. An account chad configured now comes through pointing at the
     * home chad made for it, which is what lets nop run it without signing it in again.
     */
    @Test
    fun `an antigravity account from chad points at the home chad made for it`(@TempDir tmp: Path) {
        chadConfig(
            tmp,
            """
            {"accounts": {"google-one": {"provider": "antigravity", "model": "gemini-3.1-pro-high"}}}
            """.trimIndent(),
        )

        val found = withHome(tmp) { Accounts.discover() }.single()

        assertEquals("google-one", found.name)
        assertEquals(Provider.Antigravity, found.provider)
        assertEquals(tmp.resolve(".chad/antigravity-homes/google-one").toString(), found.home)
        assertEquals("gemini-3.1-pro-high", found.model)
    }

    /**
     * And its credential is the file `agy` reads, not the copy chad kept beside it — the whole
     * point of the provider being launchable at all. See [Antigravity].
     */
    @Test
    fun `an antigravity account's credential is the file the CLI reads`(@TempDir tmp: Path) {
        val account = Account("google-one", Provider.Antigravity, tmp.toString())

        assertEquals(
            tmp.resolve(".gemini/antigravity-cli/antigravity-oauth-token"),
            account.credentialFile,
        )
    }

    @Test
    fun `an unreadable chad config falls through rather than throwing`(@TempDir tmp: Path) {
        chadConfig(tmp, "{ not json")

        assertTrue(withHome(tmp) { Accounts.discover() }.isEmpty())
    }

    @Test
    fun `with no chad, this machine's own vendor logins are offered`(@TempDir tmp: Path) {
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
        chadConfig(tmp, """{"accounts": {"claude-main": {"provider": "anthropic"}}}""")

        val loaded = withHome(tmp) { Accounts.load() }

        assertEquals(listOf("claude-main"), loaded.accounts.map { it.name })
        assertFalse(
            Files.exists(Accounts.configFile),
            "looking must not write: a user who wants none of these should not have to delete a file",
        )
    }

    private fun chadConfig(home: Path, json: String) {
        Files.createDirectories(home)
        Files.writeString(home.resolve(".chad.conf"), json)
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
