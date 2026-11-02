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
        val api = DakApi(baseUrl, appId, UrlConnectionHttp(), "dak-android/it")
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
