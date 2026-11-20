package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

internal object LocalModel {
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:8000"

    private val timeout = Duration.ofSeconds(3)
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()

    fun endpoint(value: String): String {
        val url = runCatching { URI(value.trim()) }.getOrNull()
        require(url != null && url.scheme in setOf("http", "https") && url.host != null &&
            url.port in -1..65535 && url.userInfo == null && url.query == null && url.fragment == null
        ) { "Enter an HTTP or HTTPS server URL." }
        return url.toASCIIString().trimEnd('/').removeSuffix("/v1")
    }

    private data class Model(val id: String, val maxContext: Int?)

    private fun discover(url: String): Model {
        val request = HttpRequest.newBuilder(URI.create("$url/v1/models")).timeout(timeout).build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: IOException) {
            error("Could not reach the local model server at $url.")
        }
        check(response.statusCode() == 200) { "The local model list at $url/v1/models returned HTTP ${response.statusCode()}." }
        val data = runCatching {
            Json.parseToJsonElement(response.body()).obj()?.get("data").arr()
        }.getOrNull()
        checkNotNull(data) { "The server at $url did not return an OpenAI model list." }
        return data.firstNotNullOfOrNull {
            val entry = it.obj() ?: return@firstNotNullOfOrNull null
            val id = (entry["id"] as? JsonPrimitive)?.takeIf { id ->
                id.isString && id.content.isNotBlank() && id.content.none(Char::isISOControl)
            }?.content ?: return@firstNotNullOfOrNull null
            Model(id, entry["meta"].obj()?.get("n_ctx").int()?.takeIf { size -> size > 0 })
        }
            ?: error("The server at $url has no model available.")
    }

    fun status(account: Account): UsageReading = runCatching {
        discover(endpoint(account.endpoint ?: DEFAULT_ENDPOINT))
    }.fold(
        onSuccess = { UsageReading(null, null, Instant.now()) },
        onFailure = { UsageReading.unavailable(it.message ?: "Local model unavailable.") },
    )

    fun prepare(account: Account, command: AgentCommand): AgentCommand {
        val url = endpoint(account.endpoint ?: DEFAULT_ENDPOINT)
        val model = discover(url)
        val environment = buildMap {
            put("ANTHROPIC_BASE_URL", url)
            put("ANTHROPIC_AUTH_TOKEN", "local")
            put("ANTHROPIC_API_KEY", "")
            put("CLAUDE_CODE_USE_BEDROCK", "0")
            put("CLAUDE_CODE_USE_VERTEX", "0")
            put("CLAUDE_CODE_USE_FOUNDRY", "0")
            put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
            put("CLAUDE_CODE_ATTRIBUTION_HEADER", "0")
            put("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1")
            // Standard Messages gateways expect system instructions before the conversation.
            put("CLAUDE_CODE_MODEL_CAPABILITIES", "*=-mid_conv_system,-mid_conv_tool_change")
            put("CLAUDE_CODE_EFFORT_LEVEL", "medium")
            put("ANTHROPIC_MODEL", model.id)
            // Claude Code also uses its smaller model tiers for background work.
            put("ANTHROPIC_DEFAULT_OPUS_MODEL", model.id)
            put("ANTHROPIC_DEFAULT_SONNET_MODEL", model.id)
            put("ANTHROPIC_DEFAULT_HAIKU_MODEL", model.id)
            put("ANTHROPIC_SMALL_FAST_MODEL", model.id)
            model.maxContext?.let { put("CLAUDE_CODE_MAX_CONTEXT_TOKENS", it.toString()) }
        }
        return command.copy(
            argv = command.argv.take(1) + listOf("--model", model.id) + command.argv.drop(1),
            env = command.env + environment,
        )
    }
}
