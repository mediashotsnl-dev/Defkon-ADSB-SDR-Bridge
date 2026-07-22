# Building from source

## Required tools

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools installed by the Android Gradle Plugin
- Android NDK `28.2.13676358`
- CMake `3.22.1`

The repository pins Gradle `9.4.1` and Android Gradle Plugin `9.2.1`.

## Configure the Android SDK

Create an untracked `local.properties` file in the repository root:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Install the required native tools with Android SDK Manager when needed:

```text
platforms;android-37
ndk;28.2.13676358
cmake;3.22.1
```

## Build and test

On Linux or macOS:

```bash
./gradlew :bridge:testDebugUnitTest :bridge:assembleDebug
./gradlew :bridge:bundleRelease
```

On Windows:

```powershell
.\gradlew.bat :bridge:testDebugUnitTest :bridge:assembleDebug
.\gradlew.bat :bridge:bundleRelease
```

A release can be built without a private signing configuration. To create a
locally signed release, provide the four values referenced by
`bridge/build.gradle.kts` through environment variables or an untracked
`release-signing.properties` file. Signing keys and passwords are not part of
Corresponding Source and must never be committed.
