import java.util.Properties
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
}

val localSigningProperties = Properties().apply {
    listOf(
        rootProject.file("release-signing.properties"),
        rootProject.file("signing/release-signing.properties")
    ).firstOrNull { it.isFile }?.inputStream()?.use { load(it) }
    keys.toList().forEach { key ->
        val rawKey = key.toString()
        val cleanKey = rawKey.removePrefix("\uFEFF")
        if (cleanKey != rawKey) {
            setProperty(cleanKey, getProperty(rawKey))
            remove(rawKey)
        }
    }
}

fun bridgeSigningValue(name: String): String? {
    return providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = bridgeSigningValue("DEFKON_RELEASE_STORE_FILE")
val releaseStorePassword = bridgeSigningValue("DEFKON_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = bridgeSigningValue("DEFKON_RELEASE_KEY_ALIAS")
val releaseKeyPassword = bridgeSigningValue("DEFKON_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

val bridgeReleaseVersion = "0.1.6"
val bridgeSourceUrl =
    "https://github.com/mediashotsnl-dev/Defkon-ADSB-SDR-Bridge/tree/bridge-v$bridgeReleaseVersion"

android {
    namespace = "com.mediashots.defkonadsbbridge"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mediashots.defkonadsbbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = bridgeReleaseVersion

        buildConfigField("String", "BRIDGE_SOURCE_URL", "\"$bridgeSourceUrl\"")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                debugSymbolLevel = "FULL"
            }
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation(libs.json)
    testImplementation(libs.junit)
}

val verifyBridgeOpenSourceRelease = tasks.register("verifyBridgeOpenSourceRelease") {
    group = "verification"
    description = "Verifies GPL source contents and the public source tag for this Bridge release."
    notCompatibleWithConfigurationCache("Checks the public release URL during a release build.")
    inputs.property("bridgeVersion", bridgeReleaseVersion)
    inputs.property("sourceUrl", bridgeSourceUrl)
    inputs.files(
        file("LICENSE"),
        file("COPYRIGHT"),
        file("THIRD_PARTY_NOTICES.md"),
        file("NOTICE_ADSB_SDR.md"),
        file("src/main/cpp/CMakeLists.txt"),
        file("src/main/cpp/gpl/readsb/COPYING"),
        file("src/main/cpp/gpl/readsb/LICENSE"),
        file("src/main/cpp/gpl/rtl-sdr/COPYING"),
        file("src/main/cpp/gpl/libusb/COPYING")
    )
    doLast {
        inputs.files.files.forEach { required ->
            check(required.isFile && required.length() > 0L) {
                "Missing GPL release source or license file: ${required.absolutePath}"
            }
        }
        val sourceReference = file("src/main/java/com/mediashots/defkonadsbbridge/MainActivity.java").readText()
        check(sourceReference.contains("BuildConfig.BRIDGE_SOURCE_URL")) {
            "Bridge UI must use the version-bound BuildConfig.BRIDGE_SOURCE_URL."
        }
        val connection = URI(bridgeSourceUrl).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "DEFKON-GPL-release-check")
        val responseCode = connection.responseCode
        connection.disconnect()
        check(responseCode in 200..299) {
            "Public GPL source tag is unavailable ($responseCode): $bridgeSourceUrl. " +
                "Publish the exact source before building the release APK."
        }
        println("GPL source release verified: $bridgeSourceUrl")
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyBridgeOpenSourceRelease)
}
