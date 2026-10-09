# Enable Google accounts and cross-device sync

The 0.4.0 code supports Google sign-in, per-account watchlists, viewing progress,
watched episode markers, and series resets. Until the owner adds the Firebase
configuration, Settings shows setup is required and Brio works locally.

## Owner setup (once for Brio, not once per user)

1. Create a project at https://console.firebase.google.com/. Google Analytics is
   optional; Brio does not use it.
2. Add an Android app with package `com.bencsik.brio`.
3. Register the APK signing certificate SHA-1 and SHA-256. The Android workflow
   uploads `Brio-signing-fingerprints` with those public fingerprints. Use the
   certificate for the APK you actually distribute. A public release needs a
   stable release signing key, separate from the debug build.
4. Authentication > Sign-in method > enable Google and select a support email.
5. Create a Cloud Firestore **Standard edition** database. Use production mode;
   then publish the rules in `firebase/firestore.rules`. Do not use open test rules.
6. Download the updated Android `google-services.json` **after** enabling Google
   and registering the signing fingerprints. It should include an OAuth client
   with `client_type: 3` (the web client ID).
7. Add that file at `app/google-services.json` and rebuild. The Gradle plugin is
   enabled automatically when this file exists. These are client configuration
   values, not service-account credentials. Never upload an admin/service-account
   private key or a Google password.
8. Deploy Firestore rules and indexes via Firebase Console, or with the Firebase
   CLI: `firebase deploy --only firestore --project YOUR_PROJECT_ID` from this repo.

## User flow

Settings > Sign in with Google. Sign in with the same account on the second
device. Each account's library is isolated in `/users/{firebaseUid}/library`.
Only the signed-in UID can read/write its collection. Guest data is imported
on first connection on that device. Signing out restores the separate guest
library and leaves cloud data intact.

## Sync behavior and limits

- Progress saves locally during playback and uploads in coalesced cycles, at
  most once a minute. Leaving the player or tapping Sync now requests an immediate
  upload. Keep the app open briefly to finish a requested sync.
- Each watchlist item and episode is merged individually using its edit time.
  Removing an item retains a tombstone; restarting a series retains a reset marker,
  so older offline changes do not restore old progress.
- Changes made without a connection stay in an encrypted local pending queue.
  Upload transactions retry when the app is active. If the process is killed,
  they resume when Brio is opened again.
- Keep device clocks set automatically: conflict resolution uses edit timestamps.
- Only catalog title IDs, names, years, episode numbers, progress, and edit times
  go to Firestore. Stream URLs/headers, local files, direct links, provider keys,
  TMDB tokens, and Real-Debrid keys are excluded.
- A cloud history entry resolves a fresh stream on the destination device.
  That device must independently have a working provider and any required keys.
- Brio shows up to 50 entries in Continue watching and marks an episode watched
  at 90% completion. Cloud watched markers are retained beyond those 50 entries.
- Firestore reads/writes use the owner's Firebase quota. Review actual usage and
  pricing before enabling a paid billing plan or distributing publicly.

## Verification before enabling publicly

Unit tests cover per-item merges, backward progress, removals, reset markers, and
excluding stream credentials. CI also runs Android lint and APK compilation.
Live sign-in and Firestore access require the project configuration and have not
been tested before it is supplied. On two devices verify: sign-in, add/remove a
watchlist item, resume an episode, restart a series, offline/reconnect, sign-out,
and switch accounts. Verify an unauthenticated user and another UID cannot read
or write the first user's library (Firebase rules simulator or Emulator Suite).

References:
- https://firebase.google.com/docs/auth/android/google-signin
- https://firebase.google.com/docs/firestore/security/get-started
