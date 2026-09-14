# CodeYantra Privacy Relay — Phase 1D.0

Status: **Phase 1D.2 server foundation implemented** — see [RELAY_IMPLEMENTATION.md](RELAY_IMPLEMENTATION.md). No FCM, WebSocket, or production chat-send enablement.
Decision date: 2026-09-05. Protocol and security dependencies:
[Messaging protocol](MESSAGING_PROTOCOL.md), [Threat model](THREAT_MODEL.md).
All endpoints, schemas and limits below are proposed contracts, not live services.

## 1. Trust and ownership

CodeYantra operates authentication/policy ingress, a durable ciphertext queue,
prekey distribution, delivery leases, abuse controls and optional wake-up dispatch.
Veltrix may implement the service security boundary; it receives no message
plaintext or E2EE private keys. Clients communicate over authenticated TLS (1.2
minimum, 1.3 preferred), validate hostnames with Android platform trust, disable
cleartext and never accept all certificates. Infrastructure key rotation must not
change contact trust. Certificate pinning is not assumed; operational pinning
would need backup pins and a reviewed rotation plan.

Queue acceptance is a service promise, not a cryptographic proof of storage.
A compromised relay can drop, delay, censor, correlate and lie about SENT. It
cannot normally forge the pinned recipient's E2EE delivery/read receipt.
Availability is explicitly separate from confidentiality and authenticity.

## 2. Addressing and bootstrap

Separate three namespaces:

| Identifier | Scope / visibility |
| --- | --- |
| ArmorChat ac1 + full Ed25519 key | Local contact trust; no public routing directory. |
| `mailbox_id` | 32 CSPRNG bytes, unpadded Base64URL (43 characters), per contact and receiving device. Visible to relay and that contact. |
| `device_id` / generation / session ID | Device ID is 16 random bytes; generation monotonically increases locally. Session ID belongs to library. Device/session identity stays in ciphertext except required signed setup credentials. |

Mailbox ID is an address, **not authentication**. Each mailbox has a separate
32-byte submit capability issued by relay and a separate 32-byte owner token for
fetch/manage/consume. Tokens use Authorization headers, never URLs, analytics,
crash logs or public QR. Store owner secrets Keystore-protected. Relay stores
keyed token hashes; bearer credential verifier keys stay outside the queue DB.
TLS theft/endpoint compromise may steal capabilities; they grant queue access,
not plaintext decryption. Rotate/revoke per-contact submit tokens independently.

Registration: client calls rate-limited HTTPS `POST /v1/mailboxes`; relay creates
random address and owner/submit tokens, returns once over TLS. No ac1 is needed
to register. IP and creation quotas prevent unbounded anonymous allocation;
this is pseudonymous capability authentication, not Sybil resistance. Owner-auth
requests publish credentials/prekeys, rotate submit tokens or bind optional push.
Prekey reads/claims require mailbox-specific submit capability. Relay can verify
self-consistency of a submitted credential, but only a peer's saved pin confers
contact authenticity. Limit mailbox creation to 5/IP/hour initially; NAT false
positives need operational review without collecting contact plaintext.

**Bootstrap is a separate explicit action.** Existing identity QR v1 has no
route, so it cannot by itself enable the first message. After mutual contact
pinning, each user shares a new signed route invitation with the intended peer
via an independently authenticated channel. This may be a separate QR or private
share sheet; possession grants submission rights. It is not safe to publish as a
public identity QR. No global hash-of-ac1 lookup is used: a deterministic hash
would retain correlation and dictionary lookup properties.

Route signed body (using messaging document signature framing): `v=1`, `suite`,
`relay_origin` (exact allowed HTTPS origin, no path/userinfo/query), `mailbox_id`,
`submit_capability`, `device_id`, `generation`, `recipient_identity_key`
(the intended inviter's peer), `revision`, `issued_at`, `expires_at`.
Attach inviter's identity-signed device credential and device signature.
Maximum lifetime seven days, no later than credential expiry. Import compares
inviter credential to saved pin and intended-recipient key to self. Only configured
CodeYantra origins are accepted in phase 1D; an invite cannot direct tokens or
traffic to an arbitrary host. Maximum invitation size 8 KiB. Store highest revision;
same revision with different body is a conflict. Renew via existing E2EE channel
in future or re-share before expiry; never infer a route from display name.

Receipts need the other peer's mailbox. Both directional invitations are a setup
prerequisite; return routing is stored locally, not taken from arbitrary message
fields. Rotating a mailbox keeps the old one fetchable for seven days to drain
accepted traffic; no automatic resubmission to a new mailbox under the same ID.
New route applies to newly encrypted messages. Credential/generation changes use
the explicit approval policy in the messaging document.

Per-contact mailboxes reduce easy contact-to-contact correlation but do not hide
relationships from CodeYantra: shared IPs, owner credentials, timing, common Olm
account public keys/prekey pools and FCM tokens can link them. Relay-side public
prekeys and setup credentials reveal cryptographic identity associations. This
is a metadata-minimizing design, not sealed-sender or anonymous routing.

## 3. Versioned wire envelope

HTTPS `application/json`, strict UTF-8. Required outer fields:

| Field | Rule |
| --- | --- |
| `protocol_version` | Integer `1`; unsupported version rejected, no downgrade. |
| `suite` | Exact string `ac-olm-v1`. |
| `message_id` | 16 random bytes, canonical unpadded Base64URL (22 chars). |
| `recipient_mailbox` | Canonical 32-byte mailbox ID. |
| `expires_at` | Integer UTC epoch seconds, future and <= acceptance time + 7 days. |
| `payload_type` | `prekey` or `normal`, from library envelope type. |
| `encrypted_payload` | Canonical Base64URL library message, 1–24 KiB decoded. |
| `sender_credential` | Required for `prekey`, forbidden for `normal`; credential object from messaging document. |

No outer created-at is needed; relay records its own acceptance time. Sender
created-at remains inside ciphertext. No message text, display name, local
conversation ID, private key, recovery material, read flag or content kind is
permitted outside encryption. Olm's wire headers remain visible within the opaque
encoded payload: Base64 is not encryption. Do not promise session-header secrecy.

Encode outer object with JCS and persist exact UTF-8 bytes on sender. Relay
computes SHA-256 of those canonical bytes as `envelope_sha256`. Receipt
`target_ciphertext_sha256` instead hashes decoded library ciphertext bytes; this
separates relay-submission identity from recipient-content identity. Outer
mailbox/message ID/expiry/suite must match protected inner copies after decryption.

Parser contract on both service and client:

- Cap request/frame at 48 KiB before allocation; stream-count even if Content-Length
  absent/false. Disable HTTP body and WebSocket compression in v1.
- Reject duplicate/unknown keys, non-object roots, invalid UTF-8, trailing input,
  excessive nesting (>4 levels), invalid numbers, floats, nulls, wrong field
  types, noncanonical Base64URL (re-encode equality), invalid key/signature lengths.
- Validate byte sizes before parsing library bytes. Maximum header budget 8 KiB,
  maximum fetch response 512 KiB and 10 envelopes. No unbounded collections.
- Relay validates shape, expiry, submit capability and quota; recipient validates
  signatures, pinned context, library integrity and inner fields independently.
- Reject malformed ciphertext with generic errors and local rate limits, never
  crash or expose detailed decryption errors to senders. No reflection of input
  bytes in logs/error messages. Unsupported records never upgrade the protocol.

## 4. HTTP contracts, persistence and acknowledgements

| Operation | Authentication / result |
| --- | --- |
| `POST /v1/mailboxes` | Rate-limited allocation as above. |
| `PUT /v1/prekeys` | Owner token; persist signed public keys, idempotent key IDs; same ID/different key conflicts. Private keys never uploaded. |
| `POST /v1/prekeys/claim` | Submit token + `claim_id`; atomic once-only allocation across all mailboxes sharing a device prekey pool. |
| `POST /v1/envelopes` | Submit token scoped to outer recipient; `Idempotency-Key` equals message ID; commit ciphertext/dedup before response. |
| `POST /v1/inbox/fetch` | Owner token; bounded leased batch, optional opaque cursor. |
| `POST /v1/inbox/consume` | Owner token + message IDs/digests/lease tokens; idempotent transport deletion after local acceptance. |
| `POST /v1/envelopes/status` | Original submit capability + IDs, bounded batch; accepted/consumed/expired/unknown service state only, never DELIVERED. |
| `GET /v1/events` (WebSocket upgrade) | Owner authentication; generic wake/status hints, no authoritative receipt. |

Relay acceptance body: `protocol_version=1`, `message_id`, `recipient_mailbox`,
`envelope_sha256`, `accepted_at`, `expires_at`, `state="accepted"`.
Authenticated by the TLS connection; no extra service signature or novel MAC
scheme. Sender must compare all submitted identifiers/hash/expiry before SENT.
No acceptance from a WebSocket hint, FCM or HTTP status code without matching body.
Persist response as delivery evidence. A malicious relay can forge its own ACK;
only recipient receipts provide stronger evidence.

Use transactional durable queue + unique `(mailbox_id, message_id)` dedup index.
First submission returns 201 after committed durable storage (synchronous replica
acknowledgement across two failure domains is the proposed production contract).
Retry identical canonical body returns 200 with original acceptance; mismatched
hash is 409, never overwrite. Once consumed/expired, retain hash and original
acceptance tombstone; exact retry returns original acceptance plus terminal queue
state, without re-enqueuing. Expired requests with no retained acceptance return
410. Never accept first and asynchronously write later. This contract needs
fault-injection evidence before promising durability.

Other results: 400 malformed, 401 invalid credential, 403 revoked/policy denied,
413 oversized, 429 throttled with bounded Retry-After, 503 temporarily unavailable
or suspended. Missing/revoked mailboxes should have uniform authorization failure
behavior to reduce enumeration. No secret-bearing URLs or cross-origin redirects.
A 401 permits one credential refresh only when a valid replacement already exists;
it does not authorize replacing identity. 400/403/409/413 are terminal pending
user/configuration repair; timeout, connection loss, 429 and 5xx retry.

Fetch leases last 60 seconds. Fetch does not delete. Expired leases redeliver.
Consumer commits durable message/ratchet/dedup/receipt intent, then consumes.
Owner consume is transport housekeeping: it can be forged by a malicious owner
or relay and MUST NOT set sender DELIVERED. Consume is idempotent by mailbox,
message ID and digest even if lease response is lost; stale/wrong lease cannot
consume a different item. Cursor is only an optimization; expired leases remain
fetchable, and reconnect can reset cursor without skipping unconsumed messages.
Invalid envelopes may be discarded/quarantined locally and consumed to avoid
poison loops, but never generate a success E2EE receipt.

Queue stores ciphertext, mailbox ID, random message ID, digest, accepted/expiry
times and lease bookkeeping. Service stores capability hashes, public key bundles,
claim records and optional FCM mapping separately with scoped access. No plaintext
search, conversation roster, read status or server-generated recipient receipts.

Delete online queue ciphertext promptly after consume or TTL; expiry sweep at
least hourly. Keep minimal submission/claim tombstones through expiry + seven days
+ five-minute skew. No ciphertext in ordinary analytics/logs. Encrypt disks and
replicas with managed service keys; this reduces stolen-disk exposure but E2EE
must survive loss of those service keys. Exclude queue payloads from long-lived
backups; any disaster snapshots have a documented <=24-hour retention and expiry
filter on restore. Replica deletion is an operational policy, not proof that a
compromised server erased copies.

Initial abuse budgets: 60 submissions/minute/submit capability, burst 20;
1,000 queued envelopes or 10 MiB per mailbox (first reached); 10 prekey claims/hour
per capability. Return 429/queue-full without silently dropping accepted data.
V1 cannot see encrypted content kinds: recipient must drain regularly,
and receipts receive the same quota/retry treatment. Do not trust a client-sent
“receipt” priority flag to bypass abuse controls. Metrics aggregate counts/latency;
security logs omit tokens, full IDs, payloads and use short-lived pseudonyms.
Proposed access-log retention 24 hours; longer incident retention needs explicit
policy with metadata disclosure, not silent indefinite storage.

## 5. Offline, retry and reconnect

Sender stores protected local intent immediately, encrypts only when verified
session inputs exist, and commits ciphertext before first attempt. Retry exact
bytes. Attempts use full jitter `U(0, min(300 seconds, 2 seconds * 2^n))`, with
`n=0` for the first retry. Persist attempt count and next attempt; maximum 20
network attempts automatically per envelope or TTL expiry, whichever first.
No-network waits and declared service suspension do not burn attempts. Android
background scheduling may run later than this schedule. A 429/503 Retry-After
cannot shorten backoff; honor it up to 24 hours and the remaining TTL. No busy loops.
User can resume a retryable FAILED message before expiry with the same bytes/ID.

Message expiry is fixed when encrypting: default seven days after creation,
never extended on retries. Local intents waiting seven days also expire with a
clear failure. Relay compares expiry to its time; recipient additionally enforces
the authenticated expiry with five-minute skew tolerance. Do not depend on clock
checks for replay protection. Unknown clock blocks new setup rather than accepting
arbitrarily old credentials. A successfully accepted but unacknowledged expired
message remains SENT with delivery unknown; do not claim guaranteed nondelivery.

Receipts are independent encrypted messages with their own seven-day expiry and
retry schedule, so recipient acceptance near text expiry can still be acknowledged.
Persist delivery/read intents until submitted or expired; receipt expiry may leave
sender state unknown forever. Reconnect prioritizes queued receipt intents before
new text, without leaking content type in transport. Missing receipts never trigger
automatic reencryption of original text into a new session.

```mermaid
sequenceDiagram
    participant A as Sender
    participant R as Relay
    participant B as Recipient
    Note over A,B: C. Recipient offline
    A->>R: Submit encrypted text
    R->>R: Commit queue and dedup
    R-->>A: SENT acceptance
    Note over R,B: Recipient offline; queue retained until TTL
    opt FCM configured
        R-->>B: Generic wake hint (best effort)
    end
    B->>R: App opens; owner-auth fetch
    R-->>B: Lease ciphertext
    B->>B: Verify, decrypt and atomically accept
    B->>R: Consume and queue encrypted delivery receipt
    R->>A: Receipt on next fetch
    A->>A: DELIVERED
```

```mermaid
sequenceDiagram
    participant A as Sender
    participant R as Relay
    participant B as Recipient
    Note over A,B: E. Reconnect and ambiguous outcomes
    A->>A: Commit ciphertext and ratchet state
    A->>R: Submit ID X, digest H
    R->>R: Durable acceptance
    R--xA: ACK lost during network switch
    A->>A: Persist jittered retry; reconnect
    A->>R: Resubmit identical X and H
    R-->>A: Original acceptance; no duplicate queue row
    B->>R: Fetch leased X
    B->>B: Commit accepted message and receipt intent
    B--xR: Consume response lost
    R-->>B: Redeliver after lease expires
    B->>B: Durable digest match; no second message or ratchet advance
    B->>R: Idempotent consume; retry stored receipt
```

## 6. Android transport and FCM

| Option | Benefits | Costs / decision |
| --- | --- | --- |
| HTTPS polling | Simple stateless retry, proxy friendly, easy idempotency. | Frequent background polling costs battery; foreground fallback at 30 seconds with jitter. |
| WebSocket only | Low-latency foreground hints. | Mobile network churn, Doze, process death and background limits make it unreliable as sole delivery channel. |
| Hybrid | HTTPS authoritative operations plus foreground WebSocket hints. | Two paths to test; selected because correctness resides in durable fetch/submit, not socket survival. |

Open one socket while app is foreground, close on background; coalesce hints.
Use 60-second foreground heartbeat, reconnect with jitter, reset backoff after
60 seconds healthy connection. Connectivity changes trigger one coalesced fetch
and outbox retry. WorkManager performs unique network-constrained background
sync, with >=15-minute periodic fallback if enabled; Doze/OEM/force-stop restrictions
mean no guaranteed latency. App-open always syncs, even with no Google services.
No permanent foreground service is proposed.
[Android persistent work scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

Future FCM data payload exactly `{"v":"1","event":"relay_data_available"}`,
coalesced per installation. No text, full ciphertext, contact name, message ID,
mailbox or session identifiers. Token lifecycle belongs to transport service,
not contact identity. Google still sees app registration/token, IP/device-related
service metadata and wake timing; relay sees token/mailbox mapping. Treat push as
untrusted and rate-limit sync: no local deletion, receipt transition, or key change
can follow a push alone. Normal priority can be delayed; high priority is only
appropriate for genuine user-visible notification behavior under FCM policy,
not a guarantee of silent background execution.
[FCM Android priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority).

## 7. Domain boundary (conceptual interface, not code)

Evolve existing `MessageTransport` in a later phase to expose:

- `availability`: available, offline, retry-after, service-suspended; never E2EE status.
- `submit(ImmutableEnvelope) -> Acceptance | Retryable | Rejected` with exact digest.
- `fetch(cursor?, limit) -> LeasedEnvelopes` and `consume(lease, id, digest)`.
- `events() -> WakeHint | AvailabilityChanged`; always re-fetch authoritative data.

Keep mailbox provisioning/prekey claims in a separate `SessionDirectory` boundary
returning **untrusted** public records; crypto/trust layer verifies them. Domain
repository coordinates local intent, crypto transaction, transport and evidence
reducer; UI sees repository models. No Composable handles WebSocket, FCM, raw
session keys or network receipt booleans.

`CodeYantraRelayTransport` implements relay acceptance durability.
`FutureMeshTransport` must define its own acceptance capabilities; intermediate
peer forwarding cannot be labeled SENT under this relay definition. Either map
a reviewed durable acceptance contract or show transport-specific “submitted”
while retaining E2EE DELIVERED/READ. Alternate transports carry the same encrypted
inner context, but require separately versioned routing wrappers; changing mailbox
routing must not break protected context checks. There is no implemented mesh path.

## 8. Temporary relay suspension

Operator can switch relay into maintenance/suspended state: stop registration,
prekey claims, submission, fetch and wake dispatch consistently; return TLS-auth
503 `SERVICE_SUSPENDED` with bounded retry hint and non-sensitive status text.
Already accepted ciphertext retains original expiry; queue cleanup still runs.
No silent TTL extension and no false acceptance. UI displays “Relay temporarily
unavailable; your identity and local messages remain on this device.” Pending
local work remains protected and resumes only while within expiry.

```mermaid
sequenceDiagram
    participant O as CodeYantra Operations
    participant R as Relay
    participant A as Android client
    Note over O,A: F. Temporary service suspension
    O->>R: Suspend relay ingress and delivery
    A->>R: Submit or fetch
    R-->>A: 503 SERVICE_SUSPENDED, retry hint
    A->>A: Preserve identity, local history and outbox; pause attempts
    R->>R: Honor existing queue TTL cleanup
    O->>R: Restore service
    A->>R: Scheduled/app-open retry and fetch
    R-->>A: Accept/fetch unexpired work; expired outcome otherwise
    A->>A: Resume; show expiry/unknown outcome accurately
```

This controls CodeYantra infrastructure only. No private-key escrow, remote
identity deletion, local database wipe, decryption capability or hidden device
kill switch. A service response never executes device administration commands.
This is an operational model, not a legal conclusion. Future peer-to-peer/mesh
can function independently of CodeYantra and has different control properties;
it requires a separate feature decision, not a promise of relay-wide suspension.
