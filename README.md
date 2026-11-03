# Brio

Brio is an ad-free Android movie and TV browser and player for phones, tablets,
and Android TV / Google TV devices such as onn. The same APK supports touch and
remote navigation.

## What it does

- Browse and search movies and shows with the public Cinemeta catalog, or add
  your own TMDB read token. Browse genres, seasons, and episodes.
- Play direct MP4, HLS, and DASH links, local files, and supported sources through
  Media3. A catalog entry has video only when a source returns a playable link.
- Check the built-in source and up to six additional source URLs together. Add
  Stremio-compatible manifests or Brio JSON endpoints in Settings. [Source
  setup and API](docs/SOURCE_PROVIDER.md).
- Keep a watchlist and resume progress; skip an intro or credits when accurate
  markers exist, and move to the next episode.
- Browse files in your own Real-Debrid account if you configure its token.
- Transfer a watchlist and progress between Brio devices on the same Wi-Fi with
  a temporary code. [Device sync](docs/DEVICE_SYNC.md) is manual, not an account
  or an automatic cloud backup.

## Get the Android app

1. Open [Releases](https://github.com/benjaminbencsik/Brio/releases/latest)
   while signed in to the GitHub account with access to this private repository.
2. Download `app-debug.apk` and install it. For an onn. or other Android TV
   device, transfer the APK once with a file-transfer app or ADB and open it.
3. For later versions, use **Settings → About → App updates** inside Brio.
   Create a fine-grained GitHub token restricted to this repository with only
   **Contents: read-only**, then enter it on the device once. Tap **Check for
   updates** and **Download and install** when a newer version is available.
   Android shows its normal install confirmation and may first ask you to allow
   installs from Brio. The token is encrypted with Android Keystore on that
   device; remove it any time in the same settings panel.

Enable installation from the app opening the APK when Android asks. The CI
workflow caches one debug signing key to make updates install over builds made
with the same cached key. If Android reports a signature conflict, uninstalling
an older debug build will remove its local data. The GitHub release contains a
debug APK; there is no Play Store distribution yet.

## Web preview

Brio Web is a separate browser version with the same browsing style, watchlist,
search, and direct-link playback. Browser data stays local and does not sync
with the Android app. The Android built-in playback source is unavailable in a
normal browser; browser sources need direct HTTPS video URLs and CORS access.

## Build from source

Use JDK 17, Android SDK platform 35, and build-tools 35.0.0. Android Studio can
import the repository as a Gradle project.

```sh
bash gradlew :core:test :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub
Actions runs the same checks on pushes and pull requests.

## Project map

| Path | Purpose |
| --- | --- |
| `app/` | Adaptive Compose UI, settings, encrypted local library, device pairing, Media3 player |
| `core/` | Catalog clients, source adapters, playback models, Real-Debrid client, JVM tests |
| `docs/SOURCE_PROVIDER.md` | Supported playback sources and JSON endpoint contract |
| `docs/DEVICE_SYNC.md` | Phone, tablet, and TV pairing instructions |
| `.github/workflows/android.yml` | APK checks, private release, and build artifacts |

Secrets and source URLs are encrypted with Android Keystore on the device and
are excluded from backups. They are not shared by device pairing. Brio has no
ads or analytics. Catalog metadata and source availability come from external
services; Brio is not affiliated with them. This product uses the TMDB API but
is not endorsed or certified by TMDB.
