# ArmorChat messaging crypto replacement — architecture review

**Status:** research only. No production messaging, no relay, nothing pushed.  
**Date:** 2026-09-06.  
**Reason for reopen:** vodozemac 0.10.0 stable Olm (`SessionConfig::version_1()`) uses a 64-bit truncated HMAC and was rejected ([OLM_MAC_SECURITY.md](OLM_MAC_SECURITY.md)).

This document evaluates replacements against the mandatory gates. The local A↔B spike is recorded in [MLS_SHOOTOUT.md](MLS_SHOOTOUT.md).

Read with [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md) and [THREAT_MODEL.md](THREAT_MODEL.md).

## Decision

**B. mls-rs 0.56.0** (`mls-rs-crypto-rustcrypto` 0.22.1) for the next integration path.

Spike result (2026-09-06): both OpenMLS 0.9.0 and mls-rs 0.56.0 passed local A↔B, Android API 26 arm64-v8a/x86_64 16 KiB packaging, identity binding, negatives, and Keystore-style persist/reload **without weakening the security model**. mls-rs is preferred on persistence shape, Apache-compatible graph (no MPL hpke-rs), and supply-chain size. OpenMLS keeps the SRLabs audit advantage and is the fallback if mls-rs later fails an adapter review.

Do **not** enable `CryptoEngine` until the remaining gates in [MLS_SHOOTOUT.md](MLS_SHOOTOUT.md) are closed (adapter review, production JNI, device restart wrap, epoch-update policy).

## Mandatory gates (how they were applied)

| # | Gate | Filter used here |
| --- | --- | --- |
| 1 | ≥128-bit stable authentication | AEAD tag ≥128 bits in a **stable, non-experimental** mode. Truncated HMAC-SHA256-64 fails. |
| 2 | FS + PCR for asynchronous messaging | Established async protocol (ratchet / MLS epochs + KeyPackages). Not one-shot HPKE. |
| 3 | Android Kotlin/JNI/FFI | Rust `cdylib` or UniFFI, or pure JVM. Must be maintainable. |
| 4 | Actively maintained | Tagged releases in 2026, public issue tracker, more than a single hobby crate. |
| 5 | Permissive, Apache-2.0 distribution | Apache-2.0, MIT, BSD, BC Licence. **Reject AGPL / GPL / SSPL.** |
| 6 | No ArmorChat-written ratchet/KDF/MAC | Use the library’s session/group APIs intact. |
| 7 | Bind existing ArmorChat Ed25519; no Ed25519→ECDH reuse | Independent DH/HPKE keys; identity only **signs** device/leaf credentials. |
| 8 | Serializable state under existing Keystore wrap | Library state export or storage trait; encrypt blobs with the session wrapping key (not the identity KEK). |
| 9 | minSdk 26, arm64-v8a / x86_64 / armeabi-v7a, 16 KiB pages, transitive licenses | Native must be rebuildable with NDK API 26 and `max-page-size=16384`, or stay on JVM. |
| 10 | Mature protocol, not stretched | Prefer IETF/industry protocol used as designed. Do not turn Noise/HPKE into a ratchet. |

## Rejected (do not spike)

### vodozemac 0.10.0 Olm v1 — already rejected

64-bit truncated HMAC. See [OLM_MAC_SECURITY.md](OLM_MAC_SECURITY.md).

### vodozemac 0.10.0 Olm v2 (`experimental-session-config`)

Full HMAC-SHA256 (32-byte tag) exists behind a **compile-time experimental flag**. Matrix spec still documents Olm message version byte `0x03` (truncated). Upstream moved v2 behind the flag **because it is not spec-complete** (vodozemac #280 / #287 / #300). Fails gates **4** (not stable) and **10** (not a mature published protocol). Enabling it to save JNI work is exactly the “minimize existing code” path this review forbids.

### libsignal (e.g. 0.86.x / current 0.10x line)

Signal Protocol / PQXDH, 128-bit AEAD, first-class Android AAR, strongest 1:1 pedigree. **AGPL-3.0.** Fails gate **5**. Upstream also states the Android packages are not a supported SDK for third-party apps. Out unless ArmorChat separately changes its Apache-only distribution plan.

### Wire CoreCrypto / Proteus

MLS + Proteus, Android FFI, encrypted keystore. **GPL-3.0.** Fails gate **5**. Proteus is also a Double Ratchet cousin of Olm; Wire itself is migrating 1:1 to MLS.

### Matrix Rust SDK / `matrix-sdk-crypto`

Apache-2.0, but session crypto is still Olm/Megolm (same 64-bit Olm v1 MAC). High Matrix room/device coupling. Fails gates **1** and **10** for ArmorChat’s non-Matrix relay.

### Tink / HPKE / Noise (as the session protocol)

Tink remains the identity signer. HPKE (RFC 9180) and Noise are not asynchronous Double Ratchet / MLS. Using them as the messenger would be a custom protocol (gates **2**, **6**, **10**).

### Homegrown `double-ratchet` crates or “Olm but AES-GCM”

Fails gate **6**.

### Bouncy Castle `bcmls-jdk18on` (1.85)

Permissive BC Licence, AES-GCM, pure JVM (no NDK). Already adjacent to ArmorChat’s `bcprov-jdk18on`. **Not spiked** as a primary: the MLS Java module is far less used in production messengers than OpenMLS/mls-rs, Android already ships a conflicting Bouncy Castle provider, and group-state persistence/interop against RFC test vectors is less documented. Revisit **only** if both Rust spikes fail native packaging.

## Viable candidates

### 1. OpenMLS 0.9.0

| Field | Report |
| --- | --- |
| Protocol / library / version | IETF **MLS RFC 9420**. Crate `openmls` **0.9.0** (2026-08-25). Crypto: `openmls_rust_crypto` 0.6.0 (pure RustCrypto). Optional `openmls_libcrux_crypto` not required for a first spike. |
| License | **MIT** (OpenMLS). RustCrypto primitives Apache-2.0/MIT. Compatible with Apache-2.0 distribution. |
| Maintenance | Phoenix R&D + Cryspen. Regular 2025–2026 releases (0.7 → 0.9). Used in production-shaped apps (Phoenix Air; Wire via a GPL wrapper fork — use **upstream crates**, not Wire’s fork). |
| Authentication / tag | Ciphersuites use **AES-GCM or ChaCha20-Poly1305** (128-bit tags). Mandatory suite: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`. Welcome/handshake use HPKE AEAD. **Not** truncated HMAC. Prefer this MTI suite or `MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519`. |
| Session establishment | **KeyPackage** (prekey analogue) + **Welcome** to join a group of two + **Commit** for epoch changes. Async by design. Application messages are AEAD under epoch secrets. |
| FS / PCR | RFC 9420: forward secrecy by deleting epoch/message secrets; post-compromise security after an honest **Commit**/update. Weaker during long one-way offline periods until an update is processed — same class of limit as Double Ratchet, documented in the RFC. No PQ claim unless a later PQ suite is separately reviewed. |
| Android integration | CI **builds** `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` (not a full device test). No official Kotlin bindings: same class of small JNI/`cdylib` adapter as vodozemac. Pure-Rust crypto avoids AWS-LC/OpenSSL NDK. |
| Persistence | `StorageProvider` trait (not one Olm pickle). Values are serde-serializable entities. A custom provider can AEAD-wrap each write with `armorchat_session_storage_wrap_v1` + Room, matching gate 8’s **key hierarchy** (not the 1D.1 single-blob schema). |
| Identity binding | MLS **leaf signature key** and **HPKE key** are library-generated, independent of ArmorChat Ed25519. `BasicCredential` can carry the pinned identity fingerprint; ArmorChat still **identity-signs** the KeyPackage/credential bytes (`ArmorChat/device/v1` style). Never convert identity Ed25519 to X25519. |
| Major risks | Storage is a KV API, not a single snapshot — crash-atomicity with outbox must be redesigned. Handshake (Welcome/Commit) needs a DS-like ordering policy on the mailbox (RFC expects a Delivery Service; ArmorChat’s queue is dumb). Group-of-two still needs periodic updates for PCR. SRLabs audit (STA, 2026) found issues including one High; fixed in 0.8.1 / 0.7.3 — pin **≥ 0.9.0**. Optional libcrux backend had unrelated 2026 advisories; **do not enable it** in a first spike. Larger API than Olm. |

### 2. mls-rs 0.56.0

| Field | Report |
| --- | --- |
| Protocol / library / version | IETF **MLS RFC 9420**. Crate `mls-rs` **0.56.0** (2026-08-19). Crypto for Android: **`mls-rs-crypto-rustcrypto` 0.22.1** (pure Rust). Do **not** default to `mls-rs-crypto-awslc` / OpenSSL on Android (native C, ABI/page-size, extra supply chain). |
| License | **Apache-2.0 OR MIT**. |
| Maintenance | AWS Labs (`awslabs/mls-rs`). Frequent 2026 releases (0.53–0.56). Listed on the MLS implementation list. UniFFI helper (`mls-rs-uniffi` / `mls-rs-ffi`) exists. |
| Authentication / tag | Same RFC suites: AES-128-GCM / ChaCha20-Poly1305 / AES-256-GCM — **128-bit tags**. Same MTI ciphersuite as OpenMLS. |
| Session establishment | KeyPackage + Welcome + Commit; `rfc_compliant` feature includes private messages and out-of-order application decrypt. |
| FS / PCR | Same RFC 9420 properties as OpenMLS. |
| Android integration | WASM is advertised; Android NDK not a first-class CI matrix in the same way as OpenMLS. **UniFFI** can generate Kotlin, which may be easier to maintain than a hand JNI surface — or a small `cdylib` like 1D.1. RustCrypto provider keeps the .so self-contained. |
| Persistence | `write_to_storage` / SQLite provider, or a custom store trait. Same Keystore-wrap pattern as OpenMLS: encrypt stored group/key-package blobs; do not use SQLCipher if it pulls GPL or extra native. Prefer application AEAD + Room. |
| Identity binding | `SigningIdentity` + basic or X.509 credentials. For ArmorChat, **basic credential** + outer identity signature; skip X.509 unless a later review wants it. Independent HPKE/signature keys. |
| Major risks | **No third-party security audit** (upstream security notice). Faster crate version churn (0.5x). Android/16 KiB packaging unproven here. Default feature set is large (`rfc_compliant` pulls X.509); spike must **disable unused features** and inventory transitive licenses. UniFFI is another native toolchain to lock. |

## Gate-by-gate comparison (spike pair vs rejected Olm)

| Gate | OpenMLS 0.9.0 | mls-rs 0.56.0 | Olm v1 (rejected) |
| --- | --- | --- | --- |
| 1 Tag ≥128 | Yes (GCM / ChaPoly) | Yes | No (64-bit HMAC) |
| 2 Async FS/PCR | RFC 9420 | RFC 9420 | Olm DR (OK if MAC were OK) |
| 3 Android path | JNI + rustcrypto; CI android triples | UniFFI or JNI + rustcrypto | JNI already in-tree |
| 4 Maintained | Yes | Yes | Yes |
| 5 Permissive | MIT | Apache-2.0 OR MIT | Apache-2.0 |
| 6 No custom crypto | Library MLS APIs | Library MLS APIs | n/a |
| 7 Identity bind | BasicCredential + Tink sign | SigningIdentity + Tink sign | Already designed |
| 8 Keystore state | StorageProvider + AEAD | Group store + AEAD | Pickle + AEAD |
| 9 ABI / 16 KiB / licenses | Likely; must rebuild | Likely; must rebuild | Already shown for v1 .so |
| 10 Established protocol | RFC 9420 group-of-2 | RFC 9420 group-of-2 | Olm v1 spec, but MAC fail |

MLS for **two members** is in-design for RFC 9420 (“groups of size two to many”), not a stretch of Noise/HPKE. What *is* application work (not crypto invention): mapping KeyPackage distribution and Commit/Welcome delivery onto ArmorChat’s per-contact mailbox, plus the existing pin/signature chain.

## Spike scope (still no relay, no production engine)

Each spike, independently, should stop at a **local A↔B harness** analogous to 1D.1:

1. Lock crate versions + cargo-ndk `--platform 26` for the three ABIs; `readelf` API 26 and `LOAD` align `0x4000`.
2. One ciphersuite only (MTI AES-GCM-128 or ChaCha20-Poly1305).
3. Generate MLS keys **independent** of the Tink identity; identity-sign a KeyPackage/credential; reject wrong pin / modified credential / tampered ciphertext.
4. Encrypt `"Hello ArmorChat"`; peer recovers exact plaintext; AEAD tag length visible from the library/ciphersuite (do not invent a tag).
5. Export/reload group state through Keystore-wrapped storage (custom provider, not plaintext SQLite).
6. Transitive license inventory of the enabled feature set.
7. **Do not** wire `CryptoEngine`, chat UI, or a relay.

The spike succeeded for **both** libraries. Next path is **mls-rs 0.56.0**; details, sizes, licenses, and remaining gates are in [MLS_SHOOTOUT.md](MLS_SHOOTOUT.md). A follow-up identity/session-binding review is still required before rewriting [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md) session sections from Olm to MLS. Do not write a substitute ratchet.

## Assumptions (conservative)

- RFC 9420 AEAD tags are 128 bits for the listed suites (AES-GCM default tag; Poly1305 128-bit). Not re-derived from first principles beyond the RFC/ciphersuite IANA names.
- “Actively maintained” is evidenced by 2026 crates.io releases, not by a promise of long-term support.
- Wire/XMTP/Phoenix using OpenMLS does not audit ArmorChat’s adapter.
- MLS PCR still needs honest epoch updates; a silent always-online guarantee is not claimed.
- Deployment-scale MAC math from the Olm review does not apply to 128-bit AEAD at ArmorChat volumes.

## What this does not change

`UnimplementedCryptoEngine` / `NoOpMessageTransport` stay disabled. The 1D.1 vodozemac harness may remain as historical feasibility of JNI + Keystore persistence; it is **not** a production crypto choice. Identity, contacts, and backup are unchanged.
