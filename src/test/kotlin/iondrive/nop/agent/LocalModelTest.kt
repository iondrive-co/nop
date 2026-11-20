package iondrive.nop.agent

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `discovers the model and configures the Messages client for a resumed session`() {
        withModels("""{"data":[{"id":"local-orchid","meta":{"n_ctx":32768}}]}""") { endpoint ->
            val home = dir.resolve("account")
            val account = Account("local", Provider.Local, home.toString(), endpoint = "$endpoint/v1/")
            val command = Spawn.command(account, dir.toFile(), seed = "Continue.", resumeId = "previous-session")
            val launch = LocalModel.prepare(account, command)

            assertEquals(endpoint, launch.env["ANTHROPIC_BASE_URL"])
            assertEquals(home.toString(), launch.env["CLAUDE_CONFIG_DIR"])
            assertEquals("local-orchid", launch.env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
            assertEquals("32768", launch.env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
            assertEquals("", launch.env["ANTHROPIC_API_KEY"])
            assertTrue(launch.env.getValue("CLAUDE_CODE_MODEL_CAPABILITIES").contains("-mid_conv_system"))
            assertEquals(listOf("--model", "local-orchid"), launch.argv.slice(1..2))
            assertEquals(listOf("--resume", "previous-session"), launch.argv.dropWhile { it != "--resume" }.take(2))
            assertEquals("Continue.", launch.argv.last())
            assertNull(LocalModel.status(account).unavailable)
        }
    }

    @Test
    fun `an empty catalog reports an error instead of launching with a default model`() {
        withModels("""{"data":[]}""") { endpoint ->
            val account = Account("local", Provider.Local, dir.toString(), endpoint = endpoint)
            assertNotNull(LocalModel.status(account).unavailable)
            val error = assertFailsWith<IllegalStateException> {
                LocalModel.prepare(account, Spawn.command(account, dir.toFile()))
            }
            assertTrue(error.message.orEmpty().contains("no model available"))
        }
    }

    private fun withModels(body: String, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { request ->
            val bytes = body.toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
