package io.github.hridoyrocks.dak.internal

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DakApiTest {
    private val server = MockWebServer()
    private val appId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun api(timeoutMs: Long = 5_000) =
        DakApi(server.url("/").toString(), appId, UrlConnectionHttp(), "dakpush-android/test", timeoutMs)

    @Test
    fun registerPostsTheStateAndReadsTheSecret() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"device_id":"d1","device_secret":"s1","device":{}}"""))
        val reg = api().register(JSONObject().put("token", "t").put("external_id", JSONObject.NULL))
        assertEquals("d1", reg.deviceId)
        assertEquals("s1", reg.deviceSecret)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/apps/$appId/devices", req.path)
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals("dakpush-android/test", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertTrue(body.has("external_id") && body.isNull("external_id"))
    }

    @Test
    fun updateIsAPatchWithTheDeviceSecret() {
        server.enqueue(MockResponse().setBody("""{"device":{}}"""))
        api().update("d1", "s1", JSONObject().put("tags", JSONObject().put("a", JSONObject.NULL)))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/v1/apps/$appId/devices/d1", req.path)
        assertEquals("Device s1", req.getHeader("Authorization"))
        assertEquals("""{"tags":{"a":null}}""", req.body.readUtf8())
    }

    @Test
    fun opensAndReceipts() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        api().opened("d1", "s1", "m1", "accept")
        api().opened("d1", "s1", "m2", null)
        api().delivered("d1", "s1", listOf("m1", "m2"))
        val tap = server.takeRequest()
        assertEquals("/v1/apps/$appId/devices/d1/opened", tap.path)
        assertEquals("""{"message_id":"m1","action_id":"accept"}""", tap.body.readUtf8())
        assertEquals("""{"message_id":"m2"}""", server.takeRequest().body.readUtf8())
        val receipt = server.takeRequest()
        assertEquals("/v1/apps/$appId/devices/d1/delivered", receipt.path)
        assertEquals("""{"message_ids":["m1","m2"]}""", receipt.body.readUtf8())
    }

    @Test
    fun inAppMessagesWithTheirETag() {
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("""{"messages":[{"id":"x"}]}"""))
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(MockResponse().setBody("""{"accepted":1}"""))
        val list = api().fetchInApp("d1", "s1", null)!!
        assertEquals(1, list.messages.length())
        assertEquals("\"v1\"", list.etag)
        assertNull(api().fetchInApp("d1", "s1", list.etag))
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        api().sendInAppEvents("d1", "s1", JSONArray().put(JSONObject().put("type", "impression")))
        assertEquals("/v1/apps/$appId/devices/d1/in-app/events", server.takeRequest().path)
    }

    @Test
    fun dakErrorsCarryTheirCodeAndField() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"invalid","message":"bad tag","field":"tags"}}"""))
        val e = assertThrows(DakApiException::class.java) { api().update("d1", "s1", JSONObject()) }
        assertEquals(422, e.status)
        assertEquals("invalid", e.code)
        assertEquals("tags", e.field)
        assertTrue(e.fromDak)
        assertTrue(e.isPermanent)
        assertFalse(e.isUnauthorized)
    }

    @Test
    fun whatIsPermanentAndWhatIsNot() {
        fun err(status: Int, fromDak: Boolean = true) = DakApiException(status, "x", "", fromDak = fromDak)
        assertTrue(err(400).isPermanent)
        assertTrue(err(403).isPermanent)
        assertFalse(err(401).isPermanent)
        assertTrue(err(401).isUnauthorized)
        assertFalse(err(408).isPermanent)
        assertFalse(err(429).isPermanent)
        assertFalse(err(500).isPermanent)
        // A proxy's own page is a network problem.
        assertFalse(err(403, fromDak = false).isPermanent)
        assertFalse(err(401, fromDak = false).isUnauthorized)
    }

    @Test
    fun anAnswerThatIsNotDaksIsMarkedSo() {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Retry-After", "30").setBody("<html>no</html>"))
        val e = assertThrows(DakApiException::class.java) { api().opened("d1", "s1", "m1", null) }
        assertFalse(e.fromDak)
        assertEquals("http_403", e.code)
        assertEquals(30_000L, e.retryAfterMs)
    }

    @Test
    fun aStalledAnswerTimesOutAsAWhole() {
        // Headers at once, then the body a byte a second: no single read waits long.
        server.enqueue(MockResponse().setBody("""{"device":{}}""").throttleBody(1, 1, TimeUnit.SECONDS))
        val started = System.nanoTime()
        try {
            api(timeoutMs = 1_500).update("d1", "s1", JSONObject())
            fail("no timeout")
        } catch (e: IOException) {
            // expected
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 4_000)
    }

    @Test
    fun noAnswerAtAllTimesOut() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThrows(IOException::class.java) { api(timeoutMs = 1_000).delivered("d1", "s1", listOf("m")) }
    }

    @Test
    fun aRegistrationAnswerWithoutTheSecretIsAnError() {
        server.enqueue(MockResponse().setBody("""{"device_id":"d1"}"""))
        assertThrows(IOException::class.java) { api().register(JSONObject()) }
    }

    @Test
    fun baseUrlsMustBeHttps() {
        assertEquals("https://push.example.com", DakApi.checkBaseUrl("https://push.example.com/"))
        assertEquals("https://example.com/dak", DakApi.checkBaseUrl("https://example.com/dak"))
        assertEquals("http://10.0.2.2:8080", DakApi.checkBaseUrl("http://10.0.2.2:8080"))
        assertEquals("http://localhost:8080", DakApi.checkBaseUrl("http://localhost:8080"))
        assertEquals("http://[::1]:8080", DakApi.checkBaseUrl("http://[::1]:8080"))
        for (bad in listOf("http://push.example.com", "push.example.com", "ftp://x.com", "https://", "https://x.com/?a=1", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { DakApi.checkBaseUrl(bad) }
        }
    }

    @Test
    fun appIdsCannotChangeThePath() {
        DakApi.checkAppId(appId)
        for (bad in listOf("", "a/b", "../x", "a b")) {
            assertThrows(bad, IllegalArgumentException::class.java) { DakApi.checkAppId(bad) }
        }
    }

    @Test
    fun retryAfterInSecondsOrAsADate() {
        assertEquals(120_000L, DakApi.parseRetryAfter("120"))
        assertNull(DakApi.parseRetryAfter("-1"))
        assertNull(DakApi.parseRetryAfter(null))
        assertNull(DakApi.parseRetryAfter("soon"))
        val now = 1_700_000_000_000L // Tue, 14 Nov 2023 22:13:20 GMT
        assertEquals(40_000L, DakApi.parseRetryAfter("Tue, 14 Nov 2023 22:14:00 GMT", now))
        assertEquals(0L, DakApi.parseRetryAfter("Tue, 14 Nov 2023 22:00:00 GMT", now))
    }
}
