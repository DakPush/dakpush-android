package io.github.hridoyrocks.dak.internal

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.WindowCompat
import io.github.hridoyrocks.dak.DakInAppBlocker
import io.github.hridoyrocks.dak.R
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * Draws in-app messages over the app's activity on screen, each in a window
 * of its own (a dialog without a frame): the modal, card and full-screen
 * ones take the focus, the back button and TalkBack like a dialog; banners
 * leave the app usable around them. A message goes when its activity pauses
 * (another screen, the background, a rotation) and the controller brings it
 * back on the next one without counting it again.
 *
 * Main thread, apart from [show] and [hide], which the controller calls.
 */
internal class InAppPresenter(
    val images: InAppImages?,
    private val log: DakLog,
) : InAppUi {
    lateinit var controller: InAppController

    private val main = Handler(Looper.getMainLooper())

    // Weak, though cleared when the activity pauses: the SDK never keeps a screen alive.
    private var hostRef: WeakReference<Activity>? = null
    private var host: Activity?
        get() = hostRef?.get()
        set(value) {
            hostRef = value?.let { WeakReference(it) }
        }
    private var window: InAppWindow? = null

    /** The showing whose image is loading; 0 for none. */
    private var loading = 0

    /** The activity on screen is a [DakInAppBlocker]. */
    @Volatile
    var blocked: Boolean = false
        private set

    /** The activity messages show over, if any. */
    val activity: Activity? get() = host

    /** The message window on screen, for tests. */
    internal val shownWindow: InAppWindow? get() = window

    fun onActivityResumed(activity: Activity) {
        if (activity is DakInAppBlocker) {
            blocked = true
            return
        }
        blocked = false
        if (host === activity) return
        host = activity
        controller.attach()
    }

    fun onActivityPaused(activity: Activity) {
        if (activity is DakInAppBlocker) {
            blocked = false
            return
        }
        if (activity !== host) return
        host = null
        loading = 0
        removeNow()
        controller.detach()
    }

    override fun show(message: InAppMessage, token: Int, again: Boolean) {
        main.post { present(message, token, again) }
    }

    override fun hide(token: Int) {
        main.post {
            if (loading == token) loading = 0
            val w = window
            if (w != null && w.token == token) {
                window = null
                w.close(animated = true)
            }
        }
    }

    private fun present(m: InAppMessage, token: Int, again: Boolean) {
        val activity = host ?: return
        if (activity.isFinishing) return
        removeNow()
        val url = m.content.imageUrl
        if (url.isNullOrEmpty()) {
            open(activity, m, token, null, again)
            return
        }
        loading = token
        val loaded = { bitmap: Bitmap? ->
            if (loading == token && host === activity) {
                loading = 0
                when {
                    bitmap != null -> open(activity, m, token, bitmap, again)
                    // The image did not load: without it, or skipped when it is all there is.
                    m.content.hasText -> open(activity, m.withContent(m.content.withoutImage()), token, null, again)
                    else -> controller.didFail(token)
                }
            }
        }
        val images = images
        if (images == null || !url.startsWith("https://")) loaded(null) else images.load(url, imageWidth(activity, m.layout), IMAGE_WAIT_MS, loaded)
    }

    private fun open(activity: Activity, m: InAppMessage, token: Int, image: Bitmap?, again: Boolean) {
        val w = try {
            InAppWindow(activity, m, token, image, quiet = again, onClick = { id ->
                controller.click(token, id)
                closeWindow(token)
            }, onDismissed = {
                controller.dismiss(token)
                closeWindow(token)
            })
        } catch (e: RuntimeException) {
            log.w("dak: could not draw an in-app message", e)
            return
        }
        try {
            w.present { if (window === w) controller.didDisplay(token) }
        } catch (e: RuntimeException) {
            // A window token that went away (the activity finishing): it comes back on the next one.
            log.d("dak: could not show an in-app message now: $e")
            return
        }
        window = w
    }

    private fun closeWindow(token: Int) {
        val w = window ?: return
        if (w.token != token) return
        window = null
        w.close(animated = true)
    }

    private fun removeNow() {
        val w = window ?: return
        window = null
        w.close(animated = false)
    }

    companion object {
        /** How long a message waits for its image, like the Flutter SDK. */
        const val IMAGE_WAIT_MS = 5_000L

        /** The image's width in pixels, about as large as it is drawn (dakInAppImage in views.dart). */
        fun imageWidth(activity: Activity, layout: InAppLayout): Int {
            val cfg = activity.resources.configuration
            val w = cfg.screenWidthDp.toFloat()
            val h = cfg.screenHeightDp.toFloat()
            val logical = when (layout) {
                InAppLayout.MODAL -> min(w - 48, 400f)
                // Wide images fill the card's 200 dp box by its height.
                InAppLayout.CARD -> min(w, 600f) * 1.5f
                InAppLayout.FULLSCREEN -> max(w, h)
                InAppLayout.BANNER_TOP, InAppLayout.BANNER_BOTTOM -> 96f
            }
            return (max(logical, 48f) * activity.resources.displayMetrics.density).toInt()
        }
    }
}

/** One message's window over [activity]. */
internal class InAppWindow(
    activity: Activity,
    val message: InAppMessage,
    val token: Int,
    image: Bitmap?,
    /** Back on screen after a pause or rotation: no entrance animation. */
    private val quiet: Boolean,
    private val onClick: (String?) -> Unit,
    private val onDismissed: () -> Unit,
) : Dialog(activity, R.style.DakInAppWindow) {
    private val main = Handler(Looper.getMainLooper())
    private val banner = message.layout.isBanner
    private var closing = false
    private var autoDismiss: Runnable? = null

    val view: InAppView = InAppView(context, message, image, InAppTheme.of(context), object : InAppViewEvents {
        override fun onClick(buttonId: String?) {
            if (!closing) this@InAppWindow.onClick(buttonId)
        }

        override fun onDismiss() {
            if (!closing) onDismissed()
        }
    })

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(view.root)
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        // TalkBack names the window by it.
        val c = message.content
        setTitle(if (c.hasTitle) c.title else c.body.orEmpty())
        configure(window!!)
    }

    private fun configure(w: Window) {
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (banner) {
            // The app around a banner stays usable and keeps the keyboard.
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(if (message.layout == InAppLayout.BANNER_TOP) Gravity.TOP else Gravity.BOTTOM)
        } else {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        // Edge to edge: the views keep clear of the bars, the cutout and the keyboard themselves.
        WindowCompat.setDecorFitsSystemWindows(w, false)
        val lp = w.attributes
        if (Build.VERSION.SDK_INT >= 30) {
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        w.attributes = lp
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                w.isStatusBarContrastEnforced = false
            }
            w.isNavigationBarContrastEnforced = false
        }
        if (message.layout == InAppLayout.FULLSCREEN) {
            val light = !isDark(InAppStyle(InAppTheme.of(context), message.content).background)
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    /** Shows the window; [shown] runs after its first frame. */
    fun present(shown: () -> Unit) {
        show()
        OneShotPreDrawListener.add(view.root) {
            view.enter(quiet)
            view.root.post { if (!closing && isShowing) shown() }
            startTimer()
        }
    }

    /** Banners close by themselves after their time, but not for TalkBack users, who need the time to find them. */
    private fun startTimer() {
        val ms = message.content.autoDismissMs ?: return
        if (!banner) return
        val am = context.getSystemService(AccessibilityManager::class.java)
        if (am?.isTouchExplorationEnabled == true) return
        val wait = if (Build.VERSION.SDK_INT >= 29 && am != null) {
            am.getRecommendedTimeoutMillis(ms.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT).toLong()
        } else {
            ms
        }
        val r = Runnable { if (!closing) onDismissed() }
        autoDismiss = r
        main.postDelayed(r, wait)
    }

    /** Back closes the message, with the same fade as the X. */
    @Deprecated("Dialog's back handling; the SDK closes the message itself")
    override fun onBackPressed() {
        if (!closing) onDismissed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.isTracking && !event.isCanceled) {
            if (!closing) onDismissed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Takes the window away, after a short fade when [animated]. */
    fun close(animated: Boolean) {
        if (closing && animated) return
        closing = true
        autoDismiss?.let { main.removeCallbacks(it) }
        autoDismiss = null
        if (!animated || !isShowing) {
            dismissQuietly()
        } else {
            view.exit { dismissQuietly() }
        }
    }

    private fun dismissQuietly() {
        try {
            if (isShowing) dismiss()
        } catch (e: IllegalArgumentException) {
            // The activity's window went first.
        }
    }
}
