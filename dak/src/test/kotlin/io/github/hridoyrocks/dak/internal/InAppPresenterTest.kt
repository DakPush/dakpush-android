package io.github.hridoyrocks.dak.internal

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.hridoyrocks.dak.DakInAppBlocker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

class PlainActivity : Activity()

class CallActivity : Activity(), DakInAppBlocker

/**
 * The presenter drawing real windows over Robolectric activities, driven by a
 * real controller; on the oldest Android the SDK runs on, the one before
 * window insets moved (28), and a current one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h720dp-xhdpi", sdk = [23, 28, 35])
class InAppPresenterTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = FakeDakServer()
    private val scheduler = TestScheduler()
    private val actions = FakeActions()
    private lateinit var presenter: InAppPresenter
    private lateinit var controller: InAppController
    private lateinit var watcher: DakActivityWatcher
    private val activities = mutableListOf<ActivityController<out Activity>>()

    @Before
    fun setUp() {
        server.start()
        val api = DakApi(server.baseUrl, server.appId, UrlConnectionHttp(), "dakpush-android/test", timeoutMs = 5_000)
        val reg = api.register(JSONObject().put("token", "t-${UUID.randomUUID()}").put("platform", "android"))
        presenter = InAppPresenter(images = null, log = DakLog(true) { _, m, _ -> println(m) })
        lateinit var w: DakActivityWatcher
        controller = InAppController(
            api = api,
            store = MemoryStateStore(),
            credentials = { reg.deviceId to reg.deviceSecret },
            ui = presenter,
            actions = actions,
            scheduler = scheduler,
            network = { it.run() },
            log = DakLog(true) { _, m, _ -> println(m) },
            clock = { scheduler.now },
            isForeground = { w.isForeground },
        )
        presenter.controller = controller
        w = DakActivityWatcher(
            onForeground = { controller.onForeground() },
            onIntent = {},
            onBackground = { controller.onBackground() },
            onResumed = { presenter.onActivityResumed(it) },
            onPaused = { presenter.onActivityPaused(it) },
        )
        watcher = w
        app.registerActivityLifecycleCallbacks(w)
    }

    @After
    fun tearDown() {
        app.unregisterActivityLifecycleCallbacks(watcher)
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        server.shutdown()
    }

    private fun <T : Activity> open(cls: Class<T>): ActivityController<T> =
        Robolectric.buildActivity(cls).setup().also { activities.add(it) }

    /** Runs the controller's thread and the main thread until both are idle, 50 ms of main-thread time at a go. */
    private fun pump(rounds: Int = 6) {
        repeat(rounds) {
            scheduler.runUntilIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        scheduler.runUntilIdle()
    }

    private fun start(vararg messages: JSONObject): ActivityController<PlainActivity> {
        server.inApp.addAll(messages)
        controller.start()
        val a = open(PlainActivity::class.java)
        pump()
        return a
    }

    private fun window(): InAppWindow? = presenter.shownWindow?.takeIf { it.isShowing }

    private fun texts(v: View): List<TextView> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> listOf(v)
        else -> emptyList()
    }

    private fun buttons(m: JSONObject) = m.getJSONObject("content").put(
        "buttons",
        JSONArray()
            .put(Msg.button("join", "যোগ দিন", JSONObject().put("type", "deep_link").put("url", "app://join")))
            .put(Msg.button("later", "Later", JSONObject().put("type", "dismiss"))),
    )

    private fun events(type: String) = server.inAppEvents.map { it.second }.filter { it.getString("type") == type }

    @Test
    fun aModalShowsOverTheActivityAndAButtonClosesIt() {
        val m = Msg.of(title = "নতুন চ্যালেঞ্জ", body = "আজকের বিষয়: ভ্রমণ").also(::buttons)
        start(m)
        val w = window()!!
        assertEquals("dak-in-app-modal", w.view.root.tag)
        val shown = texts(w.view.root).map { it.text.toString() }
        assertTrue(shown.containsAll(listOf("নতুন চ্যালেঞ্জ", "আজকের বিষয়: ভ্রমণ", "যোগ দিন", "Later")))
        // The window is the whole screen and takes the focus, like a dialog.
        val lp = w.window!!.attributes
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        scheduler.advanceBy(2_000)
        assertEquals(1, events("impression").size)

        w.view.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}join").performClick()
        pump()
        assertNull(window())
        assertFalse(w.isShowing)
        assertEquals("join", actions.clicks.single().buttonId)
        scheduler.advanceBy(2_000)
        assertEquals("join", events("click").single().getString("button_id"))
    }

    @Test
    fun backAndTheXCloseIt() {
        start(Msg.of(frequency = JSONObject().put("type", "every_session")))
        @Suppress("DEPRECATION")
        window()!!.onBackPressed()
        pump()
        assertNull(window())
        scheduler.advanceBy(2_000)
        assertEquals(1, events("dismiss").size)
    }

    @Test
    fun theXAndATapOutsideClose() {
        val m = Msg.of(frequency = JSONObject().put("type", "interval").put("interval_seconds", 60))
        start(m)
        window()!!.view.closeButton!!.performClick()
        pump()
        assertNull(window())
        scheduler.advanceBy(61_000)
        controller.check()
        pump()
        window()!!.view.root.performClick() // the dimmed backdrop
        pump()
        assertNull(window())
        scheduler.advanceBy(2_000)
        assertEquals(2, events("dismiss").size)
    }

    @Test
    fun aBannerLeavesTheAppUsable() {
        start(Msg.of(layout = "banner_top", content = { it.put("auto_dismiss_seconds", 5) }))
        val w = window()!!
        val lp = w.window!!.attributes
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, lp.height)
        assertEquals(Gravity.TOP, lp.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        // It closes by itself after its time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        pump()
        assertNull(window())
        scheduler.advanceBy(2_000)
        assertEquals(1, events("dismiss").size)
    }

    @Test
    fun aRotationBringsTheMessageBackWithoutCountingItAgain() {
        val a = start(Msg.of())
        val first = window()!!
        scheduler.advanceBy(2_000)
        a.recreate()
        pump()
        val again = window()!!
        assertTrue(first !== again)
        assertFalse(first.isShowing)
        assertTrue(presenter.activity === a.get())
        scheduler.advanceBy(2_000)
        assertEquals(1, events("impression").size)
        // A rotation is not leaving the app.
        assertTrue(watcher.isForeground)
    }

    @Test
    fun theBackgroundHidesItAndItComesBack() {
        val a = start(Msg.of())
        a.pause().stop()
        pump()
        assertNull(window())
        assertFalse(watcher.isForeground)
        a.start().resume()
        pump()
        assertNotNull(window())
        scheduler.advanceBy(2_000)
        assertEquals(1, events("impression").size)
        assertEquals(0, events("dismiss").size)
    }

    @Test
    fun aBlockerActivityKeepsMessagesAway() {
        server.inApp.add(Msg.of())
        controller.start()
        val call = open(CallActivity::class.java)
        pump()
        assertNull(window())
        assertTrue(presenter.blocked)
        call.pause().stop().destroy()
        open(PlainActivity::class.java)
        pump()
        assertNotNull(window())
        assertFalse(presenter.blocked)
    }

    @Test
    fun aFullScreenMessageAlwaysHasAWayOut() {
        start(Msg.of(layout = "fullscreen", content = { it.put("close_button", false) }))
        assertNotNull(window()!!.view.closeButton)
    }

    @Test
    fun aCardWithoutTheXHasNone() {
        start(Msg.of(layout = "card", content = { it.put("close_button", false) }))
        assertNull(window()!!.view.closeButton)
    }

    @Test
    fun aClickOnTheMessageItself() {
        start(Msg.of(layout = "banner_bottom", content = { it.put("click_action", JSONObject().put("type", "custom")) }))
        val root = window()!!.view.root
        // The banner's card is the tappable part.
        val card = (root.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        card.getChildAt(0).performClick()
        pump()
        assertNull(window())
        assertEquals(1, actions.clicks.size)
        assertNull(actions.clicks[0].buttonId)
    }
}

/** The views on their own: sizes, colours, accessibility, right-to-left. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h720dp-xhdpi", sdk = [23, 28, 35])
class InAppViewTest {
    private val activity = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
    private val clicks = mutableListOf<String?>()
    private var dismissed = 0
    private val events = object : InAppViewEvents {
        override fun onClick(buttonId: String?) {
            clicks.add(buttonId)
        }

        override fun onDismiss() {
            dismissed++
        }
    }

    /** Laid out in the activity, as wide as the phone (720 px). */
    private fun view(js: JSONObject, image: Bitmap? = null, theme: InAppTheme = InAppTheme.LIGHT, on: Activity = activity): InAppView {
        val v = InAppView(on, InAppMessage.parse(js)!!, image, theme, events)
        on.setContentView(v.root)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(720, v.root.width)
        return v
    }

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun text(v: InAppView, s: String) = all(v.root).filterIsInstance<TextView>().single { it.text.toString() == s }

    @Test
    fun aModalInTheDashboardsSizes() {
        val v = view(Msg.of(title = "Title", body = "Body"))
        val sheet = v.root.getChildAt(0)
        // 24 dp around, 400 dp at most: on a 360 dp phone 312 dp wide (xhdpi: 2 px a dp).
        assertEquals(624, sheet.width)
        // Centred between the system bars.
        val r = v.root
        assertEquals(r.paddingTop + (r.height - r.paddingTop - r.paddingBottom - sheet.height) / 2, sheet.top)
        val title = text(v, "Title")
        assertEquals(44f, title.textSize) // 22 sp
        assertEquals(Gravity.CENTER_HORIZONTAL, title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(36 * 2, (title.parent as View).paddingTop) // no image: 36 dp above the title
    }

    @Test
    fun buttonsSideBySideOrStacked() {
        fun withButtons(n: Int) = view(Msg.of(content = { c ->
            c.put("buttons", JSONArray((1..n).map { Msg.button("b$it", "B$it", JSONObject().put("type", "dismiss")) }))
        }))
        val two = withButtons(2)
        val b1 = two.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}b1")
        val b2 = two.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}b2")
        assertEquals(b1.top, b2.top)
        assertEquals(b1.width, b2.width)
        assertEquals(96, b1.height) // 48 dp to touch (40 dp to see)
        val three = withButtons(3)
        val s1 = three.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}b1")
        val s2 = three.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}b2")
        assertTrue(s2.top >= s1.bottom)
        s2.performClick()
        assertEquals(listOf<String?>("b2"), clicks)
        assertEquals(Button::class.java.name, s2.accessibilityClassName)
    }

    @Test
    fun coloursComeFromTheMessageThenTheTheme() {
        val dark = view(Msg.of(title = "T", body = "B", content = { c ->
            c.put("background_color", "#101820")
            c.put("buttons", JSONArray().put(Msg.button("a", "A", JSONObject().put("type", "dismiss")))
                .put(Msg.button("b", "B2", JSONObject().put("type", "dismiss"))))
        }))
        assertEquals(android.graphics.Color.WHITE, text(dark, "T").currentTextColor)
        assertEquals(withAlpha(android.graphics.Color.WHITE, 0xC0), text(dark, "B").currentTextColor)
        assertEquals(InAppTheme.LIGHT.onPrimary, text(dark, "A").currentTextColor)
        // The second button is a text button: on the message's own colour, its readable text.
        assertEquals(android.graphics.Color.WHITE, text(dark, "B2").currentTextColor)

        val plain = view(Msg.of(title = "T", body = "B"), theme = InAppTheme.DARK)
        assertEquals(InAppTheme.DARK.onSurface, text(plain, "T").currentTextColor)
        assertEquals(InAppTheme.DARK.onSurfaceVariant, text(plain, "B").currentTextColor)
    }

    @Test
    fun theThemeOfAnAppWithNoColoursIsReadable() {
        val t = InAppTheme.of(activity)
        assertTrue(contrast(t.onSurface, t.surface) >= 3.0)
        assertTrue(contrast(t.onPrimary, t.primary) >= 3.0)
        assertTrue(contrast(t.primary, t.surface) >= 1.5)
    }

    @Test
    fun anImageCapsItsHeight() {
        val wide = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val tall = Bitmap.createBitmap(200, 800, Bitmap.Config.ARGB_8888)
        val a = view(Msg.of(content = { it.put("image_url", "https://x/y.png") }), wide)
        val img = all(a.root).filterIsInstance<ImageView>().first { it.tag != InAppView.TAG_CLOSE }
        assertEquals(624 / 2, img.height)
        val b = view(Msg.of(content = { it.put("image_url", "https://x/y.png") }), tall)
        val img2 = all(b.root).filterIsInstance<ImageView>().first { it.tag != InAppView.TAG_CLOSE }
        assertEquals(240 * 2, img2.height)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, img2.importantForAccessibility)
    }

    @Test
    fun aBannerIsReadOutAndClosable() {
        val v = view(Msg.of(layout = "banner_bottom", title = "Sale", body = "Today only"))
        val swipe = all(v.root).first { it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }
        assertEquals("Sale", androidx.core.view.ViewCompat.getAccessibilityPaneTitle(swipe))
        assertEquals("Close", v.closeButton!!.contentDescription)
        assertEquals(Button::class.java.name, v.closeButton!!.accessibilityClassName)
        assertEquals(2, text(v, "Sale").maxLines)
        assertEquals(3, text(v, "Today only").maxLines)
        assertEquals(Gravity.START, text(v, "Sale").gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        v.closeButton!!.performClick()
        assertEquals(1, dismissed)
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h720dp-xhdpi")
    fun theXIsReadInBangla() {
        val v = view(Msg.of())
        assertEquals("বন্ধ করুন", v.closeButton!!.contentDescription)
    }

    @Test
    @Config(qualifiers = "ar-ldrtl-w360dp-h720dp-xhdpi")
    fun rightToLeft() {
        // As an app with android:supportsRtl="true".
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val rtl = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        val v = view(Msg.of(layout = "banner_top", title = "مرحبا"), on = rtl)
        assertEquals(View.LAYOUT_DIRECTION_RTL, v.root.layoutDirection)
        val close = v.closeButton!!
        val title = text(v, "مرحبا")
        val closeX = IntArray(2).also { close.getLocationInWindow(it) }[0]
        val titleX = IntArray(2).also { title.getLocationInWindow(it) }[0]
        assertTrue("the X is on the left in RTL", closeX < titleX)
    }

    @Test
    fun textGrowsWithTheFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        val big = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        val v = InAppView(big, InAppMessage.parse(Msg.of(title = "T"))!!, null, InAppTheme.LIGHT, events)
        val title = all(v.root).filterIsInstance<TextView>().single { it.text.toString() == "T" }
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, big.resources.displayMetrics), title.textSize)
        // Larger than at 1x (44 px); Android 14+ grows large text less than 2x.
        assertTrue(title.textSize > 60f)
        RuntimeEnvironment.setFontScale(1f)
    }
}
