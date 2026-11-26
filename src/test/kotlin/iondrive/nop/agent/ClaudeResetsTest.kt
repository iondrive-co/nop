package iondrive.nop.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

class ClaudeResetsTest {
    private val now = Instant.parse("2030-01-01T10:00:00Z")

    @Test
    fun `offers the earliest usable grant and leaves expired grants out of the count`() {
        val status = Json.parseToJsonElement("""{
            "eligible": true,
            "weekly_resets_at": "2030-01-04T10:00:00+00:00",
            "grants": [
                {"id":"later", "resets_left":2, "usable_now":true, "clears":["five_hour"]},
                {"id":"early", "resets_left":1, "usable_now":true, "clears":["five_hour"],
                 "ends_at":"2030-01-02T10:00:00Z", "use_requires_limit":false, "percent_used":{"five_hour":60}},
                {"id":"expired", "resets_left":5, "usable_now":true, "clears":["five_hour"],
                 "ends_at":"2030-01-01T10:00:00Z"},
                {"id":"weekly", "resets_left":1, "clears":["seven_day"]},
                {"id":"paused", "resets_left":2, "paused":true, "clears":["five_hour","seven_day"]},
                {"id":"bad/id", "resets_left":1, "usable_now":true}
            ]
        }""")
        val resets = ClaudeResets.resets(status, now)!!
        val offer = resets.offers.single()
        assertEquals("early", offer.id)
        assertEquals("3 5-hour resets", offer.count())
        assertEquals(Instant.parse("2030-01-02T10:00:00Z"), offer.until)
        assertEquals(Instant.parse("2030-01-04T10:00:00Z"), offer.weekResets)
        assertTrue(offer.early!!.contains("40%"))
        assertEquals(listOf("Weekly reset · usable once you reach a limit it refills"), resets.blocked)
    }

    @Test
    fun `Fable reset describes the partial weekly refill and clears an exhausted weekly limit`() {
        val status = Json.parseToJsonElement("""{
            "eligible":true, "at_limit":true, "exhausted":["seven_day"],
            "grants":[{"id":"model-reset", "resets_left":1, "usable_now":true, "use_requires_limit":false,
                       "gives_back_weekly":true, "clears":["five_hour","seven_day_overage_included"]}]
        }""")
        val offer = ClaudeResets.resets(status, now)!!.offers.single()
        assertEquals("Fable reset", offer.kind)
        assertTrue(offer.effect.contains("returns what Fable used to your weekly limit"))
        assertNull(offer.early)
        assertNull(ClaudeResets.resets(null, now))
    }

    @Test
    fun `claim addresses the selected organization with its own login`(@TempDir temp: Path) {
        data class Claim(val method: String, val path: String, val auth: String?, val app: String?, val body: String)
        val calls = LinkedBlockingQueue<Claim>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                calls.add(Claim(it.requestMethod, it.requestURI.path, it.requestHeaders.getFirst("Authorization"),
                    it.requestHeaders.getFirst("x-app"), it.requestBody.bufferedReader().readText()))
                val reply = """{"result":"reset","resets_left":0}""".toByteArray()
                it.sendResponseHeaders(200, reply.size.toLong())
                it.responseBody.write(reply)
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val offer = ResetOffer("sample-grant", "5-hour reset", 1, null, "Refills session", done = "Session reset")
            val keys = listOf("alpha", "bravo").map { name ->
                val home = Files.createDirectory(temp.resolve(name))
                val account = Account(name, Provider.Anthropic, home.toString())
                Files.writeString(home.resolve(".claude.json"), """{"oauthAccount":{"organizationUuid":"org-$name"}}""")
                Files.writeString(account.credentialFile,
                    """{"claudeAiOauth":{"accessToken":"token-$name","expiresAt":${Instant.now().plusSeconds(3600).toEpochMilli()}}}""")
                assertEquals(ResetOutcome(true, "Session reset · no 5-hour resets left"), ClaudeResets.claim(account, offer, base))
                val call = calls.remove()
                assertEquals("POST", call.method)
                assertEquals("/api/organizations/org-$name/reset_rate_limits", call.path)
                assertEquals("Bearer token-$name", call.auth)
                assertEquals("cli", call.app)
                val body = Json.parseToJsonElement(call.body).obj()!!
                assertEquals(ClaudeResets.PROGRAM, body["program"].str())
                assertEquals(offer.id, body["grant_id"].str())
                UUID.fromString(body["request_id"].str())
            }
            assertEquals(2, keys.distinct().size)
            assertTrue(calls.isEmpty())
            assertEquals(ResetOutcome(false, UNCONFIRMED), ClaudeResets.outcome(500, "", offer))
            assertEquals(ResetOutcome(false, UNCONFIRMED), ClaudeResets.outcome(200, "{}", offer))
            assertFalse(ClaudeResets.outcome(200, """{"result":"not_limited"}""", offer).ok)
        } finally {
            server.stop(0)
        }
    }
}
