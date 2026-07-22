# GPL release procedure

Use this procedure for every distributed APK or Android App Bundle.

1. Update `versionCode` and `versionName` in `build.gradle.kts`.
2. Confirm the revisions and local modifications in
   `THIRD_PARTY_NOTICES.md` and `NOTICE_ADSB_SDR.md`.
3. Run `export-public-source.ps1` from the private development repository.
4. Build the exported tree and run its unit tests.
5. Commit the exported tree and tag it `bridge-vX.Y.Z`.
6. Build the distributed AAB/APK from that exact tag.
7. Publish the tag-generated source archive beside the binary release.
8. Link the exact tag from the app listing and release notes.
9. Upload the generated native debug-symbol archive to Google Play.

Do not publish signing properties, keystores, `local.properties`, build output,
crash logs, or source from unrelated private applications and services.

The public source must remain available for as long as the corresponding
binary is distributed. Do not replace an old tag with newer or older source.
