package io.github.hridoyrocks.dak

/**
 * A tap on an in-app message or one of its buttons. See
 * [Dak.addInAppClickListener].
 */
public class DakInAppClick internal constructor(
    /** The message's id on the DakPush server. */
    public val messageId: String,
    /** The button's id, or null when the message itself was tapped. */
    public val buttonId: String?,
    /**
     * What the tap does: [DEEP_LINK], [URL], [TAGS], [DISMISS] or [CUSTOM].
     * A newer server may send others; treat them like [CUSTOM].
     */
    public val actionType: String,
    /** For [DEEP_LINK] and [URL]: the link. */
    public val url: String?,
    /** For [TAGS]: the tags the SDK set; an empty value removed the tag. */
    public val tags: Map<String, String>,
) {
    override fun toString(): String =
        "DakInAppClick($messageId, button: $buttonId, $actionType${if (url == null) "" else " $url"})"

    public companion object {
        /** A deep link for the app to route, with any scheme: the SDK does not open it. */
        public const val DEEP_LINK: String = "deep_link"

        /** A web page (http or https), which the SDK has opened in the browser. */
        public const val URL: String = "url"

        /** Tags, which the SDK has set on the device. */
        public const val TAGS: String = "tags"

        /** Only closes the message. */
        public const val DISMISS: String = "dismiss"

        /** Nothing but this callback, with the button's id. */
        public const val CUSTOM: String = "custom"
    }
}

/** Taps on in-app messages. See [Dak.addInAppClickListener]. */
public fun interface DakInAppClickListener {
    /** The user tapped an in-app message or one of its buttons; the message has closed. */
    public fun onClick(click: DakInAppClick)
}

/**
 * Put this on an activity that in-app messages must never cover, such as a
 * call or a payment screen:
 *
 * ```kotlin
 * class CallActivity : AppCompatActivity(), DakInAppBlocker
 * ```
 *
 * While it is on screen no message shows; one that was on screen comes back
 * when the user returns to the screen it was on, and what came due meanwhile
 * shows then. For a part of a screen (a Compose destination, a fragment), use
 * [Dak.blockInApp].
 */
public interface DakInAppBlocker
