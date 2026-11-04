package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * nop runs each CLI with its config moved to the account's home, which hid the MCP servers the user
 * set up for their own agents. These check that the servers are read from where the user keeps
 * them and reach each CLI in the form it takes.
 */
class McpServersTest {

    private val project = File("/home/dev/core")

    private fun userConfig(home: Path) = Files.writeString(
        home.resolve(".claude.json"),
        """
        {
          "mcpServers": {
            "tracker": {"type": "stdio", "command": "/opt/tracker-mcp", "args": [], "env": {}},
            "old": {"type": "stdio", "command": "/opt/old"}
          },
          "projects": {
            "/home/dev/core": {
              "mcpServers": {"api": {"type": "http", "url": "https://api.example/mcp"}},
              "disabledMcpServers": ["old"]
            },
            "/home/dev/other": {"mcpServers": {"elsewhere": {"command": "/opt/x"}}}
          }
        }
        """.trimIndent(),
    )

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a project gets the user-scope servers and its own, less the ones disabled there`(@TempDir home: Path) {
        userConfig(home)

        val servers = McpServers.forProject(project, userHome = home, configDir = null)

        assertEquals(setOf("tracker", "api"), servers.keys)
    }

    @Test
    fun `an exported CLAUDE_CONFIG_DIR is where the user's CLI reads them`(@TempDir home: Path) {
        val dir = Files.createDirectories(home.resolve("elsewhere"))
        userConfig(dir)

        assertEquals(setOf("tracker", "api"), McpServers.forProject(project, home, dir.toString()).keys)
        assertTrue(McpServers.forProject(project, home, null).isEmpty())
    }

    @Test
    fun `claude is pointed at an owner-only file holding them`(@TempDir tmp: Path) {
        val servers = mapOf("tracker" to obj("""{"type":"stdio","command":"/opt/tracker-mcp"}"""))
        val file = McpServers.writeClaudeConfig(project, servers, tmp)!!
        val account = Account("c", Provider.Anthropic, "/home/dev/.claude-c")

        val argv = Spawn.command(account, project, mcp = McpServers.Launch(servers, file)).argv

        assertEquals(file.toString(), argv[argv.indexOf("--mcp-config") + 1])
        assertEquals(servers, obj(Files.readString(file))["mcpServers"])
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a seed prompt is not read as one more config file`(@TempDir tmp: Path) {
        val servers = mapOf("tracker" to obj("""{"type":"stdio","command":"/opt/tracker-mcp"}"""))
        val file = McpServers.writeClaudeConfig(project, servers, tmp)!!
        val account = Account("c", Provider.Anthropic, "/home/dev/.claude-c")
        val seed = "Continue the task handed over from Codex."

        val argv = Spawn.command(account, project, seed = seed, mcp = McpServers.Launch(servers, file)).argv

        // `--mcp-config` takes a list and keeps taking arguments up to the next flag.
        val separator = argv.indexOf("--")
        assertTrue(separator > argv.indexOf("--mcp-config"), "the seed needs a `--` after the config: $argv")
        assertEquals(listOf(seed), argv.drop(separator + 1))
    }

    @Test
    fun `codex gets one override per server, and none it cannot speak`() {
        val servers = mapOf(
            "tracker" to obj("""{"type":"stdio","command":"/opt/x","args":["-v"],"env":{"K":"v\"q"}}"""),
            "api" to obj("""{"type":"http","url":"https://api.example/mcp","headers":{"A":"b"}}"""),
            "stream" to obj("""{"type":"sse","url":"https://sse.example"}"""),
            "has space" to obj("""{"command":"/opt/y"}"""),
        )

        assertEquals(
            listOf(
                "-c", """mcp_servers.tracker={command="/opt/x", args=["-v"], env={"K"="v\"q"}, default_tools_approval_mode="approve"}""",
                "-c", """mcp_servers.api={url="https://api.example/mcp", http_headers={"A"="b"}, default_tools_approval_mode="approve"}""",
            ),
            McpServers.codexOverrides(servers),
        )
    }

    /**
     * The policy that lets a server's own form reach the user would also put Codex's "Allow the …
     * MCP server to run tool …?" in front of every call, so each server, nop's or the account's,
     * runs its tools without that.
     */
    @Test
    fun `codex runs the account's own servers without asking first, and names no other`() {
        val servers = mapOf("tracker" to obj("""{"command":"/opt/x"}"""))

        assertEquals(
            listOf(
                "-c", """mcp_servers.tracker={command="/opt/x", default_tools_approval_mode="approve"}""",
                "-c", """mcp_servers.ledger.default_tools_approval_mode="approve"""",
            ),
            McpServers.codexOverrides(servers, configured = setOf("ledger", "tracker")),
        )
    }

    /**
     * An override for a server Codex has not loaded is a table with no transport, and the CLI will
     * not start on it — so only names the file really defines, and not one that picks its own mode.
     */
    @Test
    fun `the servers in a codex config are read from every form a server can take`() {
        val Q = "\"\"\""
        val toml = """
            model = "m"
            notes = $Q
            [mcp_servers.quoted_in_a_string]
            mcp_servers.also_in_a_string.command = "x"
            $Q
            mcp_servers.dotted.command = "/opt/dotted"
            mcp_servers.inline = { command = "/opt/inline" }
            mcp_servers.inline_picky = { command = "/opt/p", default_tools_approval_mode = "auto" }

            [projects."/home/dev/mcp_servers.x"]
            trust_level = "trusted"

            # [mcp_servers.commented]
            [mcp_servers.ledger]
            command = "/opt/ledger"
            args = [
              "--mode=fast",
            ]

            [mcp_servers.ledger.env]
            LEDGER_DIR = "/srv/ledger"

            [mcp_servers."quoted"]
            url = "https://quoted.example/mcp"

            [mcp_servers."has space"]
            command = "/opt/space"

            [mcp_servers.picky]
            command = "/opt/picky"
            default_tools_approval_mode = "prompt"

            [notice]
            fast_default_opt_out = true
        """.trimIndent()

        assertEquals(setOf("dotted", "inline", "ledger", "quoted"), McpServers.codexConfigured(toml))
    }

    @Test
    fun `servers kept as keys of one mcp_servers table are found too`() {
        val toml = """
            [mcp_servers]
            ledger = { command = "/opt/ledger", env = { LEDGER_DIR = "/srv/ledger" } }
            picky = { url = "https://picky.example/mcp", default_tools_approval_mode = "prompt" }
        """.trimIndent()

        assertEquals(setOf("ledger"), McpServers.codexConfigured(toml))
    }

    @Test
    fun `an account with no codex config has no servers of its own`(@TempDir home: Path) {
        assertEquals(emptySet<String>(), McpServers.codexConfigured(home.resolve(".codex/config.toml")))
    }

    @Test
    fun `agy's home gets them beside its own, and loses the ones the user dropped`(@TempDir tmp: Path) {
        val user = Files.createDirectories(tmp.resolve("user"))
        val home = tmp.resolve("acct")
        val config = home.resolve(".gemini/config/mcp_config.json")
        Files.createDirectories(config.parent)
        Files.writeString(config, """{"mcpServers":{"mine":{"command":"/opt/mine"}},"other":1}""")
        Files.createDirectories(user.resolve(".gemini/config"))
        Files.writeString(user.resolve(".gemini/config/mcp_config.json"), """{"mcpServers":{"shared":{"serverUrl":"https://shared.example"}}}""")

        McpServers.installForAntigravity(
            home,
            mapOf(
                "tracker" to obj("""{"type":"stdio","command":"/opt/x","args":[]}"""),
                "mine" to obj("""{"command":"/opt/not-mine"}"""),
            ),
            user,
        )
        var written = obj(Files.readString(config))
        assertEquals(setOf("mine", "shared", "tracker"), written["mcpServers"]!!.jsonObject.keys)
        assertEquals(obj("""{"command":"/opt/mine"}"""), written["mcpServers"]!!.jsonObject["mine"])
        assertEquals("/opt/x", (written["mcpServers"]!!.jsonObject["tracker"] as JsonObject)["command"].str())
        assertEquals("1", written["other"].str())

        McpServers.installForAntigravity(home, emptyMap(), user)
        written = obj(Files.readString(config))
        assertEquals(setOf("mine", "shared"), written["mcpServers"]!!.jsonObject.keys)
    }

    @Test
    fun `the user's own agy home is never written to`(@TempDir user: Path) {
        McpServers.installForAntigravity(user, mapOf("tracker" to obj("""{"command":"/opt/x"}""")), user)

        assertFalse(Files.exists(user.resolve(".gemini/config/mcp_config.json")))
    }
}
