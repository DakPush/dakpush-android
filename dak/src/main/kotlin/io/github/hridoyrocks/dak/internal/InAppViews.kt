package io.github.hridoyrocks.dak.internal

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import io.github.hridoyrocks.dak.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours of the app's theme that a message without its own falls back
 * on, like the Flutter SDK takes them from the app's ColorScheme. Material
 * 3's baseline when the theme has nothing usable.
 */
internal class InAppTheme(
    val primary: Int,
    val onPrimary: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
) {
    companion object {
        /** Flutter's ThemeData() defaults, which the dashboard preview also uses. */
        val LIGHT = InAppTheme(0xFF6750A4.toInt(), 0xFFFFFFFF.toInt(), 0xFFFEF7FF.toInt(), 0xFF1D1B20.toInt(), 0xFF49454F.toInt())
        val DARK = InAppTheme(0xFFD0BCFF.toInt(), 0xFF381E72.toInt(), 0xFF141218.toInt(), 0xFFE6E0E9.toInt(), 0xFFCAC4D0.toInt())

        /**
         * Material Components and AppCompat attributes first (by name: the
         * SDK does not depend on those libraries), then the platform's.
         * A colour that would not read is replaced.
         */
        fun of(context: Context): InAppTheme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val base = if (night) DARK else LIGHT
            val surface = listOf(appColor(context, "colorSurface"), color(context, android.R.attr.colorBackground))
                .firstOrNull { it != null && Color.alpha(it) == 255 } ?: base.surface
            val onSurface = listOf(appColor(context, "colorOnSurface"), color(context, android.R.attr.textColorPrimary))
                .firstOrNull { it != null && contrast(it, surface) >= 3.0 } ?: readableOn(surface)
            val onSurfaceVariant = listOf(appColor(context, "colorOnSurfaceVariant"), color(context, android.R.attr.textColorSecondary))
                .firstOrNull { it != null && contrast(it, surface) >= 3.0 } ?: withAlpha(onSurface, 0xC0)
            // White-toolbar apps have a white colorPrimary; buttons need a colour that shows.
            val primary = listOf(
                appColor(context, "colorPrimary"),
                appColor(context, "colorSecondary"),
                appColor(context, "colorAccent"),
                color(context, android.R.attr.colorAccent),
                color(context, android.R.attr.colorPrimary),
            ).firstOrNull { it != null && Color.alpha(it) == 255 && contrast(it, surface) >= 1.5 }
                ?: base.primary.takeIf { contrast(it, surface) >= 1.5 } ?: onSurface
            val onPrimary = listOf(appColor(context, "colorOnPrimary"))
                .firstOrNull { it != null && contrast(it, primary) >= 3.0 } ?: readableOn(primary)
            return InAppTheme(primary, onPrimary, surface, onSurface, onSurfaceVariant)
        }

        /** An attribute of the app's own resources (from a library it uses), looked up by name. */
        @SuppressLint("DiscouragedApi")
        private fun appColor(context: Context, name: String): Int? {
            val res = context.resources
            val pkg = try {
                res.getResourcePackageName(R.string.dak_in_app_close)
            } catch (e: Exception) {
                context.packageName
            }
            val id = res.getIdentifier(name, "attr", pkg)
            return if (id == 0) null else color(context, id)
        }

        private fun color(context: Context, attr: Int): Int? {
            val tv = TypedValue()
            if (!context.theme.resolveAttribute(attr, tv, true)) return null
            return when {
                tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> tv.data
                tv.resourceId != 0 -> try {
                    ContextCompat.getColorStateList(context, tv.resourceId)?.defaultColor
                } catch (e: Exception) {
                    null
                }
                else -> null
            }
        }
    }
}

/** sRGB relative luminance, as Flutter's Color.computeLuminance (alpha ignored). */
internal fun luminance(c: Int): Double {
    fun ch(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
}

/** Flutter's ThemeData.estimateBrightnessForColor. */
internal fun isDark(c: Int): Boolean {
    val l = luminance(c)
    return (l + 0.05) * (l + 0.05) <= 0.15
}

/** White on a dark colour, 87% black on a light one, like the Flutter SDK. */
internal fun readableOn(bg: Int): Int = if (isDark(bg)) Color.WHITE else 0xDE000000.toInt()

internal fun withAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or (alpha shl 24)

/** WCAG contrast of [fg] (blended onto [bg]) against [bg]. */
internal fun contrast(fg: Int, bg: Int): Double {
    val a = Color.alpha(fg) / 255.0
    val blended = Color.rgb(
        (Color.red(fg) * a + Color.red(bg) * (1 - a)).toInt(),
        (Color.green(fg) * a + Color.green(bg) * (1 - a)).toInt(),
        (Color.blue(fg) * a + Color.blue(bg) * (1 - a)).toInt(),
    )
    val l1 = luminance(blended)
    val l2 = luminance(bg)
    return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
}

/** The colours a message is drawn with: its own, else the theme's (views.dart's _Parts). */
internal class InAppStyle(val theme: InAppTheme, private val c: InAppContent) {
    val background: Int = c.backgroundColor ?: theme.surface
    val onBackground: Int = if (c.backgroundColor == null) theme.onSurface else readableOn(c.backgroundColor)
    val onBackgroundVariant: Int = if (c.backgroundColor == null) theme.onSurfaceVariant else withAlpha(onBackground, 0xC0)
    val title: Int = c.titleColor ?: onBackground
    val body: Int = c.bodyColor ?: onBackgroundVariant

    /** The X: white on a dark circle over an image, else the title's colour. */
    val close: Int = if (c.hasImage) Color.WHITE else c.titleColor ?: onBackground
    val bannerClose: Int = c.titleColor ?: onBackgroundVariant

    /** Null: a text button without a fill. The first button is the primary one. */
    fun buttonBackground(b: InAppButton, primary: Boolean): Int? = b.backgroundColor ?: if (primary) theme.primary else null

    fun buttonText(b: InAppButton, primary: Boolean): Int = b.textColor ?: when {
        b.backgroundColor != null -> readableOn(b.backgroundColor)
        primary -> theme.onPrimary
        // A text button on the message's own background.
        c.backgroundColor == null -> theme.primary
        else -> onBackground
    }
}

/** What the user does with a message on screen. */
internal interface InAppViewEvents {
    /** A button ([buttonId]) or the message itself (null). */
    fun onClick(buttonId: String?)

    /** The X, a tap outside, a swipe, back, or the banner's timer. */
    fun onDismiss()
}

/**
 * One message drawn with plain Android views, in the sizes of the dashboard
 * preview (InAppPreview.svelte) and the Flutter SDK (views.dart).
 * [root] fills the message's window; [applyInsets] keeps it clear of the
 * system bars, the cutout and the keyboard.
 */
internal class InAppView(
    private val context: Context,
    val message: InAppMessage,
    private val image: Bitmap?,
    theme: InAppTheme,
    private val events: InAppViewEvents,
) {
    private val c = message.content
    private val style = InAppStyle(theme, c)
    private val density = context.resources.displayMetrics.density
    private val layout = message.layout

    val root: InAppRoot = InAppRoot(context)

    /** The part that moves in and out (the sheet, the card, the banner). */
    private lateinit var panel: View

    /** The view the bottom system inset goes into (the card's inside), if any. */
    private var insetBottom: View? = null
    private var insetBottomBase = 0

    /** The message's own close button and buttons, for tests. */
    val closeButton: View? get() = root.findViewWithTag(TAG_CLOSE)

    init {
        when (layout) {
            InAppLayout.MODAL -> buildModal()
            InAppLayout.CARD -> buildCard()
            InAppLayout.FULLSCREEN -> buildFullscreen()
            InAppLayout.BANNER_TOP, InAppLayout.BANNER_BOTTOM -> buildBanner(layout == InAppLayout.BANNER_TOP)
        }
        root.tag = "dak-in-app-${layout.wire}"
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyInsets(insets)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---- sizes

    private fun dp(v: Number): Int = (v.toFloat() * density + 0.5f).toInt()

    /** The X, also when the message turned it off but has no other way out (a full-screen message with nothing to tap). */
    private val showClose: Boolean
        get() = c.closeButton || (layout == InAppLayout.FULLSCREEN && c.buttons.isEmpty() && c.clickAction == null)

    fun applyInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottom = max(bars.bottom, ime.bottom)
        when (layout) {
            InAppLayout.MODAL -> root.setPadding(bars.left, bars.top, bars.right, bottom)
            InAppLayout.CARD -> {
                // The card's colour goes under the navigation bar; its content stays above it.
                root.setPadding(bars.left, bars.top, bars.right, ime.bottom)
                insetBottom?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, insetBottomBase + if (ime.bottom > 0) 0 else bars.bottom) }
            }
            InAppLayout.FULLSCREEN -> root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            InAppLayout.BANNER_TOP -> root.setPadding(bars.left + dp(12), bars.top + dp(12), bars.right + dp(12), dp(12))
            InAppLayout.BANNER_BOTTOM -> root.setPadding(bars.left + dp(12), dp(12), bars.right + dp(12), bottom + dp(12))
        }
    }

    // ---- layouts

    private fun buildModal() {
        root.setBackgroundColor(BACKDROP)
        root.setOnClickListener { events.onDismiss() }
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val sheet = Sheet(context, dp(400), 0.85f).apply {
            background = rounded(style.background, dp(20).toFloat())
            elevation = dp(8).toFloat()
            clipToOutline = true
        }
        val column = column()
        if (image != null) column.addView(AspectImage(context, image, dp(240)).also(::decorative), matchWrap())
        column.addView(texts(top = if (image != null) 20 else 36))
        sheet.addView(scroll(tappable(column)), FrameLayout.LayoutParams(MATCH, WRAP))
        if (showClose) sheet.addView(close(), corner(top = 0))
        root.addView(sheet, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
        panel = sheet
    }

    private fun buildCard() {
        root.setBackgroundColor(BACKDROP)
        root.setOnClickListener { events.onDismiss() }
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val radius = dp(24).toFloat()
        val sheet = Sheet(context, dp(600), 0.9f).apply {
            background = ColorDrawable(style.background)
            elevation = dp(8).toFloat()
            // Only the top corners are round: the outline's bottom ones lie below the card.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
                }
            }
            clipToOutline = true
        }
        val column = column()
        if (image != null) {
            column.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(image)
                decorative(this)
            }, LinearLayout.LayoutParams(MATCH, dp(200)))
        } else {
            column.addView(View(context), LinearLayout.LayoutParams(MATCH, dp(16)))
        }
        column.addView(texts(top = 20))
        val scroll = scroll(tappable(column))
        sheet.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP))
        insetBottom = scroll
        insetBottomBase = 0
        if (showClose) sheet.addView(close(), corner(top = 4))
        root.addView(sheet, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        panel = sheet
    }

    private fun buildFullscreen() {
        root.setBackgroundColor(style.background)
        val frame = FrameLayout(context)
        val column = column()
        if (image != null) {
            column.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(image)
                decorative(this)
            }, LinearLayout.LayoutParams(MATCH, 0, 5f))
        }
        val textArea = FrameLayout(context)
        val inner = column().apply { setPadding(dp(28), dp(24), dp(28), dp(24)) }
        if (c.hasTitle) inner.addView(title(24f, 32f, 0f, View.TEXT_ALIGNMENT_CENTER, null))
        if (c.hasTitle && c.hasBody) inner.addView(space(12))
        if (c.hasBody) inner.addView(body(View.TEXT_ALIGNMENT_CENTER, null))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        textArea.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        column.addView(textArea, LinearLayout.LayoutParams(MATCH, 0, 4f))
        if (c.buttons.isNotEmpty()) {
            val area = buttons().apply { setPadding(dp(20), 0, dp(20), dp(16)) }
            column.addView(area, matchWrap())
        }
        frame.addView(tappable(column), FrameLayout.LayoutParams(MATCH, MATCH))
        if (showClose) frame.addView(close(), corner(top = 0))
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        panel = frame
    }

    private fun buildBanner(top: Boolean) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(style.background, dp(16).toFloat())
            elevation = dp(6).toFloat()
            clipToOutline = true
            setPaddingRelative(dp(12), dp(10), dp(4), dp(10))
        }
        if (image != null) {
            card.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(image)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                clipToOutline = true
                decorative(this)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        }
        val texts = column()
        if (c.hasTitle) texts.addView(title(14f, 20f, 0.1f, View.TEXT_ALIGNMENT_VIEW_START, 2))
        if (c.hasTitle && c.hasBody) texts.addView(space(2))
        if (c.hasBody) texts.addView(body(View.TEXT_ALIGNMENT_VIEW_START, 3))
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (showClose) {
            // 48 dp to touch, 40 dp in the row (the margins overlap the card's padding).
            card.addView(closeView(18, style.bannerClose, false), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(-dp(4), -dp(4), -dp(4), -dp(4))
            })
        } else {
            card.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        }
        val tappableCard = tappable(card)
        val swipe = SwipeLayout(context, up = top) { events.onDismiss() }
        swipe.addView(tappableCard, FrameLayout.LayoutParams(MATCH, WRAP))
        // Read out when it appears, and closable without the X or a swipe.
        ViewCompat.setAccessibilityPaneTitle(swipe, c.title?.takeIf { c.hasTitle } ?: c.body)
        swipe.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.addAccessibilityAction(swipe, context.getString(R.string.dak_in_app_close)) { _, _ ->
            events.onDismiss()
            true
        }
        val sheet = Sheet(context, dp(600), 1f)
        sheet.addView(swipe, FrameLayout.LayoutParams(MATCH, WRAP))
        // The card's shadow falls outside it, into the window's 12 dp margin.
        for (v in listOf<ViewGroup>(swipe, sheet, root)) {
            v.clipChildren = false
            v.clipToPadding = false
        }
        root.addView(sheet, FrameLayout.LayoutParams(WRAP, WRAP, (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL))
        panel = swipe
    }

    // ---- parts

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun space(heightDp: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp)) }

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun corner(top: Int) = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(top) }

    private fun scroll(child: View) = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(child, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /** Title, text and buttons, centred (texts() in views.dart); [top] in dp. */
    private fun texts(top: Int): View {
        val col = column()
        // The buttons are 48 dp to touch and 40 dp to see: 4 dp of their own above and below.
        val buttons = c.buttons.isNotEmpty()
        col.setPadding(dp(20), dp(top), dp(20), dp(if (buttons) 12 else 16))
        if (c.hasTitle) col.addView(title(22f, 28f, 0f, View.TEXT_ALIGNMENT_CENTER, null))
        if (c.hasTitle && c.hasBody) col.addView(space(8))
        if (c.hasBody) col.addView(body(View.TEXT_ALIGNMENT_CENTER, null))
        if (buttons) {
            col.addView(space(16))
            col.addView(buttons(), matchWrap())
        }
        return col
    }

    private fun title(sizeSp: Float, lineSp: Float, letterSp: Float, align: Int, maxLines: Int?) = text(c.title.orEmpty(), sizeSp, lineSp, letterSp, align, maxLines).apply {
        setTextColor(style.title)
        weight(600)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun body(align: Int, maxLines: Int?) = text(c.body.orEmpty(), 14f, 20f, 0.25f, align, maxLines).apply {
        setTextColor(style.body)
    }

    private fun text(value: String, sizeSp: Float, lineSp: Float, letterSp: Float, align: Int, maxLines: Int?) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        TextViewCompat.setLineHeight(this, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineSp, resources.displayMetrics).toInt())
        letterSpacing = letterSp / sizeSp
        // Gravity too: apps without android:supportsRtl ignore textAlignment.
        textAlignment = align
        gravity = if (align == View.TEXT_ALIGNMENT_CENTER) Gravity.CENTER_HORIZONTAL else Gravity.START
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (maxLines != null) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    /** One or two buttons side by side, three stacked. */
    private fun buttons(): LinearLayout {
        val stack = c.buttons.size > 2
        val group = LinearLayout(context).apply { orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        c.buttons.forEachIndexed { i, b ->
            val view = button(b, primary = i == 0)
            if (stack) {
                group.addView(view, LinearLayout.LayoutParams(MATCH, WRAP))
            } else {
                group.addView(view, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        }
        return group
    }

    private fun button(b: InAppButton, primary: Boolean): View {
        val bg = style.buttonBackground(b, primary)
        val fg = style.buttonText(b, primary)
        val pill = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(bg ?: Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.WHITE)
        }
        return PillButton(context).apply {
            text = b.text
            tag = "$TAG_BUTTON${b.id}"
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            letterSpacing = 0.1f / 14f
            weight(500)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 48 dp to touch, 40 dp to see; taller with a large font size.
            minHeight = dp(48)
            val side = dp(if (bg != null) 24 else 12)
            setPaddingRelative(side, dp(8), side, dp(8))
            background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(withAlpha(fg, 0x1F)), pill, mask), 0, dp(4), 0, dp(4))
            setOnClickListener { events.onClick(b.id) }
        }
    }

    private fun close(): View = closeView(20, style.close, c.hasImage)

    private fun closeView(iconDp: Int, color: Int, onImage: Boolean): View {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (onImage) 0x42000000 else Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val icon: Drawable = ContextCompat.getDrawable(context, R.drawable.dak_ic_close)!!.mutate().apply { setTint(color) }
        return CloseButton(context).apply {
            tag = TAG_CLOSE
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (dp(48) - dp(iconDp)) / 2
            setPadding(pad, pad, pad, pad)
            background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(withAlpha(color, 0x1F)), circle, mask), dp(4))
            contentDescription = context.getString(R.string.dak_in_app_close)
            setOnClickListener { events.onDismiss() }
        }
    }

    /**
     * With a click action the message itself is a button. Without one it
     * still takes the taps that miss its buttons, so they do not reach the
     * backdrop (which closes it), and TalkBack does not call it a button.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun tappable(view: View): View {
        if (c.clickAction == null) {
            view.setOnTouchListener { _, _ -> true }
            return view
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(withAlpha(style.onBackground, 0x1F)), null, ColorDrawable(Color.WHITE))
        view.foreground = ripple
        view.setOnClickListener { events.onClick(null) }
        return view
    }

    private fun decorative(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun TextView.weight(w: Int) {
        val base = typeface ?: Typeface.DEFAULT
        typeface = when {
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(base, w, false)
            w >= 600 -> Typeface.create(base, Typeface.BOLD)
            base == Typeface.DEFAULT -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> base
        }
    }

    // ---- motion

    /** Fades in, the modal growing, the card and banners sliding in; nothing moves with reduced motion or [quiet]. */
    fun enter(quiet: Boolean) {
        if (quiet || !animationsOn(context)) return
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(EASE_OUT_CUBIC).start()
        when (layout) {
            InAppLayout.MODAL -> {
                panel.scaleX = 0.92f
                panel.scaleY = 0.92f
                panel.animate().scaleX(1f).scaleY(1f).setDuration(ENTER_MS).setInterpolator(EASE_OUT_CUBIC).start()
            }
            InAppLayout.CARD, InAppLayout.BANNER_BOTTOM -> slide(root.height.toFloat())
            InAppLayout.BANNER_TOP -> slide(-root.height.toFloat())
            InAppLayout.FULLSCREEN -> {}
        }
    }

    private fun slide(from: Float) {
        panel.translationY = from
        panel.animate().translationY(0f).setDuration(ENTER_MS).setInterpolator(EASE_OUT_CUBIC).start()
    }

    /** Fades out, then [done]. */
    fun exit(done: () -> Unit) {
        if (!animationsOn(context)) {
            done()
            return
        }
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(EXIT_MS).withEndAction(done).start()
    }

    companion object {
        const val TAG_CLOSE = "dak-in-app-close"
        const val TAG_BUTTON = "dak-in-app-button-"
        const val ENTER_MS = 220L
        const val EXIT_MS = 150L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val BACKDROP = 0x8A000000.toInt()

        /** Flutter's Curves.easeOutCubic. */
        val EASE_OUT_CUBIC = PathInterpolator(0.215f, 0.61f, 0.355f, 1f)

        /** Off when the user turned animations off (Settings, accessibility). */
        fun animationsOn(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 26) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }
    }
}

/** The window's content; remembers its height for the sheets' maximum. */
@SuppressLint("ViewConstructor") // made in code only, never inflated
internal class InAppRoot(context: Context) : FrameLayout(context) {
    var fullHeight = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fullHeight = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** As wide as it may be up to [maxWidth], at most [maxHeightFraction] of the window high. */
@SuppressLint("ViewConstructor") // made in code only, never inflated
private class Sheet(context: Context, private val maxWidth: Int, private val maxHeightFraction: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = min(MeasureSpec.getSize(widthMeasureSpec), maxWidth)
        var height = MeasureSpec.getSize(heightMeasureSpec)
        val full = (parent as? InAppRoot)?.fullHeight ?: 0
        if (maxHeightFraction < 1f && full > 0) height = min(height, (full * maxHeightFraction).toInt())
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            if (hMode == MeasureSpec.UNSPECIFIED) heightMeasureSpec else MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
    }
}

/** The full width, as high as the picture's shape asks, up to [maxHeight]; cropped to fill. */
@SuppressLint("ViewConstructor") // made in code only, never inflated
private class AspectImage(context: Context, private val bitmap: Bitmap, private val maxHeight: Int) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
        setImageBitmap(bitmap)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val natural = if (bitmap.width > 0) (width.toLong() * bitmap.height / bitmap.width).toInt() else 0
        setMeasuredDimension(width, min(natural, maxHeight))
    }
}

/** A text view that TalkBack calls a button. */
@SuppressLint("ViewConstructor") // made in code only, never inflated
private class PillButton(context: Context) : TextView(context) {
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

/** An image view that TalkBack calls a button. */
@SuppressLint("ViewConstructor") // made in code only, never inflated
private class CloseButton(context: Context) : ImageView(context) {
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

/** A banner closed by swiping it off its edge (up for a top banner). */
@SuppressLint("ViewConstructor") // made in code only, never inflated
private class SwipeLayout(context: Context, private val up: Boolean, private val onSwiped: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downY = 0f
    private var tracker: VelocityTracker? = null
    private var gone = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(ev)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.rawY - downY
                if (abs(dy) > slop && (if (up) dy < 0 else dy > 0)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downY = ev.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility") // TalkBack closes it with the accessibility action.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (gone) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(ev)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.rawY - downY
                translationY = if (up) min(dy, 0f) else max(dy, 0f)
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val v = tracker?.yVelocity ?: 0f
                val flung = if (up) v < -minFling else v > minFling
                if (abs(translationY) > height * 0.4f || (flung && translationY != 0f)) {
                    gone = true
                    val to = if (up) -height * 1.5f else height * 1.5f
                    animate().translationY(to).alpha(0f).setDuration(150).withEndAction(onSwiped).start()
                } else {
                    animate().translationY(0f).setDuration(150).start()
                }
                stop()
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().translationY(0f).setDuration(150).start()
                stop()
            }
        }
        return true
    }

    private fun start(ev: MotionEvent) {
        downY = ev.rawY
        tracker?.recycle()
        tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun stop() {
        tracker?.recycle()
        tracker = null
    }
}
