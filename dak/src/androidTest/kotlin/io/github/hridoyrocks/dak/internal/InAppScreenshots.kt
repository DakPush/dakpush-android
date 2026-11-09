package io.github.hridoyrocks.dak.internal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hridoyrocks.dak.DakInAppClick
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Pictures of every layout on the device, for a person to look at. Skipped
 * unless asked for:
 *
 * ```
 * ./gradlew :dak:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.hridoyrocks.dak.internal.InAppScreenshots \
 *   -Pandroid.testInstrumentationRunnerArguments.dakScreenshots=light
 * adb pull /sdcard/Android/data/io.github.hridoyrocks.dak.test/files/screenshots
 * ```
 *
 * Set dark mode, the font size, rotation or the app's language on the device
 * first, and name the run with the argument.
 */
@RunWith(AndroidJUnit4::class)
class InAppScreenshots {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val run = InstrumentationRegistry.getArguments().getString("dakScreenshots").orEmpty()
    private val log = DakLog(true)

    private fun content(title: String?, body: String?, extra: (JSONObject) -> Unit = {}) = JSONObject().apply {
        if (title != null) put("title", title)
        if (body != null) put("body", body)
        extra(this)
    }

    private fun button(id: String, text: String) = JSONObject().put("id", id).put("text", text).put("action", JSONObject().put("type", "custom"))

    private val bangla = mapOf(
        "modal" to content("নতুন স্পিকিং চ্যালেঞ্জ!", "আজকের বিষয়: ভ্রমণ। পাঁচ মিনিট কথা বলুন আর পয়েন্ট জিতুন।") {
            it.put("image_url", IMAGE).put("buttons", JSONArray().put(button("join", "শুরু করুন")).put(button("later", "পরে")))
        },
        "modal-colours" to content("প্রিমিয়াম ৫০% ছাড়ে", "শুধু আজ রাত ১২টা পর্যন্ত। সব কোর্স, সব পার্টনার।") {
            it.put("background_color", "#101820").put("buttons", JSONArray()
                .put(button("buy", "এখনই কিনুন").put("background_color", "#FFCC00"))
                .put(button("plans", "প্ল্যান দেখুন"))
                .put(button("no", "না, ধন্যবাদ")))
        },
        "card" to content("আপনার পার্টনার অনলাইনে", "রাহিম এখন কথা বলতে প্রস্তুত। একটা কল দিন?") {
            it.put("image_url", IMAGE).put("buttons", JSONArray().put(button("call", "কল দিন")).put(button("later", "পরে")))
        },
        "fullscreen" to content("ব্যান্ড ৭-এর পথে", "৩০ দিনের প্ল্যান: প্রতিদিন ১৫ মিনিট স্পিকিং, সাপ্তাহিক মক টেস্ট আর ব্যক্তিগত ফিডব্যাক।") {
            it.put("image_url", IMAGE).put("buttons", JSONArray().put(button("start", "শুরু করি")).put(button("later", "পরে দেখব")))
        },
        "fullscreen-plain" to content("অ্যাপ আপডেট হয়েছে", "নতুন ভার্সনে কল আরও পরিষ্কার, আর ব্যাটারি কম লাগে।") { it.put("close_button", false) },
        "banner_top" to content("নতুন মেসেজ", "সাদিয়া: কালকের প্র্যাকটিস কি সন্ধ্যা ৭টায় হবে?") { it.put("image_url", IMAGE) },
        "banner_bottom" to content(null, "ইন্টারনেট সংযোগ ফিরে এসেছে। আপনার কল আবার চালু করা যাবে।") { it.put("close_button", false) },
    )

    @Test
    fun everyLayout() {
        assumeTrue("asked for with -Pandroid.testInstrumentationRunnerArguments.dakScreenshots=NAME", run.isNotEmpty())
        val out = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val images = File(app.cacheDir, "dak_in_app_shots")
        seedImage(images)
        for ((name, c) in bangla) {
            val layout = name.substringBefore('-')
            shoot(File(out, "$run-$name.png"), images, JSONObject().put("id", "00000000-0000-0000-0000-00000000000${bangla.keys.indexOf(name)}")
                .put("layout", layout).put("content", c).put("frequency", JSONObject().put("type", "once")))
        }
    }

    private fun shoot(file: File, images: File, message: JSONObject) {
        val dir = File(app.noBackupFilesDir, "dak_shots").apply { deleteRecursively() }
        val store = FileStateStore(File(dir, "in_app.json"))
        store.write(JSONObject().put("v", 1).put("messages", JSONArray().put(message)).put("fetched_at", System.currentTimeMillis()).toString())
        val presenter = InAppPresenter(InAppImages(images, log), log)
        lateinit var w: DakActivityWatcher
        val controller = InAppController(
            api = DakApi("http://10.0.2.2:1", "shots", UrlConnectionHttp(), "shots"),
            store = store,
            credentials = { null },
            ui = presenter,
            actions = object : InAppActions {
                override fun clicked(click: DakInAppClick) {}

                override fun openUrl(url: String) {}

                override fun setTags(tags: Map<String, String?>) {}
            },
            scheduler = ThreadScheduler(log, "dak-shots"),
            network = { Thread(it).start() },
            log = log,
            isForeground = { w.isForeground },
        )
        presenter.controller = controller
        w = DakActivityWatcher({ controller.onForeground() }, {}, { controller.onBackground() }, { presenter.onActivityResumed(it) }, { presenter.onActivityPaused(it) })
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(w) }
        try {
            ActivityScenario.launch(InAppTestActivity::class.java).use {
                controller.start()
                val until = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < until) {
                    var shown = false
                    instrumentation.runOnMainSync { shown = presenter.shownWindow?.isShowing == true }
                    if (shown) break
                    Thread.sleep(50)
                }
                var host: Any? = null
                instrumentation.runOnMainSync { host = presenter.activity }
                android.util.Log.i("DakShots", "${file.name}: current=${controller.currentMessage} host=$host fg=${w.isForeground} sessions=${controller.sessionCount}")
                Thread.sleep(800) // the entrance animation
                val shot = instrumentation.uiAutomation.takeScreenshot()
                file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(w) }
        }
    }

    /** A picture in the image cache for [IMAGE], so no network is needed. */
    private fun seedImage(dir: File) {
        dir.mkdirs()
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1200f, 800f, 0xFF0F766E.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP) })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        canvas.drawCircle(900f, 250f, 180f, dot)
        canvas.drawCircle(300f, 600f, 120f, dot)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val name = MessageDigest.getInstance("SHA-256").digest(IMAGE.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, name).writeBytes(bytes)
    }

    companion object {
        const val IMAGE = "https://example.invalid/dak-shots/hero.png"
    }
}
