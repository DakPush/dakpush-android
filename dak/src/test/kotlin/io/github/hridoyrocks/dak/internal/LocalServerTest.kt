package io.github.hridoyrocks.dak.internal

import io.github.hridoyrocks.dak.DakException
import io.github.hridoyrocks.dak.internal.FakeDakServer.Companion.hmac
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The client against a real Dak server (`dak serve`), with a made-up push
 * token. Skipped unless these are set:
 *
 * ```
 * DAK_IT_BASE_URL=http://127.0.0.1:8080 DAK_IT_APP_ID=… DAK_IT_IDENTITY_SECRET=… DAK_IT_REST_KEY=dak_… \
 *   ./gradlew :dak:testDebugUnitTest --tests '*LocalServerTest*'
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class LocalServerTest {
    private val baseUrl = System.getenv("DAK_IT_BASE_URL").orEmpty()
    private val appId = System.getenv("DAK_IT_APP_ID").orEmpty()
    private val identitySecret = System.getenv("DAK_IT_IDENTITY_SECRET").orEmpty()
    private val restKey = System.getenv("DAK_IT_REST_KEY").orEmpty()
    private val errors = mutableListOf<DakException>()

    @Test
    fun registersAndKeepsTheServerInStep() {
        assumeTrue("set DAK_IT_BASE_URL and the others to run it", listOf(baseUrl, appId, identitySecret, restKey).all { it.isNotEmpty() })
        val api = DakApi(baseUrl, appId, UrlConnectionHttp(), "dakpush-android/it")
        val store = MemoryStateStore()
        val pending = MemoryPendingStore()
        val ids = mutableListOf<String>()
        val c = DakClient(
            api = api,
            store = store,
            pending = pending,
            push = { DakPushToken("it-${UUID.randomUUID()}") },
            platform = FakePlatform(),
            scheduler = ThreadScheduler(DakLog(true)),
            callbacks = Executor { it.run() },
            events = object : DakEvents {
                override fun deviceId(id: String) {
                    synchronized(ids) { ids.add(id) }
                }

                override fun error(error: DakException) {
                    synchronized(errors) { errors.add(error) }
                }
            },
            log = DakLog(true) { _, m, _ -> println(m) },
            sdkVersion = "android-0.1.0",
            shareTimezoneOffset = true,
        )
        c.start()
        waitFor { c.currentDeviceId != null }
        val deviceId = c.currentDeviceId!!

        val user = "it-${System.currentTimeMillis()}"
        assertEquals("identity_verification_failed", call { cb -> c.login(user, "0".repeat(64), cb) }?.code)
        assertEquals(1, errors.size)
        assertTrue(userDevices(user).length() == 0)

        assertNull(call { cb -> c.login(user, hmac(identitySecret, user), cb) })
        assertNull(call { cb -> c.setTags(mapOf("level" to "b2", "name" to "রহিম"), cb) })
        assertNull(call { cb -> c.setSubscribed(false, cb) })

        val device = userDevices(user).getJSONObject(0)
        assertEquals(deviceId, device.getString("id"))
        assertEquals("android", device.getString("platform"))
        assertEquals("android-0.1.0", device.getString("sdk_version"))
        assertEquals("bn", device.getString("language"))
        assertEquals(360, device.getInt("utc_offset_minutes"))
        assertFalse(device.getBoolean("subscribed"))
        assertEquals("রহিম", device.getJSONObject("tags").getString("name"))

        // Opens and receipts for messages the server does not know are accepted and ignored.
        pending.addOpen(UUID.randomUUID().toString(), "accept")
        pending.addReceipt(UUID.randomUUID().toString())
        c.takePending()
        assertNull(call { cb -> c.flush(cb) })
        val saved = JSONObject(store.value!!)
        assertEquals(0, saved.getJSONArray("opens").length())
        assertEquals(0, saved.getJSONArray("receipts").length())

        assertNull(call { cb -> c.logout(cb) })
        assertEquals(0, userDevices(user).length())
        assertEquals(listOf(deviceId), synchronized(ids) { ids.toList() })
        assertNotNull(c.credentials)
    }

    @Test
    fun inAppMessagesFromARealServer() {
        assumeTrue("set DAK_IT_BASE_URL and the others to run it", listOf(baseUrl, appId, identitySecret, restKey).all { it.isNotEmpty() })
        val log = DakLog(true) { _, m, _ -> println(m) }
        val api = DakApi(baseUrl, appId, UrlConnectionHttp(), "dakpush-android/it")
        val c = DakClient(
            api = api,
            store = MemoryStateStore(),
            pending = MemoryPendingStore(),
            push = { DakPushToken("it-${UUID.randomUUID()}") },
            platform = FakePlatform(),
            scheduler = ThreadScheduler(log),
            callbacks = Executor { it.run() },
            events = object : DakEvents {
                override fun deviceId(id: String) {}

                override fun error(error: DakException) {}
            },
            log = log,
            sdkVersion = "android-0.2.0",
        )
        c.start()
        waitFor { c.credentials != null }
        val (deviceId, secret) = c.credentials!!

        // A message for this device only, shown on the home screen.
        val created = rest(
            "POST", "/v1/in-app",
            JSONObject()
                .put("name", "Android SDK test")
                .put("status", "active")
                .put("layout", "modal")
                .put("content", JSONObject()
                    .put("title", "নতুন চ্যালেঞ্জ")
                    .put("body", "আজকের বিষয়: ভ্রমণ")
                    .put("buttons", JSONArray()
                        .put(JSONObject().put("id", "join").put("text", "শুরু").put("action", JSONObject().put("type", "deep_link").put("url", "amarpartner://challenge")))
                        .put(JSONObject().put("id", "later").put("text", "পরে").put("action", JSONObject().put("type", "dismiss")))))
                .put("target", JSONObject().put("device_ids", JSONArray().put(deviceId)))
                .put("triggers", JSONArray().put(JSONArray().put(JSONObject().put("kind", "custom").put("key", "screen").put("op", "=").put("value", "home"))))
                .put("frequency", JSONObject().put("type", "interval").put("interval_seconds", 60).put("max_displays", 2))
                .put("priority", 1000),
            201,
        )
        val id = created.getString("id")
        try {
            val shown = java.util.concurrent.atomic.AtomicReference<Pair<InAppMessage, Int>?>(null)
            val ui = object : InAppUi {
                override fun show(message: InAppMessage, token: Int, again: Boolean) = shown.set(message to token)

                override fun hide(token: Int) {
                    shown.getAndUpdate { if (it?.second == token) null else it }
                }
            }
            val clicks = java.util.concurrent.CopyOnWriteArrayList<io.github.hridoyrocks.dak.DakInAppClick>()
            val inApp = InAppController(
                api = api,
                store = MemoryStateStore(),
                credentials = { c.credentials },
                ui = ui,
                actions = object : InAppActions {
                    override fun clicked(click: io.github.hridoyrocks.dak.DakInAppClick) {
                        clicks.add(click)
                    }

                    override fun openUrl(url: String) {}

                    override fun setTags(tags: Map<String, String?>) {}
                },
                scheduler = ThreadScheduler(log, "it-in-app"),
                network = { Thread(it).start() },
                log = log,
                eventDelayMs = 100,
            )
            inApp.attach()
            inApp.start()
            // The first fetch, then nothing of ours until the trigger. Other
            // messages for every device on this server are put away unseen
            // (never drawn, so nothing is counted for them).
            Thread.sleep(1_000)
            while (true) {
                val other = shown.get() ?: break
                assertTrue("not before the trigger", other.first.id != id)
                inApp.dismiss(other.second)
                Thread.sleep(200)
            }
            inApp.addTriggers(mapOf("screen" to "home"))
            waitFor { shown.get() != null }
            val (m, token) = shown.get()!!
            assertEquals(id, m.id)
            assertEquals("নতুন চ্যালেঞ্জ", m.content.title)
            assertEquals(listOf("join", "later"), m.content.buttons.map { it.id })
            inApp.didDisplay(token)
            inApp.click(token, "join")
            waitFor {
                val stats = server(id).getJSONObject("stats")
                stats.getInt("impressions") == 1 && stats.getInt("clicks") == 1
            }
            assertEquals("join", clicks.single().buttonId)
            val stats = server(id).getJSONObject("stats")
            assertEquals(1, stats.getJSONObject("buttons").getInt("join"))
            assertEquals(1, stats.getInt("unique_impressions"))

            // The server counts what this device saw, and answers 304 while nothing changed.
            val list = api.fetchInApp(deviceId, secret, null)!!
            assertEquals(1, list.messages.getJSONObject(0).getInt("shown"))
            assertNull(api.fetchInApp(deviceId, secret, list.etag))
            // A resent event is not counted twice.
            val again = JSONArray().put(JSONObject().put("message_id", id).put("type", "impression").put("seq", 1).put("at", formatTime(System.currentTimeMillis())))
            api.sendInAppEvents(deviceId, secret, again)
            api.sendInAppEvents(deviceId, secret, again)
            assertEquals(1, server(id).getJSONObject("stats").getInt("impressions"))

            // Paused on the server: gone from the device's list.
            rest("PATCH", "/v1/in-app/$id", JSONObject().put("status", "paused"), 200)
            val after = api.fetchInApp(deviceId, secret, null)!!.messages
            assertTrue((0 until after.length()).none { after.getJSONObject(it).getString("id") == id })
        } finally {
            rest("DELETE", "/v1/in-app/$id", null, 204)
        }
    }

    private fun server(id: String): JSONObject = rest("GET", "/v1/in-app/$id", null, 200)

    private fun rest(method: String, path: String, body: JSONObject?, status: Int): JSONObject {
        val headers = mutableMapOf("Authorization" to "Bearer $restKey")
        if (body != null) headers["Content-Type"] = "application/json"
        val res = UrlConnectionHttp().send(method, "$baseUrl$path", headers, body?.toString(), 10_000)
        assertEquals(res.body, status, res.status)
        return if (res.body.isEmpty()) JSONObject() else JSONObject(res.body)
    }

    private fun userDevices(user: String): JSONArray {
        val res = UrlConnectionHttp().send(
            "GET", "$baseUrl/v1/users/$user/devices",
            mapOf("Authorization" to "Bearer $restKey"), null, 10_000,
        )
        assertEquals(200, res.status)
        return JSONObject(res.body).getJSONArray("devices")
    }

    /** Runs [action] with a callback and returns what the callback got. */
    private fun call(action: (io.github.hridoyrocks.dak.DakCallback) -> Unit): DakException? {
        val done = CountDownLatch(1)
        var result: DakException? = null
        action { e ->
            result = e
            done.countDown()
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out")
            Thread.sleep(50)
        }
    }
}
