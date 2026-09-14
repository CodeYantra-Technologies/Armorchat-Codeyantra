# Third-party notices — ArmorChat (Android)

Engineering inventory of direct dependencies and assets for the ArmorChat Android
application (`com.codeyantra.armorchat`). This is **not legal advice**.

Copyright 2026 CodeYantra Technologies.

Project license: [Apache License 2.0](LICENSE). See also [NOTICE](NOTICE).

## Summary

- **No GPL, AGPL, or SSPL** found among **direct** dependencies listed in
  `gradle/libs.versions.toml` / `app/build.gradle.kts`.
- A full **transitive** license scan is incomplete without an automated license
  plugin (optional future work). Review transitive artifacts before Play Store /
  redistribution milestones.
- **JUnit 4** is **EPL-1.0** (copyleft-ish) but is **test scope only** and is
  **not shipped in the APK**.
- Optional consistent **SPDX** file headers were deferred; value is limited versus
  NOTICE / THIRD_PARTY_NOTICES for this stage. Do not strip any existing
  third-party headers (none found in project Kotlin sources).

---

## A. Runtime / bundled dependencies

| Component | Version | Purpose | License | Source | Attribution / notes | Apache-2.0 compatible? |
| --- | --- | --- | --- | --- | --- | --- |
| Google Tink (`tink-android`) | 1.21.0 | Ed25519 identity crypto | Apache-2.0 | https://github.com/tink-crypto/tink-java | Apache NOTICE via upstream; no extra proprietary terms known | Yes |
| Bouncy Castle (`bcprov-jdk18on`) | 1.81 | scrypt (backup) + Ed25519 `validatePublicKeyFull` | Bouncy Castle Licence (MIT-like / MIT) | https://www.bouncycastle.org/ | **Copyright notice required** in copies — see [NOTICE](NOTICE) | Yes (permissive) |
| ZXing Core (`zxing-core`) | 3.5.3 | QR encode/decode helpers | Apache-2.0 | https://github.com/zxing/zxing | Apache-2.0 | Yes |
| CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) | 1.5.3 | QR camera preview / capture | Apache-2.0 | https://developer.android.com/training/camerax | AndroidX / AOSP notices | Yes |
| Jetpack Compose BOM | 2026.06.01 | Compose UI platform BOM | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose | Pins Compose artifacts | Yes |
| Compose UI / Graphics / Tooling Preview | (BOM) | UI toolkit | Apache-2.0 | https://developer.android.com/jetpack/compose | AndroidX | Yes |
| Material 3 | (BOM) | Material Design 3 components | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material3 | AndroidX | Yes |
| Material Icons Extended | (BOM) | Vector icons in UI | Apache-2.0 | https://developer.android.com/reference/kotlin/androidx/compose/material/icons/package-summary | Via AndroidX; not bundled font files | Yes |
| Hilt (`hilt-android` + compiler) | 2.57.2 | Dependency injection | Apache-2.0 | https://dagger.dev/hilt/ | Google/Dagger Apache-2.0 | Yes |
| Hilt Navigation Compose | 1.2.0 | Hilt ↔ Navigation | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/hilt | AndroidX | Yes |
| Room (`runtime`, `ktx`, compiler) | 2.8.4 | Local SQLite persistence | Apache-2.0 | https://developer.android.com/training/data-storage/room | AndroidX | Yes |
| DataStore Preferences | 1.1.7 | Settings preferences | Apache-2.0 | https://developer.android.com/topic/libraries/architecture/datastore | AndroidX | Yes |
| WorkManager | 2.10.3 | Background work (reserved / future) | Apache-2.0 | https://developer.android.com/topic/libraries/architecture/workmanager | AndroidX | Yes |
| Navigation Compose | 2.9.8 | In-app navigation | Apache-2.0 | https://developer.android.com/guide/navigation | AndroidX | Yes |
| Lifecycle Runtime / Compose / ViewModel | 2.9.3 | Lifecycle-aware UI | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle | AndroidX | Yes |
| Activity Compose | 1.10.1 | ComponentActivity + Compose | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity | AndroidX | Yes |
| AndroidX Core KTX | 1.16.0 | Kotlin extensions | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core | AndroidX / AOSP notices | Yes |
| SplashScreen | 1.0.1 | Splash API compat | Apache-2.0 | https://developer.android.com/develop/ui/views/launch/splash-screen | AndroidX | Yes |
| OkHttp | 4.12.0 | HTTP client (reserved / future networking) | Apache-2.0 | https://square.github.io/okhttp/ | Square Apache-2.0 | Yes |
| kotlinx-coroutines-android | 1.10.2 | Async / Flow | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | JetBrains Apache-2.0 | Yes |

`app/build.gradle.kts` packaging excludes `META-INF/{AL2.0,LGPL2.1}` — this is
standard Android Gradle Plugin duplicate-license noise handling, **not** evidence
that ArmorChat app source is LGPL.

---

## B. Build / development dependencies

| Component | Version | Purpose | License | Source | Attribution / notes | Shipped in APK? |
| --- | --- | --- | --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | Android build | Apache-2.0 | https://developer.android.com/build | Build tooling | No |
| Kotlin / Kotlin Compose plugin | 2.2.21 | Language + Compose compiler | Apache-2.0 | https://kotlinlang.org/ | JetBrains | No (stdlib may ship via app deps — Apache-2.0) |
| KSP | 2.2.21-2.0.5 | Annotation processing (Hilt/Room) | Apache-2.0 | https://github.com/google/ksp | Build-time | No |
| JUnit | 4.13.2 | Unit tests | **EPL-1.0** | https://junit.org/junit4/ | Copyleft-ish **test scope**; not a runtime distribution concern for the APK | No |
| Google Truth | 1.4.4 | Test assertions | Apache-2.0 | https://github.com/google/truth | Test | No |
| Robolectric | 4.16.1 | JVM Android unit tests | Apache-2.0 | https://robolectric.org/ | Test | No |
| androidx.test runner / ext-junit | 1.7.0 / 1.3.0 | Instrumented tests | Apache-2.0 | https://developer.android.com/training/testing | androidTest | No (test APK only) |
| kotlinx-coroutines-test | 1.10.2 | Coroutine test utilities | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | Test | No |
| Compose UI Tooling / test manifest | (BOM) | Debug previews / test helpers | Apache-2.0 | AndroidX Compose | `debugImplementation` | Debug builds only |

**Flag:** JUnit 4’s EPL-1.0 is the only direct **copyleft-ish** dependency identified.
It applies to **tests / build**, not APK runtime distribution. Do not relocate JUnit
into `implementation` without a separate license review.

---

## C. Assets / fonts / icons

| Asset | License / status | Notes |
| --- | --- | --- |
| `armor_mark.png` + launcher mipmaps | **CodeYantra original** (best-effort from project git history; first appeared in CodeYantra commits) | Retain design provenance records; not claimed as third-party |
| Third-party fonts | **None** bundled in app resources | System / Compose typography only |
| Material Icons Extended | Apache-2.0 via AndroidX dependency | Vector icons; not a separate font package |

---

## Compatibility conclusion (engineering)

Apache-2.0 distribution of this **fresh** ArmorChat implementation appears
**compatible** with the current **direct** dependency set: Apache-2.0 libraries
plus Bouncy Castle’s MIT-like licence (NOTICE copyright retained) and
test-only EPL JUnit.

This repository is **not** a relicensing of any GPL/BChat/Session tree.

Disclaimer: engineering compliance audit only — not legal advice. Before
international Play Store / export distribution, review encryption
export-compliance requirements with qualified counsel.
