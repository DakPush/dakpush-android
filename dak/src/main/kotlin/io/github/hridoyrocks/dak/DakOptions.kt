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
) {
    /** The options for Java. */
    public class Builder {
        private var shareTimezoneOffset = false
        private var useFid = true
        private var openAppOnTap = true
        private var debugLogs = false

        /** See [DakOptions.shareTimezoneOffset]. */
        public fun shareTimezoneOffset(value: Boolean): Builder = apply { shareTimezoneOffset = value }

        /** See [DakOptions.useFid]. */
        public fun useFid(value: Boolean): Builder = apply { useFid = value }

        /** See [DakOptions.openAppOnTap]. */
        public fun openAppOnTap(value: Boolean): Builder = apply { openAppOnTap = value }

        /** See [DakOptions.debugLogs]. */
        public fun debugLogs(value: Boolean): Builder = apply { debugLogs = value }

        /** The options. */
        public fun build(): DakOptions = DakOptions(shareTimezoneOffset, useFid, openAppOnTap, debugLogs)
    }
}
