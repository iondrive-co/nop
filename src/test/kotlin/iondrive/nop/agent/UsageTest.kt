package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The usage readers, against fixture files on disk and a real local HTTP server.
 *
 * The Codex half is all file parsing — there is no endpoint, so the reading is scavenged out of the
 * CLI's own transcripts. The Claude half is HTTP, and the assertions that matter are about what
 * happens to `.credentials.json`: that file belongs to the real CLI as much as to nop, so a broken
 * write here signs the account out of both.
 */
class UsageTest {

    /**
     * A signed-in Codex account at [home]. Signed in on purpose: usage is only read for an account
     * that can actually run, so a fixture without a login now reports "not signed in" whatever its
     * transcripts say.
     */
    private fun codexAccount(home: Path): Account {
        val account = Account("codex", Provider.OpenAI, home.toString())
        Files.createDirectories(account.credentialFile.parent)
        Files.writeString(account.credentialFile, """{"tokens":{"access_token":"tok"}}""")
        return account
    }

    private fun rollout(home: Path, name: String, vararg lines: String): Path {
        val dir = home.resolve(".codex/sessions/2026/09/15")
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.writeString(file, lines.joinToString("\n") + "\n")
        return file
    }

    private fun tokenCount(primaryPercent: Double, windowMinutes: Int, resetsAt: Long): String =
        """{"type":"event_msg","payload":{"type":"token_count","rate_limits":""" +
            """{"primary":{"used_percent":$primaryPercent,"window_minutes":$windowMinutes,"resets_at":$resetsAt},""" +
            """"secondary":null}}}"""

    // ── Codex ──

    @Test
    fun `codex usage comes from the last populated snapshot in the newest session`(@TempDir tmp: Path) {
        val future = Instant.now().plusSeconds(3600).epochSecond
        rollout(
            tmp, "rollout-a.jsonl",
            tokenCount(12.0, 300, future),
            tokenCount(37.5, 300, future),
        )

        val reading = Usage.read(codexAccount(tmp))

        assertEquals(37.5, reading.session?.percent)
        assertNotNull(reading.asOf, "a scavenged reading must carry its age — it is not a live answer")
    }

    @Test
    fun `a snapshot with no windows never clobbers a real reading`(@TempDir tmp: Path) {
        val future = Instant.now().plusSeconds(3600).epochSecond
        rollout(
            tmp, "rollout-a.jsonl",
            tokenCount(44.0, 300, future),
            """{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":null,"secondary":null}}}""",
        )

        assertEquals(44.0, Usage.read(codexAccount(tmp)).session?.percent)
    }

    @Test
    fun `window_minutes decides which window is the session one, not the slot it arrived in`(@TempDir tmp: Path) {
        val future = Instant.now().plusSeconds(600_000).epochSecond
        // A team plan reports its seven-day window in `primary` with `secondary` empty. Reading
        // `primary` as the session window put a weekly figure, and a reset a week out, in the
        // session row.
        rollout(tmp, "rollout-a.jsonl", tokenCount(90.0, 10080, future))

        val reading = Usage.read(codexAccount(tmp))

        assertNull(reading.session, "a weekly-length window is not the session window")
        assertEquals(90.0, reading.weekly?.percent)
    }

    @Test
    fun `a window that has already rolled over reads as spent, not as maxed out`(@TempDir tmp: Path) {
        val past = Instant.now().minusSeconds(60).epochSecond
        rollout(tmp, "rollout-a.jsonl", tokenCount(100.0, 300, past))

        assertEquals(
            0.0,
            Usage.read(codexAccount(tmp)).session?.percent,
            "the snapshot is old; the window it described has since reset",
        )
    }

    @Test
    fun `an account with no sessions says so rather than reporting nothing used`(@TempDir tmp: Path) {
        val reading = Usage.read(codexAccount(tmp))

        assertNull(reading.session)
        assertNotNull(reading.unavailable, "0% would claim the week is untouched when nop simply couldn't ask")
    }

    /**
     * An abandoned home keeps its old rollouts, and reading those reported a tidy 0% for an account
     * that cannot run at all — the one reading worse than none, because it says the week is
     * untouched when the truth is that nop has nothing to ask.
     */
    @Test
    fun `a home with transcripts but no login reports nothing, not zero`(@TempDir tmp: Path) {
        val future = Instant.now().plusSeconds(3600).epochSecond
        rollout(tmp, "rollout-old.jsonl", tokenCount(80.0, 300, future))

        val reading = Usage.read(Account("codex", Provider.OpenAI, tmp.toString()))

        assertEquals("not signed in", reading.unavailable)
        assertNull(reading.session)
    }

    @Test
    fun `usage is read from the account's own home, not the machine owner's`(@TempDir tmp: Path) {
        val mine = tmp.resolve("mine")
        val theirs = tmp.resolve("theirs")
        val future = Instant.now().plusSeconds(3600).epochSecond
        rollout(theirs, "rollout-theirs.jsonl", tokenCount(99.0, 300, future))
        rollout(mine, "rollout-mine.jsonl", tokenCount(5.0, 300, future))

        assertEquals(5.0, Usage.read(codexAccount(mine)).session?.percent)
        assertEquals(99.0, Usage.read(codexAccount(theirs)).session?.percent)
    }

    @Test
    fun `the eta counts down to the window reset`() {
        val window = UsageWindow(50.0, Instant.parse("2026-09-15T12:00:00Z"))

        assertEquals("3h 12m", window.eta(Instant.parse("2026-09-15T08:48:00Z")))
        assertEquals("45m", window.eta(Instant.parse("2026-09-15T11:15:00Z")))
        assertEquals("0m", window.eta(Instant.parse("2026-09-15T13:00:00Z")), "a passed reset is not negative time")
        assertNull(UsageWindow(50.0, null).eta())
    }

    // ── Claude: percentages ──

    @Test
    fun `a fractional utilization is scaled and a percentage is left alone`() {
        assertEquals(54.0, Usage.normalizePercent(0.54))
        assertEquals(54.0, Usage.normalizePercent(54.0))
        // 1.0 is ambiguous. Real full usage produces a quota error, not a tidy number, so it is 1%.
        assertEquals(1.0, Usage.normalizePercent(1.0))
        assertEquals(100.0, Usage.normalizePercent(140.0), "clamped: a bar can't be fuller than full")
        assertEquals(0.0, Usage.normalizePercent(Double.NaN))
    }

    // ── Claude: the OAuth token, which is the part that can break the real CLI ──

    private fun credentials(home: Path, expiresAt: Long, access: String = "tok", refresh: String = "ref"): Account {
        Files.createDirectories(home)
        Files.writeString(
            home.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$access","refreshToken":"$refresh","expiresAt":$expiresAt,""" +
                """"scopes":["user:inference"]},"otherKey":{"keep":"me"}}""",
        )
        return Account("claude", Provider.Anthropic, home.toString())
    }

    @Test
    fun `an unexpired token is used as it is`(@TempDir tmp: Path) {
        val account = credentials(tmp, System.currentTimeMillis() + 3_600_000)

        assertEquals("tok", Usage.claudeToken(account))
        assertTrue(Usage.signedIn(account))
    }

    @Test
    fun `an account with no credentials file is signed out`(@TempDir tmp: Path) {
        val account = Account("claude", Provider.Anthropic, tmp.resolve("nothing").toString())

        assertNull(Usage.claudeToken(account))
        assertFalse(Usage.signedIn(account))
    }

    @Test
    fun `a refused refresh leaves the credentials file untouched`(@TempDir tmp: Path) {
        Usage.clearAuthCache()
        val account = credentials(tmp, System.currentTimeMillis() - 1000)
        val before = Files.readString(account.credentialFile)

        // No network stub here: the refresh reaches a real endpoint with a junk token and fails.
        assertNull(Usage.claudeToken(account))

        assertEquals(before, Files.readString(account.credentialFile), "a failed refresh must not damage the file")
        Usage.clearAuthCache()
    }

    @Test
    fun `an unreadable credentials file is signed out rather than an exception`(@TempDir tmp: Path) {
        Files.createDirectories(tmp)
        Files.writeString(tmp.resolve(".credentials.json"), "{not json")
        val account = Account("claude", Provider.Anthropic, tmp.toString())

        assertNull(Usage.claudeToken(account))
    }

    /**
     * The refresh write, exercised directly.
     *
     * This is the assertion the whole risky port hangs on. `.credentials.json` belongs to the real
     * CLI as much as to nop, so the rewritten file has to stay a document that CLI can read: every
     * key nop does not own still there, and only the three it does changed. Driving the real
     * endpoint from a test is not an option — Anthropic's refresh tokens are single-use, so a test
     * that spent one would sign the account out to prove that it doesn't.
     */
    @Test
    fun `a refresh rewrites only the fields it owns and keeps every other one`(@TempDir tmp: Path) {
        val account = credentials(tmp, System.currentTimeMillis() - 1000)
        val response = Json.parseToJsonElement(
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""",
        ).jsonObject

        val returned = Usage.mergeRefreshedToken(account.credentialFile, response)

        assertEquals("new-access", returned)
        val after = Json.parseToJsonElement(Files.readString(account.credentialFile)).jsonObject
        assertEquals("me", after["otherKey"]!!.jsonObject["keep"]!!.jsonPrimitive.content)
        val oauth = after["claudeAiOauth"]!!.jsonObject
        assertEquals("new-access", oauth["accessToken"]!!.jsonPrimitive.content)
        assertEquals("new-refresh", oauth["refreshToken"]!!.jsonPrimitive.content)
        assertNotNull(oauth["scopes"], "the rewrite dropped a field the CLI put there")
        val expiresAt = oauth["expiresAt"]!!.jsonPrimitive.longOrNull!!
        assertTrue(
            expiresAt > System.currentTimeMillis(),
            "the refreshed token must not still look expired, or every poll refreshes again",
        )
        // And the account is usable straight away, without going back to the network.
        assertEquals("new-access", Usage.claudeToken(account))
    }

    @Test
    fun `a token response with nothing usable in it writes nothing`(@TempDir tmp: Path) {
        val account = credentials(tmp, System.currentTimeMillis() - 1000)
        val before = Files.readString(account.credentialFile)

        val returned = Usage.mergeRefreshedToken(
            account.credentialFile,
            Json.parseToJsonElement("""{"error":"invalid_grant"}""").jsonObject,
        )

        assertNull(returned)
        assertEquals(before, Files.readString(account.credentialFile))
    }

    @Test
    fun `a response that keeps the old refresh token leaves it in place`(@TempDir tmp: Path) {
        val account = credentials(tmp, System.currentTimeMillis() - 1000, refresh = "still-good")

        Usage.mergeRefreshedToken(
            account.credentialFile,
            Json.parseToJsonElement("""{"access_token":"a","expires_in":60}""").jsonObject,
        )

        val oauth = Json.parseToJsonElement(Files.readString(account.credentialFile))
            .jsonObject["claudeAiOauth"]!!.jsonObject
        assertEquals("still-good", oauth["refreshToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the rewrite leaves no temp file beside the credentials`(@TempDir tmp: Path) {
        val account = credentials(tmp, System.currentTimeMillis() - 1000)

        Usage.mergeRefreshedToken(
            account.credentialFile,
            Json.parseToJsonElement("""{"access_token":"a","expires_in":60}""").jsonObject,
        )

        val strays = Files.list(tmp).use { stream ->
            stream.map { it.fileName.toString() }.filter { it != ".credentials.json" }.toList()
        }
        assertTrue(strays.isEmpty(), "expected only the credentials file, found $strays")
    }
}
