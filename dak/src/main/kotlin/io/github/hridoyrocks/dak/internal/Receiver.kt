package io.github.hridoyrocks.dak.internal

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.google.firebase.messaging.RemoteMessage
import io.github.hridoyrocks.dak.BuildConfig
import io.github.hridoyrocks.dak.Dak
import io.github.hridoyrocks.dak.DakNotification
import org.json.JSONObject
import java.io.IOException

/** An FCM message as the receiver got it. */
internal class DakIncoming(
    /** The message's data, without Firebase's own keys. */
    val data: Map<String, String>,
    /** Firebase's message id, which a tap on a notification Firebase shows carries. */
    val firebaseId: String?,
    val title: String?,
    val body: String?,
    val image: String?,
    /** The Android channel of a notification message, which Firebase keeps outside the data. */
    val channel: String?,
    /** Firebase shows it itself when the app is in the background (a notification message). */
    val shownByFirebase: Boolean,
) {
    val dakId: String? get() = data[DakNotification.ID_KEY]?.takeIf { it.isNotEmpty() }

    /** As the app sees it; the channel goes along, so [io.github.hridoyrocks.dak.Dak.showNotification] uses it. */
    fun notification(): DakNotification {
        val all = if (channel.isNullOrEmpty() || DakNotification.CHANNEL_KEY in data) data else data + (DakNotification.CHANNEL_KEY to channel)
        return DakNotification.fromData(all, title, body, image)
    }

    companion object {
        fun from(extras: Bundle): DakIncoming {
            val message = RemoteMessage(extras)
            val n = message.notification
            return DakIncoming(
                data = HashMap(message.data),
                firebaseId = message.messageId,
                title = n?.title,
                body = n?.body,
                image = n?.imageUrl?.toString(),
                channel = n?.channelId,
                shownByFirebase = n != null,
            )
        }
    }
}

/**
 * Gets every FCM message, like Firebase's own receiver, in every app state
 * (Google Play services sends them as an ordered broadcast to both). It
 * reports deliveries, shows the messages Dak sends as data, and hands
 * messages to an open app. Firebase still shows its own notifications and
 * calls the app's FirebaseMessagingService as before.
 */
internal class DakMessagingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECEIVE) return
        val incoming = try {
            val extras = intent.extras ?: return
            // Deleted-messages and send events are not messages.
            val type = extras.getString("message_type")
            if (type != null && type != "gcm") return
            DakIncoming.from(extras)
        } catch (e: RuntimeException) {
            return
        }
        val app = context.applicationContext
        val result = goAsync()
        Thread({
            try {
                DakDelivery.handle(app, incoming)
            } catch (t: Throwable) {
                // Never crash the app from the background.
            } finally {
                result.finish()
            }
        }, "dak-receiver").start()
    }

    companion object {
        const val ACTION_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
    }
}

/** What happens to an incoming message, off the main thread. */
internal object DakDelivery {
    fun handle(context: Context, incoming: DakIncoming) {
        val files = DakFiles.of(context)
        val dakId = incoming.dakId
        if (dakId != null) {
            // Written first, so a process killed right after still sends it on the next start.
            files.pending.addReceipt(dakId)
            if (incoming.shownByFirebase && incoming.firebaseId != null) {
                files.taps.recordReceived(incoming.firebaseId, ReceivedPush(incoming.title, incoming.body, incoming.image))
            }
        }
        // Like Firebase: an open, unlocked app gets the message itself and nothing is shown.
        if (isAppInForeground(context) && Dak.isInitialized) {
            Dak.dispatchForeground(incoming.notification())
        } else {
            DakNotifier.dataToShow(incoming.data)?.let { DakNotifier.show(context, it, files.taps) }
        }
        if (dakId != null && !Dak.takePending()) sendKeptReceipts(context, files)
    }

    /**
     * Sends kept receipts without a client (the app's process started for this
     * push, and Dak.init has not run), with the device id and secret the app
     * saved. What cannot be sent stays for the app's next start.
     */
    fun sendKeptReceipts(context: Context, files: DakFiles, http: DakHttp = UrlConnectionHttp()) {
        val ids = files.pending.read().receipts
        if (ids.isEmpty()) return
        val state = try {
            files.state.read()?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        } ?: return
        val baseUrl = state.optStringOrNull("base_url") ?: return
        val appId = state.optStringOrNull("app_id") ?: return
        val deviceId = state.optStringOrNull("device_id") ?: return
        val secret = state.optStringOrNull("secret") ?: return
        val api = try {
            DakApi(baseUrl, appId, http, userAgent(), timeoutMs = 10_000)
        } catch (e: IllegalArgumentException) {
            return
        }
        val batch = ids.take(DakClient.RECEIPT_BATCH)
        try {
            api.delivered(deviceId, secret, batch)
        } catch (e: DakApiException) {
            // A refusal cannot change by trying again; a 401 means the device
            // registers again, and the server ignores the old device's receipts.
            if (!e.isPermanent && !e.isUnauthorized) return
        } catch (e: IOException) {
            return
        }
        files.pending.remove(batch, emptyList())
    }

    /** Like Firebase's own check: an unlocked phone with this app's process in the foreground. */
    fun isAppInForeground(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val pid = Process.myPid()
        return am.runningAppProcesses?.any {
            it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    fun userAgent(): String = "dakpush-android/${BuildConfig.SDK_VERSION} (Android ${android.os.Build.VERSION.RELEASE})"
}
