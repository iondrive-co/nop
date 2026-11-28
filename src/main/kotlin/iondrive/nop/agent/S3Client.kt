package iondrive.nop.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.w3c.dom.Element

/**
 * Just enough of the S3 API for sync, spoken to any store that offers it (Cloudflare R2, MinIO,
 * Backblaze B2, AWS S3 itself): get, put, head, delete and list, signed with AWS Signature V4.
 *
 * Requests use path-style addresses (`<endpoint>/<bucket>/<key>`), which every such store accepts.
 * [putIf] is a conditional write: it lands only while the object is still the one read (`If-Match`)
 * or is not there yet (`If-None-Match: *`), and the store turns down every other one at once. That
 * is all the locking the lease needs; [SyncLink.ping] makes sure the store really does it.
 *
 * Nothing the store sends back is taken on trust: an answer larger than the caller allows ends the
 * request, and every request has a time limit. [rateLimit] caps what object transfers ([paced]) take,
 * in KiB a second; the small lease calls are never held back by it. [close] ends every request in
 * flight and refuses any later one.
 */
internal class S3Client(
    endpoint: String,
    private val region: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    rateLimit: Int,
    private val target: String,
) : AutoCloseable {

    /** A store's answer: its status, its body, and the headers sync reads. */
    class Reply(val status: Int, val body: ByteArray, val etag: String?, val date: Long?, val modified: Long?)

    /** One object a listing found. */
    data class Listed(val key: String, val size: Long)

    private val base: URI = normalize(URI.create(endpoint.trim().trimEnd('/')))
    private val host: String = hostHeader(base)
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT.toJavaDuration())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val inFlight = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val pace = Pace(rateLimit)

    @Volatile
    private var closed = false

    /** The object at [key], or null when there is none; refused when it is larger than [limit]. */
    fun get(key: String, limit: Int, paced: Boolean = false, timeout: Duration = CALL_TIMEOUT): Reply? {
        if (paced) pace.waitTurn(::stopped)
        val reply = send("GET", key, limit = limit, timeout = timeout, retry = true)
        if (reply.status == 404) return missing(reply)
        expect(reply, 200)
        if (paced) pace.charge(reply.body.size.toLong())
        return reply
    }

    /** What the store says about the object at [key], or null when there is none. */
    fun head(key: String): Reply? {
        val reply = send("HEAD", key, retry = true)
        if (reply.status == 404) return null
        expect(reply, 200)
        return reply
    }

    /** Writes [body] to [key] whatever is there. */
    fun put(key: String, body: ByteArray, paced: Boolean = false, timeout: Duration = CALL_TIMEOUT): Reply {
        if (paced) {
            pace.waitTurn(::stopped)
            pace.charge(body.size.toLong())
        }
        return expect(send("PUT", key, body = body, timeout = timeout, retry = true), 200)
    }

    /**
     * Writes [body] to [key] only while it still has the etag [ifMatch], or, when that is null, only
     * while there is nothing there. Null when the store turned the write down because the object had
     * changed (412) or another write to it was under way (409).
     *
     * Never tried twice here: a write whose answer was lost may have landed, so only the caller, by
     * reading again, can tell.
     */
    fun putIf(key: String, body: ByteArray, ifMatch: String?): Reply? {
        val condition = if (ifMatch != null) "if-match" to ifMatch else "if-none-match" to "*"
        val reply = send("PUT", key, body = body, extra = mapOf(condition), retry = false)
        if (reply.status == 412 || reply.status == 409) return null
        // Some stores answer If-Match on an object that was removed meanwhile with 404, not 412.
        if (ifMatch != null && reply.status == 404) return missing(reply)
        return expect(reply, 200)
    }

    /** Removes the object at [key], if there is one. */
    fun delete(key: String) {
        val reply = send("DELETE", key, retry = true)
        if (reply.status != 404) expect(reply, 200, 204)
    }

    /** Every object whose key starts with [prefix]. */
    fun list(prefix: String): List<Listed> {
        val out = ArrayList<Listed>()
        var token: String? = null
        do {
            val query = sortedMapOf("list-type" to "2", "prefix" to prefix, "max-keys" to "1000")
            if (token != null) query["continuation-token"] = token
            val reply = expect(send("GET", null, query = query, limit = LIST_LIMIT, retry = true), 200)
            val root = xml(reply.body) ?: throw SyncFailure("$target sent a listing nop could not read")
            for (contents in root.children("Contents")) {
                val key = contents.child("Key") ?: continue
                out += Listed(key, contents.child("Size")?.toLongOrNull() ?: 0)
            }
            token = root.child("NextContinuationToken")?.takeIf { root.child("IsTruncated") == "true" }
        } while (token != null)
        return out
    }

    override fun close() {
        closed = true
        inFlight.forEach { it.cancel(true) }
        client.shutdownNow()
    }

    private fun stopped() {
        if (closed) throw SyncFailure("syncing with $target was stopped")
    }

    private fun missing(reply: Reply): Reply? {
        if (errorCode(reply)?.first == "NoSuchBucket") throw SyncFailure(problem(reply))
        return null
    }

    private fun expect(reply: Reply, vararg statuses: Int): Reply {
        if (reply.status !in statuses) throw SyncFailure(problem(reply))
        return reply
    }

    private fun problem(reply: Reply): String {
        val (code, message) = errorCode(reply) ?: (null to null)
        val said = listOfNotNull(code, message).joinToString(": ").take(300)
        return when {
            code == "NoSuchBucket" -> "there is no bucket called $bucket at $host"
            code == "RequestTimeTooSkewed" -> "$host turned nop down because this machine's clock is off by more than 15 minutes"
            code in KEY_REFUSED || reply.status == 403 ->
                "$host refused the access key${if (said.isNotEmpty()) ": $said" else ""}"
            said.isNotEmpty() -> "could not sync with $target: $said (HTTP ${reply.status})"
            else -> "could not sync with $target (HTTP ${reply.status})"
        }
    }

    private fun errorCode(reply: Reply): Pair<String?, String?>? {
        val root = xml(reply.body) ?: return null
        return root.child("Code") to root.child("Message")
    }

    private fun send(
        method: String,
        key: String?,
        body: ByteArray = EMPTY,
        query: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        limit: Int = SMALL_LIMIT,
        timeout: Duration = CALL_TIMEOUT,
        retry: Boolean,
    ): Reply {
        var attempt = 0
        while (true) {
            stopped()
            attempt++
            val last = !retry || attempt >= TRIES
            try {
                val reply = once(method, key, body, query, extra, limit, timeout)
                if (!last && (reply.status >= 500 || reply.status == 429)) {
                    backOff(attempt)
                    continue
                }
                return reply
            } catch (e: IOException) {
                if (last) throw SyncFailure(unreachable(e))
                backOff(attempt)
            }
        }
    }

    private fun backOff(attempt: Int) {
        val until = System.nanoTime() + attempt * BACK_OFF.inWholeNanoseconds
        while (System.nanoTime() < until) {
            stopped()
            Thread.sleep(SLICE_MS)
        }
    }

    private fun unreachable(e: IOException): String = when (e) {
        is java.net.http.HttpConnectTimeoutException, is java.net.ConnectException -> "could not reach $host: ${e.message ?: "no answer"}"
        is java.net.http.HttpTimeoutException -> "$host took too long to answer"
        is java.net.UnknownHostException -> "could not find $host: ${e.message}"
        else -> "could not sync with $target: ${e.message ?: e.toString()}"
    }

    private fun once(
        method: String,
        key: String?,
        body: ByteArray,
        query: Map<String, String>,
        extra: Map<String, String>,
        limit: Int,
        timeout: Duration,
    ): Reply {
        val path = "/" + encode(bucket) + if (key != null) "/" + key.split('/').joinToString("/") { encode(it) } else ""
        val queryText = query.entries.sortedBy { encode(it.key) }.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val payloadHash = SyncCrypto.hex(SyncCrypto.sha256(body))
        val amzDate = AMZ_DATE.format(Instant.now())
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate)
        headers.putAll(extra)
        val authorization = authorization(method, host, path, queryText, headers, payloadHash, amzDate, region, accessKey, secretKey)

        val uri = URI.create(base.toString() + path + if (queryText.isNotEmpty()) "?$queryText" else "")
        val publisher = if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout.toJavaDuration())
            .method(method, publisher)
            .apply { headers.forEach { (name, value) -> if (name != "host") header(name, value) } }
            .header("authorization", authorization)
            .build()
        val handler = HttpResponse.BodyHandler { info ->
            Limited(if (info.statusCode() in 200..299) limit.toLong() else ERROR_LIMIT)
        }
        val future = client.sendAsync(request, handler)
        inFlight += future
        try {
            if (closed) future.cancel(true)
            val response = future.get(timeout.inWholeMilliseconds + SLICE_MS, TimeUnit.MILLISECONDS)
            val headerOf = { name: String -> response.headers().firstValue(name).orElse(null) }
            return Reply(
                status = response.statusCode(),
                body = response.body(),
                etag = headerOf("etag"),
                date = headerOf("date")?.let(::httpDate),
                modified = headerOf("last-modified")?.let(::httpDate),
            )
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw java.net.http.HttpTimeoutException("no answer in $timeout")
        } catch (_: CancellationException) {
            stopped()
            throw IOException("the request was cancelled")
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw SyncFailure("syncing with $target was stopped")
        } catch (e: ExecutionException) {
            stopped()
            when (val cause = e.cause) {
                is TooLarge -> throw SyncFailure("$host sent more than nop asked for")
                is IOException -> throw cause
                is SyncFailure -> throw cause
                else -> throw IOException(cause?.message ?: e.toString(), cause)
            }
        } finally {
            inFlight -= future
        }
    }

    /** Collects a body, ending the request once it passes [limit] bytes. */
    private class Limited(private val limit: Long) : HttpResponse.BodySubscriber<ByteArray> {
        private val out = ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray>()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            for (buffer in item) {
                if (out.size() + buffer.remaining().toLong() > limit) {
                    subscription?.cancel()
                    result.completeExceptionally(TooLarge())
                    return
                }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(out.toByteArray())
        }
    }

    private class TooLarge : IOException("the answer was larger than allowed")

    /**
     * Keeps transfers to [kibPerSecond] on average. A transfer waits its turn, then pushes the next
     * turn back by as long as its bytes take at the cap, so at most one chunk goes over it at a time.
     */
    private class Pace(private val kibPerSecond: Int) {
        private var nextFree = System.nanoTime()

        fun waitTurn(check: () -> Unit) {
            if (kibPerSecond <= 0) return
            while (true) {
                check()
                val wait = synchronized(this) { nextFree - System.nanoTime() }
                if (wait <= 0) return
                Thread.sleep(minOf(SLICE_MS, TimeUnit.NANOSECONDS.toMillis(wait) + 1))
            }
        }

        fun charge(bytes: Long) {
            if (kibPerSecond <= 0) return
            val cost = bytes * 1_000_000_000L / (kibPerSecond * 1024L)
            synchronized(this) { nextFree = maxOf(System.nanoTime(), nextFree) + cost }
        }
    }

    companion object {
        val CALL_TIMEOUT = 60.seconds
        private val CONNECT_TIMEOUT = 20.seconds
        private val BACK_OFF = 1.seconds
        private const val TRIES = 3
        private const val SLICE_MS = 250L
        private const val ERROR_LIMIT = 64L * 1024
        private const val SMALL_LIMIT = 16 * 1024
        private const val LIST_LIMIT = 8 * 1024 * 1024
        private val EMPTY = ByteArray(0)
        private val KEY_REFUSED = setOf("AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken")
        private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /**
         * The `Authorization` header AWS Signature V4 gives a request: [path] and [query] already
         * encoded as they are sent, [headers] by lower-case name (all of them are signed).
         */
        fun authorization(
            method: String,
            host: String,
            path: String,
            query: String,
            headers: Map<String, String>,
            payloadHash: String,
            amzDate: String,
            region: String,
            accessKey: String,
            secretKey: String,
        ): String {
            val signed = (headers + ("host" to host)).mapKeys { it.key.lowercase() }.toSortedMap()
            val canonicalHeaders = signed.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
            val signedNames = signed.keys.joinToString(";")
            val canonical = listOf(method, path, query, canonicalHeaders, signedNames, payloadHash).joinToString("\n")
            val day = amzDate.substring(0, 8)
            val scope = "$day/$region/s3/aws4_request"
            val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n" + SyncCrypto.hex(SyncCrypto.sha256(canonical.toByteArray()))
            var signingKey = SyncCrypto.hmac("AWS4$secretKey".toByteArray(), day.toByteArray())
            for (part in listOf(region, "s3", "aws4_request")) signingKey = SyncCrypto.hmac(signingKey, part.toByteArray())
            val signature = SyncCrypto.hex(SyncCrypto.hmac(signingKey, toSign.toByteArray()))
            return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope,SignedHeaders=$signedNames,Signature=$signature"
        }

        /** [value] as S3 wants it in a path segment or query: everything but A-Z a-z 0-9 - _ . ~ escaped. */
        fun encode(value: String): String {
            val out = StringBuilder()
            for (b in value.toByteArray()) {
                val c = b.toInt() and 0xff
                if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.~") {
                    out.append(c.toChar())
                } else {
                    out.append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 0xf])
                }
            }
            return out.toString()
        }

        /** [uri] without its port when that is the scheme's own, so it matches the Host header sent. */
        fun normalize(uri: URI): URI {
            val default = if (uri.scheme == "https") 443 else 80
            val port = if (uri.port == default) -1 else uri.port
            return URI(uri.scheme, null, uri.host, port, null, null, null)
        }

        /** The Host header Java's HttpClient sends for [uri], which is what must be signed. */
        fun hostHeader(uri: URI): String = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"

        private fun httpDate(text: String): Long? =
            runCatching { DateTimeFormatter.RFC_1123_DATE_TIME.parse(text.trim(), Instant::from).epochSecond }.getOrNull()

        private fun xml(bytes: ByteArray): Element? {
            if (bytes.isEmpty()) return null
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.isExpandEntityReferences = false
                factory.isNamespaceAware = false
                factory.newDocumentBuilder().apply { setErrorHandler(null) }.parse(bytes.inputStream()).documentElement
            }.getOrNull()
        }

        private fun Element.children(name: String): List<Element> {
            val out = ArrayList<Element>()
            var node = firstChild
            while (node != null) {
                if (node is Element && node.tagName == name) out += node
                node = node.nextSibling
            }
            return out
        }

        private fun Element.child(name: String): String? = children(name).firstOrNull()?.textContent

        private fun Duration.toJavaDuration(): java.time.Duration = java.time.Duration.ofMillis(inWholeMilliseconds)
    }
}
