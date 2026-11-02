# Security policy

## Supported versions

The SDK is below 1.0, so only the newest release gets security fixes. Fixes go
into a new patch release; update to it.

| Version | Supported |
|---|---|
| Newest 0.x release | Yes |
| Older releases | No |

## Reporting a vulnerability

Please do not open a public issue, discussion or pull request for a security
problem.

Report it privately on GitHub: open the repository's **Security** tab and
choose **Report a vulnerability**. Only the maintainers can see the report.

Include what you can of:

- the affected version or commit, the Android version and the Firebase
  Messaging version;
- what an attacker can do, and what they need first (another app on the
  phone, a network position, a stolen device, access to the DakPush server);
- steps or a small app that shows the problem.

## What happens next

- We aim to confirm we received the report within 3 working days and to give a
  first assessment within 7.
- We fix confirmed problems in a new release and publish a GitHub security
  advisory with it. Unless you prefer otherwise, the advisory credits you.
- Please give us 90 days, or until the fix is released if that is sooner,
  before you disclose the problem publicly. If a problem is already being
  exploited, we will move faster and tell you.

## Scope

In scope: this repository, the library and the example app.

Report problems in the DakPush server the same way; we pass them on. Problems in
Firebase or other dependencies belong to their own projects, but tell us if the
SDK uses them in an unsafe way.

## How the SDK handles secrets and taps

Useful context for a report:

- The device secret the server issues is kept in a file in the app's
  no-backup folder (private to the app, left out of backups, deleted with the
  app). It is sent only to the `baseUrl` given to `Dak.init`, which must be
  `https` (plain `http` only for `localhost` and the emulator's `10.0.2.2`).
- The identity verification hash (`authHash`) is an HMAC your server computes.
  The identity secret must never be in the app.
- The FCM receiver is exported only to senders holding
  `com.google.android.c2dm.permission.SEND` (Google Play services).
- Taps: the SDK's tap activity is not exported and accepts a one-time nonce
  per notification; taps on Firebase's notifications count only with a
  Firebase message id the receiver saw, once.
