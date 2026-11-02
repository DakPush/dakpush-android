package io.github.hridoyrocks.dak

/** Taps on notifications. See [Dak.addOpenedListener]. */
public fun interface DakOpenedListener {
    /** The user tapped [notification], or one of its buttons ([DakNotification.actionId]). */
    public fun onOpened(notification: DakNotification)
}

/** Messages that arrive while the app is open. See [Dak.addForegroundListener]. */
public fun interface DakForegroundListener {
    /** [notification] arrived while the app is open; nothing was shown. */
    public fun onForeground(notification: DakNotification)
}

/** The device id. See [Dak.addDeviceIdListener]. */
public fun interface DakDeviceIdListener {
    /** This device's id on the Dak server. */
    public fun onDeviceId(deviceId: String)
}

/** Changes the server refused. See [Dak.addErrorListener]. */
public fun interface DakErrorListener {
    /** The server refused a change; only that field was rolled back. */
    public fun onError(error: DakException)
}

/** The end of a call such as [Dak.login]. */
public fun interface DakCallback {
    /**
     * [error] is null when the change was saved and either sent or kept to send
     * later (no network), and the server's refusal otherwise.
     */
    public fun onComplete(error: DakException?)
}

/** A yes or no answer, such as [Dak.requestPermission]'s. */
public fun interface DakResultCallback {
    /** The answer. */
    public fun onResult(result: Boolean)
}

/** A listener that was added. */
public fun interface DakSubscription {
    /** Stops the listener. */
    public fun remove()
}
