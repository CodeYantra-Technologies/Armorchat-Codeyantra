# Armor Deal Phase 1 — secure storage foundation

Phase 1 is an isolated library inside the ArmorChat app module plus a local Cloudflare-shaped backend. It is not linked from navigation, chat, or messaging. `DealFeature.PRODUCTION_UI_ENABLED` is false. The app process does not open `armorchat-deal.db`.

Armor Deal signatures are technical evidence that a specific document hash was signed by a specific Ed25519 key under this protocol. They are not Aadhaar eSign, not an X.509 digital-signature certificate, and not a regulated electronic signature. A hash, timestamp, or signature does not by itself prove a person's legal identity, consent, or enforceability. Identity proofing, regulated eSign, stamping, and jurisdiction rules are separate future work.

## What was reused

Messaging already uses Tink 1.21.0, Bouncy Castle 1.81 scrypt, and Android Keystore. Armor Deal calls those libraries. It does not call messaging identity stores, `AttachmentCrypto`, or `CanonicalJson`. Those messaging formats stay unchanged.

Emergency Mesh files were not edited.

## Algorithms

| Use | Choice |
| --- | --- |
| Canonical agreement | `ADCB` version 1, length-prefixed binary, parties sorted by UTF-8 party id |
| Document hash | SHA-256 over those canonical bytes |
| Document encryption | One AES-256-GCM, 12-byte random nonce, 128-bit tag |
| Document AAD | `ArmorDeal/doc/v1`, deal id, version id, format version, purpose `deal-document` |
| Party access | Tink HPKE: X25519 HKDF-SHA256, HKDF-SHA256, AES-256-GCM, no Tink prefix. Each party gets an envelope of the same document key. The document is not encrypted twice. |
| Signatures | Ed25519 over `ArmorDeal/sign/v1`, action, protocol version, deal id, version id, party id, pinned public key, document hash |
| Recovery package | `ADRP` version 1. scrypt N=32768, r=8, p=1, then AES-256-GCM. Secret must be at least 32 bytes. Password enrollment is not enabled. |
| Device key wrap | Android Keystore AES-256-GCM alias `armorchat_deal_local_kek_v1`, format `ADKS`. No software fallback in that class. |

The recovery package holds Tink private keysets and the sealed document. It does not hold the Android Keystore key. A restored phone seals the keysets again under a new device key. If the recovery secret is lost, this phase cannot reconstruct the deal. A missing Keystore alias is treated as revoked device access and does not delete stored rows.

## Local database

`DealDatabase` is Room database `armorchat-deal.db`, schema version 1, with foreign keys and no destructive migration. It is separate from `ArmorChatDatabase`. Tables hold metadata, public keys, sealed documents, signatures, backup receipts, audit events, recovery metadata, and Keystore-sealed keysets. Terms are not a column.

## Cloudflare shape

`deal-backend/` implements `/v1/deals` against a local sql.js database and an in-memory R2 map. Object keys are derived on the server as `deals/{dealId}/versions/{versionId}/parties/{partyId}/backup.enc`. Client object keys and party ids are not authorization inputs. A confirmed backup cannot be overwritten or deleted, including by its owner. Execute is refused until both parties have receipts for the same ciphertext hash and both signatures verify again.

The server stores document hashes and ciphertext hashes. It does not store contract plaintext. Each device still has to check the hash against canonical bytes it can decrypt.

Account deletion, retention, and legal hold are not implemented. They need an owner decision before any production policy is written.

## Not in this phase

- No production deployment and no live credentials.
- No production recovery enrollment and no recovery UI.
- No mock authenticator in the Android app. The app ships `RejectingDealAuthenticator` only.
- The worker local-test authenticator fails closed in production.
- Device Keystore behavior was not exercised on a phone. Unit tests use a test-only software protector that is not in `src/main`.
