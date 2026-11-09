package io.github.hridoyrocks.dak.internal

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hridoyrocks.dak.DakInAppClick
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** A screen of the test app for messages to show over. */
class InAppTestActivity : Activity()

/**
 * In-app messages on a real Android: real windows over a real activity, the
 * back stack, rotation and the SDK's own HTTP. Most run from a saved list
 * (no server); the last one needs the same instrumentation arguments as
 * [DeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class InAppDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val args = InstrumentationRegistry.getArguments()
    private val log = DakLog(true)
    private val dir = File(app.noBackupFilesDir, "dak_in_app_test_${UUID.randomUUID()}")
    private val store = FileStateStore(File(dir, "in_app.json"))
    private val clicks = CopyOnWriteArrayList<DakInAppClick>()
    private val credentials = AtomicReference<Pair<String, String>?>(null)
    private lateinit var presenter: InAppPresenter
    private lateinit var controller: InAppController
    private lateinit var watcher: DakActivityWatcher

    @Before
    fun setUp() {
        // No permission prompt may cover the test activity (nobody taps one):
        // granted up front, like DeviceTest does.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        presenter = InAppPresenter(InAppImages(File(app.cacheDir, "dak_in_app_test_images"), log), log)
        lateinit var w: DakActivityWatcher
        controller = InAppController(
            api = DakApi(args.getString("dakBaseUrl") ?: "http://10.0.2.2:8091", args.getString("dakAppId") ?: "test", UrlConnectionHttp(), DakDelivery.userAgent()),
            store = store,
            credentials = { credentials.get() },
            ui = presenter,
            actions = object : InAppActions {
                override fun clicked(click: DakInAppClick) {
                    clicks.add(click)
                }

                override fun openUrl(url: String) {}

                override fun setTags(tags: Map<String, String?>) {}
            },
            scheduler = ThreadScheduler(log, "dak-in-app-test"),
            network = { Thread(it).start() },
            log = log,
            isForeground = { w.isForeground },
            eventDelayMs = 200,
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
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(w) }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(watcher) }
        dir.deleteRecursively()
    }

    private fun message(layout: String, extra: (JSONObject) -> Unit = {}): JSONObject {
        val content = JSONObject().put("title", "পরীক্ষা").put("body", "From the device test")
            .put("buttons", if (layout.startsWith("banner")) JSONArray() else JSONArray()
                .put(JSONObject().put("id", "ok").put("text", "ঠিক আছে").put("action", JSONObject().put("type", "custom"))))
        val m = JSONObject().put("id", UUID.randomUUID().toString()).put("layout", layout).put("content", content)
            .put("frequency", JSONObject().put("type", "once"))
        extra(m)
        return m
    }

    /** A saved list, as if fetched a minute ago. */
    private fun cache(vararg messages: JSONObject) {
        store.write(JSONObject().put("v", 1).put("messages", JSONArray(messages.toList())).put("fetched_at", System.currentTimeMillis() - 60_000).toString())
    }

    private fun window(): InAppWindow? {
        var w: InAppWindow? = null
        instrumentation.runOnMainSync { w = presenter.shownWindow?.takeIf { it.isShowing } }
        return w
    }

    private fun saved(): JSONObject = JSONObject(store.read() ?: "{}")

    private fun events(type: String): List<JSONObject> {
        val list = saved().optJSONArray("events") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.getString("type") == type }
    }

    @Test
    fun aModalFromTheCacheShowsAndAButtonClosesIt() {
        cache(message("modal"))
        ActivityScenario.launch(InAppTestActivity::class.java).use {
            controller.start()
            waitFor { window() != null }
            waitFor { events("impression").size == 1 }
            val w = window()!!
            assertEquals(0, w.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            instrumentation.runOnMainSync { w.view.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}ok").performClick() }
            waitFor { window() == null }
            waitFor { events("click").size == 1 }
            assertEquals("ok", clicks.single().buttonId)
        }
    }

    @Test
    fun aRotationKeepsTheMessageAndCountsItOnce() {
        cache(message("card"))
        ActivityScenario.launch(InAppTestActivity::class.java).use { scenario ->
            controller.start()
            waitFor { events("impression").size == 1 }
            val first = window()!!
            scenario.recreate()
            waitFor { window() != null && window() !== first }
            Thread.sleep(500)
            assertEquals(1, events("impression").size)
            assertTrue(watcher.isForeground)
        }
    }

    @Test
    fun aBannerLeavesTheActivityFocused() {
        cache(message("banner_bottom") { it.getJSONObject("content").put("auto_dismiss_seconds", 2) })
        ActivityScenario.launch(InAppTestActivity::class.java).use { scenario ->
            controller.start()
            waitFor { window() != null }
            val w = window()!!
            assertTrue(w.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            // Focus comes to the activity a moment after it resumes; the banner does not take it.
            waitFor {
                var focused = false
                scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            // It goes by itself after 2 s.
            waitFor { window() == null }
            waitFor { events("dismiss").size == 1 }
        }
    }

    @Test
    fun anotherScreenTakesTheMessageAlong() {
        cache(message("banner_top"))
        ActivityScenario.launch(InAppTestActivity::class.java).use { scenario ->
            controller.start()
            waitFor { events("impression").size == 1 }
            val first = window()!!
            // ActivityScenario opens an activity of its own over this one.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            waitFor { window() != null && window() !== first }
            assertTrue(watcher.isForeground)
            Thread.sleep(500)
            assertEquals(1, events("impression").size)
        }
    }

    @Test
    fun theBackgroundHidesTheMessageAndItComesBack() {
        cache(message("fullscreen"))
        ActivityScenario.launch(InAppTestActivity::class.java).use {
            controller.start()
            waitFor { events("impression").size == 1 }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            waitFor { window() == null && !watcher.isForeground }
            // Back, like a tap on the app's icon (FLAG_ACTIVITY_REORDER_TO_FRONT).
            val component = "${app.packageName}/${InAppTestActivity::class.java.name}"
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x20000 -n $component").close()
            waitFor { window() != null && watcher.isForeground }
            Thread.sleep(500)
            assertEquals(1, events("impression").size)
            assertTrue(events("dismiss").isEmpty())
        }
    }

    @Test
    fun aRealServer() {
        val baseUrl = args.getString("dakBaseUrl").orEmpty()
        val appId = args.getString("dakAppId").orEmpty()
        val restKey = args.getString("dakRestKey").orEmpty()
        assumeTrue("needs a Dak server: see DeviceTest", listOf(baseUrl, appId, restKey).all { it.isNotEmpty() })
        val api = DakApi(baseUrl, appId, UrlConnectionHttp(), DakDelivery.userAgent())
        val reg = api.register(JSONObject().put("token", "device-test-${UUID.randomUUID()}").put("platform", "android"))
        credentials.set(reg.deviceId to reg.deviceSecret)
        fun rest(method: String, path: String, body: JSONObject?): HttpResponse =
            UrlConnectionHttp().send(method, "$baseUrl$path", mapOf("Authorization" to "Bearer $restKey", "Content-Type" to "application/json"), body?.toString(), 10_000)
        // The highest priority, so it goes before other messages for every device on the server.
        val created = rest("POST", "/v1/in-app", JSONObject().put("name", "Device test").put("status", "active").put("layout", "modal").put("priority", 1000)
            .put("content", message("modal").getJSONObject("content"))
            .put("target", JSONObject().put("device_ids", JSONArray().put(reg.deviceId))))
        assertEquals(created.body, 201, created.status)
        val id = JSONObject(created.body).getString("id")
        try {
            ActivityScenario.launch(InAppTestActivity::class.java).use {
                controller.start()
                waitFor { window() != null }
                val w = window()!!
                assertEquals(id, w.message.id)
                instrumentation.runOnMainSync { w.view.root.findViewWithTag<View>("${InAppView.TAG_BUTTON}ok").performClick() }
                waitFor {
                    val stats = JSONObject(rest("GET", "/v1/in-app/$id", null).body).getJSONObject("stats")
                    stats.getInt("impressions") == 1 && stats.getInt("clicks") == 1
                }
                assertNotNull(clicks.singleOrNull())
            }
        } finally {
            rest("DELETE", "/v1/in-app/$id", null)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out")
            Thread.sleep(50)
        }
    }
}
