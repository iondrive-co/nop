package iondrive.nop.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A stand-in for an S3-compatible store, served on 127.0.0.1: objects kept in memory by bucket and
 * key, with the conditional writes, listing and clock headers sync relies on. Signatures are not
 * checked here; [S3ClientTest] covers those.
 */
internal class FakeS3(vararg buckets: String = arrayOf(BUCKET)) : AutoCloseable {

    class Stored(val body: ByteArray, val etag: String, val modified: Instant)

    private val buckets = buckets.toSet()
    private val objects = ConcurrentHashMap<String, Stored>()
    private val lock = Any()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "fake-s3").also { it.isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** When false the store ignores `If-Match` and `If-None-Match`, as some stores still do. */
    @Volatile
    var conditional = true

    /** How many keys a listing gives at most, so paging is used. */
    @Volatile
    var pageSize = 1000

    /** The `Authorization` header of every request. */
    val authorizations: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Every request as its method and key, such as `GET nop-sync/key-check`. */
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    init {
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.executor = pool
        server.start()
    }

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    /** The keys held in [bucket], in order. */
    fun keys(bucket: String = BUCKET): List<String> =
        objects.keys.filter { it.startsWith("$bucket/") }.map { it.removePrefix("$bucket/") }.sorted()

    fun body(key: String, bucket: String = BUCKET): ByteArray? = objects["$bucket/$key"]?.body

    /** Puts [body] at [key] as a write from somewhere else would, with a new version and time. */
    fun replace(key: String, body: ByteArray, bucket: String = BUCKET) {
        synchronized(lock) { objects["$bucket/$key"] = stored(body) }
    }

    fun remove(key: String, bucket: String = BUCKET) {
        objects.remove("$bucket/$key")
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun stored(body: ByteArray): Stored {
        val md5 = MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it) }
        return Stored(body, "\"$md5\"", Instant.now())
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestHeaders.getFirst("Authorization")?.let { authorizations += it }
        val path = exchange.requestURI.path.removePrefix("/")
        val bucket = path.substringBefore('/')
        val key = path.substringAfter('/', "")
        requests += "${exchange.requestMethod} $key"
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        val body = exchange.requestBody.readAllBytes()
        exchange.responseHeaders.set("Date", HTTP_DATE.format(Instant.now()))
        if (bucket !in buckets) return error(exchange, 404, "NoSuchBucket", "The specified bucket does not exist.")
        val name = "$bucket/$key"
        when {
            exchange.requestMethod == "GET" && key.isEmpty() && query["list-type"] == "2" -> list(exchange, bucket, query)
            key.isEmpty() -> error(exchange, 400, "InvalidRequest", "No key was given.")
            exchange.requestMethod == "GET" || exchange.requestMethod == "HEAD" -> {
                val found = objects[name] ?: return error(exchange, 404, "NoSuchKey", "The specified key does not exist.")
                exchange.responseHeaders.set("ETag", found.etag)
                exchange.responseHeaders.set("Last-Modified", HTTP_DATE.format(found.modified))
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    exchange.sendResponseHeaders(200, found.body.size.toLong())
                    exchange.responseBody.write(found.body)
                }
            }
            exchange.requestMethod == "PUT" -> synchronized(lock) {
                val existing = objects[name]
                val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                if (conditional) {
                    if (ifNoneMatch == "*" && existing != null) {
                        return error(exchange, 412, "PreconditionFailed", "At least one of the preconditions did not hold.")
                    }
                    if (ifMatch != null && existing == null) return error(exchange, 404, "NoSuchKey", "The specified key does not exist.")
                    if (ifMatch != null && ifMatch != existing!!.etag) {
                        return error(exchange, 412, "PreconditionFailed", "At least one of the preconditions did not hold.")
                    }
                }
                val next = stored(body)
                objects[name] = next
                exchange.responseHeaders.set("ETag", next.etag)
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.requestMethod == "DELETE" -> {
                objects.remove(name)
                exchange.sendResponseHeaders(204, -1)
            }
            else -> error(exchange, 405, "MethodNotAllowed", "The method is not allowed.")
        }
    }

    private fun list(exchange: HttpExchange, bucket: String, query: Map<String, String>) {
        val prefix = query["prefix"].orEmpty()
        val after = query["continuation-token"]
        val max = minOf(query["max-keys"]?.toIntOrNull() ?: 1000, pageSize)
        val matching = keys(bucket).filter { it.startsWith(prefix) && (after == null || it > after) }
        val page = matching.take(max)
        val truncated = matching.size > page.size
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
            append("<Name>").append(bucket).append("</Name><Prefix>").append(escape(prefix)).append("</Prefix>")
            append("<KeyCount>").append(page.size).append("</KeyCount><IsTruncated>").append(truncated).append("</IsTruncated>")
            if (truncated) append("<NextContinuationToken>").append(escape(page.last())).append("</NextContinuationToken>")
            for (k in page) {
                val size = objects["$bucket/$k"]?.body?.size ?: 0
                append("<Contents><Key>").append(escape(k)).append("</Key><Size>").append(size).append("</Size></Contents>")
            }
            append("</ListBucketResult>")
        }.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/xml")
        exchange.sendResponseHeaders(200, xml.size.toLong())
        exchange.responseBody.write(xml)
    }

    private fun error(exchange: HttpExchange, status: Int, code: String, message: String) {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$message</Message></Error>"
            .toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/xml")
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, xml.size.toLong())
            exchange.responseBody.write(xml)
        }
    }

    companion object {
        const val BUCKET = "shop-sync"

        private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        private fun decode(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
