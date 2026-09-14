# Contact QR v3 — compact invite + signed Contact Bundle

Status: **implemented locally** (Android client + in-tree relay).  
**Not deployed.** The production relay at `relay1.codeyantra.in` does not serve these endpoints until a later operator deploy.

This document is the security review required before the rendezvous protocol change.

## Why this change is necessary

Contact QR v2 encodes the full signed contact record in the visual QR:

- ArmorChat ID (`ac1:…`)
- Ed25519 public identity key
- relay origin
- mailbox ID
- mailbox **submit capability**
- device ID
- signed MLS KeyPackage binding
- issued/expiry timestamps
- Ed25519 signature

That payload is dense (typically well over 1 KiB, often several KiB once a real mls-rs KeyPackage is present). Phone cameras fail or retry. The same QR also **directly exposes a mailbox write capability** to anyone who photographs the screen.

v3 splits **rendezvous** from **authenticated identity**:

1. The visual QR carries only an opaque high-entropy invite token.
2. The scanner fetches a **signed Contact Bundle** from an authorized CodeYantra relay.
3. The client verifies the owner’s Ed25519 signature, recomputes `ac1` from `pk`, checks expiry/version/relay registry/MLS binding, then pins the key and bootstraps MLS.

The relay transports the bundle. It is **not** an identity authority.

## Compact QR format

```
armorchat://add/<token>
```

- `token` is 32 cryptographically random bytes, unpadded Base64URL, **exactly 43 characters**, canonical re-encode required.
- Entropy: 256 bits. Not derived from `ac1`, mailbox ID, owner token, or any key.
- No public key, mailbox, submit capability, KeyPackage, relay origin, or signature in the QR.
- Authorized relay origin is **not** encoded. The client looks the token up at its compiled authorized bootstrap relay. A QR screenshot therefore does not advertise a routing hostname beyond the already-public official relay.

Approximate payload size:

| Version | Typical payload | Visual density |
| --- | --- | --- |
| v2 Contact QR | ~1.5–8 KiB JSON (grows with KeyPackage) | Dense; unreliable camera scans |
| v3 compact invite | `armorchat://add/` + 43 chars ≈ **60 bytes** | Sparse; Version 1 QR, easy to scan |

## Invite token lifecycle (decision: reusable until expiry)

**Chosen: Option A — reusable until expiry, 48 hours.**

Option B (single-use) was rejected for normal sharing: a retry after a dropped network fetch, a second scan of the same screenshot, or sharing the QR with one person who opens it later would all fail for no security gain against a 256-bit token.

Rules:

- First `My ArmorChat` open creates a token and uploads the signed bundle. Reopening the screen **reuses** the token until it is within 60 seconds of expiry.
- **Refresh QR** issues a new token, revokes the previous invite, and re-signs a new bundle. ArmorChat identity, Ed25519 key, and mailbox **do not change**.
- Existing contacts are unaffected by expiry or refresh. Their pinned key and stored route remain.
- Opening My QR never allocates a new mailbox.
- Regenerating the invite never rotates the identity key.

After expiry, lookup and redeem fail with the same generic error as an unknown or revoked token (`INVITE_UNKNOWN`). Outstanding **bootstrap grants** issued for that invite expire or are revoked with it. Established contacts keep their **peer grants** and continue messaging.

## Handle model

Human-facing identifier, **not** a cryptographic identity.

Canonical form: lowercase `[a-z0-9]`, hyphen allowed, 3–30 characters, no leading/trailing hyphen, no consecutive hyphens, ASCII only (Unicode/confusable scripts rejected). Displayed as `@neeraj-veltrix`.

Reserved words (non-exhaustive): `armorchat`, `admin`, `support`, `official`, `veltrix`, `codeyantra`, `system`, `root`, `help`, `security`, `everyone`, `here`, `relay`, `invite`, `contact`, `mls`, `null`, `undefined`, `me`, `self`.

Display name is a separate UTF-16 field (max 100). Changing a handle does not change `ac1` or the Ed25519 key. Contacts continue to trust the **pinned public key**.

**Uniqueness (v1):** enforced only for the local profile (one handle per device identity). There is **no** global `@handle → ac1` directory, no occupancy API, and no handle search. Two contacts may show the same handle; they remain distinct if their public keys differ.

## Privacy / storage

| Data | Where | Notes |
| --- | --- | --- |
| Display name, handle | Local Room `identities` / `contacts` | Public metadata. Not a trust anchor. |
| `ac1` + Ed25519 public key | Local Room, signed bundle | Trust anchor. Recomputed from `pk` on import. |
| Ed25519 private key | Keystore-wrapped blob | Never in QR, bundle, or relay. |
| Invite token | Local `local_invites` row; HMAC-hashed at relay | Screenshot of QR leaks the token until expiry. |
| Signed Contact Bundle | Relay invite record (plaintext to the relay) | Relay can read handle, display name, `ac1`, `pk`, mailbox id, device id. No submit capability, no KeyPackage, no owner token. |
| Mailbox owner token | Keystore-wrapped session row | Never in QR or bundle. |

There is no enumerable handle list and no public user directory.

### What the relay learns

On invite create and lookup the relay sees:

- Client IP and timing
- Mailbox that owns the invite (create is owner-authenticated)
- The **signed identity bundle** (handle, display name, `ac1`, public key, mailbox ID, device ID, timestamps). Not a write credential and not a KeyPackage.
- Token HMAC (not the raw token at rest)

The relay cannot forge a bundle: the client verifies Ed25519 over `ArmorChat/contact-bundle-v3/v1` using the `pk` inside the bundle, then checks `ac1 = derive(pk)`.

Token lookup is **not** a user directory: the only query key is a 256-bit secret. Unknown/expired/revoked invites return the same `404 INVITE_UNKNOWN`.

### What a QR screenshot leaks

Leaked:

- The opaque invite token (until expiry or revoke)
- That the holder is an ArmorChat user

Not leaked by inspection of the QR:

- Private identity key
- Mailbox owner token
- MLS private state
- Recovery material
- Keystore secrets
- `ac1` / public key / handle (those are in the bundle, obtained only by presenting the token to the relay)

A party who **looks up** the token before expiry obtains the signed identity bundle (no write credential). A party who **redeems** it also receives one unused KeyPackage binding and a **bootstrap grant** that dies with the invite.

## Multi-peer KeyPackages

MLS KeyPackages are one-time HPKE init secrets (RFC 9420). The QR and signed bundle do **not** embed a KeyPackage. The owner publishes a pool (`PUT /v1/key-packages`, 16 by default). Each scanner calls `POST /v1/invites/redeem` with a unique `claim_id` and receives a **fresh** identity-signed KeyPackage. The same compact QR can be shown to A, B, and C during the validity window; each gets an independent 1:1 MLS session. Reusing one KeyPackage across peers is rejected. Pool exhaustion returns the same generic `INVITE_UNKNOWN` as a dead invite. The owner replenishes the pool when opening My ArmorChat.

## Submit capability model

The mailbox still has a long-lived `submit_capability` for legacy v2 / owner rotation. v3 **never** puts it in the QR or the signed bundle.

| Grant | Lifetime | Scope | How issued | Revocation |
| --- | --- | --- | --- | --- |
| Bootstrap | Invite TTL (≤ 48 h) | That mailbox, that redeem/`claim_id` | `POST /v1/invites/redeem` | Invite expiry/revoke; 20 submits/hour, burst 5 |
| Peer | ≤ 90 days | That mailbox, one contact | Owner `POST /v1/grants` after session setup; delivered inside MLS `setup_route` | `POST /v1/grants/revoke` or rotate; 60/min burst 20 |
| Legacy mailbox submit | Until rotated | Whole mailbox | Mailbox create | `POST /v1/mailboxes/rotate-submit` — does **not** revoke peer grants |

Renewal: the owner mints a new peer grant and sends it as an encrypted `setup_route`. Existing contacts keep working across QR refresh and across long-lived submit rotation. A leaked bootstrap grant is useless after invite expiry/revoke. A leaked peer grant can be rotated/revoked without changing ArmorChat identity. Relay still cannot decrypt MLS content. Mailbox queue caps (1000 envelopes / 10 MiB) remain.

## Replay, substitution, brute force

| Abuse | Mitigation |
| --- | --- |
| Token brute force | 256-bit CSPRNG; lookup rate-limited per IP |
| Expired invite | Client and relay reject `expires_at <= now` |
| Revoked / refreshed invite | Relay returns generic unknown; old QR dies |
| Bundle substitution | Ed25519 over canonical body; `invite_id = SHA-256(token bytes)` must match the scanned token |
| Tampered handle / name / pk / ac1 / route / mailbox | Signature fails or `ac1` recompute fails |
| Malicious relay | Authorized origin registry; signature not produced by the relay |
| Relay scraping | No list endpoint; HMAC lookup key; uniform 404 |
| QR photograph | Time-bounded token; no owner token / private keys |

## Identity remains independently verifiable

```
token → POST /v1/invites/lookup (identity bundle; no KeyPackage, no write grant)
     → POST /v1/invites/redeem {token, claim_id} (one-scan; same QR, fresh KP + bootstrap grant)
     → validate Ed25519(pk, ArmorChat/contact-bundle-v3/v1, body)
     → ac1' = ArmorChatId.derive(pk); require ac1' == bundle.id
     → validate expiry, version, suite, applies_to
     → require relay_origin ∈ authorized registry
     → require invite_id == SHA-256(token)
     → verify claimed KeyPackage binding under the same pk
     → pin pk (existing contact with same pk is reused; different pk is a conflict)
     → import mailbox + bootstrap grant + this peer's KeyPackage
     → RFC 9420 MLS welcome/join (independent group per peer)
     → peer grant delivered over MLS; bootstrap grant may expire
     → composer enabled only when session ESTABLISHED
```

Pinned Ed25519 key always wins over handle. Handle rename with the same key updates metadata and may record a local “Handle changed” audit. The same handle with a different key is a **different identity**.

## Relay API (local only — do not deploy)

All invite operations keep the token in the JSON body, never in the URL.

```
POST /v1/invites
Authorization: Bearer <mailbox owner token>
{ "protocol_version": 1, "token": "<b64url 32>", "bundle": "<canonical signed JSON>", "expires_at": <epoch seconds> }
→ 201 { protocol_version, state: "accepted" }

POST /v1/invites/lookup
{ "protocol_version": 1, "token": "<b64url 32>" }
→ 200 { protocol_version, bundle }  |  404 { error: "INVITE_UNKNOWN" }

POST /v1/invites/revoke
Authorization: Bearer <mailbox owner token>
{ "protocol_version": 1, "token": "<b64url 32>" }
→ 200 { protocol_version, state: "accepted" }

POST /v1/invites/redeem
{ "protocol_version": 1, "token": "<b64url 32>", "claim_id": "<b64url 16>" }
→ 201 { protocol_version, bundle, key_package_id, binding, bootstrap_grant }
  (200 on claim_id replay)

POST /v1/grants
Authorization: Bearer <mailbox owner token>
{ "protocol_version": 1, "kind": "peer", "expires_at": <epoch seconds> }
→ 201 { protocol_version, grant_id, kind, mailbox_id, token, expires_at }

POST /v1/grants/rotate | /v1/grants/revoke
POST /v1/mailboxes/rotate-submit
```

Limits: bundle ≤ 8 KiB; TTL ≤ 48 h; one active invite per mailbox (a new PUT replaces the previous); lookup 30/min/IP burst 10; redeem 10/min/IP burst 5; 32 redemptions/invite; bootstrap 20 submits/hour burst 5. Unknown/expired/revoked/exhausted all return `404 INVITE_UNKNOWN`.

EVSync is not touched. FCM, WebRTC, federation, community relays, and handle search are out of scope.

## Signed Contact Bundle schema

Canonical JSON, keys sorted (existing `CanonicalJson`).

Unsigned transport wrapper:

```json
{
  "contact_bundle": {
    "body": { ...signed fields... },
    "signature": "<unpadded Base64URL Ed25519>"
  },
  "p": "armorchat",
  "v": 3
}
```

Signed body fields (`ArmorChat/contact-bundle-v3/v1` + 0x00 + canonical body):

| Field | Role |
| --- | --- |
| `applies_to` | `armorchat-contact-bundle-v3` |
| `v` | `3` |
| `suite` | `ac-mls-v1` |
| `id` | ArmorChat ID; must equal `derive(pk)` |
| `pk` | unpadded Base64URL Ed25519 public key |
| `handle` | canonical handle without `@` (optional if unset) |
| `display_name` | human name (optional if unset) |
| `relay_origin` | authorized HTTPS origin |
| `mailbox_id` | inbound mailbox address (not a write credential) |
| `device_id` | device identifier |
| `invite_id` | SHA-256 of the 32 token bytes, unpadded Base64URL |
| `issued_at` | UTC epoch seconds |
| `expires_at` | UTC epoch seconds, ≤ issued_at + 48h |

Forbidden in QR and bundle: owner token, private keys, recovery material, Keystore blobs, MLS private snapshots.

## One-scan user flow

Phone B opens **My ArmorChat** → compact QR.  
Phone A **Add Contact** → **Scan ArmorChat QR** (one scan).

App: parse token → lookup bundle → verify → pin → save name/handle → import route → MLS welcome/join → **Securing chat…** → composer when `ESTABLISHED`.

No Connection QR in normal user mode. v1 Identity QR and v2 Contact QR remain parseable for compatibility / Developer Mode.
