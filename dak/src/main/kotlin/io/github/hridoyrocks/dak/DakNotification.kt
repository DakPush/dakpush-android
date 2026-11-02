package io.github.hridoyrocks.dak

import org.json.JSONArray

/** A notification button, as the message was sent. */
public class DakButton internal constructor(
    /** The button's id, as [DakNotification.actionId] reports a tap on it. */
    public val id: String,
    /** The button's label. */
    public val text: String,
    /** The deep link for this button, if it has its own. */
    public val url: String?,
) {
    override fun toString(): String = "DakButton($id)"
}

/** A push notification as the app sees it. */
public class DakNotification internal constructor(
    /** The message's id on the DakPush server, or null for pushes not sent through it. */
    public val id: String?,
    /** The title, or null for a silent push. */
    public val title: String?,
    /** The text, or null for a silent push. */
    public val body: String?,
    /**
     * The deep link: the tapped button's own URL if it has one, otherwise the
     * message's `url`.
     */
    public val url: String?,
    /** The notification's image (`image` when it was sent), if any. */
    public val imageUrl: String?,
    /** The message's data, without the SDK's own `_dak_` keys. */
    public val data: Map<String, String>,
    /** The message's buttons. */
    public val buttons: List<DakButton>,
    /**
     * The id of the button the user tapped, or null for a tap on the
     * notification itself (and for messages that were not opened).
     */
    public val actionId: String?,
    /** The FCM data as it came, `_dak_` keys included. */
    public val raw: Map<String, String>,
) {
    /** A silent (data only) push has neither title nor body. */
    public val isSilent: Boolean get() = title == null && body == null

    /** The same notification, opened with [actionId]. */
    internal fun withAction(actionId: String?): DakNotification =
        fromData(raw, title, body, imageUrl, actionId)

    override fun toString(): String = "DakNotification(id: $id, title: $title, url: $url, action: $actionId, data: $data)"

    internal companion object {
        const val ID_KEY = "_dak_id"
        const val URL_KEY = "_dak_url"
        const val BUTTONS_KEY = "_dak_buttons"
        const val SHOW_KEY = "_dak_show"
        const val TITLE_KEY = "_dak_title"
        const val BODY_KEY = "_dak_body"
        const val IMAGE_KEY = "_dak_image"
        const val CHANNEL_KEY = "_dak_channel"
        const val SOUND_KEY = "_dak_sound"
        const val TAG_KEY = "_dak_tag"
        const val BADGE_INCREMENT_KEY = "_dak_badge_inc"

        private const val DAK_PREFIX = "_dak_"

        /**
         * Builds a notification from an FCM data map and the notification
         * fields. Messages the SDK shows itself (those with buttons) carry
         * their title, body and image in the data.
         */
        fun fromData(
            raw: Map<String, String>,
            title: String? = null,
            body: String? = null,
            imageUrl: String? = null,
            actionId: String? = null,
        ): DakNotification {
            val all = LinkedHashMap(raw)
            val data = LinkedHashMap<String, String>()
            for ((k, v) in all) if (!k.startsWith(DAK_PREFIX)) data[k] = v
            val buttons = parseButtons(all[BUTTONS_KEY])
            var url = all[URL_KEY]
            if (actionId != null) buttons.firstOrNull { it.id == actionId && it.url != null }?.let { url = it.url }
            return DakNotification(
                id = all[ID_KEY]?.takeIf { it.isNotEmpty() },
                title = title ?: all[TITLE_KEY],
                body = body ?: all[BODY_KEY],
                url = url,
                imageUrl = imageUrl ?: all[IMAGE_KEY],
                data = data,
                buttons = buttons,
                actionId = actionId,
                raw = all,
            )
        }

        /**
         * The buttons from their JSON. Entries without an id are skipped; an
         * empty list when there are none or the JSON is bad.
         */
        fun parseButtons(json: String?): List<DakButton> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val list = JSONArray(json)
                (0 until list.length()).mapNotNull { i ->
                    val b = list.optJSONObject(i) ?: return@mapNotNull null
                    val id = b.optString("id")
                    if (id.isEmpty()) return@mapNotNull null
                    val url = if (b.isNull("url")) null else b.optString("url").takeIf { it.isNotEmpty() }
                    DakButton(id, b.optString("text"), url)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
