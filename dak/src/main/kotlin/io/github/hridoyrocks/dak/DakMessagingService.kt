package io.github.hridoyrocks.dak

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Passes new push tokens and Firebase Installation IDs to the SDK. The SDK's
 * manifest declares it, so nothing is needed unless the app has its own
 * FirebaseMessagingService: then extend this class instead, and call super in
 * the methods you override.
 *
 * Messages do not go through here: the SDK's own receiver gets all of them, in
 * every app state, so [onMessageReceived] is yours.
 */
public open class DakMessagingService : FirebaseMessagingService() {
    /** A new registration token. Firebase deprecates tokens for installation IDs, but they stay the default. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        Dak.onNewToken(token)
    }

    /** The Firebase Installation ID pushes go to, when the app turned them on. */
    override fun onRegistered(installationId: String) {
        Dak.onRegistered(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {}
}
