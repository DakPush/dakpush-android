package io.github.hridoyrocks.dak.internal

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How an in-app message is laid out on screen. */
internal enum class InAppLayout(val wire: String) {
    /** A centred dialog over a dimmed app. */
    MODAL("modal"),

    /** A banner at the top; the app stays usable. */
    BANNER_TOP("banner_top"),

    /** A banner at the bottom; the app stays usable. */
    BANNER_BOTTOM("banner_bottom"),

    /** A page that covers the app. */
    FULLSCREEN("fullscreen"),

    /** A card that slides up from the bottom. */
    CARD("card"),
    ;

    val isBanner: Boolean get() = this == BANNER_TOP || this == BANNER_BOTTOM

    companion object {
        fun parse(v: Any?): InAppLayout? = entries.firstOrNull { it.wire == v }
    }
}

/** What a click does. Every click also closes the message and reaches the click listeners. */
internal class InAppAction(
    /** deep_link | url | tags | dismiss | custom */
    val type: String,
    /** For deep_link and url. */
    val url: String? = null,
    /** For tags: tags to set; an empty value removes the tag. */
    val tags: Map<String, String> = emptyMap(),
) {
    companion object {
        val TYPES = setOf("deep_link", "url", "tags", "dismiss", "custom")

        /** Null for an unknown type or a field of the wrong type. */
        fun parse(js: Any?): InAppAction? {
            if (js !is JSONObject) return null
            val type = js.opt("type") as? String ?: return null
            if (type !in TYPES) return null
            val tags = LinkedHashMap<String, String>()
            when (val t = js.opt("tags")) {
                is JSONObject -> for (k in t.keys()) {
                    val v = t.opt(k)
                    tags[k] = if (v == null || v == JSONObject.NULL) "" else v.toString()
                }
                null, JSONObject.NULL -> {}
                else -> return null
            }
            return InAppAction(type, js.str("url"), tags)
        }
    }
}

/** A button on an in-app message. */
internal class InAppButton(
    /** The click id: reported to the click listeners and counted in the stats. */
    val id: String,
    val text: String,
    val action: InAppAction,
    /** ARGB; null takes the theme's. */
    val backgroundColor: Int? = null,
    /** ARGB; null picks one that reads on the background. */
    val textColor: Int? = null,
)

/** What an in-app message shows. */
internal class InAppContent(
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val backgroundColor: Int? = null,
    val titleColor: Int? = null,
    val bodyColor: Int? = null,
    /** Whether the message has an X. A full-screen message with no other way out gets one anyway. */
    val closeButton: Boolean = true,
    /** Banners only: closes by itself after this long. */
    val autoDismissMs: Long? = null,
    /** What tapping the message itself does. */
    val clickAction: InAppAction? = null,
    /** At most three. */
    val buttons: List<InAppButton> = emptyList(),
) {
    val hasTitle: Boolean get() = !title.isNullOrBlank()
    val hasBody: Boolean get() = !body.isNullOrBlank()
    val hasImage: Boolean get() = !imageUrl.isNullOrEmpty()

    /** There is a title or a text to show without the image. */
    val hasText: Boolean get() = hasTitle || hasBody

    /** The same content without the image, for when it failed to load. */
    fun withoutImage(): InAppContent = InAppContent(
        title, body, null, backgroundColor, titleColor, bodyColor, closeButton, autoDismissMs, clickAction, buttons,
    )
}

/** One condition of a trigger; a message's triggers are OR-ed groups of AND-ed conditions. */
internal class InAppCondition(
    /** session_count | session_time | since_install | custom */
    val kind: String,
    /** =, !=, >, >=, <, <=, and for custom also in, exists, not_exists. */
    val op: String,
    /** For custom: the trigger key. */
    val key: String? = null,
    /** A count, seconds, or the trigger value (String, Number or Boolean). */
    val value: Any? = null,
    /** For `in`. */
    val values: List<String> = emptyList(),
) {
    companion object {
        private val OPS = mapOf(
            "session_count" to setOf("=", "!=", ">", ">=", "<", "<="),
            "session_time" to setOf(">=", "<"),
            "since_install" to setOf(">=", "<"),
            "custom" to setOf("=", "!=", ">", ">=", "<", "<=", "in", "exists", "not_exists"),
        )

        /** Null for a kind or comparison this SDK does not know. */
        fun parse(js: Any?): InAppCondition? {
            if (js !is JSONObject) return null
            val kind = js.opt("kind") as? String ?: return null
            val op = js.opt("op") as? String ?: return null
            if (OPS[kind]?.contains(op) != true) return null
            val raw = js.opt("value").takeUnless { it == JSONObject.NULL }
            if (raw != null && raw !is String && raw !is Number && raw !is Boolean) return null
            val values = when (val v = js.opt("values")) {
                is JSONArray -> (0 until v.length()).map { v.opt(it).toString() }
                null, JSONObject.NULL -> emptyList()
                else -> return null
            }
            val c = InAppCondition(kind, op, js.str("key"), raw, values)
            if (kind == "custom" && c.key.isNullOrEmpty()) return null
            if (kind != "custom" && raw !is Number) return null
            return c
        }
    }
}

/** How often one device may see a message. */
internal class InAppFrequency(
    /** once | every_session | interval */
    val type: String,
    val intervalMs: Long = 0,
    /** At most this many showings in all; null for no limit. */
    val maxDisplays: Int? = null,
) {
    companion object {
        fun parse(js: Any?): InAppFrequency? {
            if (js !is JSONObject) return null
            val type = js.opt("type") as? String ?: return null
            if (type != "once" && type != "every_session" && type != "interval") return null
            return InAppFrequency(
                type,
                (js.num("interval_seconds")?.toLong() ?: 0L) * 1000,
                js.num("max_displays")?.toInt(),
            )
        }
    }
}

/** An in-app message as the server sent it. */
internal class InAppMessage(
    val id: String,
    val layout: InAppLayout,
    val content: InAppContent,
    /** OR-ed groups of AND-ed conditions; empty means "when the app opens". */
    val triggers: List<List<InAppCondition>> = emptyList(),
    val frequency: InAppFrequency = InAppFrequency("once"),
    /** When several are due, the highest shows first. */
    val priority: Int = 0,
    /** Epoch ms: not shown before. */
    val startAt: Long? = null,
    /** Epoch ms: not shown from then on. */
    val endAt: Long? = null,
    /** Impressions the server counted for this device. */
    val shown: Int = 0,
    /** Epoch ms of the last impression the server counted for this device. */
    val lastShownAt: Long? = null,
) {
    fun withContent(content: InAppContent) =
        InAppMessage(id, layout, content, triggers, frequency, priority, startAt, endAt, shown, lastShownAt)

    override fun toString(): String = "InAppMessage($id, ${layout.wire})"

    companion object {
        /**
         * Reads a message from the server's JSON. Null for anything this SDK
         * version does not understand (a newer layout, action or trigger) or
         * cannot read (a field of the wrong type), so it is skipped rather
         * than shown wrongly. Never throws.
         */
        fun parse(js: Any?): InAppMessage? = try {
            parseOrNull(js)
        } catch (e: Exception) {
            null
        }

        private fun parseOrNull(js: Any?): InAppMessage? {
            if (js !is JSONObject) return null
            val id = js.opt("id") as? String ?: return null
            val layout = InAppLayout.parse(js.opt("layout")) ?: return null
            val c = js.opt("content") as? JSONObject ?: return null
            val frequency = InAppFrequency.parse(js.opt("frequency")) ?: return null

            var click: InAppAction? = null
            if (!c.isNull("click_action")) {
                click = InAppAction.parse(c.opt("click_action")) ?: return null
            }
            val buttons = ArrayList<InAppButton>()
            when (val list = c.opt("buttons")) {
                is JSONArray -> for (i in 0 until list.length()) {
                    val b = list.opt(i) as? JSONObject ?: return null
                    val action = InAppAction.parse(b.opt("action")) ?: return null
                    val bid = b.opt("id") as? String ?: return null
                    val text = b.opt("text") as? String ?: return null
                    buttons.add(InAppButton(bid, text, action, parseColor(b.opt("background_color")), parseColor(b.opt("text_color"))))
                }
                null, JSONObject.NULL -> {}
                else -> return null
            }
            val auto = c.num("auto_dismiss_seconds")?.toLong() ?: 0L
            val close = when (val v = c.opt("close_button")) {
                is Boolean -> v
                null, JSONObject.NULL -> true
                else -> return null
            }
            val content = InAppContent(
                title = c.str("title"),
                body = c.str("body"),
                imageUrl = c.str("image_url"),
                backgroundColor = parseColor(c.opt("background_color")),
                titleColor = parseColor(c.opt("title_color")),
                bodyColor = parseColor(c.opt("body_color")),
                closeButton = close,
                autoDismissMs = if (auto > 0) auto * 1000 else null,
                clickAction = click,
                buttons = buttons,
            )

            val triggers = ArrayList<List<InAppCondition>>()
            when (val groups = js.opt("triggers")) {
                is JSONArray -> for (i in 0 until groups.length()) {
                    val g = groups.opt(i) as? JSONArray ?: return null
                    if (g.length() == 0) return null
                    val group = ArrayList<InAppCondition>()
                    for (j in 0 until g.length()) group.add(InAppCondition.parse(g.opt(j)) ?: return null)
                    triggers.add(group)
                }
                null, JSONObject.NULL -> {}
                else -> return null
            }

            return InAppMessage(
                id = id,
                layout = layout,
                content = content,
                triggers = triggers,
                frequency = frequency,
                priority = js.num("priority")?.toInt() ?: 0,
                startAt = parseTime(js.opt("start_at")),
                endAt = parseTime(js.opt("end_at")),
                shown = js.num("shown")?.toInt() ?: 0,
                lastShownAt = parseTime(js.opt("last_shown_at")),
            )
        }
    }
}

/** `#RRGGBB` or `#AARRGGBB` (alpha first) as ARGB; null when absent or malformed. */
internal fun parseColor(v: Any?): Int? {
    if (v !is String || !v.startsWith("#")) return null
    val hex = v.substring(1)
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val n = hex.toLong(16)
    return (if (hex.length == 6) 0xFF000000L or n else n).toInt()
}

private val rfc3339 = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?([Zz]|[+-]\\d{2}:?\\d{2})?$",
)

/** An RFC 3339 time (as the server writes them) in epoch ms; null when absent or unreadable. */
internal fun parseTime(v: Any?): Long? {
    if (v !is String) return null
    val m = rfc3339.matchEntire(v.trim()) ?: return null
    val g = m.groupValues
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
    cal.clear()
    cal.isLenient = false
    return try {
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        val ms = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toInt() ?: 0
        var at = cal.timeInMillis + ms
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            at -= sign * minutes * 60_000L
        }
        at
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Epoch ms as UTC RFC 3339 with milliseconds, like 2026-11-09T10:15:00.123Z. */
internal fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

/** A string field; null when absent or null; throws for another type, so the message is skipped. */
private fun JSONObject.str(key: String): String? = when (val v = opt(key)) {
    null, JSONObject.NULL -> null
    is String -> v
    else -> throw IllegalArgumentException("$key is not a string")
}

/** A number field; null when absent or null; throws for another type. */
private fun JSONObject.num(key: String): Number? = when (val v = opt(key)) {
    null, JSONObject.NULL -> null
    is Number -> v
    else -> throw IllegalArgumentException("$key is not a number")
}
