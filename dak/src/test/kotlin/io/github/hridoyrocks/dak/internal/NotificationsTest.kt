package io.github.hridoyrocks.dak.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.github.hridoyrocks.dak.internal.FakeDakServer.Companion.APP_ID
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
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val files = DakFiles.of(context)
    private val server = FakeDakServer()

    private val fourButtons =
        """[{"id":"a","text":"A","url":"app://a"},{"id":"b","text":"B"},{"id":"c","text":"C"},{"id":"d","text":"D"}]"""

    private fun message(vararg extra: Pair<String, String>) = mapOf(
        "_dak_show" to "1",
        "_dak_id" to "m1",
        "_dak_title" to "Hello",
        "_dak_body" to "From Dak",
        "_dak_url" to "app://inbox",
        "type" to "friend_request",
    ) + extra

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun posted(): Notification = shadowOf(nm).allNotifications.single()

    private fun savedIntent(n: Notification, action: Int? = null): Intent {
        val pending = if (action == null) n.contentIntent else n.actions[action].actionIntent
        return Intent(shadowOf(pending).savedIntent)
    }

    @Test
    fun showsTheTextAndAtMostThreeButtons() {
        assertTrue(DakNotifier.show(context, message("_dak_buttons" to fourButtons), files.taps))
        val n = posted()
        assertEquals("Hello", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("From Dak", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("A", "B", "C"), n.actions.map { it.title.toString() })
        assertEquals(DakNotifier.DEFAULT_CHANNEL, n.channelId)
        val tap = savedIntent(n)
        assertEquals(DakTapActivity::class.java.name, tap.component?.className)
        assertNotNull(tap.getStringExtra(DakNotifier.EXTRA_NONCE))
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun nothingWithoutTextOrWithNotificationsOff() {
        assertFalse(DakNotifier.show(context, mapOf("_dak_show" to "1", "_dak_id" to "m1"), files.taps))
        shadowOf(nm).setNotificationsEnabled(false)
        assertFalse(DakNotifier.show(context, message(), files.taps))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun theAppsOwnChannelIsUsed() {
        nm.createNotificationChannel(NotificationChannel("social", "Social", NotificationManager.IMPORTANCE_HIGH))
        DakNotifier.show(context, message("_dak_channel" to "social"), files.taps)
        assertEquals("social", posted().channelId)
    }

    @Test
    fun theCollapseKeyReplacesTheEarlierOne() {
        DakNotifier.show(context, message("_dak_tag" to "chat"), files.taps)
        DakNotifier.show(context, message("_dak_tag" to "chat", "_dak_id" to "m2", "_dak_title" to "Newer"), files.taps)
        assertEquals("Newer", posted().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun aTapCountsOnceAndAButtonTapClosesTheNotification() {
        DakNotifier.show(context, message("_dak_buttons" to fourButtons), files.taps)
        val n = posted()
        val body = savedIntent(n)
        val replay = Intent(body)
        val tap = DakTaps.sdkTap(context, body, files.taps)!!
        assertEquals("m1", tap.id)
        assertNull(tap.actionId)
        assertEquals("app://inbox", tap.url)
        assertEquals(mapOf("type" to "friend_request"), tap.data)
        // The system replays the intent after the app was killed: not a second tap.
        assertNull(DakTaps.sdkTap(context, replay, files.taps))
        // One nonce for the notification: its buttons are used up with it.
        assertNull(DakTaps.sdkTap(context, savedIntent(n, 0), files.taps))

        nm.cancelAll()
        DakNotifier.show(context, message("_dak_buttons" to fourButtons, "_dak_id" to "m2"), files.taps)
        val button = DakTaps.sdkTap(context, savedIntent(posted(), 0), files.taps)!!
        assertEquals("m2", button.id)
        assertEquals("a", button.actionId)
        assertEquals("app://a", button.url)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun madeUpTapsAreIgnored() {
        val data = Bundle().apply { putString("_dak_id", "m1") }
        val noNonce = Intent().putExtra(DakNotifier.EXTRA_DATA, data)
        val wrongNonce = Intent().putExtra(DakNotifier.EXTRA_DATA, data).putExtra(DakNotifier.EXTRA_NONCE, "00".repeat(16))
        assertNull(DakTaps.sdkTap(context, noNonce, files.taps))
        assertNull(DakTaps.sdkTap(context, wrongNonce, files.taps))
        assertNull(DakTaps.sdkTap(context, null, files.taps))

        DakNotifier.show(context, message(), files.taps)
        val fromHistory = savedIntent(posted()).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(DakTaps.sdkTap(context, fromHistory, files.taps))
    }

    @Test
    fun theTapActivityRecordsTheOpenAndOpensTheApp() {
        val launcher = ComponentName(context, "com.example.Main")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        DakNotifier.show(context, message("_dak_buttons" to fourButtons), files.taps)
        val controller = Robolectric.buildActivity(DakTapActivity::class.java, savedIntent(posted(), 1)).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertEquals(listOf("m1" to "b"), files.pending.read().opens.map { it.id to it.action })
        assertEquals(launcher, shadowOf(activity).nextStartedActivity.component)
    }

    @Test
    fun aFirebaseTapCountsOnlyForAMessageTheReceiverSaw() {
        fun tapIntent(id: String) = Intent(Intent.ACTION_MAIN)
            .putExtra("google.message_id", id)
            .putExtra("google.sent_time", 1L)
            .putExtra("from", "1234")
            .putExtra("_dak_id", "m7")
            .putExtra("_dak_url", "app://inbox")
            .putExtra("type", "friend_request")

        assertNull(DakTaps.firebaseTap(tapIntent("0:unknown"), files.taps))

        DakDelivery.handle(context, DakIncoming.from(firebaseMessage("0:seen", "m7")))
        val first = tapIntent("0:seen")
        val tap = DakTaps.firebaseTap(first, files.taps)!!
        assertEquals("m7", tap.id)
        assertEquals("Hello", tap.title)
        assertEquals("app://inbox", tap.url)
        assertEquals(mapOf("type" to "friend_request"), tap.data)
        // The same intent when the activity resumes, and a made-up copy later.
        assertNull(DakTaps.firebaseTap(first, files.taps))
        assertNull(DakTaps.firebaseTap(tapIntent("0:seen"), files.taps))
    }

    @Test
    fun aPushWithButtonsInTheBackgroundIsShownAndItsReceiptKept() {
        val extras = Bundle().apply {
            putString("google.message_id", "0:1")
            putString("from", "1234")
            message("_dak_buttons" to fourButtons).forEach { (k, v) -> putString(k, v) }
        }
        DakDelivery.handle(context, DakIncoming.from(extras))
        assertEquals("Hello", posted().extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("m1"), files.pending.read().receipts)
    }

    @Test
    fun keptReceiptsAreSentWithTheSavedDevice() {
        val api = DakApi(server.baseUrl, APP_ID, UrlConnectionHttp(), "test")
        val reg = api.register(JSONObject().put("token", "t").put("platform", "android"))
        files.state.write(
            JSONObject().put("base_url", server.baseUrl).put("app_id", APP_ID)
                .put("device_id", reg.deviceId).put("secret", reg.deviceSecret).toString(),
        )
        files.pending.addReceipt("m1")
        DakDelivery.sendKeptReceipts(context, files)
        assertEquals(listOf("m1"), server.receipts.toList())
        assertTrue(files.pending.read().receipts.isEmpty())

        // No network: kept for the app's next start.
        server.shutdown()
        files.pending.addReceipt("m2")
        DakDelivery.sendKeptReceipts(context, files)
        assertEquals(listOf("m2"), files.pending.read().receipts)
    }

    @Test
    fun aFirebaseMessageShownInTheForegroundKeepsItsChannel() {
        nm.createNotificationChannel(NotificationChannel("social", "Social", NotificationManager.IMPORTANCE_HIGH))
        val extras = firebaseMessage("0:3", "m3").apply { putString("gcm.notification.android_channel_id", "social") }
        val n = DakIncoming.from(extras).notification()
        assertEquals("Hello", n.title)
        assertEquals(mapOf("type" to "friend_request"), n.data)
        assertTrue(DakNotifier.show(context, DakNotifier.showData(n), files.taps))
        assertEquals("social", posted().channelId)
        assertEquals("m3", savedIntent(posted()).getBundleExtra(DakNotifier.EXTRA_DATA)!!.getString("_dak_id"))
    }

    @Test
    fun receiptsWaitWithoutASavedDevice() {
        files.pending.addReceipt("m1")
        DakDelivery.sendKeptReceipts(context, files)
        assertEquals(listOf("m1"), files.pending.read().receipts)
        assertTrue(server.calls.isEmpty())
    }

    /** A notification message, which Firebase shows itself in the background. */
    private fun firebaseMessage(firebaseId: String, dakId: String) = Bundle().apply {
        putString("google.message_id", firebaseId)
        putString("from", "1234")
        putString("gcm.n.e", "1")
        putString("gcm.notification.title", "Hello")
        putString("gcm.notification.body", "From Dak")
        putString("_dak_id", dakId)
        putString("_dak_url", "app://inbox")
        putString("type", "friend_request")
    }
}
