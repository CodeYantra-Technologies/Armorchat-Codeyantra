# ArmorChat MLS replacement feasibility shootout

**Status:** isolated local A↔B spike complete. Not production E2EE.  
**Date:** 2026-09-06.  
**Scope:** Android API 26, arm64-v8a + x86_64, two independent MLS devices.  
**Not in this pass:** relay, FCM/WebSocket, chat UI, `CryptoEngine`, MLS extensions, custom ratchets/KDFs/MACs, git push.

Read with [MESSAGING_CRYPTO_REPLACEMENT.md](MESSAGING_CRYPTO_REPLACEMENT.md), [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md), [THREAT_MODEL.md](THREAT_MODEL.md), [IDENTITY_SECURITY.md](IDENTITY_SECURITY.md).

## Choice

**B. mls-rs 0.56.0** (`mls-rs-crypto-rustcrypto` 0.22.1).

Ciphersuite: RFC 9420 `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (`CipherSuite::CURVE25519_AES128`). 128-bit AES-GCM tags. Independent library-generated MLS HPKE/signature keys. ArmorChat Ed25519 only signs the KeyPackage binding.

Neither candidate required weakening the approved ArmorChat security model. `UnimplementedCryptoEngine` stays disabled. The new `.so` files are not loaded from production UI.

Remaining gates before any production enablement are listed at the end. They are review/integration work, not a reason to pick **C**.

## What was built

Two isolated crates. They do not share code, storage AAD, or JNI class names.

| Candidate | Crate | JNI probe (not production) | Wrap AAD |
| --- | --- | --- | --- |
| OpenMLS 0.9.0 | `android/native/armorchat-openmls` | `OpenMlsFeasibility` | `ArmorChat/mls-openmls/state/v1` |
| mls-rs 0.56.0 | `android/native/armorchat-mlsrs` | `MlsRsFeasibility` | `ArmorChat/mls-mlsrs/state/v1` |

Identity binding (both, documented ArmorChat signing layer):

```
ASCII("ArmorChat/mls-keypackage/v1") || 0x00 || JCS({v:1, suite:"ac-mls-v1", identity_key, key_package})
```

signed with the pinned ArmorChat Ed25519 identity. MLS leaf/HPKE keys are library-generated. The identity seed is never converted to X25519.

Session wrap (both): AES-256-GCM over exported MLS state with a 32-byte data key, matching the existing session-storage key hierarchy (`armorchat_session_storage_wrap_v1` class of key — not the identity KEK). The harness uses a fixed nonce for a one-shot snapshot; production wrap must keep the existing random-nonce envelope.

NDK: r28c `28.2.13676358`. rustc 1.98.1. `cargo ndk --platform 26` with `-C link-arg=-Wl,-z,max-page-size=16384`. Scripts: `android/scripts/build-openmls.sh`, `android/scripts/build-mlsrs.sh`.

## Gate results (both candidates)

| # | Required proof | OpenMLS 0.9.0 | mls-rs 0.56.0 |
| --- | --- | --- | --- |
| 1 | Android API 26 arm64-v8a + x86_64, 16 KiB LOAD | PASS (`1a 00 00 00`, LOAD `0x4000`) | PASS (same) |
| 2 | Two independent MLS clients; no Ed25519→HPKE reuse | PASS | PASS |
| 3 | Bind MLS credential/KeyPackage to pinned Ed25519 | PASS (`ac-mls-v1`) | PASS (`ac-mls-v1`) |
| 4 | RFC 9420 KeyPackage / add / Welcome / Commit | PASS | PASS |
| 5 | A encrypts `"Hello ArmorChat"`; B recovers exact plaintext | PASS | PASS |
| 6 | B replies; A decrypts | PASS | PASS |
| 7a | Wrong pinned identity rejected | PASS | PASS |
| 7b | Modified credential binding rejected | PASS | PASS |
| 7c | Tampered MLS ciphertext rejected | PASS | PASS |
| 7d | Replay/duplicate application message rejected | PASS | PASS |
| 7e | Stale epoch ciphertext rejected after self-update | PASS | PASS |
| 8 | Persist + reload continues encrypt/decrypt | PASS | PASS |

Host tests: `cargo test --locked --offline` — **8/8** each crate.

RFC 9420 authentication was not altered. No custom MAC/KDF/ratchet was added.

## Native size (API 26, NDK r28c)

`file(1)` reports “not stripped” because Cargo `strip = "debuginfo"` leaves symbols. Fully stripped sizes are `llvm-strip --strip-unneeded` copies; packaged `jniLibs` were not rewritten.

| ABI | OpenMLS packaged | OpenMLS stripped | mls-rs packaged | mls-rs stripped |
| --- | ---: | ---: | ---: | ---: |
| arm64-v8a | 631,464 | 392,128 | 524,904 | 337,392 |
| x86_64 | 581,144 | 422,824 | 493,304 | 366,568 |

armeabi-v7a was **not** in this shootout’s ABI list. 16 KiB compatibility is ELF LOAD alignment, not a boot test on a 16 KiB device.

## Protocol sizes (host, two-member group)

Plaintext `"Hello ArmorChat"` is 15 bytes. Overhead = ciphertext − 15.

| Metric | OpenMLS | mls-rs |
| --- | ---: | ---: |
| KeyPackage (MLS message bytes) | 308 | 310 |
| Initial handshake (Welcome) | 843 | 776 |
| Application ciphertext | 160 | 190 |
| Application overhead | 145 | 175 |
| Group/session state A | ~10,486 | 1,362 |
| Group/session state B | ~8,253 | 1,361 |

OpenMLS storage is a dump of the in-memory `StorageProvider` KV map (group plus signer/key-package rows). mls-rs storage is the explicit `write_to_storage` group blob. That is why OpenMLS state is ~8× larger and why mls-rs matches ArmorChat’s existing single-blob wrap more closely.

## Timings (host x86_64, release, microseconds)

Debug timings are larger and not used for the comparison.

| Step | OpenMLS | mls-rs |
| --- | ---: | ---: |
| Create pair of devices | 1,159 | 1,710 |
| Create group + add + Welcome/join | 2,738 | 1,898 |
| Encrypt | 100 | 214 |
| Decrypt | 138 | 130 |

These are not Android device timings. Both are well inside interactive budgets on the host.

## Licenses (exact locked graphs)

**No GPL / AGPL / SSPL in either graph.**

### OpenMLS (`cargo metadata` resolve: 221 packages)

| License family | Count (approx.) |
| --- | ---: |
| MIT and/or Apache-2.0 | 211 |
| BSD-3-Clause / BSD-2-Clause combinations | 9 |
| ISC | 1 |
| **MPL-2.0** | **4** (`hpke-rs`, `hpke-rs-crypto`, `hpke-rs-rust-crypto`, `hpke-rs-libcrux` 0.7.0) |
| MIT OR Apache-2.0 OR LGPL-2.1-or-later | 1 (`r-efi` — MIT/Apache may be chosen) |

`openmls_rust_crypto` 0.6.0 enables **`hpke-rs` feature `experimental`**. Host `cargo tree` uses `hpke-rs` + rustcrypto backend (MPL). `hpke-rs-libcrux` is in the lockfile/metadata graph and is not printed by host `cargo tree -i`. The tree also pulls `libcrux-sha3` / `ml-kem` / `ml-dsa` / `x-wing` / `k256` as HPKE-adjacent crates even though the spike ciphersuite is X25519+AES-GCM.

MPL-2.0 is file-level copyleft. It is not AGPL, but it is **outside** the replacement review’s stated permissive set (Apache-2.0, MIT, BSD, BC Licence) and would require MPL source notices for those files if OpenMLS were shipped.

### mls-rs (`cargo metadata` resolve: 142 packages)

Apache-2.0 and/or MIT, BSD-3-Clause, Unlicense, Unicode-3.0, BSL-1.0 dual, LLVM-exception. **0 MPL. 0 LGPL.** HPKE is `mls-rs-crypto-hpke` 0.21.0 (Apache-2.0 OR MIT). AWS-LC / OpenSSL were not enabled. Host `cargo tree` does not activate `mls-rs-identity-x509`; `p384` is still pulled by `mls-rs-crypto-rustcrypto`.

## Comparison used for the choice

| Criterion | OpenMLS 0.9.0 | mls-rs 0.56.0 | Winner |
| --- | --- | --- | --- |
| Security maturity | SRLabs audit (STA, 2026): 8 issues, 1 High; remediations in 0.8.1 / 0.7.3. Pin 0.9.0. Broader production use (Phoenix; Wire uses a GPL fork — we would use upstream). **Counterweight:** rustcrypto backend turns on experimental `hpke-rs`. | AWS Labs, RFC 9420, cargo-audit CI only. **No third-party implementation audit.** Cleaner HPKE crate (`mls-rs-crypto-hpke`), no experimental feature flag. | OpenMLS on audit pedigree; mls-rs on HPKE backend hygiene. **Not decisive alone.** |
| Android reliability | CI builds Android triples. Spike `.so` API 26 + 16 KiB proven here. Larger binary. | Android not a first-class CI matrix. Spike `.so` API 26 + 16 KiB proven here. Smaller binary. | Tie for this spike. |
| Maintainability | Larger API, 221-package graph, KV `StorageProvider`, MSRV 1.91. | Smaller API for group-of-two, 142-package graph, UniFFI exists if later wanted, faster crate churn. | mls-rs |
| Persistence | Must snapshot many storage keys; ~10 KiB; `MlsGroup::load`. Crash-atomicity with outbox still needs a provider design. | Explicit `write_to_storage` + `Client::load_group`; ~1.4 KiB blob maps onto existing Keystore-wrapped session files. | mls-rs |
| Dependency / licensing risk | MIT crate, but **MPL-2.0 hpke-rs** + experimental feature + extra PQ/libcrux-adjacent crates. 2026 libcrux/hpke advisories were a reason the replacement review forbade the libcrux **backend**; the rustcrypto path still depends on hpke-rs. | Apache-2.0 OR MIT graph. No MPL. No AWS-LC. Residual: unused `p384` from the rustcrypto provider. | mls-rs |
| Integration complexity | Hand JNI; storage trait is the hard part; more types to wrap. | Hand JNI (same class as 1D.1) or UniFFI later; one group blob. | mls-rs |

The choice is **not** “which harness was shorter.” OpenMLS’s audit is real. It does not outweigh experimental HPKE, MPL copyleft in the required crypto backend, a KV store that does not match ArmorChat’s wrap, and a substantially larger supply chain — given that mls-rs already proved the same RFC 9420 A↔B, identity binding, negatives, and Android packaging.

**C** would apply if a candidate could only pass by truncating tags, reusing identity Ed25519 as HPKE, writing a ratchet, or dropping pin binding. That did not happen.

## Security-model check (no weakening)

| Approved rule | Spike behavior |
| --- | --- |
| ≥128-bit stable authentication | AES-128-GCM (RFC 9420 MTI). Not Olm’s 64-bit HMAC. |
| No ArmorChat-written ratchet/KDF/MAC | Library `encrypt` / `process` / Commit / Welcome only. |
| No Ed25519→ECDH | Separate MLS signature + HPKE keys. |
| Identity signs device/leaf material | Outer Ed25519 over JCS KeyPackage binding. |
| Session wrap ≠ identity KEK | Distinct AAD and data key. |
| Reject unauthenticated input | Wrong pin, mutated binding, bit-flipped ciphertext, replay, stale epoch all fail closed. |

## What this does **not** authorize

- Enabling `CryptoEngine` or loading `libarmorchat_mlsrs.so` / `libarmorchat_openmls.so` from chat UI.
- Claiming device-restart or Keystore-hardware proof (host AEAD wrap only; 1D.1 vodozemac still owns the instrumented restart script).
- A Delivery Service. Welcome/Commit still need mailbox ordering later.
- Post-quantum MLS, X.509 credentials, or AWS-LC.
- Interop with OpenMLS wire format as a shipping requirement (both are RFC 9420; this spike did not cross-test them).

## Remaining gates before production (mls-rs path)

1. Third-party or at least an ArmorChat-scoped adapter review of `mls-rs` 0.56.0 + this binding (upstream has no SRLabs-class audit).
2. Production JNI/UniFFI surface, panic-to-typed-error, zeroization, and R8 packaging.
3. Random-nonce Keystore wrap of `write_to_storage` blobs; atomic commit with outbox; process-death test on device.
4. Disable unused rustcrypto curves if practical; re-inventory licenses after any feature change.
5. Epoch update policy for PCR (group-of-two still needs honest Commits).
6. Rewrite [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md) session sections from Olm to MLS only after (1)–(3).
