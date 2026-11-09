package io.github.hridoyrocks.dak

import android.app.Activity
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
import io.github.hridoyrocks.dak.internal.DakStateStore
import io.github.hridoyrocks.dak.internal.DakTaps
import io.github.hridoyrocks.dak.internal.FirebasePushSource
import io.github.hridoyrocks.dak.internal.InAppActions
import io.github.hridoyrocks.dak.internal.InAppController
import io.github.hridoyrocks.dak.internal.InAppImages
import io.github.hridoyrocks.dak.internal.InAppPresenter
import io.github.hridoyrocks.dak.internal.ThreadScheduler
import io.github.hridoyrocks.dak.internal.UrlConnectionHttp
import io.github.hridoyrocks.dak.internal.utf8Length
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The SDK's entry point.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Dak.init(this, appId = "APP_ID", baseUrl = "https://push.example.com")
 *         Dak.addOpenedListener { n -> route(n.url) }
 *         Dak.addInAppClickListener { c -> if (c.actionType == DakInAppClick.DEEP_LINK) route(c.url) }
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

    @Volatile
    private var inApp: InAppController? = null

    @Volatile
    private var presenter: InAppPresenter? = null

    @Volatile
    private var log = DakLog(false)

    private val opened = DakOpenedListeners(main)
    private val foreground = DakListeners<DakForegroundListener>(main)
    private val errors = DakListeners<DakErrorListener>(main)
    private val inAppClicks = DakListeners<DakInAppClickListener>(main)
    private val deviceIds = DakDeviceIdListeners(main) { client?.currentDeviceId }
    private val tagKey = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    private val events = object : DakEvents {
        override fun deviceId(id: String) = deviceIds.dispatch(id)

        override fun error(error: DakException) = errors.dispatch { it.onError(error) }

        override fun loaded() {
            inApp?.start()
        }

        override fun registered() {
            inApp?.onRegistered()
        }
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
     * @param appId the App ID from the DakPush dashboard.
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
            this.log = log
            appContext = app
            client = c
            val p = if (options.inAppMessages) InAppPresenter(InAppImages(File(app.cacheDir, "dak_in_app_images"), log), log) else null
            lateinit var w: DakActivityWatcher
            val i = p?.let { inAppController(api, files.inApp, c, it, log, options) { w.isForeground } }
            presenter = p
            inApp = i
            w = DakActivityWatcher(
                onForeground = {
                    c.onResume()
                    i?.onForeground()
                },
                onIntent = ::handleIntent,
                onBackground = { i?.onBackground() },
                onResumed = { p?.onActivityResumed(it) },
                onPaused = { p?.onActivityPaused(it) },
            )
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
     * This device's id on the DakPush server, or null before it is registered.
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

    // ---- in-app messages

    /**
     * Sets a trigger that in-app messages can wait for, such as the screen
     * the user is on (`Dak.addTrigger("screen", "checkout")`). Values are
     * strings, numbers or booleans. Triggers stay on the phone and are
     * forgotten when the app's process ends.
     *
     * @throws IllegalArgumentException for a bad key (1-64 of
     *   `A-Z a-z 0-9 _ . : -`) or a value of another type.
     */
    @JvmStatic
    public fun addTrigger(key: String, value: Any) {
        addTriggers(mapOf(key to value))
    }

    /** Sets several triggers at once; see [addTrigger]. */
    @JvmStatic
    public fun addTriggers(triggers: Map<String, Any>) {
        val values = LinkedHashMap<String, Any>()
        for ((k, v) in triggers) {
            require(tagKey.matches(k)) { "trigger key $k must be 1-64 of A-Z a-z 0-9 _ . : -" }
            @Suppress("USELESS_IS_CHECK", "SENSELESS_COMPARISON") // Java callers can pass anything.
            require(v != null && (v is String || v is Number || v is Boolean)) {
                "trigger $k: values must be strings, numbers or booleans"
            }
            values[k] = v
        }
        requireClient()
        inApp?.addTriggers(values)
    }

    /** Removes a trigger set with [addTrigger]. */
    @JvmStatic
    public fun removeTrigger(key: String) {
        removeTriggers(listOf(key))
    }

    /** Removes several triggers. */
    @JvmStatic
    public fun removeTriggers(keys: Collection<String>) {
        requireClient()
        inApp?.removeTriggers(keys.toList())
    }

    /** Removes all triggers. */
    @JvmStatic
    public fun clearTriggers() {
        requireClient()
        inApp?.clearTriggers()
    }

    /**
     * Stops (true) or allows (false) in-app messages, for example during a
     * call or a payment. A message on screen is hidden, not dismissed, and
     * comes back when they are allowed again. For a whole activity, implement
     * [DakInAppBlocker]; for a part of a screen, [blockInApp] nests better.
     */
    @JvmStatic
    public fun pauseInApp(paused: Boolean) {
        requireClient()
        inApp?.setPaused(paused)
    }

    /**
     * Keeps in-app messages away until the returned block is removed, for a
     * part of a screen that must stay clear (a Compose destination, a
     * fragment). Blocks nest: messages come back when the last one ends.
     *
     * ```kotlin
     * DisposableEffect(Unit) {
     *     val block = Dak.blockInApp()
     *     onDispose { block.remove() }
     * }
     * ```
     */
    @JvmStatic
    public fun blockInApp(): DakSubscription {
        requireClient()
        val i = inApp ?: return DakSubscription {}
        i.block()
        val ended = java.util.concurrent.atomic.AtomicBoolean(false)
        return DakSubscription { if (ended.compareAndSet(false, true)) i.unblock() }
    }

    /**
     * Whether in-app messages are kept away now: by [pauseInApp],
     * [blockInApp], a [DakInAppBlocker] activity on screen, or
     * `DakOptions(inAppMessages = false)`.
     */
    @JvmStatic
    public val isInAppPaused: Boolean
        get() {
            val i = inApp ?: return client != null
            return i.isPaused || presenter?.blocked == true
        }

    /**
     * Calls [listener] for taps on in-app messages and their buttons, on the
     * main thread, after the message closed. Deep links
     * ([DakInAppClick.DEEP_LINK]) are yours to route; web pages are opened in
     * the browser and tags set by the SDK already.
     */
    @JvmStatic
    public fun addInAppClickListener(listener: DakInAppClickListener): DakSubscription = inAppClicks.add(listener)

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

    private fun inAppController(
        api: DakApi,
        store: DakStateStore,
        c: DakClient,
        p: InAppPresenter,
        log: DakLog,
        options: DakOptions,
        isForeground: () -> Boolean,
    ): InAppController {
        val network = Executors.newSingleThreadExecutor { r -> Thread(r, "dak-in-app-net").apply { isDaemon = true } }
        val controller = InAppController(
            api = api,
            store = store,
            credentials = { c.credentials },
            ui = p,
            actions = object : InAppActions {
                override fun clicked(click: DakInAppClick) = inAppClicks.dispatch { it.onClick(click) }

                override fun openUrl(url: String) = main.execute { openInAppUrl(url) }

                override fun setTags(tags: Map<String, String?>) = c.setTags(tags, null)
            },
            scheduler = ThreadScheduler(log, "dak-in-app"),
            network = { network.execute(it) },
            log = log,
            isForeground = isForeground,
            onUnauthorized = { c.verifyDevice() },
            images = p.images,
            urlSchemes = options.inAppUrlSchemes.map { it.lowercase() }.toSet(),
            sessionTimeoutMs = options.sessionTimeoutMillis,
        )
        p.controller = controller
        return controller
    }

    /** A "web page" action: the browser (or the app, for its own App Links), over the activity on screen. */
    private fun openInAppUrl(url: String) {
        val context: Context = presenter?.activity ?: appContext ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            log.w("dak: nothing can open $url")
        }
    }

    private fun requireClient(): DakClient = client ?: throw IllegalStateException("Call Dak.init first")

    private fun requireContext(): Context = appContext ?: throw IllegalStateException("Call Dak.init first")
}
