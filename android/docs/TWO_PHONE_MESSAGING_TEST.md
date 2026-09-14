# ArmorChat Phase 1D.3 — two-phone encrypted messaging proof

**Status:** debug test build. Real chat send is enabled only after an automated MLS session.  
`CryptoEngine` remains unimplemented. The Two-phone MLS screen is behind a developer flag.  
**Date:** 2026-09-07.

Read with [MLS_SHOOTOUT.md](MLS_SHOOTOUT.md), [RELAY_IMPLEMENTATION.md](RELAY_IMPLEMENTATION.md), [RELAY_ARCHITECTURE.md](RELAY_ARCHITECTURE.md), [MESSAGING_PROTOCOL.md](MESSAGING_PROTOCOL.md).

## What this phase proves

Phone A encrypts `"Hello ArmorChat"` with mls-rs, submits an opaque envelope to the relay, Phone B fetches and decrypts the exact plaintext. B replies `"Hello from B"`; A decrypts exactly.

SENT is relay durable `state=accepted` only. DELIVERED and READ are not implemented.

## What is not enabled

- Release chat send (`NoOpMessageTransport`; `isAvailable` is false)
- FCM, WebSocket, attachments, calls, mesh, community/federation relays
- Trust-all TLS or cleartext HTTP
- Public ac1 directory
- Relay capabilities inside the public identity QR (`p=armorchat`)
- Hard-coded permanent global relay (Node-1 is bootstrap only)

## Pieces

| Piece | Role |
| --- | --- |
| `android/native/armorchat-mlsrs` JNI `transact` | RFC 9420 group-of-two. MLS HPKE/signature keys are library-generated. Snapshot does **not** store ArmorChat identity private keys. |
| ArmorChat Ed25519 | Signs `ArmorChat/mls-keypackage/v1` and `ArmorChat/route/v1` only. |
| CodeYantra Privacy Relay | Opaque KeyPackage bindings + ciphertext queue. No identity verification. |
| Debug screen | Settings → “Two-phone MLS test” in **debug** builds. Release has no INTERNET permission and no chat-send transport. |

## Routing invite (not identity QR)

Product `armorchat-route`. Signed body (JCS, context `ArmorChat/route/v1`):

`v`, `suite=ac-mls-v1`, `relay_origin` (https, no path), `mailbox_id`, `submit_capability`, `device_id`, `generation`, `recipient_identity_key` (intended peer pin), `revision`, `issued_at`, `expires_at` (≤ 7 days), `key_package_binding`.

Import checks: HTTPS origin, recipient pin is self, inviter pin matches the already-pinned contact, Ed25519 over the framed body. The existing identity QR parser still accepts only `p,v,id,pk`.

## TLS

The Android client uses the platform trust store. The relay CLI still requires TLS files. Devices must trust the relay certificate (public CA or a user-installed CA). There is no trust-all code in the app.

Host JVM tests pin **that test’s** generated certificate in the test OkHttp client only.

## Automated proof

```
./gradlew :app:testDebugUnitTest -PtwoPhoneTests \
  --tests com.codeyantra.armorchat.messaging.TwoPhoneMessagingTest
```

Covers: Hello ArmorChat + reply, persist/reload, duplicate envelope, tampered ciphertext, wrong pin/route, stale epoch, offline then fetch.

Without `-PtwoPhoneTests` the class is skipped (native library not on the path).

## Manual two-device steps (debug APK)

Normal testing no longer uses the Two-phone MLS paste screen.

1. Install the debug APK on both phones. The default bootstrap is Node-1 (`https://relay1.codeyantra.in`).
2. Create identities on A and B. Pin each other with the **identity** QR (`p=armorchat` only).
3. Open the chat. Each phone prepares a mailbox, KeyPackage, and signed route automatically.
4. On **one** phone, tap **Scan their connection QR** and scan the other phone’s connection QR (not the identity QR). Do not paste keys, routes, mailbox tokens, or KeyPackages.
5. The scanner initiates MLS. The other phone just needs the chat open (or reopen it) so it can fetch, join, and import the return route.
6. When the composer enables, send a message. SENT is relay `accepted` only. DELIVERED/READ require authenticated receipts.

Developer troubleshooting: Settings → enable **Two-phone MLS harness**, then open **Two-phone MLS test**. That screen still allows paste.

Force-stop both apps, reopen, fetch/send again. Session snapshots are stored per contact on device. Identity private keys are never written to the relay.

## Diagnostics (safe)

Shown: local `message_id`, MLS epoch, relay `accepted` / `fetched`.  
Never shown/logged: identity private keys, MLS HPKE secrets, owner/submit tokens, plaintext on the server.
