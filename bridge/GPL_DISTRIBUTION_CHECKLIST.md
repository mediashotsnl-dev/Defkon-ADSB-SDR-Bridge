# DEFKON ADSB SDR BRIDGE - GPL Distribution Checklist

Use this checklist for every public Bridge release.

## Must Include

- Complete `bridge/` source used to build the released APK.
- `bridge/build.gradle.kts`.
- Root Gradle files required to build the module.
- `bridge/src/main/cpp/CMakeLists.txt`.
- All local JNI/native adapter files.
- Vendored source under `bridge/src/main/cpp/gpl/`.
- Upstream license files:
  - `readsb/COPYING`
  - `readsb/LICENSE`
  - `rtl-sdr/COPYING`
  - `libusb/COPYING`
- `bridge/NOTICE_ADSB_SDR.md`.
- `bridge/LICENSE`, `bridge/COPYRIGHT`, and
  `bridge/THIRD_PARTY_NOTICES.md`.
- In-app license texts and the exact public source URL.
- Build instructions.
- Exact release version and commit/revision identifiers.

## Keep Consumer Applications Separate

- Do not copy GPL SDR/readsb/rtl-sdr/libusb source into `app/`.
- Do not link consumer applications directly against Bridge native SDR libraries.
- Keep client communication through localhost JSON/SBS data feeds.

## Suggested Release Shape

- Public repo or source archive:
  - `defkon-adsb-sdr-bridge-vX.Y.Z-source.zip`
- APK:
  - `defkon-adsb-sdr-bridge-vX.Y.Z.apk`
- Release notes:
  - State that source is available at the same version.
  - Link to notices and upstream projects.

## Pre-Release Checks

- Run `gradlew :bridge:assembleRelease` or the chosen release build.
- Verify `bridge/NOTICE_ADSB_SDR.md` revisions are current.
- Verify no GPL source was added under `app/`.
- Verify Bridge APK version matches the published source tag/archive.
- Verify the public tag is available without login and builds independently.
- Verify the Play listing links to the exact source tag.
- Upload the native debug-symbol archive generated for the release.
- Decide whether to remove nested `.git` directories from vendored source
  archives before publishing.
