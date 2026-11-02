package io.github.hridoyrocks.dak

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.hridoyrocks.dak.internal.AndroidPlatformInfo
import io.github.hridoyrocks.dak.internal.DakActivityWatcher
import io.github.hridoyrocks.dak.internal.DakApi
import io.github.hridoyrocks.dak.internal.DakClient
import io.github.hridoyrocks.dak.internal.DakDelivery
import io.github.hridoyrocks.dak.internal.DakDeviceIdListeners
import io.github.hridoyrocks.dak.internal.DakEvents
import io.github.hridoyrocks.dak.internal.DakFids
import io.github.hridoyrocks.dak.internal.DakFiles
import io.github.hridoyrocks.dak.internal.DakListeners
import io.github.hridoyrocks.dak.internal.DakLog
import io.github.hridoyrocks.dak.internal.DakNotifier
import io.github.hridoyrocks.dak.internal.DakOpenedListeners
import io.github.hridoyrocks.dak.internal.DakPermissions
import io.github.hridoyrocks.dak.internal.DakTaps
import io.github.hridoyrocks.dak.internal.FirebasePushSource
import io.github.hridoyrocks.dak.internal.ThreadScheduler
import io.github.hridoyrocks.dak.internal.UrlConnectionHttp
import io.github.hridoyrocks.dak.internal.utf8Length
import java.util.concurrent.Executor

/**
 * The SDK's entry point.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Dak.init(this, appId = "APP_ID", baseUrl = "https://push.example.com")
 *         Dak.addOpenedListener { n -> route(n.url) }
 *     }
 * }
 * ```
 */
public object Dak {
    /** The SDK's version, as it reports it to the server (with an `android-` prefix). */
    public const val VERSION: String = BuildConfig.SDK_VERSION

    private val mainHandler = Handler(Looper.getMainLooper())
    private val main = Executor { mainHandler.post(it) }

    @Volatile
    private var client: DakClient? = null

    @Volatile
    private var options = DakOptions()

    @Volatile
    private var appContext: Context? = null
    private var watcher: DakActivityWatcher? = null

    private val opened = DakOpenedListeners(main)
    private val foreground = DakListeners<DakForegroundListener>(main)
    private val errors = DakListeners<DakErrorListener>(main)
    private val deviceIds = DakDeviceIdListeners(main) { client?.currentDeviceId }
    private val tagKey = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    private val events = object : DakEvents {
        override fun deviceId(id: String) = deviceIds.dispatch(id)

        override fun error(error: DakException) = errors.dispatch { it.onError(error) }
    }

    /**
     * Starts the SDK. Call it in `Application.onCreate`, so taps that start the
     * app are seen. Later calls do nothing.
     *
     * It returns at once: reading the saved state, getting the push token and
     * registering the device run in the background, so a slow network never
     * holds the app's start. Calls like [login] made meanwhile wait for the
     * saved state, then sync.
     *
     * @param appId the App ID from the Dak dashboard.
     * @param baseUrl the server's public URL, without `/v1`. It must be https;
     *   plain http works only for `localhost` and the emulator's `10.0.2.2`.
     * @throws IllegalArgumentException for an appId or baseUrl that cannot be right.
     */
    @JvmStatic
    @JvmOverloads
    public fun init(context: Context, appId: String, baseUrl: String, options: DakOptions = DakOptions()) {
        val log = DakLog(options.debugLogs)
        val api = DakApi(baseUrl, appId, UrlConnectionHttp(), DakDelivery.userAgent())
        synchronized(this) {
            if (client != null) {
                log.w("Dak.init was called again; the first call's settings stay")
                return
            }
            val app = context.applicationContext
            val files = DakFiles.of(app)
            val c = DakClient(
                api = api,
                store = files.state,
                pending = files.pending,
                push = FirebasePushSource(options.useFid, log),
                platform = AndroidPlatformInfo(app),
                scheduler = ThreadScheduler(log),
                callbacks = main,
                events = events,
                log = log,
                sdkVersion = "android-$VERSION",
                shareTimezoneOffset = options.shareTimezoneOffset,
            )
            this.options = options
            appContext = app
            client = c
            val w = DakActivityWatcher(onForeground = { c.onResume() }, onIntent = ::handleIntent)
            watcher = w
            (app as? Application)?.registerActivityLifecycleCallbacks(w)
                ?: log.w("Dak.init needs the Application context to see taps and the app coming to the foreground")
            c.start()
        }
    }

    /** True once [init] ran. */
    @JvmStatic
    public val isInitialized: Boolean get() = client != null

    /**
     * This device's id on the Dak server, or null before it is registered.
     * [init] returns before a new device registers; use [addDeviceIdListener]
     * to get the id when it comes.
     */
    @JvmStatic
    public val deviceId: String? get() = client?.currentDeviceId

    /**
     * Calls [listener] with the device id: at once when there is one, then
     * each new one (the first registration, or registering again after the
     * server forgot the device). Never the same id twice in a row. On the main
     * thread.
     */
    @JvmStatic
    public fun addDeviceIdListener(listener: DakDeviceIdListener): DakSubscription = deviceIds.add(listener)

    /**
     * Links this device to your user id. With identity verification on (the
     * server's default), [authHash] must come from your server:
     * hex(HMAC-SHA256(key = identity secret, message = externalId)).
     *
     * [callback] gets a [DakException] when the server refuses (a wrong auth
     * hash); then only the user id is rolled back. Network problems never
     * fail: the change is saved and sent later.
     *
     * @throws IllegalArgumentException for an id that is empty, over 128 bytes
     *   of UTF-8, or an email address.
     */
    @JvmStatic
    @JvmOverloads
    public fun login(externalId: String, authHash: String? = null, callback: DakCallback? = null) {
        require(externalId.isNotEmpty() && utf8Length(externalId) <= 128 && '@' !in externalId) {
            "use your own user id (1-128 bytes, not an email address)"
        }
        requireClient().login(externalId, authHash?.takeIf { it.isNotEmpty() }, callback)
    }

    /**
     * Unlinks the user from this device and removes its tags, so the next user
     * of the phone starts without them. Push permission and the subscription
     * stay.
     */
    @JvmStatic
    @JvmOverloads
    public fun logout(callback: DakCallback? = null) {
        requireClient().logout(callback)
    }

    /**
     * Sets tags on this device; a null or empty value removes the tag. Values
     * are strings, numbers or booleans, sent as strings.
     *
     * @throws IllegalArgumentException for a bad key (1-64 of
     *   `A-Z a-z 0-9 _ . : -`), a value of another type or over 255 bytes of
     *   UTF-8. More than 50 tags fail through [callback].
     */
    @JvmStatic
    @JvmOverloads
    public fun setTags(tags: Map<String, Any?>, callback: DakCallback? = null) {
        val values = LinkedHashMap<String, String?>()
        for ((k, v) in tags) {
            require(tagKey.matches(k)) { "tag key $k must be 1-64 of A-Z a-z 0-9 _ . : -" }
            val s = when (v) {
                null -> null
                is String, is Number, is Boolean -> v.toString()
                else -> throw IllegalArgumentException("tag $k: values must be strings, numbers or booleans")
            }
            require(s == null || utf8Length(s) <= 255) { "tag $k: at most 255 bytes of UTF-8" }
            values[k] = s
        }
        requireClient().setTags(values, callback)
    }

    /** Sets one tag; see [setTags]. */
    @JvmStatic
    @JvmOverloads
    public fun setTag(key: String, value: Any?, callback: DakCallback? = null) {
        setTags(mapOf(key to value), callback)
    }

    /** Removes the tags with these keys. */
    @JvmStatic
    @JvmOverloads
    public fun deleteTags(keys: Collection<String>, callback: DakCallback? = null) {
        setTags(keys.associateWith { null }, callback)
    }

    /** Removes one tag. */
    @JvmStatic
    @JvmOverloads
    public fun deleteTag(key: String, callback: DakCallback? = null) {
        deleteTags(listOf(key), callback)
    }

    /**
     * Opts this device out of (false) or back into (true) all pushes, for a
     * switch in your app's settings. Notification permission is not changed.
     */
    @JvmStatic
    @JvmOverloads
    public fun setSubscribed(subscribed: Boolean, callback: DakCallback? = null) {
        requireClient().setSubscribed(subscribed, callback)
    }

    /** Whether the user allows this app's notifications. */
    @JvmStatic
    public fun hasPermission(): Boolean = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()

    /**
     * Asks the user for notification permission (Android 13+), over the
     * activity on screen. [callback] gets whether notifications are allowed,
     * also on older Android, where no prompt shows. After two refusals Android
     * stops asking; [openNotificationSettings] is left then.
     */
    @JvmStatic
    @JvmOverloads
    public fun requestPermission(callback: DakResultCallback? = null) {
        val context = requireContext()
        mainHandler.post { DakPermissions.request(context, watcher?.resumedActivity, callback) }
    }

    /** Opens this app's notification settings. */
    @JvmStatic
    public fun openNotificationSettings() {
        val context = requireContext()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            DakLog(false).w("no notification settings screen", e)
        }
    }

    /**
     * Calls [listener] for taps on notifications, on the main thread.
     * [DakNotification.actionId] tells which button was tapped, if one was.
     * Taps that come before the first listener (the tap that started the app)
     * are kept for it. The SDK reports the open itself.
     */
    @JvmStatic
    public fun addOpenedListener(listener: DakOpenedListener): DakSubscription = opened.add(listener)

    /**
     * Calls [listener] for messages that arrive while the app is open, silent
     * ones too. Android shows nothing then; call [showNotification] to show one
     * the way it would have looked in the background.
     */
    @JvmStatic
    public fun addForegroundListener(listener: DakForegroundListener): DakSubscription = foreground.add(listener)

    /**
     * Calls [listener] for changes the server refused, such as a wrong auth
     * hash or a bad tag, also from syncs that run in the background. Only the
     * refused field is rolled back; the rest (an opt-out above all) is still
     * sent.
     */
    @JvmStatic
    public fun addErrorListener(listener: DakErrorListener): DakSubscription = errors.add(listener)

    /**
     * Shows a message that arrived while the app was open the way it would
     * have been shown in the background, buttons and image included.
     * [callback] gets false when nothing was shown: notifications turned off,
     * or no title and body.
     */
    @JvmStatic
    @JvmOverloads
    public fun showNotification(notification: DakNotification, callback: DakResultCallback? = null) {
        val context = requireContext()
        Thread({
            val shown = DakNotifier.show(context, DakNotifier.showData(notification), DakFiles.of(context).taps)
            callback?.let { main.execute { it.onResult(shown) } }
        }, "dak-show").start()
    }

    /**
     * Reads a tap on a notification from an activity's new intent. The SDK
     * sees intents by itself for AndroidX activities (ComponentActivity,
     * AppCompatActivity) and for activities that call setIntent in
     * onNewIntent; any other activity that can be reused (singleTop,
     * singleTask) calls this from onNewIntent. True when it was a tap.
     */
    @JvmStatic
    public fun handleIntent(intent: Intent?): Boolean {
        val context = appContext ?: return false
        val tap = DakTaps.firebaseTap(intent, DakFiles.of(context).taps) ?: return false
        handleTap(context, tap)
        return true
    }

    /**
     * For an app with its own FirebaseMessagingService (instead of extending
     * [DakMessagingService]): pass new tokens on from its onNewToken.
     */
    @JvmStatic
    public fun onNewToken(token: String) {
        client?.onNewToken(token)
    }

    /**
     * For an app with its own FirebaseMessagingService (instead of extending
     * [DakMessagingService]): pass Firebase Installation IDs on from its
     * onRegistered.
     */
    @JvmStatic
    public fun onRegistered(installationId: String) {
        DakFids.offer(installationId)
        if (options.useFid) client?.onRegistered(installationId)
    }

    /** Sends anything waiting now, for example when the network comes back. */
    @JvmStatic
    @JvmOverloads
    public fun flush(callback: DakCallback? = null) {
        requireClient().flush(callback)
    }

    // ---- for the SDK's components

    internal val openAppOnTap: Boolean get() = options.openAppOnTap

    /** A tap: the open is kept until sent, and the listeners get it. */
    internal fun handleTap(context: Context, n: DakNotification) {
        n.id?.let { DakFiles.of(context).pending.addOpen(it, n.actionId) }
        opened.dispatch(n)
        client?.takePending()
    }

    internal fun dispatchForeground(n: DakNotification) = foreground.dispatch { it.onForeground(n) }

    /** Hands kept receipts and taps to the client; false without one. */
    internal fun takePending(): Boolean {
        val c = client ?: return false
        c.takePending()
        return true
    }

    internal fun permissionChanged() {
        client?.refreshPermission()
    }

    private fun requireClient(): DakClient = client ?: throw IllegalStateException("Call Dak.init first")

    private fun requireContext(): Context = appContext ?: throw IllegalStateException("Call Dak.init first")
}
