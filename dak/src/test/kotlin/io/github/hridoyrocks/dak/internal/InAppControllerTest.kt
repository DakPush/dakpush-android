package io.github.hridoyrocks.dak.internal

import io.github.hridoyrocks.dak.DakInAppClick
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** Draws nothing: remembers what the controller asked for. */
internal class FakeUi : InAppUi {
    class Shown(val message: InAppMessage, val token: Int, val again: Boolean)

    val shown = mutableListOf<Shown>()
    val hidden = mutableListOf<Int>()
    var current: Shown? = null

    override fun show(message: InAppMessage, token: Int, again: Boolean) {
        val s = Shown(message, token, again)
        shown.add(s)
        current = s
    }

    override fun hide(token: Int) {
        hidden.add(token)
        if (current?.token == token) current = null
    }
}

internal class FakeActions : InAppActions {
    val clicks = mutableListOf<DakInAppClick>()
    val urls = mutableListOf<String>()
    val tags = mutableListOf<Map<String, String?>>()

    override fun clicked(click: DakInAppClick) {
        clicks.add(click)
    }

    override fun openUrl(url: String) {
        urls.add(url)
    }

    override fun setTags(tags: Map<String, String?>) {
        this.tags.add(tags)
    }
}

/** Builds in-app messages as the server sends them. */
internal object Msg {
    fun of(
        id: String = UUID.randomUUID().toString(),
        layout: String = "modal",
        title: String? = "Hello",
        body: String? = "From Dak",
        priority: Int = 0,
        frequency: JSONObject = JSONObject().put("type", "once"),
        triggers: JSONArray? = null,
        content: (JSONObject) -> Unit = {},
        extra: (JSONObject) -> Unit = {},
    ): JSONObject {
        val c = JSONObject()
        if (title != null) c.put("title", title)
        if (body != null) c.put("body", body)
        content(c)
        val m = JSONObject().put("id", id).put("layout", layout).put("content", c).put("frequency", frequency)
            .put("priority", priority).put("shown", 0)
        if (triggers != null) m.put("triggers", triggers)
        extra(m)
        return m
    }

    fun cond(kind: String, op: String, value: Any? = null, key: String? = null, values: List<String>? = null): JSONObject {
        val c = JSONObject().put("kind", kind).put("op", op)
        if (value != null) c.put("value", value)
        if (key != null) c.put("key", key)
        if (values != null) c.put("values", JSONArray(values))
        return c
    }

    /** One group of AND-ed conditions. */
    fun all(vararg c: JSONObject): JSONArray = JSONArray().put(JSONArray(c.toList()))

    fun button(id: String, text: String, action: JSONObject) = JSONObject().put("id", id).put("text", text).put("action", action)
}

@RunWith(RobolectricTestRunner::class)
class InAppControllerTest {
    private val server = FakeDakServer()
    private val scheduler = TestScheduler()
    private val store = MemoryStateStore()
    private val ui = FakeUi()
    private val actions = FakeActions()
    private lateinit var api: DakApi
    private var creds: Pair<String, String>? = null
    private var foreground = true
    private var unauthorized = 0

    /** Runs network calls when the test says, to see what shows while a fetch is slow. */
    private val heldNetwork = ArrayDeque<Runnable>()
    private var holdNetwork = false

    @Before
    fun setUp() {
        server.start()
        api = DakApi(server.baseUrl, server.appId, UrlConnectionHttp(), "dakpush-android/test", timeoutMs = 5_000)
        val reg = api.register(JSONObject().put("token", "t-${UUID.randomUUID()}").put("platform", "android"))
        creds = reg.deviceId to reg.deviceSecret
    }

    @After
    fun tearDown() = server.shutdown()

    private fun controller(
        store: DakStateStore = this.store,
        eventDelayMs: Long? = 2_000,
        retryDelaysMs: List<Long> = listOf(30_000),
        urlSchemes: Set<String> = setOf("https", "http"),
    ) = InAppController(
        api = api,
        store = store,
        credentials = { creds },
        ui = ui,
        actions = actions,
        scheduler = scheduler,
        network = { r -> if (holdNetwork) heldNetwork.addLast(r) else r.run() },
        log = DakLog(true) { _, m, e -> println(if (e == null) m else "$m: $e") },
        clock = { scheduler.now },
        isForeground = { foreground },
        onUnauthorized = { unauthorized++ },
        urlSchemes = urlSchemes,
        eventDelayMs = eventDelayMs,
        retryDelaysMs = retryDelaysMs,
    )

    /** A started controller with a host, its first session run. */
    private fun started(c: InAppController = controller()): InAppController {
        c.attach()
        c.start()
        scheduler.runUntilIdle()
        return c
    }

    private fun shownId(): String? = ui.current?.message?.id

    /** The host drew what is on screen. */
    private fun display(c: InAppController) {
        c.didDisplay(ui.current!!.token)
        scheduler.runUntilIdle()
    }

    private fun events(type: String? = null) = server.inAppEvents.map { it.second }.filter { type == null || it.getString("type") == type }

    private fun session(c: InAppController, awayMs: Long = 10 * 60_000L) {
        c.onBackground()
        scheduler.advanceBy(awayMs)
        c.onForeground()
        scheduler.runUntilIdle()
    }

    @Test
    fun showsTheHighestPriorityMessageWhenTheSessionStarts() {
        val low = Msg.of(priority = 1)
        val high = Msg.of(priority = 5)
        server.inApp.addAll(listOf(low, high))
        val c = started()
        assertEquals(high.getString("id"), shownId())
        assertEquals(1, c.sessionCount)
        assertEquals(1, server.callsTo("GET", "/in-app").size)

        display(c)
        c.dismiss(ui.current!!.token)
        scheduler.advanceBy(2_000)
        assertNull(ui.current)
        assertEquals(listOf("impression", "dismiss"), events().map { it.getString("type") })
        assertTrue(events().all { it.getString("message_id") == high.getString("id") })
        val seqs = events().map { it.getLong("seq") }
        assertTrue(seqs[1] > seqs[0])
        assertTrue(events()[0].getString("at").endsWith("Z"))

        // The next one waits for something to happen (a trigger, a session).
        c.check()
        scheduler.runUntilIdle()
        assertEquals(low.getString("id"), shownId())
    }

    @Test
    fun anUnchangedListIsNotSentAgain() {
        server.inApp.add(Msg.of(frequency = JSONObject().put("type", "every_session")))
        val c = started()
        display(c)
        c.dismiss(ui.current!!.token)
        session(c)
        val gets = server.callsTo("GET", "/in-app")
        assertEquals(2, gets.size)
        assertNull(gets[0].ifNoneMatch)
        assertEquals(JSONObject(store.value!!).getString("etag"), gets[1].ifNoneMatch)
        assertEquals(2, c.sessionCount)
        // 304: the cached list is used and shows again this session.
        assertEquals(1, c.messageList.size)
        assertEquals(2, ui.shown.size)
    }

    @Test
    fun sessionsCloseTogetherUseTheListAlreadyFetched() {
        server.inApp.add(Msg.of())
        val c = started()
        c.onBackground()
        scheduler.advanceBy(20_000)
        c.onForeground()
        scheduler.runUntilIdle()
        // Back within the session timeout: the same session, no fetch.
        assertEquals(1, c.sessionCount)
        assertEquals(1, server.callsTo("GET", "/in-app").size)
    }

    @Test
    fun customTriggers() {
        val m = Msg.of(triggers = Msg.all(Msg.cond("custom", "=", "home", key = "screen")))
        server.inApp.add(m)
        val c = started()
        assertNull(ui.current)
        c.addTriggers(mapOf("screen" to "cart"))
        scheduler.runUntilIdle()
        assertNull(ui.current)
        c.addTriggers(mapOf("screen" to "home"))
        scheduler.runUntilIdle()
        assertEquals(m.getString("id"), shownId())
    }

    @Test
    fun customTriggerComparisons() {
        fun holds(cond: JSONObject, triggers: Map<String, Any>): Boolean {
            server.inApp.clear()
            val m = Msg.of(triggers = Msg.all(cond))
            server.inApp.add(m)
            ui.current = null
            val c = controller(store = MemoryStateStore())
            c.addTriggers(triggers)
            started(c)
            return shownId() == m.getString("id")
        }
        assertTrue(holds(Msg.cond("custom", "=", 3, key = "level"), mapOf("level" to "3")))
        assertTrue(holds(Msg.cond("custom", "=", "3", key = "level"), mapOf("level" to 3.0)))
        assertTrue(holds(Msg.cond("custom", "=", true, key = "pro"), mapOf("pro" to true)))
        assertFalse(holds(Msg.cond("custom", "=", true, key = "pro"), mapOf("pro" to false)))
        assertTrue(holds(Msg.cond("custom", "!=", "b1", key = "level"), mapOf("other" to 1)))
        assertFalse(holds(Msg.cond("custom", "!=", "b1", key = "level"), mapOf("level" to "b1")))
        assertTrue(holds(Msg.cond("custom", ">", 2, key = "score"), mapOf("score" to 2.5)))
        assertFalse(holds(Msg.cond("custom", "<=", 2, key = "score"), mapOf("score" to "abc")))
        assertTrue(holds(Msg.cond("custom", "in", key = "city", values = listOf("ঢাকা", "Sylhet")), mapOf("city" to "ঢাকা")))
        assertTrue(holds(Msg.cond("custom", "in", key = "n", values = listOf("5")), mapOf("n" to 5.0)))
        assertTrue(holds(Msg.cond("custom", "exists", key = "seen"), mapOf("seen" to false)))
        assertFalse(holds(Msg.cond("custom", "not_exists", key = "seen"), mapOf("seen" to false)))
        assertTrue(holds(Msg.cond("custom", "not_exists", key = "seen"), mapOf("other" to 1)))
    }

    @Test
    fun sessionCountAndTimeTriggers() {
        val second = Msg.of(triggers = Msg.all(Msg.cond("session_count", ">=", 2)))
        val thirty = Msg.of(triggers = Msg.all(Msg.cond("session_time", ">=", 30)))
        server.inApp.addAll(listOf(second, thirty))
        val c = started()
        assertNull(ui.current)
        assertEquals(scheduler.now + 30_000, c.nextTimeCheck)
        scheduler.advanceBy(30_000)
        assertEquals(thirty.getString("id"), shownId())
        display(c)
        c.dismiss(ui.current!!.token)
        scheduler.runUntilIdle()

        session(c)
        assertEquals(2, c.sessionCount)
        assertEquals(second.getString("id"), shownId())
    }

    @Test
    fun sinceInstall() {
        val day = Msg.of(triggers = Msg.all(Msg.cond("since_install", ">=", 86_400)))
        server.inApp.add(day)
        val c = started()
        assertNull(ui.current)
        scheduler.advanceBy(86_400_000)
        assertEquals(day.getString("id"), shownId())
    }

    @Test
    fun frequencies() {
        val once = Msg.of(priority = 4)
        val every = Msg.of(priority = 3, frequency = JSONObject().put("type", "every_session").put("max_displays", 2))
        val daily = Msg.of(priority = 2, frequency = JSONObject().put("type", "interval").put("interval_seconds", 86_400))
        // The server counted this one on another install of the device.
        val usedUp = Msg.of(priority = 5, extra = { it.put("shown", 1) })
        server.inApp.addAll(listOf(once, every, daily, usedUp))
        val c = started()
        val seen = mutableListOf<String>()
        fun showAll() {
            while (true) {
                val s = ui.current ?: break
                seen.add(s.message.id)
                display(c)
                c.dismiss(s.token)
                c.check()
                scheduler.runUntilIdle()
            }
        }
        showAll()
        assertEquals(listOf(once, every, daily).map { it.getString("id") }, seen)

        seen.clear()
        session(c)
        showAll()
        assertEquals(listOf(every.getString("id")), seen) // max 2 now reached; daily waits a day

        seen.clear()
        scheduler.advanceBy(86_400_000)
        session(c)
        showAll()
        assertEquals(listOf(daily.getString("id")), seen)
    }

    @Test
    fun scheduleWindow() {
        val later = Msg.of(extra = { it.put("start_at", formatTime(scheduler.now + 3_600_000)) })
        val over = Msg.of(extra = { it.put("end_at", formatTime(scheduler.now - 1)) })
        server.inApp.addAll(listOf(later, over))
        val c = started()
        assertNull(ui.current)
        scheduler.advanceBy(3_600_000)
        c.check()
        scheduler.runUntilIdle()
        assertEquals(later.getString("id"), shownId())
    }

    @Test
    fun aPauseHidesTheMessageAndItComesBackWithoutANewImpression() {
        server.inApp.add(Msg.of())
        val c = started()
        display(c)
        val first = ui.current!!
        c.setPaused(true)
        scheduler.runUntilIdle()
        assertNull(ui.current)
        assertTrue(c.isPaused)
        c.setPaused(false)
        scheduler.runUntilIdle()
        val again = ui.current!!
        assertEquals(first.message.id, again.message.id)
        assertTrue(again.again)
        display(c)
        scheduler.advanceBy(2_000)
        assertEquals(1, events("impression").size)
        assertEquals(0, events("dismiss").size)
    }

    @Test
    fun blocksNestAndAPauseIsSeparate() {
        server.inApp.add(Msg.of(frequency = JSONObject().put("type", "every_session")))
        val c = started()
        display(c)
        c.block()
        c.block()
        scheduler.runUntilIdle()
        assertNull(ui.current)
        c.unblock()
        scheduler.runUntilIdle()
        assertNull("one block is still on", ui.current)
        assertTrue(c.isPaused)
        c.setPaused(true)
        c.unblock()
        c.unblock() // one too many: ignored
        scheduler.runUntilIdle()
        assertNull("still paused", ui.current)
        c.setPaused(false)
        scheduler.runUntilIdle()
        assertFalse(c.isPaused)
        assertTrue(ui.current!!.again)
        c.block()
        c.unblock()
        scheduler.runUntilIdle()
        assertFalse(c.isPaused)
    }

    @Test
    fun aRotationMovesTheMessageToTheNewActivity() {
        server.inApp.add(Msg.of())
        val c = started()
        display(c)
        c.detach()
        scheduler.runUntilIdle()
        assertNull(ui.current)
        c.attach()
        scheduler.runUntilIdle()
        assertTrue(ui.current!!.again)
        display(c)
        scheduler.advanceBy(2_000)
        assertEquals(1, events("impression").size)
    }

    @Test
    fun aMessageNotYetDrawnIsNotCountedWhenItGoes() {
        server.inApp.add(Msg.of())
        val c = started()
        val token = ui.current!!.token
        c.dismiss(token)
        c.detach()
        scheduler.advanceBy(5_000)
        assertTrue(events().isEmpty())
    }

    @Test
    fun aMessageDueWhileAnotherIsOnScreenFollows() {
        val first = Msg.of(priority = 1)
        val cart = Msg.of(priority = 9, triggers = Msg.all(Msg.cond("custom", "exists", key = "cart")))
        server.inApp.addAll(listOf(first, cart))
        val c = started()
        display(c)
        c.addTriggers(mapOf("cart" to 1))
        scheduler.runUntilIdle()
        assertEquals(first.getString("id"), shownId())
        c.dismiss(ui.current!!.token)
        scheduler.runUntilIdle()
        assertEquals(cart.getString("id"), shownId())
    }

    @Test
    fun clicks() {
        val action = { type: String, extra: (JSONObject) -> Unit -> JSONObject().put("type", type).also(extra) }
        val m = Msg.of(content = { c ->
            c.put("buttons", JSONArray()
                .put(Msg.button("join", "যোগ দিন", action("deep_link") { it.put("url", "amarpartner://challenge/travel") }))
                .put(Msg.button("web", "Web", action("url") { it.put("url", "https://example.com/x") }))
                .put(Msg.button("tag", "Tag", action("tags") { it.put("tags", JSONObject().put("level", "b2").put("old", "")) })))
        }, frequency = JSONObject().put("type", "interval").put("interval_seconds", 60))
        server.inApp.add(m)
        val c = started()
        for (button in listOf("join", "web", "tag")) {
            display(c)
            c.click(ui.current!!.token, button)
            scheduler.runUntilIdle()
            assertNull(ui.current)
            scheduler.advanceBy(61_000)
            c.check()
            scheduler.runUntilIdle()
        }
        assertEquals(listOf("join", "web", "tag"), actions.clicks.map { it.buttonId })
        assertEquals(listOf(DakInAppClick.DEEP_LINK, DakInAppClick.URL, DakInAppClick.TAGS), actions.clicks.map { it.actionType })
        assertEquals("amarpartner://challenge/travel", actions.clicks[0].url)
        // Deep links are the app's; web pages open; tags are set ("" removes).
        assertEquals(listOf("https://example.com/x"), actions.urls)
        assertEquals(listOf(mapOf("level" to "b2", "old" to null)), actions.tags)
        assertEquals(listOf("join", "web", "tag"), events("click").map { it.getString("button_id") })
    }

    @Test
    fun onlyAllowedSchemesOpen() {
        val m = Msg.of(content = { it.put("click_action", JSONObject().put("type", "url").put("url", "intent://x#Intent;end")) })
        server.inApp.add(m)
        val c = started(controller(urlSchemes = setOf("https")))
        display(c)
        c.click(ui.current!!.token, null)
        scheduler.runUntilIdle()
        assertTrue(actions.urls.isEmpty())
        assertEquals(1, actions.clicks.size)
        assertNull(actions.clicks[0].buttonId)
    }

    @Test
    fun aClickOnAMessageWithoutAnActionDoesNothing() {
        server.inApp.add(Msg.of())
        val c = started()
        display(c)
        c.click(ui.current!!.token, null)
        c.click(ui.current!!.token, "nope")
        scheduler.runUntilIdle()
        assertTrue(ui.current != null)
        assertTrue(actions.clicks.isEmpty())
    }

    @Test
    fun eventsWaitForTheNetwork() {
        server.inApp.add(Msg.of())
        val c = started()
        server.queued.add(MockResponse().setResponseCode(503))
        display(c)
        scheduler.advanceBy(2_000)
        assertTrue(events().isEmpty())
        assertEquals(1, c.pendingEvents.size)
        scheduler.advanceBy(30_000)
        assertEquals(1, events("impression").size)
        assertTrue(c.pendingEvents.isEmpty())
    }

    @Test
    fun eventsTheServerRefusesAreDropped() {
        server.inApp.add(Msg.of())
        val c = started()
        server.queued.add(FakeDakServer.error(422, "invalid", "bad events", "events"))
        display(c)
        scheduler.advanceBy(2_000)
        assertTrue(c.pendingEvents.isEmpty())
        assertTrue(events().isEmpty())
    }

    @Test
    fun eventsAreSentInBatchesOfAHundred() {
        val ids = (1..120).map { UUID.randomUUID().toString() }
        val saved = JSONObject().put("v", 1).put("events", JSONArray(ids.mapIndexed { i, id ->
            JSONObject().put("message_id", id).put("type", "impression").put("seq", i + 1L).put("at", formatTime(scheduler.now))
        }))
        val c = started(controller(store = MemoryStateStore(saved.toString())))
        c.flushEvents()
        scheduler.runUntilIdle()
        val posts = server.callsTo("POST", "/in-app/events")
        assertEquals(listOf(100, 20), posts.map { it.body!!.getJSONArray("events").length() })
        assertEquals(120, events().size)
        assertTrue(c.pendingEvents.isEmpty())
    }

    @Test
    fun eventsSurviveARestartAndAResendIsCountedOnce() {
        server.inApp.add(Msg.of())
        val c = started()
        server.queued.add(MockResponse().setResponseCode(503))
        display(c)
        scheduler.advanceBy(2_000)
        assertEquals(1, c.pendingEvents.size)
        val pending = c.pendingEvents.single()
        val drawn = ui.shown.size
        // The process dies; the next one sends what was saved.
        val next = started(controller())
        next.flushEvents()
        scheduler.runUntilIdle()
        assertEquals(1, events("impression").size)
        // A resend (an answer lost on the way back) is skipped by seq.
        api.sendInAppEvents(creds!!.first, creds!!.second, JSONArray().put(pending))
        assertEquals(1, events("impression").size)
        // What was shown is remembered too: "once" does not show again.
        assertEquals(drawn, ui.shown.size)
        assertEquals(2, next.sessionCount)
    }

    @Test
    fun anOldCacheIsNotShown() {
        val m = Msg.of()
        val saved = JSONObject().put("v", 1).put("messages", JSONArray().put(m))
            .put("fetched_at", scheduler.now - 8 * InAppController.DAY_MS)
        server.queued.add(MockResponse().setResponseCode(503))
        started(controller(store = MemoryStateStore(saved.toString())))
        assertNull(ui.current)
    }

    @Test
    fun theCacheShowsOffline() {
        val m = Msg.of()
        val saved = JSONObject().put("v", 1).put("messages", JSONArray().put(m)).put("fetched_at", scheduler.now - 3_600_000)
        server.queued.add(MockResponse().setResponseCode(503))
        started(controller(store = MemoryStateStore(saved.toString())))
        assertEquals(m.getString("id"), shownId())
    }

    @Test
    fun aSlowServerDoesNotHoldTheCache() {
        val cached = Msg.of(priority = 1)
        val fresh = Msg.of(priority = 9)
        server.inApp.addAll(listOf(cached, fresh))
        val saved = JSONObject().put("v", 1).put("messages", JSONArray().put(cached)).put("fetched_at", scheduler.now - 3_600_000)
        holdNetwork = true
        val c = started(controller(store = MemoryStateStore(saved.toString())))
        assertNull(ui.current)
        scheduler.advanceBy(3_000)
        assertEquals(cached.getString("id"), shownId())
        holdNetwork = false
        heldNetwork.removeFirst().run()
        scheduler.runUntilIdle()
        assertEquals(2, c.messageList.size)
        assertEquals(cached.getString("id"), shownId())
    }

    @Test
    fun anImageOnlyMessageThatCannotBeDrawnWaitsForTheNextSession() {
        val m = Msg.of(title = null, body = null, frequency = JSONObject().put("type", "every_session"),
            content = { it.put("image_url", "https://example.com/i.png") })
        server.inApp.add(m)
        val c = started()
        c.didFail(ui.current!!.token)
        scheduler.runUntilIdle()
        assertNull(ui.current)
        c.check()
        scheduler.runUntilIdle()
        assertNull(ui.current)
        session(c)
        assertEquals(m.getString("id"), shownId())
    }

    @Test
    fun messagesThisVersionCannotShowAreSkipped() {
        val good = Msg.of()
        server.inApp.add(Msg.of(layout = "hologram", priority = 9))
        server.inApp.add(Msg.of(priority = 8, triggers = Msg.all(Msg.cond("weather", "=", "rain"))))
        server.inApp.add(Msg.of(priority = 7, content = { it.put("buttons", JSONArray().put(Msg.button("x", "X", JSONObject().put("type", "teleport")))) }))
        server.inApp.add(Msg.of(priority = 6, content = { it.put("title", 5) }))
        server.inApp.add(good)
        val c = started()
        assertEquals(listOf(good.getString("id")), c.messageList.map { it.id })
        assertEquals(good.getString("id"), shownId())
    }

    @Test
    fun aStartInTheBackgroundWaitsForTheForeground() {
        server.inApp.add(Msg.of())
        foreground = false
        val c = started()
        assertEquals(0, c.sessionCount)
        assertTrue(server.callsTo("GET", "/in-app").isEmpty())
        foreground = true
        c.onForeground()
        scheduler.runUntilIdle()
        assertEquals(1, c.sessionCount)
        assertTrue(ui.current != null)
    }

    @Test
    fun nothingShowsWithoutAnActivity() {
        server.inApp.add(Msg.of())
        val c = controller()
        c.start()
        scheduler.runUntilIdle()
        assertNull(ui.current)
        c.attach()
        scheduler.runUntilIdle()
        assertTrue(ui.current != null)
    }

    @Test
    fun aNewRegistrationFetchesAtOnce() {
        server.inApp.add(Msg.of())
        val saved = creds
        creds = null
        val c = started()
        assertTrue(server.callsTo("GET", "/in-app").isEmpty())
        creds = saved
        c.onRegistered()
        scheduler.runUntilIdle()
        assertTrue(ui.current != null)
    }

    @Test
    fun anUnknownDeviceAsksTheClientToCheck() {
        server.forget(creds!!.first)
        started()
        assertEquals(1, unauthorized)
    }

    @Test
    fun theStateIsSaved() {
        server.inApp.add(Msg.of(frequency = JSONObject().put("type", "every_session")))
        val c = started()
        display(c)
        val js = JSONObject(store.value!!)
        assertEquals(1, js.getInt("sessions"))
        assertTrue(js.has("installed_at"))
        val local = js.getJSONObject("local")
        assertEquals(1, local.getJSONObject(ui.current!!.message.id).getInt("shown"))
        assertEquals(1, js.getJSONArray("messages").length())
        assertTrue(js.getString("etag").isNotEmpty())
    }

    @Test
    fun anUnreadableStateIsReadAgainLaterAndKept() {
        server.inApp.add(Msg.of(frequency = JSONObject().put("type", "every_session")))
        val saved = JSONObject().put("v", 1).put("sessions", 4).put("installed_at", scheduler.now - 1000)
        val s = MemoryStateStore(saved.toString()).apply { failReads = true }
        val c = started(controller(store = s))
        display(c)
        assertEquals(1, c.sessionCount)
        assertEquals(saved.toString(), s.value) // not overwritten
        s.failReads = false
        c.onBackground()
        c.onForeground()
        scheduler.runUntilIdle()
        assertEquals(5, c.sessionCount)
        assertEquals(5, JSONObject(s.value!!).getInt("sessions"))
    }

    @Test
    fun valueRules() {
        assertEquals("3", InAppController.str(3.0))
        assertEquals("3.5", InAppController.str(3.5))
        assertEquals("true", InAppController.str(true))
        assertTrue(InAppController.equal("3", 3))
        assertTrue(InAppController.equal(" 3 ", 3.0))
        assertFalse(InAppController.equal("b2", "B2"))
        assertTrue(InAppController.compare(2.0, "<=", 2.0))
        assertFalse(InAppController.compare(2.0, "~", 2.0))
    }
}
