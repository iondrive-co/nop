package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The hook nop puts in front of every agent's tool calls, and the edits that put it there. The
 * edits are into other programs' config files — two of which another tool on the machine also
 * writes hooks into — so most of these are about what nop leaves alone.
 *
 * The guard's own verdicts are run through the real script with `python3`, the way each CLI runs it.
 */
class AddressGuardTest {

    private val command = "/opt/example/python3 -I -S /opt/example/nop-address-guard"
    private val others = """{"matcher":"*","hooks":[{"type":"command","command":"/opt/other/recorder PreToolUse"}]}"""

    private fun parse(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    private fun account(provider: Provider, home: Path) = Account("acct", provider, home.toString())

    // --- Claude Code ---------------------------------------------------------------------------

    @Test
    fun `claude gets one hook beside the ones already there, and every other setting survives`(@TempDir home: Path) {
        Files.writeString(
            home.resolve("settings.json"),
            """{"permissions":{"deny":["Agent"]},"hooks":{"PreToolUse":[$others],"Stop":[{"hooks":[{"type":"command","command":"/opt/other/stop"}]}]}}""",
        )

        AddressGuard.hook(account(Provider.Anthropic, home), command)

        val settings = parse(home.resolve("settings.json"))
        assertEquals("Agent", settings["permissions"]!!.jsonObject["deny"]!!.jsonArray[0].jsonPrimitive.content)
        val pre = settings["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(2, pre.size)
        assertEquals(Json.parseToJsonElement(others), pre[0])
        val ours = pre[1].jsonObject["hooks"]!!.jsonArray.single().jsonObject
        assertEquals("$command claude", ours["command"]!!.jsonPrimitive.content)
        assertTrue("Stop" in settings["hooks"]!!.jsonObject)
    }

    @Test
    fun `hooking twice changes nothing the second time`(@TempDir home: Path) {
        val account = account(Provider.Anthropic, home)
        AddressGuard.hook(account, command)
        val first = Files.readString(home.resolve("settings.json"))
        val stamp = Files.getLastModifiedTime(home.resolve("settings.json"))
        Thread.sleep(20)

        AddressGuard.hook(account, command)

        assertEquals(first, Files.readString(home.resolve("settings.json")))
        assertEquals(stamp, Files.getLastModifiedTime(home.resolve("settings.json")))
    }

    @Test
    fun `a moved script replaces nop's old hook rather than adding a second`(@TempDir home: Path) {
        val account = account(Provider.Anthropic, home)
        AddressGuard.hook(account, "/old/place/nop-address-guard")

        AddressGuard.hook(account, command)

        val pre = parse(home.resolve("settings.json"))["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(listOf("$command claude"), pre.map { it.jsonObject["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content })
    }

    @Test
    fun `no interpreter takes nop's hook out and leaves the others`(@TempDir home: Path) {
        Files.writeString(home.resolve("settings.json"), """{"hooks":{"PreToolUse":[$others]}}""")
        val account = account(Provider.Anthropic, home)
        AddressGuard.hook(account, command)

        AddressGuard.hook(account, null)

        assertEquals(Json.parseToJsonElement("""{"hooks":{"PreToolUse":[$others]}}"""), parse(home.resolve("settings.json")))
    }

    @Test
    fun `a settings file that is not JSON is left exactly as it was`(@TempDir home: Path) {
        Files.writeString(home.resolve("settings.json"), "{ not json")

        AddressGuard.hook(account(Provider.Anthropic, home), command)

        assertEquals("{ not json", Files.readString(home.resolve("settings.json")))
    }

    // --- Codex ---------------------------------------------------------------------------------

    /**
     * The value Codex computes for this hook, worked out from its source (codex-rs `hook_hash`) and
     * checked against hashes Codex itself wrote. If this changes, Codex quietly skips nop's hook.
     */
    @Test
    fun `codex's trust hash is the one codex computes`() {
        val handler = buildJsonObject {
            put("type", "command")
            put("command", "/opt/example/guard codex")
            put("timeout", 10)
        }
        assertEquals(
            "sha256:ca11b1ec3bb6081dcd7807d3491944dce29f4bda5c5967e9492dff3bffcc8c49",
            AddressGuard.codexTrustHash(handler, "*"),
        )
    }

    @Test
    fun `codex gets the hook after the others and is told to trust it at that place`(@TempDir home: Path) {
        val codex = Files.createDirectories(home.resolve(".codex"))
        Files.writeString(codex.resolve("hooks.json"), """{"hooks":{"PreToolUse":[$others]}}""")
        val config = "model = \"example\"\n\n[hooks.state.\"/elsewhere/hooks.json:pre_tool_use:0:0\"]\ntrusted_hash = \"sha256:abc\"\n"
        Files.writeString(codex.resolve("config.toml"), config)

        AddressGuard.hook(account(Provider.OpenAI, home), command)

        val pre = parse(codex.resolve("hooks.json"))["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(2, pre.size)
        val handler = pre[1].jsonObject["hooks"]!!.jsonArray.single().jsonObject
        val key = "${codex.resolve("hooks.json").toAbsolutePath()}:pre_tool_use:1:0"
        val toml = Files.readString(codex.resolve("config.toml"))
        assertTrue(toml.startsWith(config), "what was there stays, byte for byte:\n$toml")
        assertTrue("[hooks.state.\"$key\"]\ntrusted_hash = \"${AddressGuard.codexTrustHash(handler, "*")}\"" in toml, toml)
    }

    @Test
    fun `codex's trust follows the hook when it moves, and the old entry goes`(@TempDir home: Path) {
        val codex = Files.createDirectories(home.resolve(".codex"))
        val account = account(Provider.OpenAI, home)
        AddressGuard.hook(account, command)
        val path = codex.resolve("hooks.json").toAbsolutePath()
        assertTrue("$path:pre_tool_use:0:0" in Files.readString(codex.resolve("config.toml")))

        // Another tool puts its own hook first, as a recorder's installer does.
        val hooks = parse(codex.resolve("hooks.json"))
        val pre = hooks["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        Files.writeString(codex.resolve("hooks.json"), """{"hooks":{"PreToolUse":[$others,${pre.single()}]}}""")
        AddressGuard.hook(account, command)

        val toml = Files.readString(codex.resolve("config.toml"))
        assertTrue("$path:pre_tool_use:1:0" in toml, toml)
        assertFalse("$path:pre_tool_use:0:0" in toml, "the stale entry would mark the other tool's hook as modified:\n$toml")
    }

    @Test
    fun `codex hooked twice writes neither file again`(@TempDir home: Path) {
        val codex = Files.createDirectories(home.resolve(".codex"))
        val account = account(Provider.OpenAI, home)
        AddressGuard.hook(account, command)
        val hooks = Files.readString(codex.resolve("hooks.json"))
        val toml = Files.readString(codex.resolve("config.toml"))

        AddressGuard.hook(account, command)

        assertEquals(hooks, Files.readString(codex.resolve("hooks.json")))
        assertEquals(toml, Files.readString(codex.resolve("config.toml")))
    }

    // --- agy -----------------------------------------------------------------------------------

    @Test
    fun `agy gets a named hook of its own in its global customisation root`(@TempDir home: Path) {
        val file = home.resolve(".gemini").resolve("config").resolve("hooks.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"lint":{"PostToolUse":[{"matcher":"*","hooks":[{"command":"./lint.sh"}]}]}}""")

        AddressGuard.hook(account(Provider.Antigravity, home), command)

        val hooks = parse(file)
        assertTrue("lint" in hooks)
        val ours = hooks[AddressGuard.SCRIPT_NAME]!!.jsonObject["PreToolUse"]!!.jsonArray.single().jsonObject
        assertEquals("$command agy", ours["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content)
    }

    // --- which addresses -------------------------------------------------------------------------

    @Test
    fun `the addresses are every login nop can see and the user's git identities, but no noreply`(@TempDir tmp: Path) {
        val claude = Files.createDirectories(tmp.resolve("claude"))
        Files.writeString(claude.resolve(".claude.json"), """{"oauthAccount":{"emailAddress":"Pat@Example.org"}}""")
        val codex = Files.createDirectories(tmp.resolve("codex").resolve(".codex"))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"pat@work.example"}""".toByteArray())
        Files.writeString(codex.resolve("auth.json"), """{"tokens":{"id_token":"h.$payload.s"}}""")
        val user = Files.createDirectories(tmp.resolve("user"))
        Files.writeString(user.resolve(".gitconfig"), "[user]\n\temail = pat@home.example\n[includeIf \"gitdir:~/oss/\"]\n\tpath = ~/.gitconfig_oss\n")
        Files.writeString(user.resolve(".gitconfig_oss"), "[user]\n\temail = 1234+pat@users.noreply.github.com\n")

        val found = AddressGuard.addresses(
            listOf(account(Provider.Anthropic, claude), account(Provider.OpenAI, tmp.resolve("codex"))),
            user,
        )

        assertEquals(listOf("pat@example.org", "pat@home.example", "pat@work.example"), found)
    }

    // --- the guard itself ----------------------------------------------------------------------

    private class Guard(val root: Path, val config: Path, val python: String) {
        val script: Path = AddressGuard.scriptPath(root)

        fun protect(vararg addresses: String) {
            Files.createDirectories(AddressGuard.addressesFile(root).parent)
            Files.writeString(
                AddressGuard.addressesFile(root),
                """{"addresses":[${addresses.joinToString(",") { "\"$it\"" }}]}""",
            )
        }

        fun user(json: String) {
            Files.createDirectories(AddressGuard.userFile(config).parent)
            Files.writeString(AddressGuard.userFile(config), json)
        }

        /** What the hook printed, and its exit code, for [payload] as [cli] sends it. */
        fun run(cli: String, payload: String): Pair<String, Int> {
            val process = ProcessBuilder(python, "-I", "-S", script.toString(), cli).redirectErrorStream(true).start()
            process.outputStream.use { it.write(payload.toByteArray()) }
            val out = process.inputStream.readAllBytes().decodeToString().trim()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            return out to process.exitValue()
        }

        fun claude(tool: String, input: String, cwd: Path = root): String? {
            val (out, code) = run("claude", """{"tool_name":"$tool","tool_input":$input,"cwd":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(cwd.toString()))}}""")
            assertEquals(0, code, out)
            if (out.isEmpty()) return null
            val decision = Json.parseToJsonElement(out).jsonObject["hookSpecificOutput"]!!.jsonObject
            assertEquals("deny", decision["permissionDecision"]!!.jsonPrimitive.content)
            return decision["permissionDecisionReason"]!!.jsonPrimitive.content
        }
    }

    private fun guard(tmp: Path): Guard {
        val python = AddressGuard.python()
        assumeTrue(python != null, "needs python3")
        val root = tmp.resolve("data")
        val config = tmp.resolve("config")
        AddressGuard.writeScript(root, config, python!!)
        return Guard(root, config, python).apply { protect("pat.example@gmail.com", "pat@work.example") }
    }

    private fun bash(command: String) = buildJsonObject { put("command", command) }.toString()

    @Test
    fun `a request carrying a user's address in its User-Agent is stopped, and the agent is told why`(@TempDir tmp: Path) {
        val g = guard(tmp)

        val why = g.claude("Bash", bash("curl -s -A 'probe pat@work.example' https://api.example.com/v1/markets"))

        assertTrue(why != null && "pat@work.example" in why && "api.example.com" in why, why)
    }

    @Test
    fun `a +tag or a dotted Gmail spelling is the same mailbox`(@TempDir tmp: Path) {
        val g = guard(tmp)

        assertTrue(g.claude("Bash", bash("curl -H 'From: patexample+bot@gmail.com' https://example.com")) != null)
        assertTrue(g.claude("Bash", bash("curl -A 'p.a.t.example@googlemail.com' https://example.com")) != null)
    }

    @Test
    fun `a command that only mentions an address, without the network, goes through`(@TempDir tmp: Path) {
        val g = guard(tmp)

        assertNull(g.claude("Bash", bash("git log --author=pat@work.example --oneline | head")))
        assertNull(g.claude("Bash", bash("curl -s -A 'research-bot/1.0' https://example.com")))
    }

    @Test
    fun `an address the user allows for a host goes there and nowhere else`(@TempDir tmp: Path) {
        val g = guard(tmp)
        g.user("""{"allow":[{"address":"patexample+filings@gmail.com","hosts":["filings.example"]}]}""")

        assertNull(g.claude("Bash", bash("curl -A 'bot patexample+filings@gmail.com' https://www.filings.example/x")))
        assertTrue(g.claude("Bash", bash("curl -A 'bot patexample+filings@gmail.com' https://api.example.com/x")) != null)
        assertTrue(g.claude("Bash", bash("curl -A 'bot patexample@gmail.com' https://www.filings.example/x")) != null)
    }

    /**
     * A script names every host it ever talks to, so the one the allowed address is for is enough;
     * a command names exactly where it goes, so all of them have to be allowed.
     */
    @Test
    fun `code with an allowed address passes on one allowed host, a command needs them all`(@TempDir tmp: Path) {
        val g = guard(tmp)
        g.user("""{"allow":[{"address":"patexample+filings@gmail.com","hosts":["filings.example"]}]}""")
        val project = Files.createDirectories(tmp.resolve("project"))
        Files.writeString(
            project.resolve("pull.py"),
            "import urllib.request\nFILINGS_UA = {'User-Agent': 'bot patexample+filings@gmail.com'}\n" +
                "urllib.request.urlopen(urllib.request.Request('https://www.filings.example/feed', headers=FILINGS_UA))\n" +
                "urllib.request.urlopen('https://api.quotes.example/v1')\n",
        )
        Files.writeString(
            project.resolve("other.py"),
            "import urllib.request\nUA = {'User-Agent': 'bot patexample+filings@gmail.com'}\n" +
                "urllib.request.urlopen(urllib.request.Request('https://api.quotes.example/v1', headers=UA))\n",
        )

        assertNull(g.claude("Bash", bash("python3 pull.py"), cwd = project))
        assertTrue(g.claude("Bash", bash("python3 other.py"), cwd = project) != null)
        assertTrue(
            g.claude("Bash", bash("for u in https://www.filings.example/a https://api.quotes.example/b; do curl -A 'bot patexample+filings@gmail.com' \$u; done")) != null,
        )
        assertNull(g.claude("Bash", bash("UA='bot patexample+filings@gmail.com'; curl -A \"\$UA\" \"\$FEED_URL\"")))
        assertTrue(g.claude("Bash", bash("UA='bot pat@work.example'; curl -A \"\$UA\" \"\$FEED_URL\"")) != null)
    }

    @Test
    fun `the user can turn it off`(@TempDir tmp: Path) {
        val g = guard(tmp)
        g.user("""{"enabled":false}""")

        assertNull(g.claude("Bash", bash("curl -A 'pat@work.example' https://example.com")))
    }

    @Test
    fun `a script a command runs is read, so an address in its headers stops the run`(@TempDir tmp: Path) {
        val g = guard(tmp)
        val project = Files.createDirectories(tmp.resolve("project"))
        Files.createDirectories(project.resolve("tools"))
        Files.writeString(
            project.resolve("tools/pull.py"),
            "import urllib.request\nUA = {\"User-Agent\": \"puller pat@work.example\"}\nurllib.request.urlopen(urllib.request.Request(\"https://api.example.com/\", headers=UA))\n",
        )

        val why = g.claude("Bash", bash("timeout 60 python3 tools/pull.py --out x.json"), cwd = project)

        assertTrue(why != null && "tools/pull.py" in why, why)
    }

    @Test
    fun `writing code that sends an address is stopped, prose that names one is not`(@TempDir tmp: Path) {
        val g = guard(tmp)
        val script = buildJsonObject {
            put("file_path", tmp.resolve("pull.py").toString())
            put("content", "import requests\nrequests.get('https://api.example.com', headers={'User-Agent': 'x pat@work.example'})\n")
        }.toString()
        val doc = buildJsonObject {
            put("file_path", tmp.resolve("ACCOUNTS.md").toString())
            put("content", "The exchange account is registered to pat@work.example; see https://example.com/help.\n")
        }.toString()

        assertTrue(g.claude("Write", script) != null)
        assertNull(g.claude("Write", doc))
    }

    @Test
    fun `an edit is judged with the file it lands in, and taking an address out is always fine`(@TempDir tmp: Path) {
        val g = guard(tmp)
        val file = tmp.resolve("client.py")
        Files.writeString(file, "import requests\nUA = 'client'\nrequests.get('https://api.example.com', headers={'User-Agent': UA})\n")
        fun edit(old: String, new: String) = buildJsonObject {
            put("file_path", file.toString())
            put("old_string", old)
            put("new_string", new)
        }.toString()

        assertTrue(g.claude("Edit", edit("UA = 'client'", "UA = 'client pat@work.example'")) != null)
        assertNull(g.claude("Edit", edit("UA = 'client pat@work.example'", "UA = 'client'")))
    }

    @Test
    fun `web tools may not carry an address, but WebFetch's prompt never reaches the site`(@TempDir tmp: Path) {
        val g = guard(tmp)

        assertTrue(g.claude("WebFetch", """{"url":"https://example.com/?contact=pat%40work.example","prompt":"summarise"}""") != null)
        assertTrue(g.claude("WebSearch", """{"query":"pat@work.example breach"}""") != null)
        assertNull(g.claude("WebFetch", """{"url":"https://example.com/","prompt":"is pat@work.example listed?"}"""))
    }

    @Test
    fun `an MCP call that makes a request may not carry an address`(@TempDir tmp: Path) {
        val g = guard(tmp)
        val call = """{"url":"https://api.example.com/v1","method":"GET","headers":{"User-Agent":"x pat@work.example"}}"""

        assertTrue(g.claude("mcp__broker__fetch", call) != null)
        assertNull(g.claude("Read", """{"file_path":"/tmp/pat@work.example.txt"}"""))
    }

    @Test
    fun `codex's patches are judged by the lines they add`(@TempDir tmp: Path) {
        val g = guard(tmp)
        fun patch(sign: Char) = buildJsonObject {
            put("command", "*** Begin Patch\n*** Update File: a.py\n@@\n${sign}r = requests.get('https://api.example.com', headers={'User-Agent': 'pat@work.example'})\n*** End Patch\n")
        }.toString()

        val (added, _) = g.run("codex", """{"tool_name":"apply_patch","tool_input":${patch('+')}}""")
        val (removed, _) = g.run("codex", """{"tool_name":"apply_patch","tool_input":${patch('-')}}""")

        assertEquals("deny", Json.parseToJsonElement(added).jsonObject["hookSpecificOutput"]!!.jsonObject["permissionDecision"]!!.jsonPrimitive.content)
        assertEquals("", removed)
    }

    /** agy reads an empty answer as a denial (seen with agy 1.5.2), so a pass has to say allow. */
    @Test
    fun `agy is answered in its own shape, and a pass is an explicit allow`(@TempDir tmp: Path) {
        val g = guard(tmp)
        fun call(line: String) = buildJsonObject {
            put("toolCall", buildJsonObject {
                put("name", "run_command")
                put("args", buildJsonObject { put("CommandLine", line) })
            })
            put("workspacePaths", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(tmp.toString())) })
        }.toString()

        val (denied, _) = g.run("agy", call("wget --user-agent='pat@work.example' https://example.com"))
        val (allowed, _) = g.run("agy", call("ls -la"))

        assertEquals("deny", Json.parseToJsonElement(denied).jsonObject["decision"]!!.jsonPrimitive.content)
        assertEquals("allow", Json.parseToJsonElement(allowed).jsonObject["decision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a payload it cannot read lets the call through rather than breaking the agent's tools`(@TempDir tmp: Path) {
        val g = guard(tmp)

        assertEquals("" to 0, g.run("claude", "not json at all"))
        assertEquals("""{"decision": "allow"}""" to 0, g.run("agy", "not json at all"))
    }

    @Test
    fun `every denial is logged`(@TempDir tmp: Path) {
        val g = guard(tmp)

        g.claude("Bash", bash("curl -A 'pat@work.example' https://api.example.com"))

        val log = Files.readString(AddressGuard.logFile(g.root))
        assertTrue("claude Bash denied pat@work.example to api.example.com" in log, log)
    }
}
