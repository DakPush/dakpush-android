# Contributing

Thanks for helping. Bug reports, docs fixes and pull requests are all welcome.
For a larger change, open an issue first so we can agree on the approach
before you spend time on it.

Security problems are the exception: report them privately, as
[SECURITY.md](SECURITY.md) describes.

## Set up

You need JDK 17 or newer and the Android SDK (platform 36). Point Gradle at
the SDK with `sdk.dir` in `local.properties`, or `ANDROID_HOME`.

```bash
./gradlew :dak:testDebugUnitTest    # unit tests (Robolectric), no device needed
./gradlew :dak:lintDebug :example:lintDebug
./gradlew :example:installDebug     # the example app; see the README
```

## Tests

- **Unit tests** (`dak/src/test`) run on the JVM with Robolectric. Most run the
  real client and HTTP code against `FakeDakServer`, a MockWebServer that
  answers like the DakPush server's SDK endpoints (registration, device secrets, identity
  verification, tag checks, opens and receipts). `TestScheduler` runs the
  SDK's worker on the test thread with virtual time, for retries and backoff.
- **Against a real server** (`LocalServerTest`), skipped unless you point it at
  a running `dak serve`:

  ```bash
  DAK_IT_BASE_URL=http://127.0.0.1:8080 DAK_IT_APP_ID=… \
  DAK_IT_IDENTITY_SECRET=… DAK_IT_REST_KEY=dak_… \
    ./gradlew :dak:testDebugUnitTest --tests '*LocalServerTest*'
  ```

- **On a device or emulator** (`dak/src/androidTest`): notifications, the tap
  activity, in-app message windows over a real activity (background, rotation,
  another screen, banners keeping the focus), and Android's own HTTP stack.
  The server parts need the same four values, as instrumentation arguments
  (see `DeviceTest`):

  ```bash
  ./gradlew :dak:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.dakBaseUrl=http://10.0.2.2:8080 …
  ```

  `InAppScreenshots` draws every in-app layout and saves pictures on the
  device, for a person to look at in light and dark mode, at a large font
  size, in landscape and right-to-left (see its comment).

On emulators, never tap the system's permission dialogs in automated runs; set
permissions with `adb shell pm grant` (or `pm set-permission-flags … user-fixed`
to deny). The device tests' own APK targets the current Android, so the system
does not ask by itself.

## How the code is laid out

Public API, in `io.github.hridoyrocks.dak`: `Dak` (the entry point),
`DakOptions`, `DakNotification`, `DakException`, the listener interfaces and
`DakMessagingService`. Everything else is `internal`:

| File | What it does |
|---|---|
| `DakClient` | The state the app wants and the state the server last accepted, kept on disk; sends the difference. All of it runs on one worker thread (`DakScheduler`). |
| `DakApi` | The SDK endpoints over `HttpURLConnection`, each exchange within one deadline. |
| `Stores` | The files in the no-backup folder: state, pending receipts and taps, tap nonces. |
| `Receiver` | The FCM receiver: receipts, showing data messages, foreground messages. |
| `DakNotifier` | Builds the notifications the SDK shows. |
| `Taps` | The tap and permission activities, and telling real taps from made-up ones. |
| `Watcher` | Follows the app's activities (foreground, the activity on screen, new intents) and holds the listener lists. |
| `Platform` | Firebase tokens and installation IDs, and the facts the server targets by. |
| `InAppModel` | In-app messages as the server sends them; anything this version cannot show is skipped. |
| `InAppController` | The in-app logic: the cached list, sessions, triggers, frequency, what to show, and the events. Its own worker thread, network calls on another. |
| `InAppPresenter` | Shows the chosen message in a window of its own over the activity on screen, and takes it away when that activity pauses. |
| `InAppViews` | Draws each layout with plain views, the theme's colours, insets, motion and accessibility. |
| `Images` | Downloading and decoding pictures (notifications and in-app), and the in-app image cache. |

The client is a port of the Flutter SDK's `client.dart`, and the in-app
controller of its `in_app/controller.dart`; keep them in step when the
protocol changes. In-app messages sit beside the client, not in it: they use
`DakClient.credentials`, `DakApi.fetchInApp` and `sendInAppEvents`, and
`DakActivityWatcher` for the foreground and the activity on screen. The
layouts follow the dashboard's preview (`web/src/lib/components/dak/InAppPreview.svelte`
in the server repository) and the Flutter SDK's `views.dart`.

## Names

The product is DakPush; the Kotlin package and the Maven group are temporary
until its domain is chosen. All names live in `gradle.properties`; `scripts/rename.sh` changes them and moves the Kotlin
package along, and `scripts/rename.sh --check` (run in CI) fails when a source
file is outside the package there.

## Before you open a pull request

- `./gradlew :dak:testDebugUnitTest :dak:lintDebug` pass.
- New behaviour and fixed bugs have tests.
- Public API has KDoc (the library builds in explicit API mode).
- A line in CHANGELOG.md under `## Unreleased`; start it with **Breaking:** if
  apps must change code or settings to update.
- Notification and tap changes: say which devices and Android versions you
  tried them on.
- Keep each pull request to one change.
