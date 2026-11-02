# Changelog

All notable changes to the Dak Android SDK. Versions follow
[semantic versioning](https://semver.org); below 1.0 a breaking change raises
the minor version. **Breaking** marks changes that need code or settings
changes in apps.

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
- Shows Dak's data messages: title, body, image, up to 3 buttons, deep link,
  channel, collapse key, sound and badge count.
- Reports deliveries for every message in every app state, and opens with the
  tapped button. Only taps on notifications the app received count.
- Taps (`addOpenedListener`, kept until the first listener) and messages that
  arrive while the app is open (`addForegroundListener`, `showNotification`).
- Firebase Installation IDs when the app turns them on, registration tokens
  otherwise. `DakMessagingService` for apps with their own messaging service.
- Reports its version as `android-0.1.0`, so the server can tell it from the
  Flutter SDK.
