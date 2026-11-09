# Changelog

All notable changes to the DakPush Android SDK. Versions follow
[semantic versioning](https://semver.org); below 1.0 a breaking change raises
the minor version. **Breaking** marks changes that need code or settings
changes in apps.

## 0.2.0 - 2026-11-09

In-app messages, like the Flutter SDK's (dak_push 0.2 and later).

- Shows the dashboard's in-app messages over the app's activities with no
  setup: modal, card, full screen (always with a way out) and top or bottom
  banners, in the dashboard preview's sizes. Images, up to 3 buttons, the
  message's colours with the app's theme for the rest, dark mode,
  right-to-left, font scale, rotation, and TalkBack.
- Triggers: app open, session count, time in the session, time since install,
  and custom ones (`addTrigger`, `addTriggers`, `removeTrigger`,
  `removeTriggers`, `clearTriggers`). Frequency (once, every session, interval,
  at most n times), priority and dates, as the server sends them.
- The list is fetched per session with an ETag and kept for offline starts;
  images are loaded before a message shows and kept on disk.
- Impressions, clicks (with the button) and dismissals are sent in batches,
  kept on disk until the server has them; a message hidden by a pause, a
  rotation or another screen comes back without counting again.
- `addInAppClickListener` with `DakInAppClick` (deep links are the app's to
  route; web pages open in the browser; tag actions are set). `pauseInApp`,
  `blockInApp` (nested blocks for part of a screen), `isInAppPaused`, and
  `DakInAppBlocker` for activities that must stay clear.
- New `DakOptions`: `inAppMessages`, `sessionTimeoutMillis`, `inAppUrlSchemes`.
- The device is checked again when the server answers an in-app call with
  401, so a device the server forgot registers again.
- Rotating the screen no longer counts as leaving and coming back to the app.
- Reports its version as `android-0.2.0`.

## 0.1.0 - 2026-11-03

The first release: the push half of the Flutter SDK (dak_push 0.4), native.

- `Dak.init` returns at once; the saved state, the push token and the
  registration follow in the background. Changes are kept on disk and sent
  when the phone is online, also after a restart.
- `login(externalId, authHash)` with identity verification, `logout` (which
  also removes the device's tags), `setTags`/`deleteTags`, `setSubscribed`.
  A refusal rolls back only the refused field; network trouble never fails.
- Notification permission on Android 13+ without wiring the activity's result
  (`requestPermission`), `hasPermission`, `openNotificationSettings`.
- The device id now and when it comes (`addDeviceIdListener`), refusals from
  background syncs (`addErrorListener`).
- Shows DakPush data messages: title, body, image, up to 3 buttons, deep link,
  channel, collapse key, sound and badge count.
- Reports deliveries for every message in every app state, and opens with the
  tapped button. Only taps on notifications the app received count.
- Taps (`addOpenedListener`, kept until the first listener) and messages that
  arrive while the app is open (`addForegroundListener`, `showNotification`).
- Firebase Installation IDs when the app turns them on, registration tokens
  otherwise. `DakMessagingService` for apps with their own messaging service.
- Reports its version as `android-0.1.0`, so the server can tell it from the
  Flutter SDK.
