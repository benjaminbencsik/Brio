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

1. Open [Actions](https://github.com/benjaminbencsik/Brio/actions) and choose
   the newest successful **Build Brio APK** run on `main`.
2. Download **Brio-debug-APK** from the run's **Artifacts** section. GitHub may
   require you to sign in.
3. Extract the ZIP and install `app-debug.apk` on your phone or tablet. For an
   onn. or other Android TV device, transfer the extracted APK to the device
   using a file-transfer app or ADB, then open it in a file manager.

Enable installation from the app opening the APK when Android asks. The CI
workflow caches one debug signing key to make updates install over builds made
with the same cached key. If Android reports a signature conflict, uninstalling
an older debug build will remove its local data. There is no production release
or Play Store distribution yet.

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
| `.github/workflows/android.yml` | APK build, tests, lint, and artifact upload |

Secrets and source URLs are encrypted with Android Keystore on the device and
are excluded from backups. They are not shared by device pairing. Brio has no
ads or analytics. Catalog metadata and source availability come from external
services; Brio is not affiliated with them. This product uses the TMDB API but
is not endorsed or certified by TMDB.
