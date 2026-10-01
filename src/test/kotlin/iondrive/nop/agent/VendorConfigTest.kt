package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Claude Code decides whether to run its first-run flow from `hasCompletedOnboarding` in its own
 * config, not from whether it holds a usable token. A home that has only ever run the CLI headless
 * has a good `.credentials.json` and no such flag, so launching it interactively would ask the user
 * to sign in to an account that is already signed in.
 *
 * These guard how narrow nop's write is as much as the write itself: it is someone else's config file, and
 * nop writes two keys into it and only when they are missing — that, and the diff sidebar's default.
 */
class VendorConfigTest {

    private fun anthropic(home: Path, signedIn: Boolean = true): Account {
        Files.createDirectories(home)
        if (signedIn) Files.writeString(home.resolve(".credentials.json"), """{"claudeAiOauth":{}}""")
        return Account("acct", Provider.Anthropic, home.toString())
    }

    private fun config(home: Path): Map<String, String> =
        Json.parseToJsonElement(Files.readString(home.resolve(".claude.json"))).jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `a signed-in account is marked as onboarded, so the TUI does not ask again`(@TempDir tmp: Path) {
        val account = anthropic(tmp)

        VendorConfig.prepareForInteractive(account)

        assertEquals("true", config(tmp)["hasCompletedOnboarding"])
    }

    /**
     * An account that has genuinely never signed in should get the real flow — claiming onboarding
     * is done would drop the user into a TUI with no way to sign in.
     */
    @Test
    fun `an account with no credentials is left to its own first-run flow`(@TempDir tmp: Path) {
        val account = anthropic(tmp, signedIn = false)

        VendorConfig.prepareForInteractive(account)

        assertFalse(Files.exists(tmp.resolve(".claude.json")))
    }

    @Test
    fun `every other key the CLI put there survives`(@TempDir tmp: Path) {
        val account = anthropic(tmp)
        Files.writeString(
            tmp.resolve(".claude.json"),
            """{"userID":"u-1","oauthAccount":{"emailAddress":"x@y.z"},"theme":"dark"}""",
        )

        VendorConfig.prepareForInteractive(account)

        val after = Json.parseToJsonElement(Files.readString(tmp.resolve(".claude.json"))).jsonObject
        assertEquals("u-1", after["userID"]!!.jsonPrimitive.content)
        assertEquals("dark", after["theme"]!!.jsonPrimitive.content)
        assertTrue(after["oauthAccount"] != null, "a nested object the CLI owns was dropped")
        assertEquals("true", after["hasCompletedOnboarding"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a config that already says so is not rewritten`(@TempDir tmp: Path) {
        val account = anthropic(tmp)
        val file = tmp.resolve(".claude.json")
        Files.writeString(file, """{"hasCompletedOnboarding":true,"diffSidebarOpen":false,"theme":"dark"}""")
        val before = Files.readString(file)

        VendorConfig.prepareForInteractive(account)

        assertEquals(before, Files.readString(file), "an untouched config should stay byte-identical")
    }

    /**
     * Claude Code opens its diff sidebar by itself in any wide terminal on a repository, which an
     * agent pane always is — beside nop's own Diff tab, and at the expense of the conversation.
     */
    @Test
    fun `the CLI's diff sidebar starts closed`(@TempDir tmp: Path) {
        val account = anthropic(tmp)
        Files.writeString(tmp.resolve(".claude.json"), """{"hasCompletedOnboarding":true}""")

        VendorConfig.prepareForInteractive(account)

        assertEquals("false", config(tmp)["diffSidebarOpen"])
    }

    /** `/diff` writes the user's own answer to the same key. nop's default never overrides it. */
    @Test
    fun `a sidebar the user opened with diff stays their choice`(@TempDir tmp: Path) {
        val account = anthropic(tmp)
        Files.writeString(tmp.resolve(".claude.json"), """{"hasCompletedOnboarding":true,"diffSidebarOpen":true}""")

        VendorConfig.prepareForInteractive(account)

        assertEquals("true", config(tmp)["diffSidebarOpen"])
    }

    /** Overwriting something unparseable would turn a puzzling prompt into lost configuration. */
    @Test
    fun `an unreadable config is left alone rather than replaced`(@TempDir tmp: Path) {
        val account = anthropic(tmp)
        val file = tmp.resolve(".claude.json")
        Files.writeString(file, "{ this is not json")

        VendorConfig.prepareForInteractive(account)

        assertEquals("{ this is not json", Files.readString(file))
    }

    @Test
    fun `codex needs none of this and gets none of it`(@TempDir tmp: Path) {
        val home = tmp.resolve("codex")
        Files.createDirectories(home.resolve(".codex"))
        Files.writeString(home.resolve(".codex/auth.json"), """{"tokens":{"access_token":"t"}}""")

        VendorConfig.prepareForInteractive(Account("c", Provider.OpenAI, home.toString()))

        assertFalse(Files.exists(home.resolve(".claude.json")))
    }

    @Test
    fun `the config it writes is readable only by its owner`(@TempDir tmp: Path) {
        val account = anthropic(tmp)

        VendorConfig.prepareForInteractive(account)

        val mode = java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(tmp.resolve(".claude.json")),
        )
        assertEquals("rw-------", mode)
    }

    private fun openai(home: Path, signedIn: Boolean = true): Account {
        Files.createDirectories(home.resolve(".codex"))
        if (signedIn) Files.writeString(home.resolve(".codex/auth.json"), """{"tokens":{"access_token":"t"}}""")
        return Account("c", Provider.OpenAI, home.toString())
    }

    @Test
    fun `a signed-in codex account gets service_tier default and fast_default_opt_out`(@TempDir tmp: Path) {
        val account = openai(tmp)

        VendorConfig.prepareForInteractive(account)

        val config = Files.readString(tmp.resolve(".codex/config.toml"))
        assertTrue("service_tier = \"default\"" in config)
        assertTrue("[notice]" in config)
        assertTrue("fast_default_opt_out = true" in config)
    }

    @Test
    fun `an un-signed-in codex account is left alone`(@TempDir tmp: Path) {
        val account = openai(tmp, signedIn = false)

        VendorConfig.prepareForInteractive(account)

        assertFalse(Files.exists(tmp.resolve(".codex/config.toml")))
    }

    @Test
    fun `a codex config with an existing service_tier keeps its tier`(@TempDir tmp: Path) {
        val account = openai(tmp)
        val file = tmp.resolve(".codex/config.toml")
        Files.writeString(file, "model = \"gpt-6.1-sol\"\nservice_tier = \"priority\"\n")

        VendorConfig.prepareForInteractive(account)

        val config = Files.readString(file)
        assertTrue("service_tier = \"priority\"" in config)
        assertFalse("service_tier = \"default\"" in config)
        assertTrue("fast_default_opt_out = true" in config)
    }

    @Test
    fun `a codex config with existing tables inserts service_tier before the first table`(@TempDir tmp: Path) {
        val account = openai(tmp)
        val file = tmp.resolve(".codex/config.toml")
        Files.writeString(
            file,
            """
            model = "gpt-6.1-sol"
            [projects."/home/miles/p"]
            trust_level = "trusted"
            """.trimIndent() + "\n",
        )

        VendorConfig.prepareForInteractive(account)

        val lines = Files.readAllLines(file)
        val serviceTierIdx = lines.indexOfFirst { "service_tier = \"default\"" in it }
        val firstTableIdx = lines.indexOfFirst { it.startsWith("[") }
        assertTrue(serviceTierIdx >= 0, "service_tier should be present")
        assertTrue(serviceTierIdx < firstTableIdx, "service_tier should precede the first table header")
    }

    @Test
    fun `a codex config that already has both is not rewritten`(@TempDir tmp: Path) {
        val account = openai(tmp)
        val file = tmp.resolve(".codex/config.toml")
        val content = """
            model = "gpt-6.1-sol"
            service_tier = "default"
            [notice]
            fast_default_opt_out = true
        """.trimIndent() + "\n"
        Files.writeString(file, content)

        VendorConfig.prepareForInteractive(account)

        assertEquals(content, Files.readString(file), "an untouched codex config should stay byte-identical")
    }

    @Test
    fun `the codex config it writes is readable only by its owner`(@TempDir tmp: Path) {
        val account = openai(tmp)

        VendorConfig.prepareForInteractive(account)

        val mode = java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(tmp.resolve(".codex/config.toml")),
        )
        assertEquals("rw-------", mode)
    }
}
