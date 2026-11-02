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
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Follows the app's activities: when the app comes to the foreground, which
 * activity is on screen (for the permission prompt, and later in-app
 * messages), and the intents they start with, which carry taps on
 * notifications Firebase showed.
 */
internal class DakActivityWatcher(
    private val onForeground: () -> Unit,
    private val onIntent: (Intent?) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var resumed: WeakReference<Activity>? = null
    private val listeners = WeakHashMap<Activity, Consumer<Intent>>()

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
        if (started++ == 0) onForeground()
    }

    override fun onActivityResumed(activity: Activity) {
        if (isOwn(activity)) return
        resumed = WeakReference(activity)
        // An app that calls setIntent() in onNewIntent: its new intent.
        onIntent(activity.intent)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
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
