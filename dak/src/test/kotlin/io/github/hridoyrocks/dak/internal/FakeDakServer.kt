package io.github.hridoyrocks.dak.internal

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Dak's SDK endpoints as the real server answers them, on a MockWebServer:
 * registration takes over the row of a known token, PATCH needs the device
 * secret, identity verification checks the auth hash, tags are validated, and
 * opens and receipts are recorded.
 */
internal class FakeDakServer(val appId: String = APP_ID) {
    class Device(val id: String, var secret: String, var fields: JSONObject)

    class Call(val method: String, val path: String, val auth: String?, val body: JSONObject?)

    val server = MockWebServer()
    val devices = LinkedHashMap<String, Device>()
    val calls = CopyOnWriteArrayList<Call>()
    val opens = CopyOnWriteArrayList<Pair<String, String?>>()
    val receipts = CopyOnWriteArrayList<String>()

    /** Like `dak app set --identity-verification`; null leaves it off. */
    var identitySecret: String? = null

    /** Answers to give before the normal ones, in order. */
    val queued = ArrayDeque<MockResponse>()

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
    }

    fun start() = server.start()

    fun shutdown() = server.shutdown()

    fun forget(deviceId: String) = synchronized(this) { devices.remove(deviceId) }

    fun callsTo(method: String, suffix: String = ""): List<Call> = calls.filter { it.method == method && it.path.endsWith(suffix) }

    fun registrations() = callsTo("POST", "/devices")

    fun patches() = calls.filter { it.method == "PATCH" }

    @Synchronized
    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val text = request.body.readUtf8()
        val body = if (text.isEmpty()) null else JSONObject(text)
        calls.add(Call(request.method.orEmpty(), path, request.getHeader("Authorization"), body))
        queued.removeFirstOrNull()?.let { return it }

        val prefix = "/v1/apps/$appId/devices"
        if (!path.startsWith(prefix)) return error(404, "not_found", "unknown app")
        val rest = path.removePrefix(prefix).trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            request.method == "POST" && rest.isEmpty() -> register(body ?: JSONObject())
            request.method == "PATCH" && rest.size == 1 -> withDevice(rest[0], request) { update(it, body ?: JSONObject()) }
            request.method == "POST" && rest.size == 2 && rest[1] == "opened" -> withDevice(rest[0], request) {
                opens.add(body!!.getString("message_id") to body.optStringOrNull("action_id"))
                MockResponse().setResponseCode(204)
            }
            request.method == "POST" && rest.size == 2 && rest[1] == "delivered" -> withDevice(rest[0], request) {
                val ids = body!!.getJSONArray("message_ids")
                for (i in 0 until ids.length()) receipts.add(ids.getString(i))
                MockResponse().setResponseCode(204)
            }
            else -> error(404, "not_found", "no route")
        }
    }

    private fun register(body: JSONObject): MockResponse {
        val token = body.optStringOrNull("token") ?: return error(422, "invalid", "token and platform are required", "token")
        val existing = devices.values.firstOrNull { it.fields.optString("token") == token }
        val fields = JSONObject((existing?.fields ?: JSONObject().put("tags", JSONObject()).put("subscribed", true)).toString())
        apply(fields, body)?.let { return it }
        val secret = UUID.randomUUID().toString()
        val device = existing ?: Device(UUID.randomUUID().toString(), secret, fields).also { devices[it.id] = it }
        device.fields = fields
        device.secret = secret
        return MockResponse().setResponseCode(if (existing == null) 201 else 200)
            .setBody(JSONObject().put("device_id", device.id).put("device_secret", secret).toString())
    }

    private fun update(device: Device, body: JSONObject): MockResponse {
        val fields = JSONObject(device.fields.toString())
        apply(fields, body)?.let { return it }
        device.fields = fields
        return MockResponse().setResponseCode(200).setBody(JSONObject().put("device", fields).toString())
    }

    /** Validates and copies [body] onto [fields], or returns the error answer. */
    private fun apply(fields: JSONObject, body: JSONObject): MockResponse? {
        if (body.has("token_type") && !body.has("token")) return error(422, "invalid", "send token_type together with token", "token_type")
        if (body.has("token")) {
            fields.put("token", body.getString("token"))
            fields.put("token_type", body.optStringOrNull("token_type") ?: "token")
        }
        for (k in listOf("platform", "app_version", "os_version", "sdk_version", "language", "push_permission", "subscribed")) {
            if (body.has(k)) fields.put(k, body.get(k))
        }
        if (body.has("external_id")) {
            val id = body.optStringOrNull("external_id")
            if (id.isNullOrEmpty()) {
                fields.put("external_id", JSONObject.NULL)
            } else {
                val secret = identitySecret
                if (secret != null && body.optString("auth_hash") != hmac(secret, id)) {
                    return error(403, "identity_verification_failed", "auth_hash does not match external_id")
                }
                fields.put("external_id", id)
            }
        }
        if (body.has("utc_offset_minutes")) fields.put("utc_offset_minutes", body.get("utc_offset_minutes"))
        val tags = body.optJSONObject("tags")
        if (tags != null) {
            val merged = fields.optJSONObject("tags") ?: JSONObject()
            for (k in tags.keys()) {
                val v = tags.opt(k)
                if (v == JSONObject.NULL || v == "") {
                    merged.remove(k)
                } else {
                    val text = "$v"
                    if (text.toByteArray().size > 255) return error(422, "invalid", "tag $k is too long", "tags")
                    merged.put(k, text)
                }
            }
            fields.put("tags", merged)
        }
        return null
    }

    private fun withDevice(id: String, request: RecordedRequest, then: (Device) -> MockResponse): MockResponse {
        val device = devices[id]
        if (device == null || request.getHeader("Authorization") != "Device ${device.secret}") {
            return error(401, "unauthorized", "unknown device or wrong device secret; register again")
        }
        return then(device)
    }

    companion object {
        const val APP_ID = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"

        fun error(status: Int, code: String, message: String, field: String? = null): MockResponse {
            val err = JSONObject().put("code", code).put("message", message)
            if (field != null) err.put("field", field)
            return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("error", err).toString())
        }

        fun hmac(secret: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun JSONObject.tagMap(): Map<String, String> {
            val tags = optJSONObject("tags") ?: return emptyMap()
            return tags.keys().asSequence().associateWith { tags.getString(it) }
        }

        fun JSONArray.toList(): List<Any?> = (0 until length()).map { get(it) }
    }
}

/**
 * A [DakScheduler] for tests: tasks run on the test thread when the test
 * says, in order, and delayed tasks wait for virtual time.
 */
internal class TestScheduler : DakScheduler {
    private class Timed(val at: Long, val seq: Long, val task: () -> Unit) {
        var cancelled = false
    }

    private val ready = ArrayDeque<() -> Unit>()
    private val timed = ArrayList<Timed>()
    private var seq = 0L
    var now = 1_700_000_000_000L
        private set

    override fun execute(task: () -> Unit) {
        synchronized(this) { ready.addLast(task) }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancelable {
        val t = synchronized(this) { Timed(now + delayMs, seq++, task).also { timed.add(it) } }
        return Cancelable { t.cancelled = true }
    }

    /** Runs everything that is ready, and what that queues. */
    fun runUntilIdle() {
        while (true) {
            val task = synchronized(this) { ready.removeFirstOrNull() } ?: return
            task()
        }
    }

    /** Moves virtual time on, running the timers that come due on the way. */
    fun advanceBy(ms: Long) {
        val until = now + ms
        runUntilIdle()
        while (true) {
            val next = synchronized(this) {
                timed.filter { !it.cancelled && it.at <= until }.minWithOrNull(compareBy({ it.at }, { it.seq }))
                    ?.also { timed.remove(it) }
            } ?: break
            now = maxOf(now, next.at)
            next.task()
            runUntilIdle()
        }
        now = until
    }

    /** Timers still waiting. */
    fun pendingTimers(): Int = synchronized(this) { timed.count { !it.cancelled } }
}
