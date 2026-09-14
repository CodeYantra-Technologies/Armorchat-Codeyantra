# ArmorChat messaging threat model — Phase 1D.0

Status: **proposed architecture; protections are not yet implemented**.
Decision date: 2026-09-05. Scope: single Android device, mutually pinned contacts,
one-to-one text via CodeYantra relay. Companion contracts:
[Messaging protocol](MESSAGING_PROTOCOL.md), [Relay architecture](RELAY_ARCHITECTURE.md).

## 1. Assets and trust boundaries

Protect text, delivery/read contents, long-term signing keys, device/account
private keys, ratchet state, local history, trust pins, routing capabilities and
backup secrets. Availability, traffic metadata and read timing are separate
properties and cannot be fully hidden by E2EE.

Trusted: honest endpoint software while running, Android OS/Keystore and secure
randomness, vetted crypto-library behavior and authenticated out-of-band contact
verification. A QR-to-ac1 match verifies key consistency, not a person's identity
or private-key possession. Protocol setup proves possession through the pinned
credential and session; users still decide whom to trust.

Untrusted: Internet, relay API responses, relay operators/queue storage, push
provider data and every incoming byte. A previously verified contact may also
send malformed messages, lie about reading or abuse capability access. TLS and
service authentication do not promote the relay to a contact identity authority.

Flow: local protected intent → library ciphertext → TLS relay queue → recipient
library verification → transactional protected local acceptance → E2EE receipt.
Crossing each boundary requires bounded parsing and the verification described
in the protocol. Future attachments, groups and calls need new threat review.

## 2. Threats and residual exposure

| Threat | Proposed mitigation | Residual risk / explicit limit |
| --- | --- | --- |
| Compromised/untrusted relay | E2EE via established library; peer-pinned signed device/prekey binding; inner participant/context checks; receipts through the session. | Can censor, reorder, replay, equivocate on old credentials, lie about storage, learn metadata and deny service. Cannot guarantee availability or deletion. |
| Network observer/interception | Authenticated TLS protects transport; E2EE survives relay/TLS termination compromise. | IPs, timing, volume and connection endpoints remain visible; no anonymity promise. |
| Message modification/injection | Library authentication plus inner/outer binding; reject wrong participant, session, mailbox, ID, suite or expiry. | **Olm v1 closed (C).** Spike chose **mls-rs 0.56.0** (RFC 9420, 128-bit AES-GCM) for the next path; OpenMLS 0.9.0 also passed locally. See [MLS shootout](MLS_SHOOTOUT.md), [crypto replacement](MESSAGING_CRYPTO_REPLACEMENT.md), and [Olm MAC gate](OLM_MAC_SECURITY.md). `CryptoEngine` is still disabled — there is no production message authenticator yet. Denial of service remains possible. |
| Replay / duplicated delivery | Ratchet replay checks, persistent authenticated digest/ID ledger, idempotent queue/consume and retired-session tombstones. | Loss/rollback of full local state breaks freshness assumptions; timestamps are insufficient. |
| Stolen relay database/snapshot | Only E2EE payloads; scoped hashed credentials; disk encryption and short retention as defense in depth. | Mailbox associations, sizes, times and public key bundles remain sensitive. Stolen service keys can expose metadata, not independently decrypt E2EE text. |
| Malformed envelopes | Size/depth limits before crypto/allocation, strict parsing, no decompression, bounded native operations, fuzzing and typed errors. | Parser/JNI/library vulnerabilities are possible; no claim that Rust removes all boundary risk. |
| Contact identity/session-key substitution | Compare full saved Ed25519 key; signed device chain and signed prekeys; bind both peers inside ciphertext. | An attacker controlling initial QR exchange can substitute a different person; compromised identity key can authorize attacker devices. |
| Old signed device/route replay | Persist generation/revision high-water marks; bounded validity; new device requires explicit approval. | First-use/offline clients cannot prove latest revocation from a malicious directory. Fresh independent invite/reverification needed. |
| Stolen/lost locked device | Keystore-sealed secrets, protected message blobs, Android backup exclusion; identity-only encrypted backup. | Hardware/OS quality and password strength matter; an unlocked app can reveal plaintext. Loss of session keys makes queued ciphertext unrecoverable. |
| Weak/stolen identity backup | Existing password KDF + authenticated encryption; never upload backup to relay. | Offline password guessing; stolen decrypted identity can impersonate. Recovery does not restore contact pins or revoke lost devices automatically. |
| Push-provider visibility | Generic optional wake payload; no text/name/message IDs; fetch over relay. | Google sees installation/token and timing metadata; relay can correlate device mailboxes through a common token. |
| Malicious contact / receipt forgery | Receipt must authenticate in expected session and match exact outgoing target. No receipt-on-receipt loops. | Recipient can lie about their own behavior; READ is client report, not proof a human read or understood text. |
| Prekey depletion / spam | Per-contact capabilities, atomic claim, device-local/session limits, no fallback downgrade. | Authorized/malicious relay can exhaust keys or withhold supply. New sessions wait; availability tradeoff is explicit. |
| Session rollback / concurrent workers | Atomic sealed state+outbox/inbox, account/session serialization, CAS versions; no sessions in backups. | Keystore AEAD does not provide a trusted monotonic counter. Whole-filesystem rollback by privileged attacker may be undetectable. |
| Supply-chain / update compromise | Exact dependency locks, transitive-license review, native packaging tests, reviewed updates and release provenance. | Upstream audit is not an audit of ArmorChat, its bridge, app binding or future binaries. |

## 3. Claims and non-goals

Forward secrecy protects past ciphertext only when old relevant keys have been
erased. Locally retained decrypted history remains readable to someone who can
unlock its storage. Retained skipped keys and unused offline prekeys extend the
exposure window; storage retention is a security choice. Post-compromise recovery
requires attacker eviction and fresh honest ratchet inputs; a stolen identity
key, continuing endpoint access or manipulated new trust relationship is not
healed by ratcheting. The chosen design makes no post-quantum guarantee.

ArmorChat does **not** protect against:

- A compromised or unlocked endpoint while it can display/decrypt messages.
- Screenshots, screen recording, external cameras or a recipient sharing text.
- Malicious keyboards, accessibility malware, same-UID code or memory extraction.
- Compromised OS/root, malicious system updates or broken hardware/Keystore.
- Users voluntarily sharing messages, keys, backups or routing capabilities.
- Global traffic correlation, relay censorship, guaranteed background wake-up,
  or a push provider correlating installation activity.
- Guaranteed secure erasure on an adversarial relay, flash storage or snapshots.

No code or UI may claim “fully secure,” “anonymous,” “untraceable” or proof of
human reading based on this architecture. Service suspension preserves local
identity/history; it does not promise relay messages remain deliverable past TTL.

## 4. Metadata inventory and minimization

Network observer: client IP, relay destination, TLS traffic sizes/timing.
Relay: client IP, mailbox/capability relationships, setup identity/device public
keys, claim/fetch timing, ciphertext sizes and retention/consume state; optional
FCM token mapping. Recipient: sender's pinned identity, authenticated content,
message times and receipts as enabled. Push provider: generic event, app/token,
service timing and its normal device/network metadata. No actor is promised
less visibility than the protocol actually provides.

Per-contact random addresses prevent using ac1 as the public routing key, but
shared device keys and traffic can link addresses. Payload padding limits precise
length leakage only within fixed buckets. No contact name or plaintext enters
transport; opt-in local notification previews need a separate product decision.
Logs must redact sensitive fields even on parser/authentication failures.

## 5. Release-blocking questions and owners

| Owner (role) | Question / closure evidence |
| --- | --- |
| Security reviewer | **Olm MAC closed (C), 2026-09-06.** Replacement **open:** [crypto replacement](MESSAGING_CRYPTO_REPLACEMENT.md) recommends a spike of OpenMLS 0.9.0 and mls-rs 0.56.0. Do not lengthen Olm’s tag locally; do not ship production E2EE until a ≥128-bit library is selected. |
| Android/crypto maintainer | Can vodozemac 0.10.0 be safely packaged on minSdk 26 and every supported ABI, with bounded native state and no unsafe crypto reimplementation? Demonstrate release builds and independent adapter review. |
| Security + identity maintainer | Does the exact Tink/device credential/route/prekey framing resist key substitution, reflection and unknown-key-share? Produce cross-library signature vectors and adversarial setup tests. |
| Storage maintainer | Which established local AEAD mechanism and transaction design protects Room/WAL and commits account/session/outbox atomically? Fault-inject process death, full disk and migration failures. |
| Security + product | How is a recovered device approved and the lost device revoked without trusting relay freshness? Review explicit peer approval and user-facing recovery limits; do not promise global instant revocation. |
| Relay maintainer | Can queue durability, prekey uniqueness across mailboxes, lease replay and deletion/backup windows be demonstrated under failover? Test lost ACKs, replica loss and snapshot restore. |
| Android/product | Are seven-day retention, prekey exhaustion and no-FCM background delays acceptable? Test two devices across airplane mode, Doze, reboot and OEM restrictions. |
| Release maintainer | Are exact transitive licenses, upstream security advisories and native artifacts reviewed? Record SPDX/SBOM/notices before adopting dependencies. |

The proposal is sufficiently specific for feasibility tests and API/schema review;
these unresolved gates mean it is **not approved for production cryptographic
implementation**. Do not silently fill a library/API gap with custom crypto.

## 6. Required adversarial validation before real messaging

1. Replace relay-supplied keys, route origin, mailbox, intended peer and suite;
   no wrong-key plaintext acceptance, silent repin or token leak may occur.
2. Replay captured setup and normal envelopes with same/different IDs; duplicate
   rendering, second prekey consumption and ratchet rollback must not occur.
3. Reorder text and receipts, lose relay acceptance, drop consume response, kill
   process around every commit. Verify exact outbox bytes and monotonic evidence.
4. Mutate every field/length, fuzz Unicode and library packets, flood skipped-key
   gaps/prekey claims and simulate full disk. Remain bounded and fail closed.
5. Forge relay “delivered/read” responses. Only matching E2EE recipient receipts
   may change those states. Duplicate receipt must be idempotent.
6. Restore identity backup to a fresh device; old sessions/history remain absent,
   peer device approval is required, and old ciphertext is not falsely recoverable.
7. Suspend the relay with pending and accepted messages; identity/local data remain
   intact, deadlines persist, and recovery never silently resends expired text.
8. Inspect logs, queue snapshots, FCM payloads and network fields for content,
   names, keys and recovery data. Validate the documented metadata inventory.

No live tests are claimed in Phase 1D.0: this phase creates design documentation
only. The existing application remains at its disabled messaging foundation.
