package iondrive.nop.agent

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledOnOs(OS.LINUX, OS.MAC)
class CodexModelsTest {
    private fun cli(tmp: Path, body: String): Path = tmp.resolve("codex-stub").also {
        Files.writeString(it, "#!/bin/sh\nset -eu\n$body\n")
        check(it.toFile().setExecutable(true))
    }

    @Test
    fun `discovery initializes then collects visible model slugs across pages per account`(@TempDir tmp: Path) {
        val binary = cli(tmp, """
            [ "${'$'}1" = app-server ]
            printf '%s\n' "${'$'}CODEX_HOME" > "${'$'}HOME/codex-home"
            IFS= read -r init
            printf '%s\n' "${'$'}init" > "${'$'}HOME/requests"
            printf '%s\n' '{"id":1,"result":{}}'
            IFS= read -r ready
            printf '%s\n' "${'$'}ready" >> "${'$'}HOME/requests"
            IFS= read -r page
            printf '%s\n' "${'$'}page" >> "${'$'}HOME/requests"
            printf '%s\n' '{"method":"catalog/updated"}' '{"id":99,"result":{}}'
            printf '{"id":2,"result":{"data":[{"id":"display-id","model":"model-%s","hidden":false},{"model":"internal-model","hidden":true},{"model":""}],"nextCursor":"page-two"}}\n' "${'$'}{HOME##*/}"
            IFS= read -r page
            printf '%s\n' "${'$'}page" >> "${'$'}HOME/requests"
            printf '{"id":3,"result":{"data":[{"model":"model-%s"},{"model":"model-small"}],"nextCursor":null}}\n' "${'$'}{HOME##*/}"
            cat > /dev/null
        """.trimIndent())

        for (name in listOf("alpha", "bravo")) {
            val home = Files.createDirectory(tmp.resolve(name))
            val account = Account(name, Provider.OpenAI, home.toString())
            assertEquals(listOf("model-$name", "model-small"), CodexModels.discover(account, binary))
            assertEquals(home.resolve(".codex").toString(), Files.readString(home.resolve("codex-home")).trim())
            val requests = Files.readAllLines(home.resolve("requests")).map { Json.parseToJsonElement(it).obj()!! }
            assertEquals(listOf("initialize", "initialized", "model/list", "model/list"), requests.map { it["method"].str() })
            assertFalse(requests[2]["params"].obj()!!["includeHidden"].bool())
            assertEquals("page-two", requests[3]["params"].obj()!!["cursor"].str())
        }
    }

    @Test
    fun `a failed or malformed discovery leaves only the default and configured choices`(@TempDir tmp: Path) {
        val account = Account("alpha", Provider.OpenAI, tmp.toString())
        assertTrue(Provider.OpenAI.fallbackModels.isEmpty())
        assertTrue(CodexModels.discover(account, null).isEmpty())
        for (response in listOf("not-json", "{\"id\":1,\"error\":{\"message\":\"unavailable\"}}")) {
            val binary = cli(tmp, """
                IFS= read -r init
                printf '%s\n' '$response'
            """.trimIndent())
            assertTrue(CodexModels.discover(account, binary).isEmpty())
        }
    }

    @Test
    fun `a stalled CLI is killed and reaped when discovery times out`(@TempDir tmp: Path) {
        val binary = cli(tmp, """
            printf '%s\n' "${'$'}${'$'}" > "${'$'}HOME/pid"
            exec sleep 30
        """.trimIndent())
        val start = System.nanoTime()
        assertTrue(CodexModels.discover(
            Account("alpha", Provider.OpenAI, tmp.toString()), binary, Duration.ofMillis(300),
        ).isEmpty())
        assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(5))
        val pid = Files.readString(tmp.resolve("pid")).trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }
}
