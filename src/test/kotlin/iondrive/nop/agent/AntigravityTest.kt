package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * The provider that was deferred, and the two things that un-deferred it.
 *
 * One is that `agy` will keep its login in a file under the home nop points it at — but only while
 * it believes the OS keyring is unusable, which is a belief with a timestamp on it. If the pinning
 * here stops working, several accounts silently share one login again, which looks exactly like
 * several accounts working right up until one account's quota is spent by all of them. That is the
 * failure these guard against.
 *
 * The other is that its `/usage` output can be read. It reports what is *left*, for two model
 * families, where nop shows what is spent for one — an inversion nobody notices being wrong,
 * because 5% and 95% are both plausible numbers to see next to an account.
 */
class AntigravityTest {

    /** The shape `agy` writes, and the shape chad's `credential.json` already had. */
    private fun token(refresh: String? = "1//refresh"): String = buildString {
        append("""{"token":{"access_token":"ya29.x","token_type":"Bearer"""")
        if (refresh != null) append(""","refresh_token":"$refresh"""")
        append("""},"auth_method":"consumer"}""")
    }

    private fun account(home: Path, name: String = "google-one") =
        Account(name, Provider.Antigravity, home.toString())

    private fun signedIn(home: Path, name: String = "google-one"): Account {
        val account = account(home, name)
        Files.createDirectories(account.credentialFile.parent)
        Files.writeString(account.credentialFile, token())
        return account
    }

    // ── the login, and where it has to live ──

    @Test
    fun `an account is signed in when it holds a refresh token, not merely a file`(@TempDir tmp: Path) {
        val account = signedIn(tmp)
        assertTrue(Antigravity.signedIn(account))

        // An access token alone is a login the CLI cannot renew: it expires within the hour and
        // there is nothing to spend for another one.
        Files.writeString(account.credentialFile, token(refresh = null))
        assertFalse(Antigravity.signedIn(account))
    }

    @Test
    fun `an account with nothing on disk is signed out rather than an error`(@TempDir tmp: Path) {
        assertFalse(Antigravity.signedIn(account(tmp.resolve("never-used"))))
    }

    /**
     * The migration that makes an inherited account work without signing it in again: chad kept the
     * same JSON in a file of its own and wrote it into the keyring before each run.
     */
    @Test
    fun `chad's stored credential is moved to where the CLI now reads it`(@TempDir tmp: Path) {
        val account = account(tmp)
        Files.createDirectories(tmp)
        Files.writeString(tmp.resolve("credential.json"), token())

        Antigravity.prepareHome(account)

        assertTrue(Antigravity.signedIn(account))
        assertEquals(token(), Files.readString(account.credentialFile))
    }

    /**
     * Only ever into an empty slot. The token the CLI has been refreshing is current; chad's copy
     * has not been touched since chad last ran, and overwriting one with the other would sign a
     * working account back into a stale session.
     */
    @Test
    fun `a login the CLI has been keeping current is not replaced by chad's older copy`(@TempDir tmp: Path) {
        val account = signedIn(tmp)
        Files.writeString(account.credentialFile, token(refresh = "1//current"))
        Files.writeString(tmp.resolve("credential.json"), token(refresh = "1//stale"))

        Antigravity.prepareHome(account)

        assertTrue("1//current" in Files.readString(account.credentialFile))
    }

    /**
     * The pin itself. Without a current marker the CLI re-tries the keyring, and on a machine where
     * one is available it goes back to the single shared slot — the bug this provider was deferred
     * over.
     */
    @Test
    fun `preparing a home tells the CLI its keyring is unusable, as of now`(@TempDir tmp: Path) {
        Antigravity.prepareHome(signedIn(tmp))

        val marker = Antigravity.cliDir(tmp).resolve("cache/antigravity-keyring-unavailable")
        assertTrue(Files.isRegularFile(marker), "no keyring marker was written")
        val stamped = Instant.parse(Files.readString(marker).trim())
        assertTrue(
            stamped.isAfter(Instant.now().minusSeconds(60)),
            "the marker has to be stamped now to be believed: $stamped",
        )
    }

    @Test
    fun `preparing a home again re-stamps the marker, because the CLI stops believing an old one`(
        @TempDir tmp: Path,
    ) {
        val account = signedIn(tmp)
        val marker = Antigravity.cliDir(tmp).resolve("cache/antigravity-keyring-unavailable")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "2020-01-01T00:00:00Z")

        Antigravity.prepareHome(account)

        assertTrue(Instant.parse(Files.readString(marker).trim()).isAfter(Instant.parse("2021-01-01T00:00:00Z")))
    }

    /** The same first-run problem [VendorConfig] solves for Claude Code. */
    @Test
    fun `a signed-in account is marked as onboarded, so the TUI does not ask again`(@TempDir tmp: Path) {
        Antigravity.prepareHome(signedIn(tmp))

        val onboarding = Json.parseToJsonElement(
            Files.readString(Antigravity.cliDir(tmp).resolve("cache/onboarding.json")),
        ).jsonObject
        assertEquals("true", onboarding["onboardingComplete"]!!.jsonPrimitive.content)
        assertEquals("true", onboarding["consumerOnboardingComplete"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an account that has never signed in is left to its own first-run flow`(@TempDir tmp: Path) {
        Antigravity.prepareHome(account(tmp))

        assertFalse(Files.exists(Antigravity.cliDir(tmp).resolve("cache/onboarding.json")))
    }

    @Test
    fun `preparing a home leaves the CLI's other onboarding keys alone`(@TempDir tmp: Path) {
        val account = signedIn(tmp)
        val file = Antigravity.cliDir(tmp).resolve("cache/onboarding.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"enterpriseOnboardingComplete": true}""")

        Antigravity.prepareHome(account)

        val onboarding = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("true", onboarding["enterpriseOnboardingComplete"]!!.jsonPrimitive.content)
        assertEquals("true", onboarding["onboardingComplete"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the login is kept owner-only, like every other credential nop puts on disk`(@TempDir tmp: Path) {
        val account = account(tmp)
        Files.createDirectories(tmp)
        Files.writeString(tmp.resolve("credential.json"), token())

        Antigravity.prepareHome(account)

        assertEquals(
            "rw-------",
            java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(account.credentialFile),
            ),
        )
    }

    // ── what it says about quota ──

    /** Real output, from a real account, with the numbers left as they came. */
    private val usageOutput = """
        Gemini Models	Weekly Limit Remaining	95%	2026-09-23T07:22:50Z
        Gemini Models	Five Hour Limit Remaining	97%	2026-09-17T10:07:01Z
        Claude and GPT models	Weekly Limit Remaining	100%	2026-09-24T08:10:33Z
        Claude and GPT models	Five Hour Limit Remaining	40%	2026-09-17T13:10:33Z
    """.trimIndent()

    @Test
    fun `the CLI reports what is left and nop records what is spent`() {
        val windows = Antigravity.parseUsage(usageOutput, model = null)

        // 97% remaining is 3% spent. Reading this the other way round is a poller that reports an
        // idle account as nearly exhausted, and hands its work away on the first quota scare.
        assertEquals(3.0, windows.getValue("session").percent, 0.001)
        assertEquals(5.0, windows.getValue("weekly").percent, 0.001)
        assertEquals(Instant.parse("2026-09-17T10:07:01Z"), windows.getValue("session").resetsAt)
        assertEquals(Instant.parse("2026-09-23T07:22:50Z"), windows.getValue("weekly").resetsAt)
    }

    @Test
    fun `an account is measured against the family its own model runs in`() {
        // Antigravity serves Gemini, Claude and GPT against separate allowances; showing a Gemini
        // account the Claude number would be a figure that never moves whatever it does.
        assertEquals(3.0, Antigravity.parseUsage(usageOutput, "gemini-3.1-pro-high").getValue("session").percent, 0.001)
        assertEquals(60.0, Antigravity.parseUsage(usageOutput, "claude-sonnet-4-6").getValue("session").percent, 0.001)
        assertEquals(60.0, Antigravity.parseUsage(usageOutput, "gpt-oss-120b-medium").getValue("session").percent, 0.001)
    }

    @Test
    fun `an account on the CLI's default is a Gemini account`() {
        assertEquals("gemini models", Antigravity.familyFor(null))
        assertEquals("gemini models", Antigravity.familyFor(DEFAULT_CHOICE))
    }

    @Test
    fun `the windows carry their own spans, which is what turns a reset into a position`() {
        val windows = Antigravity.parseUsage(usageOutput, model = null)

        assertEquals(java.time.Duration.ofHours(5), windows.getValue("session").length)
        assertEquals(java.time.Duration.ofDays(7), windows.getValue("weekly").length)
    }

    @Test
    fun `output that is not a usage table reads as nothing rather than as zero usage`() {
        // An empty map is what makes the reading "unavailable". A window at 0% would be a claim
        // that the account is untouched, which is a thing to act on.
        assertTrue(Antigravity.parseUsage("Fetching limits...\nsomething went wrong", null).isEmpty())
        assertTrue(Antigravity.parseUsage("", null).isEmpty())
        assertTrue(Antigravity.parseUsage("Gemini Models\tFive Hour Limit Remaining\tmost of it\tnever", null).isEmpty())
    }

    @Test
    fun `a limit the CLI grows later is ignored rather than guessed at`() {
        val windows = Antigravity.parseUsage(
            "Gemini Models\tMonthly Limit Remaining\t50%\t2026-10-01T00:00:00Z",
            model = null,
        )

        assertNull(windows["session"])
        assertNull(windows["weekly"])
    }

    @Test
    fun `a reset the CLI phrases differently leaves the window without one`() {
        val windows = Antigravity.parseUsage(
            "Gemini Models\tFive Hour Limit Remaining\t80%\tin about an hour",
            model = null,
        )

        assertNotNull(windows["session"])
        assertEquals(20.0, windows.getValue("session").percent, 0.001)
        assertNull(windows.getValue("session").resetsAt)
    }

    @Test
    fun `usage cannot be read for an account that is not signed in`(@TempDir tmp: Path) {
        val reading = Antigravity.readUsage(account(tmp.resolve("never-used")))

        assertEquals("not signed in", reading.unavailable)
    }

    // ── conversation titles ──

    @Test
    fun `conversation title is read from the annotation file`(@TempDir tmp: Path) {
        val annotationFile = Antigravity.annotationsFile(tmp, "conv-123")
        Files.createDirectories(annotationFile.parent)
        Files.writeString(annotationFile, "title:\"Fix Calculator Addition Bug\"\n")

        assertEquals("Fix Calculator Addition Bug", Antigravity.conversationTitle(tmp, "conv-123"))
    }

    @Test
    fun `conversation title unescapes quotes in annotation file`(@TempDir tmp: Path) {
        val annotationFile = Antigravity.annotationsFile(tmp, "conv-123")
        Files.createDirectories(annotationFile.parent)
        Files.writeString(annotationFile, "title:\"Fix \\\"quoted\\\" bug\"\n")

        assertEquals("Fix \"quoted\" bug", Antigravity.conversationTitle(tmp, "conv-123"))
    }

    @Test
    fun `missing or empty annotation file returns null`(@TempDir tmp: Path) {
        assertNull(Antigravity.conversationTitle(tmp, "non-existent"))

        val annotationFile = Antigravity.annotationsFile(tmp, "conv-empty")
        Files.createDirectories(annotationFile.parent)
        Files.writeString(annotationFile, "title:\"\"\n")

        assertNull(Antigravity.conversationTitle(tmp, "conv-empty"))
    }
}
