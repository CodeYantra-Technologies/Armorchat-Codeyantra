# CodeYantra Privacy Relay — Phase 1D.2 implementation

**Status:** local TLS-only relay foundation. Not production messaging.  
**Date:** 2026-09-06.  
**Crypto foundation:** mls-rs 0.56.0 (RFC 9420). The relay does **not** run MLS; it queues opaque ciphertext and KeyPackage bindings.

Read with [RELAY_ARCHITECTURE.md](RELAY_ARCHITECTURE.md), [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md), [THREAT_MODEL.md](THREAT_MODEL.md), [MLS_SHOOTOUT.md](MLS_SHOOTOUT.md).

## What this phase implements

A standalone HTTPS service in `android/relay/` plus Android types behind `MessageTransport`. FCM is a generic wake hint only (`type=sync_hint`, `v=1`); it is not message transport. WebSocket, attachments, groups, calls, and mesh remain out of scope.

| Requirement | Implementation |
| --- | --- |
| Mailbox registration | `POST /v1/mailboxes` — random 32-byte `mailbox_id`, separate 32-byte owner token and submit capability. No ac1, no identity roster. |
| KeyPackage distribution | `PUT /v1/key-packages` (owner) and `POST /v1/key-packages/claim` (submit). Aliases: `/v1/prekeys`, `/v1/prekeys/claim`. Bindings are opaque; pin verification stays on the client. |
| Envelope submit | `POST /v1/envelopes` after durable write. Suite `ac-mls-v1`. |
| Fetch / lease / consume | `POST /v1/inbox/fetch` leases 60s and does not delete. `POST /v1/inbox/consume` deletes ciphertext. |
| SENT | HTTP 201/200 acceptance `state=accepted` only after the exact body is on disk. |
| Duplicate protection | Unique `(mailbox_id, message_id)`. Same digest → original acceptance. Different digest → `409 DIGEST_MISMATCH`. |
| TTL | Default 7 days. Sweep on each request. Tombstones kept expiry + 7 days + 5 minutes. |
| Suspension | `store.setSuspended(true)` or `RELAY_SUSPENDED=1`. All ingress/fetch return TLS `503 SERVICE_SUSPENDED`. No key/identity wipe. Queued ciphertext keeps original `expires_at`. Cleanup still runs. |
| DELIVERED / READ | Never emitted. Status is only `accepted` / `consumed` / `expired` / `unknown`. |

## Trust boundary

The relay is a ciphertext queue. It:

- stores HMAC-SHA256 token hashes (pepper in `data/keys/`, not in the queue tree)
- stores opaque MLS ciphertext and opaque KeyPackage binding strings
- records mailbox IDs, message IDs, SHA-256 of the canonical envelope, timestamps, leases

It does **not**:

- hold MLS or ArmorChat private keys
- verify Ed25519 identity signatures or pins
- become a contact directory
- generate delivery or read receipts

Identity binding remains `ArmorChat/mls-keypackage/v1` on the client ([MLS_SHOOTOUT.md](MLS_SHOOTOUT.md)).

## Wire contract (v1)

TLS 1.2+ only. `application/json` with RFC 8785 JCS bodies (no whitespace). Request cap **48 KiB** counted from the stream. Tokens only in `Authorization: Bearer …`, never in URLs or logs.

Envelope:

| Field | Rule |
| --- | --- |
| `protocol_version` | Integer `1` |
| `suite` | `ac-mls-v1` |
| `message_id` | 16 bytes, unpadded Base64URL (22 chars) |
| `recipient_mailbox` | 32 bytes, unpadded Base64URL (43 chars) |
| `expires_at` | Integer UTC seconds, first accept: now < exp ≤ now+7d |
| `payload_type` | `handshake` (Welcome/Commit) or `application` |
| `encrypted_payload` | Canonical Base64URL, 1–24 KiB decoded |
| `sender_credential` | Required object for `handshake`, forbidden for `application`. Opaque to the relay. |

`Idempotency-Key` must equal `message_id`. `envelope_sha256` is SHA-256 of the canonical UTF-8 body, unpadded Base64URL.

`payload_type` maps the architecture’s Olm `prekey`/`normal` onto MLS handshake/application. That is a suite change, not a new authenticator.

Acceptance:

```json
{"accepted_at":1,"envelope_sha256":"…","expires_at":1,"message_id":"…","protocol_version":1,"recipient_mailbox":"…","state":"accepted"}
```

## Android boundary

`MessageTransport.submitEnvelope` / `fetchInbox` / `consumeInbox` default to unavailable.

`CodeYantraRelayTransport` talks HTTPS only, refuses `http://`, and keeps `isAvailable = false`. `send(OutboundEnvelope)` stays unavailable so the local `conversationId` cannot be serialized onto the network.

`TransportBindModule` still binds `NoOpMessageTransport`. `INTERNET` is not added to the manifest.

## Running

```sh
# targeted server tests
sh android/scripts/test-relay.sh

# process
RELAY_DATA_DIR=./data RELAY_TLS_CERT=cert.pem RELAY_TLS_KEY=key.pem \
  RELAY_LISTEN=127.0.0.1:8443 node android/relay/src/cli.mjs
```

The CLI refuses to start without TLS files.

## Tests

`android/relay/test/relay.test.mjs` covers mailbox creation, unauthorized submit/fetch, duplicate retry, digest mismatch, fetch lease, consume, TTL expiry, malformed envelope, oversized payload, service suspension, and the absence of delivered/read states. A restart test reopens the same data directory and decrypts nothing — it only re-fetches the stored ciphertext.

Android unit tests cover JCS parity with the relay golden vector, HTTPS-only origin, queue-state enum, and that both NoOp and the unbound relay client leave chat send disabled.

## Limits (initial)

- 5 mailboxes / IP / hour (tests raise this)
- 60 submits / minute / submit capability, burst 20
- 10 KeyPackage claims / hour / capability
- 1000 queued envelopes or 10 MiB per mailbox
- Fetch: 10 envelopes, 512 KiB
- KeyPackage binding ≤ 8 KiB; sender_credential ≤ 8 KiB

## Not in this phase

WebSocket wake hints, attachments, calls, mesh, groups, production `CryptoEngine`, and adding INTERNET to the release APK. FCM wake is implemented: owner `POST /v1/push/register` and `/v1/push/unregister`, encrypted mailbox/device token mapping, async data-only `sync_hint` after durable envelope accept. Push failure never fails SENT. DELIVERED/READ remain authenticated device receipts.
