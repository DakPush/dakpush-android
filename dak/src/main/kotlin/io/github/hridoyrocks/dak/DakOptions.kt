package io.github.hridoyrocks.dak

/**
 * Settings for [Dak.init]. All of them have defaults.
 *
 * ```kotlin
 * DakOptions(shareTimezoneOffset = true)
 * ```
 * ```java
 * new DakOptions.Builder().shareTimezoneOffset(true).build();
 * ```
 */
public class DakOptions(
    /**
     * Send the phone's UTC offset in minutes (only the offset, like +360), so
     * pushes can go at the user's local time and respect quiet hours. Off by
     * default; then the app's timezone is used.
     */
    public val shareTimezoneOffset: Boolean = false,
    /**
     * Use a Firebase Installation ID when the app turned them on
     * (`firebase_messaging_installation_id_enabled` in its manifest);
     * a registration token otherwise.
     */
    public val useFid: Boolean = true,
    /**
     * A tap on a notification the SDK shows opens the app like its launcher
     * icon. With false only [Dak.addOpenedListener] gets the tap, and the
     * listener opens the screen; add it in `Application.onCreate` then.
     */
    public val openAppOnTap: Boolean = true,
    /** Log what the SDK does (tag "Dak"). */
    public val debugLogs: Boolean = false,
    /**
     * Show the in-app messages made in the DakPush dashboard over the app's
     * activities. With false the SDK neither fetches nor shows them.
     */
    public val inAppMessages: Boolean = true,
    /**
     * In-app messages: coming back after this long in the background starts
     * a new session (for session counts and "every session" messages).
     */
    public val sessionTimeoutMillis: Long = 5 * 60 * 1000L,
    /**
     * In-app messages: the URL schemes a "web page" action may open. Others
     * (like intent: or file:) are not opened; the click still reaches
     * [Dak.addInAppClickListener].
     */
    public val inAppUrlSchemes: Set<String> = setOf("https", "http"),
) {
    init {
        require(sessionTimeoutMillis >= 0) { "sessionTimeoutMillis must not be negative" }
    }

    /** The options for Java. */
    public class Builder {
        private var shareTimezoneOffset = false
        private var useFid = true
        private var openAppOnTap = true
        private var debugLogs = false
        private var inAppMessages = true
        private var sessionTimeoutMillis = 5 * 60 * 1000L
        private var inAppUrlSchemes: Set<String> = setOf("https", "http")

        /** See [DakOptions.shareTimezoneOffset]. */
        public fun shareTimezoneOffset(value: Boolean): Builder = apply { shareTimezoneOffset = value }

        /** See [DakOptions.useFid]. */
        public fun useFid(value: Boolean): Builder = apply { useFid = value }

        /** See [DakOptions.openAppOnTap]. */
        public fun openAppOnTap(value: Boolean): Builder = apply { openAppOnTap = value }

        /** See [DakOptions.debugLogs]. */
        public fun debugLogs(value: Boolean): Builder = apply { debugLogs = value }

        /** See [DakOptions.inAppMessages]. */
        public fun inAppMessages(value: Boolean): Builder = apply { inAppMessages = value }

        /** See [DakOptions.sessionTimeoutMillis]. */
        public fun sessionTimeoutMillis(value: Long): Builder = apply { sessionTimeoutMillis = value }

        /** See [DakOptions.inAppUrlSchemes]. */
        public fun inAppUrlSchemes(value: Set<String>): Builder = apply { inAppUrlSchemes = value.toSet() }

        /** The options. */
        public fun build(): DakOptions = DakOptions(
            shareTimezoneOffset, useFid, openAppOnTap, debugLogs, inAppMessages, sessionTimeoutMillis, inAppUrlSchemes,
        )
    }
}
