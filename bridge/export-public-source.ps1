[CmdletBinding()]
param(
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot "build\bridge-public-source"
}
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)

if ($outputRoot -eq $repositoryRoot -or $outputRoot.StartsWith($PSScriptRoot + [System.IO.Path]::DirectorySeparatorChar)) {
    throw "Output directory must not replace or sit inside the Bridge source directory."
}
if (Test-Path -LiteralPath $outputRoot) {
    throw "Output directory already exists: $outputRoot"
}

function Copy-CleanTree {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $sourceRoot = [System.IO.Path]::GetFullPath($Source)
    Get-ChildItem -LiteralPath $sourceRoot -Recurse -Force -File | ForEach-Object {
        $relative = $_.FullName.Substring($sourceRoot.Length).TrimStart('\', '/')
        $segments = $relative -split '[\\/]'
        if ($segments | Where-Object { $_ -in @('build', '.cxx', '.gradle', '.idea', '.git', '.git-vendor-backup', '.externalNativeBuild') }) {
            return
        }
        if ($_.Name -in @('local.properties', 'release-signing.properties', 'keystore.properties')) {
            return
        }
        if ($_.Extension -in @('.jks', '.keystore', '.p12', '.pem', '.key', '.apk', '.aab')) {
            return
        }

        $target = Join-Path $Destination $relative
        $targetDirectory = Split-Path -Parent $target
        New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target
    }
}

New-Item -ItemType Directory -Path $outputRoot | Out-Null
Copy-CleanTree -Source $PSScriptRoot -Destination (Join-Path $outputRoot 'bridge')

Copy-Item -LiteralPath (Join-Path $repositoryRoot 'gradlew') -Destination $outputRoot
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat') -Destination $outputRoot
New-Item -ItemType Directory -Path (Join-Path $outputRoot 'gradle\wrapper') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'gradle\wrapper\gradle-wrapper.jar') -Destination (Join-Path $outputRoot 'gradle\wrapper')
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'gradle\wrapper\gradle-wrapper.properties') -Destination (Join-Path $outputRoot 'gradle\wrapper')

@'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DEFKON ADSB SDR BRIDGE"
include(":bridge")
'@ | Set-Content -LiteralPath (Join-Path $outputRoot 'settings.gradle.kts') -Encoding ASCII

@'
plugins {
    alias(libs.plugins.android.application) apply false
}
'@ | Set-Content -LiteralPath (Join-Path $outputRoot 'build.gradle.kts') -Encoding ASCII

@'
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
'@ | Set-Content -LiteralPath (Join-Path $outputRoot 'gradle.properties') -Encoding ASCII

New-Item -ItemType Directory -Path (Join-Path $outputRoot 'gradle') -Force | Out-Null
@'
[versions]
agp = "9.2.1"
coreKtx = "1.19.0"
junit = "4.13.2"
json = "20260522"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
json = { group = "org.json", name = "json", version.ref = "json" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
'@ | Set-Content -LiteralPath (Join-Path $outputRoot 'gradle\libs.versions.toml') -Encoding ASCII

@'
.gradle/
.idea/
.cxx/
**/build/
local.properties
release-signing.properties
*.jks
*.keystore
*.p12
*.pem
*.key
*.apk
*.aab
**/.git-vendor-backup/
'@ | Set-Content -LiteralPath (Join-Path $outputRoot '.gitignore') -Encoding ASCII

@'
* text=auto
*.bat text eol=crlf
*.jar binary
*.png binary
*.jpg binary
'@ | Set-Content -LiteralPath (Join-Path $outputRoot '.gitattributes') -Encoding ASCII

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'PUBLIC_RELEASE_README.md') -Destination (Join-Path $outputRoot 'README.md')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'LICENSE') -Destination (Join-Path $outputRoot 'LICENSE')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'COPYRIGHT') -Destination (Join-Path $outputRoot 'COPYRIGHT')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $outputRoot 'THIRD_PARTY_NOTICES.md')

New-Item -ItemType Directory -Path (Join-Path $outputRoot '.github\workflows') -Force | Out-Null
@'
name: Android Bridge CI

on:
  push:
  pull_request:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - name: Install Android native toolchain
        run: sdkmanager "platforms;android-37" "ndk;28.2.13676358" "cmake;3.22.1"
      - name: Unit tests and debug build
        run: ./gradlew :bridge:testDebugUnitTest :bridge:assembleDebug
'@ | Set-Content -LiteralPath (Join-Path $outputRoot '.github\workflows\android.yml') -Encoding ASCII

Write-Host "Public Bridge source created at: $outputRoot"
