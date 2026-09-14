# Olm 64-bit MAC security gate — Phase 1D.1

**Decision: C — not acceptable. Reopen the messaging crypto/library choice.**

Date: 2026-09-06. Scope: vodozemac **0.10.0** `SessionConfig::version_1()` as selected in
[MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md). Companion:
[THREAT_MODEL.md](THREAT_MODEL.md).

This review does **not** extend the MAC, enable experimental session v2, or add an
application authentication layer. No relay was implemented. Nothing was pushed.

## 1. Exact construction (pinned vodozemac 0.10.0)

ArmorChat sessions call `SessionConfig::version_1()`. In this crate that is the
default and the only stable session MAC mode. `version_2()` (full MAC) exists
behind the `experimental-session-config` feature, which is **off** in ArmorChat
(`vodozemac = { version = "=0.10.0", default-features = false }`).

Sources under the Cargo registry copy
`vodozemac-0.10.0` (checksum locked by `Cargo.lock`):

| Item | Value | Source |
| --- | --- | --- |
| Session mode | `Version::V1` | `src/olm/session_config.rs` `version_1()` |
| Encrypt path | `encrypt_truncated_mac` | `src/olm/session/mod.rs` `encrypt()` match `Version::V1`; `src/olm/session/double_ratchet.rs` |
| Decrypt path | `decrypt_truncated_mac` | `src/olm/session/receiver_chain.rs` `FoundMessageKey::decrypt` |
| Symmetric cipher | AES-256-CBC, PKCS#7 padding | `src/cipher/mod.rs` `Cipher::encrypt` / `decrypt` |
| MAC algorithm | HMAC-SHA256 | `src/cipher/mod.rs` `type HmacSha256 = Hmac<Sha256>` |
| HMAC key | 32 bytes (Olm spec, not SHA-256 block size) | `get_hmac()` comment; `CipherKeys::mac_key` |
| Key derivation | HKDF-SHA256, salt `[0]`, info `OLM_KEYS`, 80-byte expand | `src/cipher/key.rs` `ExpandedKeys` |
| HKDF split | AES-256 key (32) \|\| HMAC key (32) \|\| AES-CBC IV (16) | `CipherKeys::from_expanded_keys` |
| Full HMAC output | 32 bytes (`Mac::LENGTH`) | `src/cipher/mod.rs` |
| **Truncated tag** | **8 bytes / 64 bits** (`Mac::TRUNCATED_LEN`) | `src/cipher/mod.rs` |
| Truncation | leftmost 8 bytes of the HMAC | `Mac::truncate`; verify via `hmac.verify_truncated_left` |
| Wire version byte | `3` (`MAC_TRUNCATED_VERSION`); v2 full-MAC uses `4` | `src/olm/messages/message.rs` |
| MAC input | version byte \|\| protobuf payload (ratchet key, chain index, ciphertext). The tag is **not** included in the MAC input | `Message::to_mac_bytes` / `encode` |
| Order | encrypt, then MAC (encrypt-then-MAC) | `MessageKey::encrypt_truncated_mac` |
| Per-ciphertext verifies | one HMAC verify against the message key for the claimed chain index | `ReceiverChain` / `FoundMessageKey` |

`version_1()` documentation (upstream): “AES-256 and HMAC with a truncated MAC
… truncated to 8 bytes.” Full 32-byte HMAC is `version_2()` only, experimental.

This is **not** AES-GCM, ChaCha20-Poly1305, or a 128-bit AEAD tag. The 64-bit
value is the sole ciphertext authenticator for each Olm message.

## 2. Forgery model

Assume HMAC-SHA256 is a PRF and that no attack on the truncated tag beats
guessing. Then each online verification of an attacker-chosen ciphertext/tag
succeeds with

\[
p = 2^{-64} \approx 5.42 \times 10^{-20}.
\]

After \(n\) independent attempts,

\[
P(\text{at least one accept}) = 1-(1-2^{-64})^{n} \approx n \cdot 2^{-64}
\quad (n \ll 2^{64}).
\]

Expected attempts for one success: \(2^{64} \approx 1.84 \times 10^{19}\).

Verification is **online**: the attacker needs the victim device (or any holder
of the message key) to call `verify_truncated_mac`. Offline guessing of the
32-byte HMAC key is not the 64-bit problem.

**Conservative authenticity failure:** a truncated-MAC accept is a break, even
if the decrypted bytes are not useful JSON. A relay that captures a genuine
ciphertext can bit-flip CBC blocks and retry tags; encrypt-then-MAC is what
stops that, and here that MAC is 64 bits. This review **does not** spend extra
bits on PKCS#7, UTF-8, or inner JSON checks.

Skipped-key search does not multiply tag strength: vodozemac derives the key
for the claimed index and performs **one** truncated-MAC verify per decrypt
call (`MAX_MESSAGE_GAP` is 2000 in-tree; stored skipped keys cap at 40). Each
submitted envelope is still one attempt.

## 3. Attempt budgets

Protocol/threat-model facts used below, and whether they are proven:

| Control | Stated bound | Proven in this tree? |
| --- | --- | --- |
| Local invalid decrypts | 20 / contact / minute | **No.** Proposed. Not on the production decrypt path (messaging still disabled). |
| Local setup attempts | 5 / contact / hour | **No.** Proposed. |
| Envelope TTL | 7 days | **No** live relay. |
| Honest-relay mailbox queue | 1,000 envelopes or 10 MiB | **Proposed only.** A compromised relay can ignore it. |
| Relay rate limits | various 429s | **Untrusted.** Threat model: relay may flood, censor, replay. |
| On-device HMAC/decrypt rate | hardware-limited | **Not measured on the HMD CREST.** Table uses 10⁴–10⁶ verifies/s as a plausible phone range, not a bench. |

Rate limits can **reduce \(n\)**. They are not cryptographic authentication: a
forged tag that hits during the allowed window is still accepted.

Catch-up is the unproven hole: a device returning from offline may process a
leased batch without the 20/min spacing. That case is modeled as “unbounded
for the duration of catch-up / flood.”

## 4. Quantified \(P\) (64-bit vs 128-bit tag)

\(P_{128}\) uses the same \(n\) with \(2^{-128}\) for the required comparison.

| Scenario | Attempts \(n\) | \(P_{64}\) | \(P_{128}\) |
| --- | --- | --- | --- |
| Single verification | 1 | \(5.4 \times 10^{-20}\) | \(2.9 \times 10^{-39}\) |
| **Per session / per contact**, 20/min enforced, 1 day | \(2.88 \times 10^{4}\) | \(1.6 \times 10^{-15}\) | \(8.5 \times 10^{-35}\) |
| Per contact, 20/min, 7-day TTL | \(2.02 \times 10^{5}\) | \(1.1 \times 10^{-14}\) | \(5.9 \times 10^{-34}\) |
| Per contact, 20/min, 1 year | \(1.05 \times 10^{7}\) | \(5.7 \times 10^{-13}\) | \(3.1 \times 10^{-32}\) |
| Per contact, 20/min, 10 years | \(1.05 \times 10^{8}\) | \(5.7 \times 10^{-12}\) | \(3.1 \times 10^{-31}\) |
| **Per device**, 50 contacts, 20/min, 1 year | \(5.26 \times 10^{8}\) | \(2.9 \times 10^{-11}\) | \(1.5 \times 10^{-30}\) |
| Per device, 500 contacts, 20/min, 1 year | \(5.26 \times 10^{9}\) | \(2.9 \times 10^{-10}\) | \(1.5 \times 10^{-29}\) |
| Honest-relay 1,000-envelope catch-up (one shot) | \(10^{3}\) | \(5.4 \times 10^{-17}\) | \(2.9 \times 10^{-36}\) |
| Adversarial relay, **no** local cap, \(10^{4}\)/s for 1 hour | \(3.6 \times 10^{7}\) | \(2.0 \times 10^{-12}\) | \(1.1 \times 10^{-31}\) |
| Same, \(10^{5}\)/s for 24 hours | \(8.64 \times 10^{9}\) | \(4.7 \times 10^{-10}\) | \(2.5 \times 10^{-29}\) |
| Same, \(10^{6}\)/s for 24 hours | \(8.64 \times 10^{10}\) | \(4.7 \times 10^{-9}\) | \(2.5 \times 10^{-28}\) |
| Same, \(10^{6}\)/s for 1 year | \(3.16 \times 10^{13}\) | \(1.7 \times 10^{-6}\) | \(9.3 \times 10^{-26}\) |
| **Aggregate**, \(10^{5}\) users × 50 contacts × 20/min × 1 year | \(5.26 \times 10^{13}\) | \(2.9 \times 10^{-6}\) | \(1.5 \times 10^{-25}\) |
| Aggregate, \(10^{6}\) users × 50 contacts × 20/min × 1 year | \(5.26 \times 10^{14}\) | \(2.9 \times 10^{-5}\) | \(1.5 \times 10^{-24}\) |
| Aggregate, \(10^{6}\) users × 100 contacts × 20/min × 10 years | \(1.05 \times 10^{16}\) | \(5.7 \times 10^{-4}\) | \(3.1 \times 10^{-23}\) |

“Per session” matches “per contact” here: one verification is one shot at
whatever message key the ciphertext names. Extra sessions (protocol allows two
per device pair) increase \(n\) only if the attacker can present attempts to
each.

## 5. Adversarial / compromised relay

The threat model already treats the relay as able to inject, replay, and ignore
its own 429s. Under that attacker:

1. Every mailbox the relay hosts is a submission oracle for truncated-MAC
   guesses, including modified copies of captured ciphertext.
2. Honest-relay caps (1,000 envelopes, 10 MiB) **do not bind**.
3. Local 20/min **would** bind \(n\) if and only if every decrypt path — UI
   worker, catch-up, prekey setup, receipt decrypt — shares a fail-closed
   counter that cannot be reset by process death in a way that sheds the
   budget. That code is not present.
4. A fleet-wide relay compromise is one attacker with \(n\) equal to the
   **sum** of all victims’ verification budgets. That is the aggregate rows
   above. Deployment size is unknown, so those rows cannot be dismissed.

Rate limits therefore do **not** meaningfully reduce 64-bit risk to a 128-bit
tag. At best they make a *single* quietly attacked contact look rare
(\(\sim 10^{-12}\) per decade **if** 20/min is perfect). They do not meet the
128-bit target, do not cover catch-up, and do not stay small at fleet scale
under a compromised relay.

## 6. Comparison to a 128-bit tag

| | 64-bit truncated HMAC (v1) | 128-bit tag target |
| --- | --- | --- |
| Bits of online forgery resistance | 64 | 128 |
| Ratio | \(2^{64}\) weaker | baseline |
| \(n\) for \(P \approx 2^{-32}\) | \(2^{32} \approx 4 \times 10^{9}\) | \(2^{96}\) (beyond realistic online \(n\)) |
| Typical modern E2EE / AEAD tags | this Olm v1 mode | AES-GCM, ChaCha20-Poly1305, Signal |
| Upstream full HMAC in vodozemac 0.10.0 | not this mode | `version_2()` is 32-byte HMAC (256-bit), **experimental** |

No operating bound in the protocol converts a 64-bit tag into a 128-bit tag.
Closing a \(2^{64}\) gap would require a longer tag from the library, not
tighter 429s.

## 7. Decision

**C. Not acceptable — reopen the messaging crypto/library choice.**

Not A: operating bounds that would make a single-contact \(P\) look small are
unproven (unimplemented local limiter, untrusted relay, unknown fleet size,
unmeasured on-device verify rate).

Not B: additional protocol constraints that still use this 64-bit tag are
rate-limit / queue policy. This review is forbidden from treating those as
authentication, and they cannot meet the 128-bit tag target. Inventing a second
MAC or widening the truncated tag locally is also forbidden.

**Consequences until a replacement is chosen:**

- Do not enable `CryptoEngine` / real messaging on `SessionConfig::version_1()`.
- Do not silently turn on `experimental-session-config` / `version_2()`.
- Do not append ArmorChat HMAC/AEAD around Olm ciphertext to “fix” the tag.
- Reopen options that stay within the original rule (no custom ratchet/MAC):
  1. A **stable** vodozemac session config with a full-length MAC, if and when
     upstream ships it without the experimental flag; both ends must use it.
  2. Another Apache-2.0 library whose message authenticator is ≥128 bits, with
     a fresh protocol/threat review.
  3. A separately decided licensing change if a 128-bit-tag library is only
     available under a license this project currently excludes (libsignal
     remains AGPL-3.0 and is not a drop-in).

Phase 1D.1 JNI/session feasibility and device persistence tests are unrelated
to this gate. They do not authorize production E2EE under a 64-bit MAC.
