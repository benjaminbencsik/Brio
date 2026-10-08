# Verification for Brio 0.1.0

Checked on October 8, 2026.

- Android debug APK assembly: passed.
- Kotlin/JVM tests: 11 passed, zero failures or errors.
- Android lint: zero errors; 15 advisory warnings (dependency updates, resource density variants, older Android backup attribute, and KTX style).
- APK signature verification: passed with APK Signature Scheme v2.
- Manifest: phone and Android TV launchers present; touchscreen optional; minimum Android API 26 (Android 8).
- Manifest and resource XML: parsed successfully.
- APK SHA-256: `adcc262ae4ce64d23b63cafb1502c721077c97adf0c81ac9a5d5d449428cf4dc`

## What still needs device testing

No phone, tablet, Android TV emulator, or Onn box was available for installation testing. Test D-pad focus navigation, stream decoding, subtitle / audio selection, resume, and marker-based skipping on the actual devices.

Real-Debrid was tested against local mock HTTP responses, not a live account. No user API token was supplied. Verify a real account from Settings and play a file to complete end-to-end testing. Catalog data and stream provider behavior likewise need the user's configured endpoints.

Debug APKs are intended for testing. GitHub Actions and this local build can use different debug signing keys. A persistent signing configuration is needed for seamless future updates.
