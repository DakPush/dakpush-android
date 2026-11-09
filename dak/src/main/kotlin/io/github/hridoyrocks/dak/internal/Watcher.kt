package io.github.hridoyrocks.dak.internal

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.core.app.OnNewIntentProvider
import androidx.core.util.Consumer
import io.github.hridoyrocks.dak.DakDeviceIdListener
import io.github.hridoyrocks.dak.DakNotification
import io.github.hridoyrocks.dak.DakOpenedListener
import io.github.hridoyrocks.dak.DakSubscription
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Follows the app's activities: when the app comes to the foreground and
 * goes to the background, which activity is on screen (for the permission
 * prompt and in-app messages), and the intents they start with, which carry
 * taps on notifications Firebase showed.
 */
internal class DakActivityWatcher(
    private val onForeground: () -> Unit,
    private val onIntent: (Intent?) -> Unit,
    private val onBackground: () -> Unit = {},
    /** The app's activities (not the SDK's own) resuming and pausing. */
    private val onResumed: (Activity) -> Unit = {},
    private val onPaused: (Activity) -> Unit = {},
) : Application.ActivityLifecycleCallbacks {
    /** The activities seen starting and not yet stopped (one that started before the watcher is not counted). */
    private val started = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var resumed: WeakReference<Activity>? = null
    private val listeners = WeakHashMap<Activity, Consumer<Intent>>()

    /**
     * An activity of the app is started. A rotation (one activity stopping
     * and its new self starting) does not count as leaving.
     */
    @Volatile
    var isForeground: Boolean = false
        private set

    /** The activity on screen, if any. */
    val resumedActivity: Activity? get() = resumed?.get()?.takeUnless { it.isFinishing }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isOwn(activity)) return
        // A recreated activity has its old intent, already read.
        if (savedInstanceState == null) onIntent(activity.intent)
        // ComponentActivity (AppCompat and most apps) tells about new intents,
        // so a tap that reaches a running singleTop activity is seen too.
        if (activity is OnNewIntentProvider) {
            val listener = Consumer<Intent> { onIntent(it) }
            activity.addOnNewIntentListener(listener)
            listeners[activity] = listener
        }
    }

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        if (!isForeground) {
            isForeground = true
            onForeground()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (isOwn(activity)) return
        resumed = WeakReference(activity)
        // An app that calls setIntent() in onNewIntent: its new intent.
        onIntent(activity.intent)
        onResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        if (!isOwn(activity)) onPaused(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (!started.remove(activity)) return
        if (started.isEmpty() && isForeground && !activity.isChangingConfigurations) {
            isForeground = false
            onBackground()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        val listener = listeners.remove(activity) ?: return
        (activity as? OnNewIntentProvider)?.removeOnNewIntentListener(listener)
    }

    private fun isOwn(activity: Activity) = activity is DakTapActivity || activity is DakPermissionActivity
}

/** Listeners called on the main thread. */
internal class DakListeners<T>(private val main: Executor) {
    private val listeners = CopyOnWriteArrayList<T>()

    fun add(listener: T): DakSubscription {
        listeners.add(listener)
        return DakSubscription { listeners.remove(listener) }
    }

    fun dispatch(call: (T) -> Unit) {
        if (listeners.isEmpty()) return
        main.execute { listeners.forEach(call) }
    }
}

/**
 * Taps on notifications. Taps that come while nobody listens (the app opened
 * by a tap, before its listener exists) are kept, 20 at most, for the first
 * listener.
 */
internal class DakOpenedListeners(private val main: Executor) {
    private val listeners = CopyOnWriteArrayList<DakOpenedListener>()
    private val held = ArrayList<DakNotification>()

    fun add(listener: DakOpenedListener): DakSubscription {
        val drained = synchronized(this) {
            listeners.add(listener)
            ArrayList(held).also { held.clear() }
        }
        if (drained.isNotEmpty()) main.execute { drained.forEach { listener.onOpened(it) } }
        return DakSubscription { listeners.remove(listener) }
    }

    fun dispatch(n: DakNotification) {
        synchronized(this) {
            if (listeners.isEmpty()) {
                held.add(n)
                if (held.size > MAX_HELD) held.removeAt(0)
                return
            }
        }
        main.execute { listeners.forEach { it.onOpened(n) } }
    }

    companion object {
        const val MAX_HELD = 20
    }
}

/**
 * The device id: the current one at once if there is one, then each new one.
 * Never the same id twice in a row to one listener.
 */
internal class DakDeviceIdListeners(private val main: Executor, private val current: () -> String?) {
    private class Entry(val listener: DakDeviceIdListener) {
        var last: String? = null // main thread
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    fun add(listener: DakDeviceIdListener): DakSubscription {
        val entry = Entry(listener)
        entries.add(entry)
        main.execute { current()?.let { deliver(entry, it) } }
        return DakSubscription { entries.remove(entry) }
    }

    fun dispatch(id: String) {
        main.execute { entries.forEach { deliver(it, id) } }
    }

    private fun deliver(entry: Entry, id: String) {
        if (entry.last == id || entry !in entries) return
        entry.last = id
        entry.listener.onDeviceId(id)
    }
}
