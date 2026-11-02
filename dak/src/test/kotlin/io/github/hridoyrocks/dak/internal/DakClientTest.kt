package io.github.hridoyrocks.dak.internal

import io.github.hridoyrocks.dak.DakCallback
import io.github.hridoyrocks.dak.DakException
import io.github.hridoyrocks.dak.internal.FakeDakServer.Companion.hmac
import io.github.hridoyrocks.dak.internal.FakeDakServer.Companion.tagMap
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DakClientTest {
    private val server = FakeDakServer()
    private val scheduler = TestScheduler()
    private val store = MemoryStateStore()
    private val pending = MemoryPendingStore()
    private val platform = FakePlatform()
    private val deviceIds = mutableListOf<String>()
    private val errors = mutableListOf<DakException>()

    @Volatile
    private var pushToken: DakPushToken? = DakPushToken("token-1")

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private val events = object : DakEvents {
        override fun deviceId(id: String) {
            deviceIds.add(id)
        }

        override fun error(error: DakException) {
            errors.add(error)
        }
    }

    private fun api(baseUrl: String = server.baseUrl, appId: String = server.appId) =
        DakApi(baseUrl, appId, UrlConnectionHttp(), "dak-android/test", timeoutMs = 5_000)

    private fun client(
        api: DakApi = api(),
        store: DakStateStore = this.store,
        scheduler: DakScheduler = this.scheduler,
        push: DakPushSource = DakPushSource { pushToken },
        background: (Runnable) -> Unit = { it.run() },
        shareTimezoneOffset: Boolean = false,
    ) = DakClient(
        api = api,
        store = store,
        pending = pending,
        push = push,
        platform = platform,
        scheduler = scheduler,
        callbacks = Executor { it.run() },
        events = events,
        log = DakLog(true) { _, m, e -> println(if (e == null) m else "$m: $e") },
        sdkVersion = "android-0.1.0",
        shareTimezoneOffset = shareTimezoneOffset,
        clock = { this.scheduler.now },
        random = Random(7),
        background = background,
    )

    /** A client that has started and registered. */
    private fun registered(): DakClient {
        val c = client()
        c.start()
        scheduler.runUntilIdle()
        assertEquals(1, server.devices.size)
        return c
    }

    private class Result : DakCallback {
        var called = false
        var error: DakException? = null

        override fun onComplete(error: DakException?) {
            called = true
            this.error = error
        }
    }

    private fun device() = server.devices.values.single()

    private fun savedState() = JSONObject(store.value!!)

    @Test
    fun registersWithTheWholeStateThenSendsOnlyChanges() {
        val c = client(shareTimezoneOffset = true)
        c.start()
        scheduler.runUntilIdle()

        val reg = server.registrations().single().body!!
        assertEquals("token-1", reg.getString("token"))
        assertEquals("token", reg.getString("token_type"))
        assertEquals("android", reg.getString("platform"))
        assertEquals("android-0.1.0", reg.getString("sdk_version"))
        assertEquals("1.2.0+12", reg.getString("app_version"))
        assertEquals("bn", reg.getString("language"))
        assertEquals(360, reg.getInt("utc_offset_minutes"))
        assertTrue(reg.getBoolean("push_permission"))
        assertTrue(reg.getBoolean("subscribed"))
        // An old row the server hands over must lose its user.
        assertTrue(reg.has("external_id") && reg.isNull("external_id"))
        assertFalse(reg.has("auth_hash"))
        assertEquals(listOf(device().id), deviceIds)
        assertEquals(device().id, c.currentDeviceId)

        val result = Result()
        c.setTags(mapOf("level" to "b2", "premium" to "true"), result)
        scheduler.runUntilIdle()
        assertTrue(result.called)
        assertNull(result.error)
        val patch = server.patches().single()
        assertEquals("Device ${device().secret}", patch.auth)
        assertEquals(setOf("tags"), patch.body!!.keys().asSequence().toSet())
        assertEquals(mapOf("level" to "b2", "premium" to "true"), device().fields.tagMap())

        // Nothing changed: nothing sent.
        c.flush()
        scheduler.runUntilIdle()
        assertEquals(1, server.patches().size)
    }

    @Test
    fun changesMadeTogetherGoInOneRound() {
        val c = registered()
        val results = List(3) { Result() }
        c.setTags(mapOf("a" to "1"), results[0])
        c.setTags(mapOf("b" to "2"), results[1])
        c.setSubscribed(false, results[2])
        scheduler.runUntilIdle()
        assertEquals(1, server.patches().size)
        assertTrue(results.all { it.called && it.error == null })
        assertEquals(mapOf("a" to "1", "b" to "2"), device().fields.tagMap())
        assertFalse(device().fields.getBoolean("subscribed"))
    }

    @Test
    fun aSlowPushTokenHoldsNothing() {
        val release = CountDownLatch(1)
        val real = ThreadScheduler(DakLog(false))
        val c = client(
            scheduler = real,
            push = DakPushSource {
                release.await(10, TimeUnit.SECONDS)
                DakPushToken("late-token")
            },
            background = { Thread(it).start() },
        )
        c.start()
        val done = CountDownLatch(1)
        c.login("42", null) { done.countDown() }
        // The login is saved and reported while Firebase still has no token.
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(server.calls.isEmpty())
        assertEquals("42", savedState().getString("external_id"))

        release.countDown()
        waitFor { server.devices.isNotEmpty() }
        assertEquals("42", device().fields.getString("external_id"))
        assertEquals("late-token", device().fields.getString("token"))
    }

    @Test
    fun changesMadeOfflineAreSentOnALaterStart() {
        val offline = client(api = api(baseUrl = "http://127.0.0.1:1"))
        offline.start()
        val result = Result()
        offline.login("42", null, result)
        offline.setTags(mapOf("level" to "b2"), null)
        scheduler.runUntilIdle()
        // Network trouble is not a refusal: the change is kept.
        assertTrue(result.called)
        assertNull(result.error)
        assertTrue(scheduler.pendingTimers() > 0)
        offline.dispose()
        scheduler.runUntilIdle()

        client().start()
        scheduler.runUntilIdle()
        assertEquals("42", device().fields.getString("external_id"))
        assertEquals(mapOf("level" to "b2"), device().fields.tagMap())
    }

    @Test
    fun failedSyncsAreRetriedWithBackoff() {
        val c = registered()
        repeat(2) { server.queued.add(MockResponse().setResponseCode(503)) }
        c.setTags(mapOf("a" to "1"), null)
        scheduler.runUntilIdle()
        assertEquals(1, server.patches().size)
        scheduler.advanceBy(5_000) // first retry: 2.5-5 s
        assertEquals(2, server.patches().size)
        scheduler.advanceBy(15_000) // second: 7.5-15 s
        assertEquals(3, server.patches().size)
        assertEquals(mapOf("a" to "1"), device().fields.tagMap())
        assertEquals(0, scheduler.pendingTimers())
    }

    @Test
    fun aWrongAuthHashRollsBackOnlyTheUserAndTheOptOutStillGoesThrough() {
        server.identitySecret = "identity-secret"
        val c = registered()
        val optOut = Result()
        val login = Result()
        c.setSubscribed(false, optOut)
        c.login("42", "wrong", login)
        scheduler.runUntilIdle()

        assertEquals("identity_verification_failed", login.error?.code)
        assertEquals(403, login.error?.status)
        assertSame(login.error, optOut.error) // the same round
        assertEquals(1, errors.size)
        assertFalse(device().fields.getBoolean("subscribed"))
        assertTrue(device().fields.isNull("external_id"))
        assertTrue(savedState().isNull("external_id"))
        assertFalse(savedState().getBoolean("subscribed"))

        val ok = Result()
        c.login("42", hmac("identity-secret", "42"), ok)
        scheduler.runUntilIdle()
        assertNull(ok.error)
        assertEquals("42", device().fields.getString("external_id"))
    }

    @Test
    fun aWrongAuthHashAtRegistrationRegistersWithoutTheUser() {
        server.identitySecret = "identity-secret"
        val c = client()
        c.login("42", "wrong", null)
        c.start()
        scheduler.runUntilIdle()
        assertEquals(2, server.registrations().size)
        assertTrue(device().fields.isNull("external_id"))
        assertEquals("identity_verification_failed", errors.single().code)
        assertNotNull(c.currentDeviceId)
    }

    @Test
    fun aRefusedTagRollsBackTheTagsOnly() {
        val c = registered()
        c.setTags(mapOf("level" to "b2"), null)
        scheduler.runUntilIdle()
        val result = Result()
        c.setTags(mapOf("bio" to "x".repeat(256)), result) // the server counts bytes
        c.setSubscribed(false, null)
        scheduler.runUntilIdle()
        assertEquals("tags", result.error?.field)
        assertEquals(mapOf("level" to "b2"), device().fields.tagMap())
        assertFalse(device().fields.getBoolean("subscribed"))
        assertEquals(mapOf("level" to "b2"), savedState().getJSONObject("tags").let { t -> t.keys().asSequence().associateWith { t.getString(it) } })
    }

    @Test
    fun anAnswerFromAProxyIsRetriedNotActedOn() {
        val c = registered()
        server.queued.add(MockResponse().setResponseCode(403).setBody("<html>Forbidden</html>"))
        val result = Result()
        c.login("42", null, result)
        scheduler.runUntilIdle()
        assertNull(result.error)
        assertTrue(errors.isEmpty())
        assertEquals("42", savedState().getString("external_id"))
        scheduler.advanceBy(5_000)
        assertEquals("42", device().fields.getString("external_id"))
    }

    @Test
    fun retryAfterIsHonoured() {
        val c = registered()
        server.queued.add(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("""{"error":{"code":"rate_limited","message":"slow down"}}"""))
        c.setTags(mapOf("a" to "1"), null)
        scheduler.runUntilIdle()
        assertEquals(1, server.patches().size)

        // Meanwhile changes are only saved.
        c.setTags(mapOf("b" to "2"), null)
        scheduler.advanceBy(60_000)
        assertEquals(1, server.patches().size)
        assertEquals("2", savedState().getJSONObject("tags").getString("b"))

        scheduler.advanceBy(61_000)
        assertEquals(2, server.patches().size)
        assertEquals(mapOf("a" to "1", "b" to "2"), device().fields.tagMap())
    }

    @Test
    fun logoutRemovesTheUserAndTheTags() {
        val c = registered()
        c.login("42", null, null)
        c.setTags(mapOf("level" to "b2"), null)
        scheduler.runUntilIdle()
        c.logout(null)
        scheduler.runUntilIdle()
        val patch = server.patches().last().body!!
        assertTrue(patch.isNull("external_id"))
        assertTrue(patch.getJSONObject("tags").isNull("level"))
        assertTrue(device().fields.isNull("external_id"))
        assertTrue(device().fields.tagMap().isEmpty())
        assertTrue(device().fields.getBoolean("subscribed"))
    }

    @Test
    fun aDeviceTheServerForgotRegistersAgainWithItsState() {
        val c = registered()
        c.login("42", null, null)
        c.setTags(mapOf("level" to "b2"), null)
        scheduler.runUntilIdle()
        val first = device().id
        server.forget(first)

        c.setSubscribed(false, null)
        scheduler.runUntilIdle()
        val again = device()
        assertTrue(again.id != first)
        assertEquals("42", again.fields.getString("external_id"))
        assertEquals(mapOf("level" to "b2"), again.fields.tagMap())
        assertFalse(again.fields.getBoolean("subscribed"))
        assertEquals(listOf(first, again.id), deviceIds)
        assertEquals(again.id, savedState().getString("device_id"))
    }

    @Test
    fun aServerThatKeepsRefusingTheDeviceIsNotLooped() {
        val c = registered()
        repeat(5) { server.queued.add(FakeDakServer.error(401, "unauthorized", "no")) }
        c.setTags(mapOf("a" to "1"), null)
        scheduler.runUntilIdle()
        // PATCH 401, register 401: then wait for a retry.
        assertTrue(server.calls.size <= 4)
    }

    @Test
    fun receiptsAndTapsKeptElsewhereAreSentAndThenForgotten() {
        val c = registered()
        repeat(60) { pending.addReceipt("m$it") }
        pending.addOpen("m1", "accept")
        c.takePending()
        scheduler.runUntilIdle()
        assertEquals(60, server.receipts.size)
        assertEquals(2, server.callsTo("POST", "/delivered").size) // 50 a batch
        assertEquals(listOf("m1" to "accept"), server.opens.toList())
        assertTrue(pending.read().isEmpty)
        assertEquals(0, savedState().getJSONArray("receipts").length())
        assertEquals(0, savedState().getJSONArray("opens").length())
    }

    @Test
    fun tapsMadeOfflineAreKeptUntilTheyCanBeSent() {
        val offline = client(api = api(baseUrl = "http://127.0.0.1:1"))
        offline.start()
        pending.addOpen("m1", null)
        pending.addReceipt("m2")
        offline.takePending()
        scheduler.runUntilIdle()
        assertTrue(pending.read().isEmpty) // moved into the client's saved state
        assertEquals(1, savedState().getJSONArray("opens").length())
        offline.dispose()
        scheduler.runUntilIdle()

        client().start()
        scheduler.runUntilIdle()
        assertEquals(listOf("m1" to null), server.opens.toList())
        assertEquals(listOf("m2"), server.receipts.toList())
    }

    @Test
    fun aNewTokenIsSentWithItsType() {
        val c = registered()
        c.onNewToken("token-2")
        scheduler.runUntilIdle()
        val patch = server.patches().last().body!!
        assertEquals("token-2", patch.getString("token"))
        assertEquals("token", patch.getString("token_type"))

        c.onRegistered("fid-1")
        scheduler.runUntilIdle()
        assertEquals("fid-1", device().fields.getString("token"))
        assertEquals("fid", device().fields.getString("token_type"))

        // With an installation ID, token refreshes are not where pushes go.
        c.onNewToken("token-3")
        scheduler.runUntilIdle()
        assertEquals("fid-1", device().fields.getString("token"))
    }

    @Test
    fun anInstallationIdAtStartRegistersAsFid() {
        pushToken = DakPushToken("fid-9", DakPushToken.TYPE_FID)
        registered()
        assertEquals("fid", server.registrations().single().body!!.getString("token_type"))
    }

    @Test
    fun aNewerTokenWinsOverTheStartFetch() {
        var fetch: Runnable? = null
        val c = client(background = { fetch = it })
        c.start()
        scheduler.runUntilIdle()
        c.onNewToken("refreshed")
        scheduler.runUntilIdle()
        fetch!!.run() // the start's fetch ends after the refresh
        scheduler.runUntilIdle()
        assertEquals("refreshed", device().fields.getString("token"))
    }

    @Test
    fun lastSeenIsKeptFreshTwiceADay() {
        val c = registered()
        scheduler.advanceBy(11 * DakClient.HOUR_MS)
        c.onResume()
        scheduler.runUntilIdle()
        assertTrue(server.patches().isEmpty())
        scheduler.advanceBy(DakClient.HOUR_MS)
        c.onResume()
        scheduler.runUntilIdle()
        assertEquals(0, server.patches().single().body!!.length())
    }

    @Test
    fun resumePicksUpAPermissionChangedInSettings() {
        val c = registered()
        platform.enabled = false
        c.onResume()
        scheduler.runUntilIdle()
        assertFalse(device().fields.getBoolean("push_permission"))
    }

    @Test
    fun theSavedDeviceIdComesBeforeAnyNetwork() {
        registered()
        val id = device().id
        deviceIds.clear()
        val offline = client(api = api(baseUrl = "http://127.0.0.1:1"))
        offline.start()
        scheduler.runUntilIdle()
        assertEquals(listOf(id), deviceIds)
        assertEquals(id, offline.currentDeviceId)
        assertNotNull(offline.credentials)
    }

    @Test
    fun aChangeMadeBeforeTheStateLoadsWinsOverTheSavedOne() {
        val c = registered()
        c.login("old", null, null)
        scheduler.runUntilIdle()

        store.failReads = true
        val again = client()
        again.start()
        again.login("new", null, null)
        scheduler.runUntilIdle()
        val writes = store.value
        store.failReads = false
        assertEquals(writes, store.value) // nothing saved before a read worked
        scheduler.advanceBy(5_000)
        assertEquals("new", device().fields.getString("external_id"))
    }

    @Test
    fun aStateThatNeverLoadsIsGivenUp() {
        store.failReads = true
        store.value = "unreadable"
        val c = client()
        c.start()
        scheduler.advanceBy(10 * 60_000)
        assertEquals(1, server.registrations().size)
        assertNotNull(c.currentDeviceId)
    }

    @Test
    fun anotherAppIdRegistersAgainButKeepsTheUser() {
        registered()
        val saved = savedState().put("app_id", "0190ffff-0000-7000-8000-000000000000").put("external_id", "42")
        store.value = saved.toString()
        // The fake server keeps one app, so a new token makes the new device.
        pushToken = DakPushToken("token-2")
        val c = client()
        c.start()
        scheduler.runUntilIdle()
        assertEquals(2, server.registrations().size)
        assertEquals("42", server.registrations().last().body!!.getString("external_id"))
        assertTrue(c.currentDeviceId != saved.getString("device_id"))
    }

    @Test
    fun moreThanFiftyTagsFailWithoutSending() {
        val c = registered()
        c.setTags((1..50).associate { "t$it" to "v" }, null)
        scheduler.runUntilIdle()
        val result = Result()
        c.setTags(mapOf("one_more" to "v"), result)
        scheduler.runUntilIdle()
        assertEquals("tags", result.error?.field)
        assertEquals(1, server.patches().size)
    }

    @Test
    fun retryWaitsAreJitteredAndHonourRetryAfterUpToAnHour() {
        val c = client()
        repeat(20) {
            val first = c.retryDelay(0, null)
            assertTrue(first in 2_500..5_000)
            assertTrue(c.retryDelay(9, null) in 150_000..300_000)
        }
        assertTrue(c.retryDelay(0, 120_000) >= 120_000)
        assertEquals(DakClient.MAX_RETRY_AFTER_MS, c.retryDelay(0, 5 * DakClient.HOUR_MS))
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out")
            Thread.sleep(20)
        }
    }
}

internal class FakePlatform : DakPlatformInfo {
    @Volatile
    var enabled = true

    override fun appVersion() = "1.2.0+12"

    override fun osVersion() = "14"

    override fun language() = "bn"

    override fun utcOffsetMinutes() = 360

    override fun notificationsEnabled() = enabled
}
