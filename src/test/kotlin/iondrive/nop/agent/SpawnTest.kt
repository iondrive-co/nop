package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the two things a spawn has to get right: the argv that decides how the CLI behaves, and
 * the environment that decides *whose account it runs as*.
 *
 * The environment half is checked against `agent/chad-env-capture.json` — a capture taken from the
 * Python implementation this was ported from, rather than a restatement of what the port is meant
 * to do. Isolation failing is silent: three accounts sharing one login look exactly like three
 * accounts working, right up until the usage of one of them is spent by all three.
 *
 * The argv half is *not* checked against that capture, and shouldn't be. chad drove these CLIs
 * headless (`-p --output-format stream-json`); nop runs their interactive TUIs, which is a
 * deliberate difference, so those assertions are against the design instead.
 */
class SpawnTest {

    private val projectDir = File("/home/dev/nop")

    private fun claude(model: String? = null, reasoning: String? = null) = Account(
        name = "claude-main",
        provider = Provider.Anthropic,
        home = "/home/dev/.chad/claude-configs/claude-main",
        model = model,
        reasoning = reasoning,
    )

    private fun codex(model: String? = null, reasoning: String? = null) = Account(
        name = "codex-main",
        provider = Provider.OpenAI,
        home = "/home/dev/.chad/codex-homes/codex-main",
        model = model,
        reasoning = reasoning,
    )

    @Test
    fun `the environment matches what chad's builder produced for every account and reasoning level`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/agent/chad-env-capture.json"))
            .bufferedReader().readText()
        val capture = Json.parseToJsonElement(text).jsonObject
        val homes = capture["homes"]!!.jsonObject
        val cases = capture["cases"]!!.jsonArray
        assertTrue(cases.size > 20, "the capture should cover every reasoning level of both providers")

        for (element in cases) {
            val case = element.jsonObject
            val providerId = case["provider"]!!.jsonPrimitive.content
            val provider = checkNotNull(Provider.byId(providerId)) { "unknown provider $providerId" }
            val account = Account(
                name = case["account"]!!.jsonPrimitive.content,
                provider = provider,
                home = homes[providerId]!!.jsonPrimitive.content,
                model = case["model"]!!.jsonPrimitive.contentOrNull,
                reasoning = case["reasoning"]!!.jsonPrimitive.contentOrNull,
            )
            val expected = (case["env"] as JsonObject).mapValues { it.value.jsonPrimitive.content }

            val actual = Spawn.command(account, projectDir).env

            assertEquals(expected, actual, "env for ${account.provider.id} reasoning=${account.reasoning}")
        }
    }

    @Test
    fun `a claude run bypasses permissions and carries a session id nop minted`() {
        val command = Spawn.command(claude(), projectDir)

        assertEquals("bypassPermissions", command.argv.after("--permission-mode"))
        val id = command.argv.after("--session-id")
        assertEquals(command.nativeSessionId, id)
        // The id must be a real UUID: it is what names the transcript file the tailer will follow,
        // so the CLI has to accept it and file the session under exactly it.
        assertTrue(
            id!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
            "expected a UUID session id, got $id",
        )
        assertFalse("--resume" in command.argv)
    }

    @Test
    fun `two claude runs never share a session id`() {
        val first = Spawn.command(claude(), projectDir).nativeSessionId
        val second = Spawn.command(claude(), projectDir).nativeSessionId
        assertTrue(first != second, "each run must get its own transcript, not append to the last one")
    }

    @Test
    fun `resuming claude passes the id through and mints no new one`() {
        val command = Spawn.command(claude(), projectDir, resumeId = "abc-123")

        assertEquals("abc-123", command.argv.after("--resume"))
        assertEquals("abc-123", command.nativeSessionId)
        assertFalse("--session-id" in command.argv, "a resume must not also ask for a fresh id")
    }

    @Test
    fun `a default model or reasoning passes no flag at all`() {
        val command = Spawn.command(claude(model = DEFAULT_CHOICE, reasoning = DEFAULT_CHOICE), projectDir)

        assertFalse("--model" in command.argv)
        assertFalse("MAX_THINKING_TOKENS" in command.env)
    }

    @Test
    fun `an explicit claude model and reasoning reach the CLI`() {
        val command = Spawn.command(claude(model = "claude-opus-5", reasoning = "high"), projectDir)

        assertEquals("claude-opus-5", command.argv.after("--model"))
        assertEquals("21000", command.env["MAX_THINKING_TOKENS"])
    }

    @Test
    fun `a codex run bypasses approvals and is pointed at the project`() {
        val command = Spawn.command(codex(), projectDir)

        assertTrue("--dangerously-bypass-approvals-and-sandbox" in command.argv)
        assertEquals(projectDir.absolutePath, command.argv.after("-C"))
        // Codex names its own session, so there is nothing to know until it writes its first line.
        assertEquals(null, command.nativeSessionId)
    }

    @Test
    fun `codex reasoning goes through the config override, quoted as the CLI expects`() {
        val command = Spawn.command(codex(model = "gpt-5.5-codex", reasoning = "high"), projectDir)

        assertEquals("gpt-5.5-codex", command.argv.after("-m"))
        assertEquals("model_reasoning_effort=\"high\"", command.argv.after("-c"))
    }

    @Test
    fun `resume is a subcommand, so it follows every codex option`() {
        val command = Spawn.command(codex(model = "gpt-5.5"), projectDir, resumeId = "roll-7")

        val resumeAt = command.argv.indexOf("resume")
        assertTrue(resumeAt > 0, "expected a resume subcommand")
        assertEquals("roll-7", command.argv[resumeAt + 1])
        assertTrue(
            command.argv.indexOf("-m") < resumeAt,
            "options must come before the subcommand or the CLI rejects them: ${command.argv}",
        )
    }

    @Test
    fun `a seed prompt is the last argument, so long text can't be mistaken for a flag value`() {
        val seed = "Continue the task handed over from Claude Code."

        for (account in listOf(claude(model = "m", reasoning = "high"), codex(model = "m", reasoning = "high"))) {
            val argv = Spawn.command(account, projectDir, seed = seed).argv
            assertEquals(seed, argv.last(), "seed should be positional and last for ${account.provider.id}")
        }
    }

    @Test
    fun `a blank seed adds no empty argument`() {
        val argv = Spawn.command(claude(), projectDir, seed = "   ").argv
        assertFalse(argv.any { it.isBlank() }, "a blank seed must not become an empty prompt: $argv")
    }

    /** The value following [flag], or null when the flag isn't there. */
    private fun List<String>.after(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }
}
