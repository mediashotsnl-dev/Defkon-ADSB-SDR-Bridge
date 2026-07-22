import java.util.Properties

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

android {
    namespace = "com.mediashots.defkonadsbbridge"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mediashots.defkonadsbbridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

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
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
