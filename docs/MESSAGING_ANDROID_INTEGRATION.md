# ArmorChat Android Integration — Phase 1D.1

**Status:** Isolated local feasibility harness only. Not production E2EE. Not wired
to `CryptoEngine`, chat UI, or any relay. Nothing was pushed.

Date of this pass: 2026-09-06.

## Requested gates — exact pass/fail

| # | Gate | Result |
| --- | --- | --- |
| 1 | Local A↔B test performs real decryption of `"Hello ArmorChat"` | **PASS** (host JNI + Rust) |
| 2 | JNI preserves vodozemac ciphertext message type with the packet; Kotlin does not infer it | **PASS** (host JNI + Rust) |
| 3 | ArmorChat identity signature of device credentials; device signing-key of one-time prekeys; pinned identity check before session setup | **PASS** (enforced in native `transact`; host JNI + Rust) |
| 4 | Negative tests: wrong pin, modified credential, modified prekey, tampered ciphertext, replay | **PASS** (host JNI + Rust) |
| 5 | Native Android API target 26 for advertised ABIs | **PASS** (rebuilt and inspected this pass) |
| 6 | Separate Keystore-wrapped session-storage key and persistence code | **IMPLEMENTED.** Restart persistence is **not physically verified** |
| 7 | Android instrumented tests for Keystore/session restart and atomic commit | **IMPLEMENTED and compiled. Not physically verified** — no device or configured emulator |

## What this pass ran

### Rust harness (`cargo test --locked --offline`)

Crate: `android/native/armorchat-vodozemac`. **6 passed, 0 failed.**

- `real_round_trip_and_serialized_reload` — A encrypts `"Hello ArmorChat"`; B
  recovers that exact plaintext; reply uses library type 1; reload via pickle
  continues encrypt/decrypt.
- `wrong_pinned_armorchat_identity`
- `modified_device_credential_and_prekey`
- `tamper_and_replay_fail_without_state_change` — includes flipped library
  `message_type` (0→1) on a prekey packet
- `normal_ciphertext_tamper_does_not_consume_ratchet`
- `strict_credential_parser_and_framing` — duplicate fields, floats, unknown
  fields, and `ArmorChat/device/v1` framing

### Host JNI tests (`:app:testDebugUnitTest -PmessagingJniTests`)

Class: `com.codeyantra.armorchat.crypto.VodozemacFeasibilityTest`.
**6 passed, 0 failed.**

- `encryptDecryptHelloArmorChatRoundTrip` — B decrypts `"Hello ArmorChat"`;
  native packet carries `message_type` 0 (prekey) then 1 (normal)
- `wrongPinnedArmorChatIdentityIsRejected`
- `modifiedDeviceCredentialIsRejected`
- `modifiedPrekeyIsRejected`
- `tamperedCiphertextAndReplayAreRejected` — prekey and normal tamper, wrong
  `message_type`, and replay; rejected operations leave committed state unchanged
- `rawTinkRfc8032Vector` — Tink raw 64-byte Ed25519 matches RFC 8032 empty-message vector

These tests load the host-built `libarmorchat_vodozemac` through the same JNI
entry as Android. They are not a substitute for Keystore or process-restart
proof.

### Native API 26 rebuild

Script: `android/scripts/build-native.sh` (`cargo ndk --platform 26`).
NDK: `28.2.13676358` (r28c). Rust: 1.98.1. cargo-ndk: 4.1.2.
`RUSTFLAGS` includes `-C link-arg=-Wl,-z,max-page-size=16384`.
No toolchain or dependency blocker required keeping API 24.

Inspected packaged `.note.android.ident` (`1a 00 00 00` = API 26) and `LOAD`
alignment (`0x4000` = 16 KiB):

| ABI | File | API | NDK | Size (bytes) | LOAD align |
| --- | --- | --- | --- | --- | --- |
| arm64-v8a | `app/src/main/jniLibs/arm64-v8a/libarmorchat_vodozemac.so` | 26 | r28c 13676358 | 1,590,360 | 0x4000 |
| x86_64 | `app/src/main/jniLibs/x86_64/libarmorchat_vodozemac.so` | 26 | r28c 13676358 | 1,674,248 | 0x4000 |
| armeabi-v7a | `app/src/main/jniLibs/armeabi-v7a/libarmorchat_vodozemac.so` | 26 | r28c 13676358 | 1,208,024 | 0x4000 |

Rust triples remain `aarch64-linux-android`, `x86_64-linux-android`, and
`armv7-linux-androideabi`. The API belongs to the NDK clang target
(`*-android26-clang` / `armv7a-linux-androideabi26-clang`), not the triple.

16-KiB alignment is recorded from ELF headers. It is **not** a device boot test
on a 16-KiB page-size phone.

### Final build

`:app:assembleDebug` and `:app:assembleDebugAndroidTest` — **BUILD SUCCESSFUL**.
The debug APK packages the API-26 libraries above. The androidTest APK includes
`SessionRestartTest`.

## JNI message type (gate 2)

`encrypt` / inbound setup return a `packet` object produced only from
`OlmMessage::to_parts()`:

- `message_type`: library value (`0` prekey, `1` normal)
- `ciphertext`: library bytes, Base64URL unpadded

`decrypt` / inbound consume `OlmMessage::from_parts(message_type, bytes)`. Kotlin
reads those fields and must not reconstruct the type. Swapping `0`→`1` on a
prekey packet is rejected.

## Identity binding (gate 3)

Before outbound or inbound session creation the native adapter:

1. Parses the signed object strictly (no duplicate keys, no floats, no unknown
   fields, no trailing JSON).
2. Checks the credential `identity_key` against the caller-supplied pinned
   ArmorChat Ed25519 key (full 32 bytes, never ac1).
3. Verifies the identity signature over `ASCII("ArmorChat/device/v1") || 0x00 || JCS(body)`.
4. Verifies the one-time prekey signature with the **credential's** device
   signing key over `ArmorChat/prekey/v1`.
5. Checks suite, device id, generation, agreement key, and validity windows.

Tink signs the device credential on the Kotlin side with a raw 64-byte Ed25519
signature. vodozemac signs prekeys with the Olm device key. There is no
Ed25519-to-X25519 conversion.

## Persistence code (gate 6) — implemented, not restart-proven

Separate from the identity KEK (`armorchat_identity_kek_v1`):

- Keystore wrapping key alias: `armorchat_session_storage_wrap_v1` (AES-256-GCM,
  non-exportable)
- Random 32-byte per-install storage data key, wrapped under that Keystore key
- Sealed vodozemac account/session snapshots and exact operation results in a
  Room database under `noBackupFilesDir`
- One Room transaction stages native output, then commits sealed ratchet state
  **and** the sealed ciphertext/result together
- Retry of the same operation id returns the committed ciphertext; it does not
  encrypt again
- Failed operations throw without replacing the caller's committed snapshot

This is the Phase 1D.1 harness store. It is not chat-history storage and is not
wired to the UI.

**Do not treat this as verified restart persistence.** Android Keystore and
process death were not exercised on a device or emulator in this pass.

## Instrumented tests (gate 7) — implemented but not physically verified

`SessionRestartTest` plus `scripts/test-session-restart.sh` cover:

- Keystore wrapping key still opens a sealed probe after a new process
- Serialized session state reloads and continues encrypt/decrypt
- Replay after restart is rejected
- Process kill after state write, after result write, and after commit
- Injected exceptions roll back; operation-id reuse with a different request fails

This pass could not run them:

- `adb devices` listed no devices
- `emulator -list-avds` listed no configured AVDs
- `/dev/kvm` is readable and an `android-36.1` system image is installed, but an
  image is not a configured, running emulator

Host JVM / Robolectric cannot substitute for Android Keystore or
`Process.killProcess` restart. Until those tests are run with
`scripts/test-session-restart.sh` on a real device or AVD, gate 7 remains
**implemented but not physically verified**.

## 64-bit Olm MAC gate (2026-09-06)

**FAIL / C — not acceptable.** Full review: [OLM_MAC_SECURITY.md](OLM_MAC_SECURITY.md).

Pinned vodozemac 0.10.0 `SessionConfig::version_1()` is AES-256-CBC plus
HMAC-SHA256 truncated to **8 bytes**. Decision: reopen the messaging
crypto/library choice; do not enable `CryptoEngine` on this MAC.

**Replacement (architecture only):** [MESSAGING_CRYPTO_REPLACEMENT.md](MESSAGING_CRYPTO_REPLACEMENT.md)
— **B**, spike OpenMLS 0.9.0 and mls-rs 0.56.0 (MLS RFC 9420). No production
library is selected. Device persistence tests do not change this gate.

## Still not claimed

- Complete transitive license SPDX/SBOM in the APK
- 16-KiB page-size **device** test
- Release/R8 packaging, zeroization, and panic-to-typed-error review
- Relay, outbox/inbox over the network, FCM, or enabling `CryptoEngine`

`UnimplementedCryptoEngine` and `NoOpMessageTransport` remain the production
bindings. Enabling real messaging still requires the protocol's remaining
Phase 1D.1/1D.2 review gates.

## How the isolated harness is invoked

Native: `VodozemacFeasibility.transact(snapshot, request) -> {state, output}`.
The caller commits `state` with any ciphertext before exposing output. There is
no process-global Olm map.

Host JNI tests require `-PmessagingJniTests` so Gradle builds the host
`cdylib` and sets `java.library.path`.

Restart tests (when a device exists): install the debug + androidTest APKs, then
`sh android/scripts/test-session-restart.sh`.
