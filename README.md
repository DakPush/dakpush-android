# DakPush Android SDK

Native Android SDK for DakPush, a self-hosted push notification server. It
registers the device with your DakPush server, links it to your user, keeps
tags, shows DakPush notifications (image, up to 3 buttons, deep link,
channel), and reports deliveries, opens and button taps.

Delivery goes through your own Firebase project (FCM), so pushes cost nothing.
Kotlin and Java. In-app messages come in 0.2.

> **Pre-release.** Installed from GitHub through JitPack for now. The Kotlin
> package and the Maven coordinates will change once the product's domain is
> chosen and the SDK moves to Maven Central.

## Requirements

| | Minimum |
|---|---|
| Android | minSdk 23 (Android 6.0), compileSdk 34 |
| Kotlin / Java | Kotlin 2.0, or Java 11 |
| Firebase Messaging | 25.1.3 (comes with the SDK; a newer one from your Firebase BoM wins) |
| Server | A DakPush server, and the Firebase project whose service account the app uses there |

## Install

From GitHub through [JitPack](https://jitpack.io/#DakPush/dakpush-android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.DakPush:dakpush-android:0.1.0")
}
```

To try a change of your own, build it into your local Maven repository with
`./gradlew :dak:publishToMavenLocal` and use
`io.github.hridoyrocks:dakpush-android:0.1.0` with `mavenLocal()`.

The app needs Firebase set up as usual: the `com.google.gms.google-services`
Gradle plugin and the app's `google-services.json` from the Firebase console.
Remove other push SDKs (OneSignal) so only one library handles notifications.

## Quick start

Start the SDK in `Application.onCreate`, so a tap that starts the app is seen:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Dak.init(this, appId = "APP_ID", baseUrl = "https://push.example.com")

        // Taps, the one that started the app included. n.url is the deep
        // link (the tapped button's own URL if it has one).
        Dak.addOpenedListener { n ->
            if (n.actionId == "accept") acceptRequest(n.data["request_id"])
            router.open(n.url)
        }
    }
}
```

Then, where it makes sense in your app:

```kotlin
Dak.requestPermission { granted -> /* Android 13+ asks; older Android just answers */ }

// After your own login. authHash comes from your server (see Identity verification).
Dak.login(user.id, authHash = user.pushAuthHash)
Dak.logout() // also removes this device's tags

Dak.setTags(mapOf("name" to "Rahim", "level" to "b2", "premium" to true))
Dak.deleteTag("level")
Dak.setSubscribed(false) // the user turned pushes off in your settings
```

The same from Java:

```java
Dak.init(this, "APP_ID", "https://push.example.com",
        new DakOptions.Builder().shareTimezoneOffset(true).build());
Dak.addOpenedListener(n -> router.open(n.getUrl()));
Dak.login(user.getId(), user.getPushAuthHash(), error -> {
    if (error != null) Log.w("push", "refused: " + error.getCode());
});
```

`Dak.init` returns at once: reading the saved state, getting the push token and
registering the device run in the background, so a bad network never holds
your app's start. Calls made meanwhile (`login`, `setTags`) wait for the saved
state and are then sent.

`login`, `setTags` and the others take an optional callback. It gets `null`
when the change was saved, and sent or kept to send later (no network: it goes
out on a later start), and a `DakException` only when the server refused it
for good, such as a wrong auth hash. Then only the refused field is rolled
back: an opt-out sent together with a wrong auth hash still goes through.
Refusals from syncs that run in the background come to `Dak.addErrorListener`.
Mistakes the app must fix before anything is sent (a bad tag key, an email
address as the user id) throw `IllegalArgumentException`.

`baseUrl` must be `https`; plain `http` works only for `localhost` and the
emulator's `10.0.2.2`. Lengths are counted in UTF-8 bytes, as the server does: a
tag value is at most 255 bytes (85 Bangla letters), a user id 128.

## The device id

`Dak.deviceId` is null until the device registers, which happens after
`Dak.init` returns on a first start. A listener gets the id at once if it is
known, and when it comes (and a new one if the device ever registers again):

```kotlin
Dak.addDeviceIdListener { id -> api.saveDakDevice(id) } // e.g. for support tools
```

As a Kotlin Flow, if your app uses coroutines:

```kotlin
val deviceIds: Flow<String> = callbackFlow {
    val subscription = Dak.addDeviceIdListener { trySend(it) }
    awaitClose { subscription.remove() }
}
```

All listeners run on the main thread, and `remove()` on what `add…Listener`
returns stops them.

## Taps and deep links

The SDK reports opens and button taps itself; your listener only routes.

- **Notifications the SDK shows** (messages with buttons, see below) open a
  small activity of the SDK's, which records the tap and opens your app like
  its launcher icon: the app's task comes back as it was. With
  `DakOptions(openAppOnTap = false)` nothing opens and your opened listener
  starts the screen itself; add it in `Application.onCreate` then.
- **Notifications Firebase shows** (messages without buttons) open your
  launcher activity with the message's data. The SDK reads the tap from it.
  For AndroidX activities (`ComponentActivity`, `AppCompatActivity`) and for
  activities that call `setIntent` in `onNewIntent` this needs nothing; another
  activity with `launchMode` `singleTop` or `singleTask` passes new intents on:

  ```kotlin
  override fun onNewIntent(intent: Intent) {
      super.onNewIntent(intent)
      Dak.handleIntent(intent)
  }
  ```

Taps that come before your first opened listener exists are kept for it.

Only taps on notifications this app actually received count: the SDK's tap
activity is not exported and checks a one-time code, and a launcher intent
counts only with a Firebase message id the SDK saw arrive, once. Another app
cannot fake an open, and the system replaying an old intent is not a second
tap.

## While the app is open

Like FCM itself, the SDK shows nothing while the app is in the foreground.
Messages arrive at the foreground listener, silent (data only) ones too:

```kotlin
Dak.addForegroundListener { n ->
    if (n.isSilent) sync(n.data) else Dak.showNotification(n) // as it would have looked in the background
}
```

## Buttons

A message can have up to 3 buttons (`buttons` in the API); `n.actionId` is the
tapped button's id, and the server counts taps per button. FCM's own Android
notifications cannot have buttons, so DakPush sends such messages as data and this
SDK shows them, with title, body, image, buttons, channel and collapse key.

The server decides per device which messages to send that way, by the SDK
version the device reports (`android-0.1.0` for this one). A DakPush server that
does not know this SDK yet sends Android button messages as plain
notifications, without the buttons.

## Delivered receipts

Nothing to set up: the SDK has its own receiver for FCM messages, which sees
every message in every app state (next to Firebase's), so pushes are counted
as delivered when they arrive, also while the app is not running. Receipts and
taps that cannot be sent (no network) are kept on disk and sent later.

## Identity verification

So nobody can claim another user's id and receive their pushes, the server
checks `authHash = hex(HMAC-SHA256(key: identity secret, message: user id))`.
Get the secret from the dashboard (or `dak app identity-secret <app id>` on
the server) and compute the hash on your server, for example in Laravel:

```php
'push_auth_hash' => hash_hmac('sha256', (string) $user->id, config('services.dak.identity_secret')),
```

The identity secret must never be in the app.

## Notification channels, icon and colour

A message's `android_channel` must be a channel your app created; otherwise
the SDK uses your default channel, then Firebase's fallback channel, then a
"Notifications" channel of its own. Create channels at startup and name the
default one, and the icon and colour, as Firebase expects:

```xml
<meta-data
    android:name="com.google.firebase.messaging.default_notification_channel_id"
    android:value="general" />
<meta-data
    android:name="com.google.firebase.messaging.default_notification_icon"
    android:resource="@drawable/ic_notification" />
<meta-data
    android:name="com.google.firebase.messaging.default_notification_color"
    android:resource="@color/brand" />
```

## Your own FirebaseMessagingService

The SDK's manifest declares a `FirebaseMessagingService` for new push tokens.
Android uses one such service per app, so if your app has its own, extend
`DakMessagingService` instead, and call `super` where you override:

```kotlin
class MyMessagingService : DakMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        myBackend.saveToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // DakPush messages (those with "_dak_id" in the data) are the SDK's:
        // its own receiver reports and shows them. Handle the rest.
        if (message.data.containsKey("_dak_id")) return
        handleMyMessage(message)
    }
}
```

Or, from a service that cannot extend it, pass tokens on with
`Dak.onNewToken(token)` and `Dak.onRegistered(installationId)`. A missed token
refresh is picked up at the next app start anyway.

## Firebase Installation IDs

Firebase Messaging 25.1 deprecates registration tokens in favour of Firebase
Installation IDs, which stay off unless the app turns them on. With
`<meta-data android:name="firebase_messaging_installation_id_enabled" android:value="true" />`
in your manifest the SDK registers and sends the installation ID
(`token_type: "fid"`); without it, or with `DakOptions(useFid = false)`, the
registration token as before. The DakPush server sends to both.

## Options

```kotlin
Dak.init(this, appId, baseUrl, DakOptions(
    shareTimezoneOffset = true, // send the UTC offset, for local-time sends and quiet hours
    useFid = true,              // installation IDs when the manifest turns them on
    openAppOnTap = true,        // a tap on an SDK notification opens the app
    debugLogs = false,          // log what the SDK does (tag "Dak")
))
```

`shareTimezoneOffset` sends only the offset in minutes (+360 in Bangladesh),
and again when it changes: no timezone name, city or location. Without it the
app's timezone (set on the server) is used.

## What is stored and sent

The SDK sends: the push token or installation ID, platform, app/OS/SDK version,
language, whether notifications are allowed, the user id and tags you set,
which pushes arrived and were opened (with the button), and the UTC offset only
if you turn it on. Nothing else: no IP, location, phone number, email or device
model.

Its state (device id and secret, what the server last accepted, changes not yet
sent) is a small file in the app's no-backup folder: private to the app, left
out of Android's backups (a restored copy would make two phones one device),
and deleted with the app. The device secret is sent only to your `baseUrl`.

## How it works

The SDK keeps two copies of the device: what the app wants (token, user, tags,
subscription, permission) and what the server last accepted. After every
change, and on start and resume, it sends the difference: the whole state to
register, then only changed fields. One sync runs at a time, changes made
together go in one request, and failed syncs are retried with backoff and
jitter (and never sooner than the server's `Retry-After`). When the server no
longer knows the device, it registers again with the whole state.

## Troubleshooting

Turn on `DakOptions(debugLogs = true)` and filter Logcat by the tag `Dak`.

**The device never shows up on the server.**
- `Firebase is not set up` in the log: add the app's `google-services.json` and
  the `com.google.gms.google-services` plugin.
- Check `appId` and `baseUrl` (the server's public URL, without `/v1`).
- The app on the DakPush server needs the service account of the same
  Firebase project as the app's `google-services.json`.

**Nothing appears while the app is open.** Expected: see
[While the app is open](#while-the-app-is-open).

**Taps on notifications are not reported.** `Dak.init` must run in
`Application.onCreate`. A non-AndroidX activity with `singleTop` or
`singleTask` passes new intents to `Dak.handleIntent`.

**`onRegistered never came` in the log.** Installation IDs are on, but another
`FirebaseMessagingService` gets Firebase's callbacks: extend
`DakMessagingService` (see above).

**Buttons do not show.** See [Buttons](#buttons): the server sends them only
to SDK versions it knows can show them.

## Example app

[`example/`](example) is a small app with every call. Put your server in
`local.properties` (git-ignored):

```properties
dak.exampleAppId=YOUR_APP_ID
dak.exampleBaseUrl=http://10.0.2.2:8080
# A package your Firebase project has an Android app for:
dak.exampleApplicationId=com.example.daktest
```

and that Firebase app's `google-services.json` in `example/` (also
git-ignored), then `./gradlew :example:installDebug`. `10.0.2.2` is the
computer running the emulator; the example allows plain `http` only for it.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for the tests, the layout of the code
and the temporary names.

## Security

Report security problems privately, as [SECURITY.md](SECURITY.md) describes,
not in a public issue.

## License

[MIT](LICENSE)
