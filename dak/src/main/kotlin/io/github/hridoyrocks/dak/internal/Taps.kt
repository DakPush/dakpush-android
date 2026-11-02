package io.github.hridoyrocks.dak.internal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import io.github.hridoyrocks.dak.Dak
import io.github.hridoyrocks.dak.DakNotification
import io.github.hridoyrocks.dak.DakResultCallback

/** Reads taps from intents, and tells real ones from made-up ones. */
internal object DakTaps {
    /** Set on an intent once read, so a resumed activity does not read it again. */
    const val EXTRA_HANDLED = "dak_push_handled"

    private val firebaseKeys = listOf("google.", "gcm.")
    private val firebasePlainKeys = setOf("from", "collapse_key", "message_type", EXTRA_HANDLED)

    /**
     * A tap on a notification the SDK showed, read once from [intent]. Only an
     * intent with a nonce the SDK issued counts, and the nonce is used up.
     * After a button tap the notification is closed (Android only closes it
     * by itself for a tap on its body).
     */
    fun sdkTap(context: Context, intent: Intent?, taps: DakTapStore): DakNotification? {
        if (intent == null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        return try {
            val bundle = intent.getBundleExtra(DakNotifier.EXTRA_DATA) ?: return null
            val nonce = intent.getStringExtra(DakNotifier.EXTRA_NONCE)
            val action = intent.getStringExtra(DakNotifier.EXTRA_ACTION)
            val tag = intent.getStringExtra(DakNotifier.EXTRA_TAG)
            val id = intent.getIntExtra(DakNotifier.EXTRA_ID, 0)
            for (extra in listOf(DakNotifier.EXTRA_DATA, DakNotifier.EXTRA_ACTION, DakNotifier.EXTRA_NONCE, DakNotifier.EXTRA_TAG, DakNotifier.EXTRA_ID)) {
                intent.removeExtra(extra)
            }
            if (!taps.redeemNonce(nonce)) return null
            val data = HashMap<String, String>()
            for (k in bundle.keySet()) bundle.getString(k)?.let { data[k] = it }
            if (action != null) NotificationManagerCompat.from(context).cancel(tag, id)
            DakNotification.fromData(data, actionId = action)
        } catch (e: RuntimeException) {
            // Extras another app made unreadable on purpose.
            null
        }
    }

    /**
     * A tap on a notification Firebase showed: it starts the app's launcher
     * activity with the message's data. The launcher activity is exported, so
     * only a Firebase message id the receiver saw counts, once.
     */
    fun firebaseTap(intent: Intent?, taps: DakTapStore): DakNotification? {
        if (intent == null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        return try {
            val extras = intent.extras ?: return null
            if (extras.getBoolean(EXTRA_HANDLED)) return null
            if (extras.getString(DakNotification.ID_KEY).isNullOrEmpty()) return null
            val firebaseId = extras.getString("google.message_id") ?: extras.getString("message_id") ?: return null
            intent.putExtra(EXTRA_HANDLED, true)
            val kept = taps.redeemReceived(firebaseId) ?: return null
            DakNotification.fromData(dataOf(extras), kept.title, kept.body, kept.image)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** The message's own data in a Firebase intent: string extras without Firebase's keys. */
    fun dataOf(extras: Bundle): Map<String, String> {
        val out = HashMap<String, String>()
        for (k in extras.keySet()) {
            if (k in firebasePlainKeys || firebaseKeys.any { k.startsWith(it) }) continue
            @Suppress("DEPRECATION")
            val value = extras.get(k)
            if (value is String) out[k] = value
        }
        return out
    }
}

/**
 * Where taps on notifications the SDK shows land. Not exported, so no other
 * app can start it; it records the open, hands the tap to the app's
 * listeners, and opens the app like its launcher icon (in the app's own task,
 * as it was).
 */
internal class DakTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        try {
            val tap = DakTaps.sdkTap(this, intent, DakFiles.of(this).taps)
            if (tap != null) {
                Dak.handleTap(this, tap)
                if (Dak.openAppOnTap) launchIntent()?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(it)
                }
            }
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

/** Asks for notification permission (Android 13+) and reports the answer to [DakPermissions]. */
internal class DakPermissionActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated while the prompt shows: its answer still comes here.
        if (savedInstanceState != null) return
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            finishWithAnswer()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) finishWithAnswer()
    }

    override fun onDestroy() {
        if (!done && isFinishing) DakPermissions.complete(this)
        super.onDestroy()
    }

    private fun finishWithAnswer() {
        done = true
        DakPermissions.complete(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST = 4521
    }
}

/** The callbacks waiting for one permission prompt. Main thread only. */
internal object DakPermissions {
    private val waiting = ArrayList<DakResultCallback>()
    private var asking = false

    /** The answer is whether notifications are allowed, also below Android 13, where no prompt shows. */
    fun request(context: Context, activity: Activity?, callback: DakResultCallback?) {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33 || enabled) {
            callback?.onResult(enabled)
            Dak.permissionChanged()
            return
        }
        callback?.let { waiting.add(it) }
        if (asking) return
        asking = true
        val intent = Intent(context, DakPermissionActivity::class.java)
        try {
            if (activity != null && !activity.isFinishing) {
                activity.startActivity(intent)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: RuntimeException) {
            complete(context)
        }
    }

    fun complete(context: Context) {
        asking = false
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val callbacks = ArrayList(waiting)
        waiting.clear()
        callbacks.forEach { it.onResult(enabled) }
        Dak.permissionChanged()
    }
}
