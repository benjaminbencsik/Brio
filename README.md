# Brio

A native, ad-free Android streaming app for phones, tablets, and Android TV / Google TV, including Onn boxes. One APK adapts to touch and D-pad navigation.

## Included in 0.1

- Movie and TV catalog with TMDB search, details, seasons, and episodes.
- Open movie samples available without configuring an account.
- Watchlist and per-episode playback history with resume positions.
- MP4, HLS, and DASH playback through Media3, subtitle / audio controls, and remote media keys.
- Direct HTTPS links, Android text sharing, and local files via the system picker.
- Optional Real-Debrid API-token connection, account verification, download history, completed torrent file selection, and host-link resolution.
- Optional JSON source provider, independent from TMDB metadata.
- Skip intro and credits using provider timing markers; Next episode in the same action area for shows, including season transitions. Manual 90-second skip when timing markers are absent.
- Android Keystore encryption for credentials, provider endpoint, watchlist, and playback history. Backups are disabled. Tokens never enter the repository.
- GitHub Actions builds and uploads a debug APK on pushes and pull requests.

## Build and install

Requirements: JDK 17, Android SDK platform 35 / build-tools 35.0.0. Android Studio can import this directory as a Gradle project. The checked-in Gradle wrapper downloads Gradle 8.11.1.

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

From your phone: open this repository's **Actions** tab, open a successful **Build Brio APK** run, download **Brio-debug-APK**, unzip it, and install the APK. GitHub may require you to sign in to download artifacts.

Install on Android TV using a file-transfer app and file manager, or your device's supported ADB / wireless-debugging method. Enable installation from the app doing the installation. Brio appears in both the mobile and TV launchers.

The initial APK is a debug build for testing. Publishing requires your own release signing key and a production release configuration. Do not replace the signing key between updates. Source changes don't update installed APKs automatically: install the newly built APK. Fresh GitHub runners generate their own debug signing key, so a CI APK may require uninstalling an earlier APK before installation (which clears its local settings). Configure a persistent signing key before distributing regular updates.

## First launch

1. Play a sample open movie to check video and remote controls.
2. Open **Settings**. Enter a TMDB **API Read Access Token** (the long Bearer token, not the short API key) for the movie / TV catalog.
3. Optionally enter a Real-Debrid token from https://real-debrid.com/apitoken and select **Verify account**. Save settings. Account premium restrictions are enforced by Real-Debrid.
4. Open **Debrid** to browse your existing account downloads and torrents, or use **Open link** with **Resolve with Real-Debrid** enabled.
5. For catalog-based playback, configure a source endpoint following `docs/SOURCE_PROVIDER.md`.

TMDB does not supply video files. Real-Debrid resolves host links and your account's files; it does not automatically match a TMDB movie to a playable file. No arbitrary third-party scraper or torrent-search index is bundled. Without a configured source provider, catalog entries can be saved and browsed, while actual playback uses samples, your links, local files, or your Real-Debrid library.

Intro/credits timestamps are content-specific. Brio does not guess them or promise automatic recognition. Next episode requires TMDB metadata and a configured source provider. Direct links and Real-Debrid library files have no episode mapping unless played through a catalog provider.

## Project layout

- `core`: Kotlin/JVM models, catalog API, provider contract, Real-Debrid client, and HTTP tests.
- `app`: Adaptive Compose interface, encrypted persistence, and lifecycle-managed Media3 player.
- `.github/workflows/android.yml`: APK, lint, and test automation.

No ads, analytics, cloud sync, background downloading, or DRM integrations are included in this first version. Playback stops when the app leaves the foreground. Each device stores its own settings and library.

This product uses the TMDB API but is not endorsed or certified by TMDB. Blender sample films retain their creators' licensing and credits. Real-Debrid and TMDB are external services and are not affiliated with Brio.
