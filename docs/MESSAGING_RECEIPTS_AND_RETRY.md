# ArmorChat Phase 1D.4 — authenticated receipts, retry, and offline delivery

**Status:** test-only harness on mls-rs 0.56.0 and the CodeYantra Privacy Relay.  
**Not production E2EE.** Production chat send and `CryptoEngine` stay disabled.  
**Date:** 2026-09-06.

Read with [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md), [RELAY_ARCHITECTURE.md](RELAY_ARCHITECTURE.md), [RELAY_IMPLEMENTATION.md](RELAY_IMPLEMENTATION.md), [THREAT_MODEL.md](THREAT_MODEL.md), [TWO_PHONE_MESSAGING_TEST.md](TWO_PHONE_MESSAGING_TEST.md).

## What this phase proves

Phone A encrypts an authenticated inner record (not raw UTF-8) with mls-rs and submits the exact ciphertext to the relay. Phone B may be offline. When B later fetches, decrypts, validates, and **durably accepts** the message, B encrypts a delivery receipt in the same MLS session. A advances **SENT → DELIVERED** only after that receipt decrypts and matches the original message id, session id, ciphertext digest, epoch, and participants.

If B then marks the message opened under the harness read policy, B sends an authenticated read receipt and A advances **DELIVERED → READ**. READ is a client-reported view event. It is not proof of human comprehension.

Relay `accepted` / `consumed` / `expired` never become DELIVERED or READ.

## What is not enabled

- Production composer (`NoOpMessageTransport`, `isAvailable = false`)
- FCM, WebSocket wake, attachments, calls, mesh, multi-member groups
- Trust-all TLS or cleartext HTTP
- Public ac1 directory

Debug Settings → “Two-phone MLS test” may show outbound status and a “mark opened” control. That is a test harness, not production send.

## Inner record (encrypted and authenticated by MLS)

Every application plaintext is strict canonical JSON, suite `ac-mls-v1`, kind `text` | `delivery` | `read`. Protected fields:

`v`, `suite`, `kind`, `message_id`, `session_id` (MLS `group_id`), sender/recipient `device_id` + `generation`, sender/recipient `identity_key`, `recipient_mailbox`, `created_at`, `expires_at`, `epoch` (MLS epoch at encrypt), `body`.

`text.body`: `text` (1–4096 UTF-8 bytes), `client_sequence`.

Receipt `body`: `target_message_id`, `target_session_id`, `target_ciphertext_sha256` (SHA-256 of **decoded library ciphertext**, not the outer envelope digest), `target_epoch`, `event_at`. Each receipt has its own `message_id`. No receipts-on-receipts.

After decrypt, inner fields are compared to the outer envelope and to the local pinned session. Mismatch rejects the record. This blocks wrong-message reuse, wrong-session reuse, stale-epoch confusion, and cross-contact substitution.

Delivery receipts are created in the same local commit as inbound accept. Read receipts are created only after that accepted text is marked opened and read receipts are enabled.

## Message states

Visible status is attempt state plus monotonic evidence:

| State | Meaning |
| --- | --- |
| QUEUED | Local outbox intent, including retry wait |
| ENCRYPTING | Preparing MLS ciphertext; no network success |
| SENDING | Committed ciphertext is being submitted |
| SENT | TLS relay acceptance of the **exact** envelope (`state=accepted`) |
| DELIVERED | Authenticated recipient delivery receipt for this message/session/digest |
| READ | Authenticated recipient read receipt after the harness marked the message opened |
| FAILED | Automatic retry exhausted or permanent local/protocol failure **before** known relay acceptance |

Normal evidence: QUEUED → ENCRYPTING → SENDING → SENT → DELIVERED → READ.

Transient submit failures return to QUEUED with `retryAt`. They do not write FAILED immediately. SENT never becomes FAILED because the recipient is offline. FAILED is not proof the recipient never received the ciphertext. A late valid receipt may still advance FAILED → DELIVERED or READ. Duplicate/older receipts never regress evidence. Relay consume/expiry is not DELIVERED.

## Retry / offline

- Transactional outbox: MLS snapshot + exact envelope + digest + `message_id` + `expires_at` commit together before the first submit.
- Retry resubmits the **same** canonical envelope. No re-encryption after ciphertext commit, and never after relay acceptance.
- Backoff: full jitter `U(0, min(300s, 2s * 2^n))` with `n=0` on the first retry. Max 20 automatic network attempts or TTL, whichever first.
- No-network and `SERVICE_SUSPENDED` do not burn attempts.
- Original `message_id` and `expires_at` are preserved.
- Reconnect fetches unconsumed leases and flushes receipt intents **before** new text.
- Duplicate relay envelopes with the same authenticated digest do not duplicate the local message. Same id / different digest is rejected.

## Tests

Host JVM (no native):

```
./gradlew :app:testDebugUnitTest \
  --tests com.codeyantra.armorchat.messaging.InnerRecordTest \
  --tests com.codeyantra.armorchat.messaging.MessageLifecycleTest \
  --tests com.codeyantra.armorchat.messaging.OutboxPolicyTest
```

JNI + local TLS relay (skipped unless `-PtwoPhoneTests`):

```
./gradlew :app:testDebugUnitTest -PtwoPhoneTests \
  --tests com.codeyantra.armorchat.messaging.ReceiptsAndRetryTest
```

Covers: offline A-send / B-fetch / delivery receipt; read receipt after open; exact-ciphertext retry; duplicate envelope; forged delivery/read; wrong message id / session / digest / epoch; stale-epoch ciphertext; duplicate receipt; receipt before durable accept; relay cannot set DELIVERED/READ; late receipt after FAILED.

## Diagnostics (safe)

Shown: local `message_id`, MLS epoch, relay `accepted`/`fetched`, outbound status.  
Never shown/logged: identity private keys, MLS HPKE secrets, owner/submit tokens, plaintext on the relay.

## Limits of this harness

The outbox/inbox here is an in-memory transactional store for the test peer, not Room chat history. Production still binds `UnimplementedCryptoEngine` and `NoOpMessageTransport`. mls-rs 0.56.0 and the Phase 1D.2 relay wire contract are unchanged: receipts are ordinary `application` envelopes.
