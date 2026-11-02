package io.github.hridoyrocks.dak.internal

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hridoyrocks.dak.DakCallback
import io.github.hridoyrocks.dak.DakException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The SDK on a real Android runtime: its own HTTP (Android's
 * HttpURLConnection, PATCH included), files and notifications. The server
 * part runs against a Dak on the computer, with a made-up push token, when
 * these instrumentation arguments are given:
 *
 * ```
 * ./gradlew :dak:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.dakBaseUrl=http://10.0.2.2:8080 \
 *   -Pandroid.testInstrumentationRunnerArguments.dakAppId=… \
 *   -Pandroid.testInstrumentationRunnerArguments.dakIdentitySecret=… \
 *   -Pandroid.testInstrumentationRunnerArguments.dakRestKey=dak_…
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun registersAndSyncsWithADakServer() {
        val baseUrl = args.getString("dakBaseUrl").orEmpty()
        val appId = args.getString("dakAppId").orEmpty()
        val identitySecret = args.getString("dakIdentitySecret").orEmpty()
        val restKey = args.getString("dakRestKey").orEmpty()
        assumeTrue("needs a Dak server: see the class comment", listOf(baseUrl, appId, identitySecret, restKey).all { it.isNotEmpty() })

        val files = DakFiles(File(context.noBackupFilesDir, "dak_push_test_${UUID.randomUUID()}"))
        val errors = mutableListOf<DakException>()
        val api = DakApi(baseUrl, appId, UrlConnectionHttp(), DakDelivery.userAgent())
        val client = DakClient(
            api = api,
            store = files.state,
            pending = files.pending,
            push = { DakPushToken("device-test-${UUID.randomUUID()}") },
            platform = AndroidPlatformInfo(context),
            scheduler = ThreadScheduler(DakLog(true)),
            callbacks = Executor { it.run() },
            events = object : DakEvents {
                override fun deviceId(id: String) {}

                override fun error(error: DakException) {
                    synchronized(errors) { errors.add(error) }
                }
            },
            log = DakLog(true),
            sdkVersion = "android-test",
        )
        client.start()
        waitFor { client.currentDeviceId != null }
        val user = "device-test-${System.currentTimeMillis()}"

        assertEquals("identity_verification_failed", call { client.login(user, "0".repeat(64), it) }?.code)
        assertNull(call { client.login(user, hmac(identitySecret, user), it) })
        assertNull(call { client.setTags(mapOf("name" to "রহিম", "level" to "b2"), it) })
        assertNull(call { client.setSubscribed(false, it) })

        val res = UrlConnectionHttp().send(
            "GET", "$baseUrl/v1/users/$user/devices", mapOf("Authorization" to "Bearer $restKey"), null, 10_000,
        )
        assertEquals(200, res.status)
        val device = JSONObject(res.body).getJSONArray("devices").getJSONObject(0)
        assertEquals(client.currentDeviceId, device.getString("id"))
        assertEquals("রহিম", device.getJSONObject("tags").getString("name"))
        assertFalse(device.getBoolean("subscribed"))
        assertEquals(Build.VERSION.RELEASE, device.getString("os_version"))

        // The state on disk, for the receiver's receipts.
        val saved = JSONObject(files.state.read()!!)
        assertEquals(client.currentDeviceId, saved.getString("device_id"))

        assertNull(call { client.logout(it) })
        val after = UrlConnectionHttp().send(
            "GET", "$baseUrl/v1/users/$user/devices", mapOf("Authorization" to "Bearer $restKey"), null, 10_000,
        )
        assertEquals(0, JSONArray(JSONObject(after.body).getJSONArray("devices").toString()).length())
    }

    @Test
    fun showsANotificationWithButtonsAndATapOnItCounts() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val files = DakFiles.of(context)
        val messageId = UUID.randomUUID().toString()
        val data = mapOf(
            "_dak_show" to "1",
            "_dak_id" to messageId,
            "_dak_title" to "পরীক্ষা",
            "_dak_body" to "From the device test",
            "_dak_buttons" to """[{"id":"accept","text":"Accept"},{"id":"later","text":"Later"}]""",
        )
        assertTrue(DakNotifier.show(context, data, files.taps))
        val nm = context.getSystemService(NotificationManager::class.java)
        val shown = nm.activeNotifications.single { it.tag == messageId }
        assertEquals(2, shown.notification.actions.size)

        // What a tap on "Accept" does.
        shown.notification.actions[0].actionIntent.send()
        waitFor { files.pending.read().opens.any { it.id == messageId } }
        assertEquals("accept", files.pending.read().opens.single { it.id == messageId }.action)
        waitFor { nm.activeNotifications.none { it.tag == messageId } }
        files.pending.remove(emptyList(), listOf(messageId))
    }

    private fun call(action: (DakCallback) -> Unit): DakException? {
        val done = CountDownLatch(1)
        var result: DakException? = null
        action(DakCallback { e ->
            result = e
            done.countDown()
        })
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

    private fun hmac(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
