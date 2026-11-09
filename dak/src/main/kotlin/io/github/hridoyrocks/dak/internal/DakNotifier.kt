package io.github.hridoyrocks.dak.internal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hridoyrocks.dak.DakNotification

/**
 * Shows the Dak messages Android's FCM cannot show itself: Dak sends a message
 * with buttons as data (`_dak_show`), and the SDK builds the notification.
 */
internal object DakNotifier {
    const val EXTRA_DATA = "dak_push_data"
    const val EXTRA_ACTION = "dak_push_action"
    const val EXTRA_TAG = "dak_push_tag"
    const val EXTRA_ID = "dak_push_notification_id"
    const val EXTRA_NONCE = "dak_push_nonce"

    const val DEFAULT_CHANNEL = "dak_default"
    private const val FCM_CHANNEL = "fcm_fallback_notification_channel"
    private const val MAX_IMAGE_BYTES = DakImages.MAX_BYTES
    private const val MAX_BUTTONS = 3

    // The whole image download, inside the receiver's ~10 s: Firebase's own
    // receiver waits for this one.
    private const val IMAGE_DEADLINE_MS = 8000L

    /** The FCM data of a message the SDK should show, or null. */
    fun dataToShow(data: Map<String, String>): Map<String, String>? =
        data.takeIf { it[DakNotification.SHOW_KEY] == "1" }

    /**
     * Builds and shows the notification; false when nothing was shown. Call it
     * off the main thread: it may download the image. It never throws, so a
     * bad message cannot crash the app from the background.
     */
    fun show(context: Context, data: Map<String, String>, taps: DakTapStore): Boolean = try {
        post(context, data, taps)
    } catch (t: Throwable) {
        false
    }

    /** What [show] needs for [n]: its data, and its title, body and image, which FCM keeps outside the data. */
    fun showData(n: DakNotification): Map<String, String> {
        val out = LinkedHashMap(n.raw)
        n.title?.let { out[DakNotification.TITLE_KEY] = it }
        n.body?.let { out[DakNotification.BODY_KEY] = it }
        n.imageUrl?.let { out[DakNotification.IMAGE_KEY] = it }
        return out
    }

    private fun post(context: Context, data: Map<String, String>, taps: DakTapStore): Boolean {
        val title = data[DakNotification.TITLE_KEY].orEmpty()
        val body = data[DakNotification.BODY_KEY].orEmpty()
        if (title.isEmpty() && body.isEmpty()) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        val tag = data[DakNotification.TAG_KEY]?.takeIf { it.isNotEmpty() } ?: data[DakNotification.ID_KEY] ?: "dak"
        val id = tag.hashCode()
        val nonce = taps.issueNonce()
        val picture = image(data[DakNotification.IMAGE_KEY])
        return try {
            manager.notify(tag, id, build(context, data, title, body, tag, id, nonce, picture))
            true
        } catch (e: SecurityException) {
            // No POST_NOTIFICATIONS permission (Android 13+).
            false
        } catch (t: Throwable) {
            // Out of memory, or a picture too big to hand to the system: post it without.
            if (picture == null) return false
            manager.notify(tag, id, build(context, data, title, body, tag, id, nonce, null))
            true
        }
    }

    private fun build(
        context: Context,
        data: Map<String, String>,
        title: String,
        body: String,
        tag: String,
        id: Int,
        nonce: String,
        picture: Bitmap?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, channel(context, data[DakNotification.CHANNEL_KEY]))
            .setSmallIcon(smallIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent(context, data, null, tag, id, nonce, 0))
        color(context)?.let { builder.color = it }
        if (data[DakNotification.SOUND_KEY] == "none") {
            builder.setSilent(true)
        } else {
            // Below Android 8 there are no channels: sound, light and vibration come from here.
            var defaults = NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
                defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            }
            builder.setDefaults(defaults)
        }
        data[DakNotification.BADGE_INCREMENT_KEY]?.toIntOrNull()?.takeIf { it > 0 }?.let { builder.setNumber(it) }
        picture?.let {
            builder.setLargeIcon(it)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?))
        }
        DakNotification.parseButtons(data[DakNotification.BUTTONS_KEY])
            .filter { it.text.isNotEmpty() }
            .take(MAX_BUTTONS)
            .forEachIndexed { i, b -> builder.addAction(0, b.text, tapIntent(context, data, b.id, tag, id, nonce, i + 1)) }
        return builder.build()
    }

    /** Opens [DakTapActivity] with the message's data and the tapped button. */
    private fun tapIntent(
        context: Context,
        data: Map<String, String>,
        action: String?,
        tag: String,
        id: Int,
        nonce: String,
        n: Int,
    ): PendingIntent {
        val intent = Intent(context, DakTapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_DATA, Bundle().apply { data.forEach { (k, v) -> putString(k, v) } })
            .putExtra(EXTRA_TAG, tag)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_NONCE, nonce)
        if (action != null) intent.putExtra(EXTRA_ACTION, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, id * 4 + n, intent, flags)
    }

    private fun channel(context: Context, requested: String?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return requested?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHANNEL
        val nm = context.getSystemService(NotificationManager::class.java)
        val candidates = listOf(
            requested,
            context.metaData()?.getString("com.google.firebase.messaging.default_notification_channel_id"),
            FCM_CHANNEL,
        )
        for (c in candidates) {
            if (!c.isNullOrEmpty() && nm.getNotificationChannel(c) != null) return c
        }
        if (nm.getNotificationChannel(DEFAULT_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(DEFAULT_CHANNEL, "Notifications", NotificationManager.IMPORTANCE_HIGH))
        }
        return DEFAULT_CHANNEL
    }

    /** Like FCM: the manifest's default icon, else the app icon, else the system's; never an adaptive icon. */
    private fun smallIcon(context: Context): Int {
        val res = context.resources
        val icon = context.metaData()?.getInt("com.google.firebase.messaging.default_notification_icon", 0) ?: 0
        if (icon != 0 && isValidIcon(res, icon)) return icon
        val app = context.applicationInfo.icon
        if (app != 0 && isValidIcon(res, app)) return app
        return android.R.drawable.sym_def_app_icon
    }

    /** Android 8.0 crashes the system UI on an adaptive icon as the small icon (FCM's isValidIcon). */
    private fun isValidIcon(res: Resources, id: Int): Boolean {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) return true
        return try {
            res.getDrawable(id, null) !is AdaptiveIconDrawable
        } catch (e: Resources.NotFoundException) {
            false
        }
    }

    private fun color(context: Context): Int? {
        val res = context.metaData()?.getInt("com.google.firebase.messaging.default_notification_color", 0) ?: 0
        return if (res == 0) {
            null
        } else {
            try {
                ContextCompat.getColor(context, res)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The picture, or null: no https URL, too slow (see [IMAGE_DEADLINE_MS]), too big, or not decodable. */
    private fun image(url: String?): Bitmap? =
        DakImages.download(url, IMAGE_DEADLINE_MS, MAX_IMAGE_BYTES)?.let { DakImages.decode(it, maxSide = 1440) }
}
