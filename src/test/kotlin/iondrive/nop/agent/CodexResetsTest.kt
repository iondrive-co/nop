package iondrive.nop.agent

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class CodexResetsTest {
    @Test
    fun `distinguishes count-only availability from an empty detail list`() {
        fun resets(summary: String) = CodexResets.resets(Json.parseToJsonElement(
            """{"rateLimitResetCredits":$summary}""",
        ).obj()!!)
        val countOnly = resets("""{"availableCount":2,"credits":null}""")!!.offers.single()
        assertEquals("", countOnly.id)
        assertEquals(2, countOnly.left)
        assertEquals(LimitResets(none = "No resets available"), resets("""{"availableCount":2,"credits":[]}"""))
        assertNull(resets("null"))
    }

    @Test
    fun `shows the first usable credit to expire and omits expired and spent credits`() {
        val result = Json.parseToJsonElement("""{
            "rateLimitResetCredits":{"availableCount":2,"credits":[
                {"id":"later","status":"available","title":"Full reset (Weekly + 5 hr)"},
                {"id":"first","status":"available","title":"Full reset (Weekly + 5 hr)","expiresAt":1900000600,"description":"Refills active windows"},
                {"id":"expired","status":"available","title":"Full reset","expiresAt":1900000000},
                {"id":"spent","status":"redeemed","title":"Full reset"},
                {"id":"pending","status":"redeeming","title":"Full reset"}
            ]}
        }""").obj()!!
        val resets = CodexResets.resets(result, Instant.ofEpochSecond(1900000000))!!
        val offer = resets.offers.single()
        assertEquals("first", offer.id)
        assertEquals("2 full resets (Weekly + 5 hr)", offer.count())
        assertEquals(Instant.ofEpochSecond(1900000600), offer.until)
        assertEquals("Refills active windows", offer.effect)
        assertEquals(listOf("Full reset · being used now"), resets.blocked)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `reads and uses a credit under the selected Codex home`(@TempDir temp: Path) {
        val binary = temp.resolve("codex-stub")
        Files.writeString(binary, """#!/bin/sh
            set -eu
            [ "${'$'}1" = app-server ]
            printf '%s\n' "${'$'}CODEX_HOME" > "${'$'}HOME/codex-home"
            IFS= read -r init
            printf '%s\n' '{"id":1,"result":{}}'
            IFS= read -r ready
            IFS= read -r request
            printf '%s\n' "${'$'}init" "${'$'}ready" "${'$'}request" >> "${'$'}HOME/requests"
            case "${'$'}request" in
                *rateLimits/read*)
                    printf '{"id":2,"result":{"rateLimitResetCredits":{"availableCount":1,"credits":[{"id":"credit-%s","status":"available"}]}}}\n' "${'$'}{HOME##*/}" ;;
                *rateLimitResetCredit/consume*) printf '%s\n' '{"id":2,"result":{"outcome":"reset"}}' ;;
            esac
            if IFS= read -r unexpected; then printf '%s\n' "${'$'}unexpected" >> "${'$'}HOME/requests"; fi
        """.trimIndent())
        check(binary.toFile().setExecutable(true))

        val keys = listOf("alpha", "bravo").map { name ->
            val home = Files.createDirectory(temp.resolve(name))
            val account = Account(name, Provider.OpenAI, home.toString())
            val offer = CodexResets.read(account, binary)!!.offers.single()
            assertEquals("credit-$name", offer.id)
            assertEquals(ResetOutcome(true, "Usage reset · no usage resets left"), CodexResets.consume(account, offer, binary))
            assertEquals(home.resolve(".codex").toString(), Files.readString(home.resolve("codex-home")).trim())
            val requests = Files.readAllLines(home.resolve("requests")).map { Json.parseToJsonElement(it).obj()!! }
            assertEquals(listOf("initialize", "initialized", CodexResets.READ,
                "initialize", "initialized", CodexResets.CONSUME), requests.map { it["method"].str() })
            val params = requests.last()["params"].obj()!!
            assertEquals(offer.id, params["creditId"].str())
            UUID.fromString(params["idempotencyKey"].str())
        }
        assertEquals(2, keys.distinct().size)
    }
}
