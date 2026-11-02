package io.github.hridoyrocks.dak.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** An error answer from the Dak server. */
internal class DakApiException(
    val status: Int,
    /**
     * The server's error code, like `identity_verification_failed`; for an
     * answer that is not Dak's own JSON error, `http_` and the status.
     */
    val code: String,
    message: String,
    /** The request field the server refused, like `tags` or `external_id`. */
    val field: String? = null,
    /**
     * The answer is Dak's own JSON error. A proxy in front of it (an HTML 403,
     * a 404 page) is not, and is treated as a network problem: retried, never
     * acted on.
     */
    val fromDak: Boolean = true,
    /** How long the server asked the SDK to wait (Retry-After on 429 and 503). */
    val retryAfterMs: Long? = null,
) : Exception(message) {
    /** The device is unknown or its secret is wrong: register again. */
    val isUnauthorized: Boolean get() = fromDak && status == 401

    /** Retrying the same request cannot succeed (bad input, identity check). */
    val isPermanent: Boolean
        get() = fromDak && status in 400..499 && status != 401 && status != 408 && status != 429

    override fun toString(): String = "DakApiException($status $code: $message)"
}

/** What the server answers to a registration. */
internal class Registration(val deviceId: String, val deviceSecret: String)

/** The in-app messages for a device, with the ETag to send next time. */
internal class InAppList(val messages: JSONArray, val etag: String?)

internal class HttpResponse(
    val status: Int,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: String,
) {
    val isSuccess: Boolean get() = status in 200..299 || status == 304
}

/** One HTTP exchange. Tests replace it; the SDK uses [UrlConnectionHttp]. */
internal fun interface DakHttp {
    /**
     * Sends the request and reads the whole answer within [timeoutMs]. Throws
     * an IOException for network problems.
     */
    fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Long): HttpResponse
}

/** [DakHttp] on HttpURLConnection, with no other library. */
internal class UrlConnectionHttp : DakHttp {
    /**
     * The exchange runs on a thread of its own and the caller waits at most
     * [timeoutMs]: no slow DNS lookup or stalled server holds the SDK's worker
     * longer. The exchange is cut off then too.
     */
    override fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): HttpResponse {
        val future = exchanges.submit(Callable { exchange(method, url, headers, body, timeoutMs) })
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("no whole answer within $timeoutMs ms")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    private fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        // A server that sends headers and then stalls would otherwise hold
        // every later call: the deadline covers the whole exchange. Cut off,
        // a read may end as if the answer were complete, so the flag decides.
        val cutOff = AtomicBoolean(false)
        val stop = watchdog.schedule({
            cutOff.set(true)
            conn.disconnect()
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            setMethod(conn, method)
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                // Not in streaming mode: bodies are small, and the JVM's
                // HttpURLConnection cannot hand back a 401 in streaming mode.
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { readLimited(it) } ?: ""
            val out = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null && v.isNotEmpty()) out[k.lowercase(Locale.ROOT)] = v.joinToString(", ")
            }
            if (cutOff.get()) throw SocketTimeoutException("no whole answer within $timeoutMs ms")
            return HttpResponse(status, out, text)
        } catch (e: IOException) {
            if (cutOff.get() && e !is SocketTimeoutException) throw SocketTimeoutException("no whole answer within $timeoutMs ms")
            throw e
        } finally {
            stop.cancel(false)
            conn.disconnect()
        }
    }

    /** Android's HttpURLConnection takes PATCH; the JVM's (unit tests) needs this way round. */
    private fun setMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
        } catch (e: ProtocolException) {
            try {
                val field = HttpURLConnection::class.java.getDeclaredField("method")
                field.isAccessible = true
                field.set(conn, method)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY_BYTES) throw IOException("answer larger than $MAX_BODY_BYTES bytes")
        }
        return out.toString("UTF-8")
    }

    companion object {
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024

        private val exchanges: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "dak-http").apply { isDaemon = true }
        }

        // Disconnects exchanges that run past their deadline; a blocked read
        // returns then.
        private val watchdog: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dak-http-deadline").apply { isDaemon = true } }
        }
    }
}

/** The SDK half of Dak's HTTP API. */
internal class DakApi(
    baseUrl: String,
    val appId: String,
    private val http: DakHttp,
    private val userAgent: String,
    /** The time a request may take, sending and reading the answer together. */
    private val timeoutMs: Long = 15_000,
) {
    /** The server URL, checked by [checkBaseUrl], without a trailing slash. */
    val baseUrl: String = checkBaseUrl(baseUrl)

    init {
        checkAppId(appId)
    }

    private fun url(path: String) = "$baseUrl/v1/apps/$appId$path"

    /** Registers this device and returns its id and secret. */
    fun register(body: JSONObject): Registration {
        val js = json(send("POST", url("/devices"), body.toString(), null).body)
        val id = js.optString("device_id")
        val secret = js.optString("device_secret")
        if (id.isEmpty() || secret.isEmpty()) throw IOException("registration answer without device_id or device_secret")
        return Registration(id, secret)
    }

    /** Sends changes to the device (token, user, tags, subscription, and so on). */
    fun update(deviceId: String, secret: String, body: JSONObject) {
        send("PATCH", url("/devices/$deviceId"), body.toString(), secret)
    }

    /** A tap on a notification, or on its button [actionId]. */
    fun opened(deviceId: String, secret: String, messageId: String, actionId: String?) {
        val body = JSONObject().put("message_id", messageId)
        if (actionId != null) body.put("action_id", actionId)
        send("POST", url("/devices/$deviceId/opened"), body.toString(), secret)
    }

    /** Receipts: these messages arrived on the device (at most 50). */
    fun delivered(deviceId: String, secret: String, messageIds: List<String>) {
        val body = JSONObject().put("message_ids", JSONArray(messageIds))
        send("POST", url("/devices/$deviceId/delivered"), body.toString(), secret)
    }

    /** The in-app messages this device may show; null when unchanged since [etag]. */
    fun fetchInApp(deviceId: String, secret: String, etag: String?): InAppList? {
        val res = send("GET", url("/devices/$deviceId/in-app"), null, secret, etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap())
        if (res.status == 304) return null
        return InAppList(json(res.body).optJSONArray("messages") ?: JSONArray(), res.headers["etag"])
    }

    /** Reports in-app impressions, clicks and dismissals. */
    fun sendInAppEvents(deviceId: String, secret: String, events: JSONArray) {
        send("POST", url("/devices/$deviceId/in-app/events"), JSONObject().put("events", events).toString(), secret)
    }

    private fun send(
        method: String,
        url: String,
        body: String?,
        secret: String?,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["User-Agent"] = userAgent
        if (body != null) headers["Content-Type"] = "application/json"
        if (secret != null) headers["Authorization"] = "Device $secret"
        headers.putAll(extra)
        val res = http.send(method, url, headers, body, timeoutMs)
        if (res.isSuccess) return res
        val retryAfter = parseRetryAfter(res.headers["retry-after"])
        val err = try {
            JSONObject(res.body).optJSONObject("error")
        } catch (e: JSONException) {
            null
        }
        val code = err?.optString("code").orEmpty()
        if (err != null && code.isNotEmpty()) {
            val field = if (err.isNull("field")) "" else err.optString("field")
            throw DakApiException(
                res.status, code, err.optString("message"),
                field = field.ifEmpty { null }, retryAfterMs = retryAfter,
            )
        }
        throw DakApiException(res.status, "http_${res.status}", "HTTP ${res.status}", fromDak = false, retryAfterMs = retryAfter)
    }

    private fun json(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw IOException("the answer is not JSON", e)
    }

    companion object {
        private val localHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")
        private val appIdPattern = Regex("^[A-Za-z0-9-]{1,64}$")

        /**
         * [url] without trailing slashes. It must be https (the device secret
         * travels in every request); plain http is allowed only for this
         * computer and the Android emulator's 10.0.2.2.
         */
        fun checkBaseUrl(url: String): String {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                null
            }
            val host = uri?.host.orEmpty()
            val ok = host.isNotEmpty() && uri?.rawQuery == null && uri?.rawFragment == null &&
                (uri?.scheme == "https" || (uri?.scheme == "http" && host in localHosts))
            require(ok) { "baseUrl must be an https URL like https://push.example.com, not $url" }
            return url.trim().trimEnd('/')
        }

        fun checkAppId(appId: String) {
            require(appIdPattern.matches(appId)) { "appId must be the App ID from the Dak dashboard, not $appId" }
        }

        /** Retry-After as seconds or as an HTTP date; null when absent or unreadable. */
        fun parseRetryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            v.toLongOrNull()?.let { return if (it < 0) null else it * 1000 }
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            return try {
                val at = format.parse(v)?.time ?: return null
                (at - now).coerceAtLeast(0)
            } catch (e: ParseException) {
                null
            }
        }
    }
}
