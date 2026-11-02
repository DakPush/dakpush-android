package io.github.hridoyrocks.dak.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * What the server sends pushes to: an FCM registration token, or a Firebase
 * Installation ID ("fid").
 */
internal class DakPushToken(val value: String, val type: String = TYPE_TOKEN) {
    companion object {
        const val TYPE_TOKEN = "token"
        const val TYPE_FID = "fid"
    }
}

/** The parts of Firebase Messaging the SDK uses, behind an interface for tests. */
internal fun interface DakPushSource {
    /** The current push target, or null if FCM has none yet. Blocks: never call it on the worker or main thread. */
    fun getPushToken(): DakPushToken?
}

/**
 * Facts about the install the server uses for targeting. Nothing that
 * identifies the person or the phone: no model, no IP, no location (the UTC
 * offset only if the app opts in).
 */
internal interface DakPlatformInfo {
    fun appVersion(): String

    fun osVersion(): String

    fun language(): String

    /** The phone's UTC offset in minutes right now (+360 in Bangladesh). */
    fun utcOffsetMinutes(): Int

    /** Whether the user allows this app's notifications. */
    fun notificationsEnabled(): Boolean
}

internal class AndroidPlatformInfo(private val context: Context) : DakPlatformInfo {
    override fun appVersion(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName.orEmpty()
        val code = PackageInfoCompat.getLongVersionCode(info)
        return if (name.isEmpty()) code.toString() else "$name+$code"
    }

    override fun osVersion(): String = Build.VERSION.RELEASE.orEmpty()

    override fun language(): String = languageOf(Locale.getDefault())

    override fun utcOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    override fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        private val languagePattern = Regex("^[a-z]{2,3}$")

        /** The language code ("bn", "en"), as the server matches translations; "" when unknown. */
        fun languageOf(locale: Locale): String {
            val code = locale.toLanguageTag().substringBefore('-').lowercase(Locale.ROOT)
            return if (code != "und" && languagePattern.matches(code)) code else ""
        }
    }
}

/**
 * Firebase Installation IDs as they come from
 * FirebaseMessagingService.onRegistered (through [io.github.hridoyrocks.dak.Dak.onRegistered]).
 */
internal object DakFids {
    private val lock = Object()
    private var version = 0
    private var value: String? = null

    fun offer(fid: String) = synchronized(lock) {
        value = fid
        version++
        lock.notifyAll()
    }

    fun mark(): Int = synchronized(lock) { version }

    /** The first FID offered after [mark], or null after [timeoutMs]. */
    fun awaitAfter(mark: Int, timeoutMs: Long): String? = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (version == mark) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return null
            lock.wait(left)
        }
        value
    }
}

/**
 * [DakPushSource] on Firebase Messaging. From 25.1 Firebase deprecates
 * registration tokens in favour of Firebase Installation IDs (`register()` and
 * `onRegistered`), which stay off unless the app's manifest turns them on
 * (`firebase_messaging_installation_id_enabled`). The SDK tries an
 * installation ID first and falls back to a token when they are off, when
 * registration fails, or when onRegistered never comes. Dak's server accepts
 * both.
 */
internal class FirebasePushSource(private val useFid: Boolean, private val log: DakLog) : DakPushSource {
    override fun getPushToken(): DakPushToken? {
        val fm = try {
            FirebaseMessaging.getInstance()
        } catch (e: IllegalStateException) {
            log.w("Firebase is not set up, so there is no push token: add the app's google-services.json (${e.message})")
            return null
        }
        if (useFid) registerFid(fm)?.let { return DakPushToken(it, DakPushToken.TYPE_FID) }
        @Suppress("DEPRECATION")
        val token = Tasks.await(fm.token, TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return token?.takeIf { it.isNotEmpty() }?.let { DakPushToken(it) }
    }

    private fun registerFid(fm: FirebaseMessaging): String? {
        val mark = DakFids.mark()
        try {
            Tasks.await(fm.register(), TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Off in the manifest, no network, or Play services too old.
            log.d("dak: no Firebase Installation ID, using a token: ${e.cause ?: e}")
            return null
        } catch (e: NoSuchMethodError) {
            // The app forces a Firebase Messaging older than 25.1.
            return null
        }
        return DakFids.awaitAfter(mark, TIMEOUT_MS).also {
            if (it == null) {
                log.w("register() worked but onRegistered never came: is DakMessagingService the app's FirebaseMessagingService? See the README.")
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}

/** The intent the launcher icon starts, or null for an app without one. */
internal fun Context.launchIntent() = packageManager.getLaunchIntentForPackage(packageName)

/** The app's manifest meta-data, or null. */
internal fun Context.metaData() = try {
    packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
} catch (e: Exception) {
    null
}
