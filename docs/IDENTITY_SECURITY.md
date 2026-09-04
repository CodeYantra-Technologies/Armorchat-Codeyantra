# ArmorChat Identity Security (Phase 1A)

Status: **Secure local identity foundation** — not complete end-to-end encryption.

## Threat assumptions

- The device OS and Android Keystore implementations are trusted for local key protection.
- An attacker with root / unlocked Keystore access may extract sealed material; Keystore raises the bar for casual extraction and backup leakage.
- Cloud backup of app data is disabled (`allowBackup=false`); identity blobs live under `noBackupFilesDir`.
- QR / ArmorChat ID exchange assumes an out-of-band trust decision by the user (no TOFU messaging yet).
- This phase does **not** defend against malware with the same UID, or against physical extraction after Keystore compromise.

## Identity architecture

```
Android Keystore non-exportable AES-256-GCM KEK
  alias: armorchat_identity_kek_v1
        ↓
AEAD (AES/GCM) encrypt
        ↓
encrypted Tink Ed25519 private keyset blob
        ↓
app-private file: noBackupFilesDir/identity/ed25519_identity.v1.enc
```

Room stores **public metadata only** (`armorChatId`, base64url public key, timestamps, version).

Layers:

`IdentityRepository` → `IdentityLocalDataSource` → `SecureIdentityKeyStore` + Room

## Key types

| Key | Algorithm | Status |
|-----|-----------|--------|
| Long-term identity | Ed25519 (Tink Signature / `ED25519` template) | **Implemented** |
| Session / ECDH | X25519 (planned) | **Reserved slot only** — do not reuse Ed25519 for ECDH |

Reserved future path (not implemented): `noBackupFilesDir/identity/x25519_session.v1.enc`

Library: Google Tink `com.google.crypto.tink:tink-android` **1.21.0** (Apache License 2.0).

## Key lifecycle

1. **Create (idempotent):** If metadata + decryptable key material exist → return existing. Mutex-synchronized.
2. **Persist:** Generate Tink Ed25519 keyset → serialize → encrypt with Keystore KEK → write envelope to no-backup file → store public metadata in Room.
3. **Load:** Read envelope → decrypt with KEK → parse keyset → expose public key bytes only to domain/UI.
4. **Never:** plaintext private-key storage, silent Keystore fallback, or auto-regeneration after decrypt failure.

## ArmorChat ID (`ac1`) specification

Do **not** encode the full raw Ed25519 public key as the ID.

Pipeline:

1. Ed25519 public key bytes (32)
2. `SHA-256(pubkey)`
3. Take first **16 bytes** (128-bit fingerprint)
4. CRC-32 (IEEE) over those 16 fingerprint bytes → 4 bytes (big-endian)
5. Binary payload = `fingerprint(16) || crc32(4)` = **20 bytes**
6. Encode with **Crockford Base32** (no padding), uppercase → **32 characters**
7. Format: `ac1:` + encoded

### Fingerprint length rationale

128-bit truncation yields a birthday collision bound of roughly \(2^{64}\). That is acceptable for a human-shareable contact fingerprint. The full 32-byte public key is carried in the QR payload; on import the ID is **recomputed** from `pk` and must match `id`.

### Checksum purpose

CRC-32 is **corruption detection only** (typos / truncated scans). It is **not** a security authenticator.

Pure functions:

- `ArmorChatId.derive(publicKeyBytes): String`
- `ArmorChatId.parse(id): Result`

## QR payload (public only)

Versioned compact JSON:

```json
{"p":"armorchat","v":1,"id":"ac1:…","pk":"<base64url unpadded 32-byte ed25519 pub>"}
```

Rules:

- Never include private keys, recovery secrets, or device IDs.
- Parser validates product, version, ID encoding/CRC, public key length.
- **Binding check:** recompute `ArmorChatId.derive(pk)` and **reject** if ≠ payload `id`.

## Keystore KEK design

- Alias: `armorchat_identity_kek_v1`
- Algorithm: AES-256-GCM, non-exportable, Android Keystore
- Envelope: `version(1) || iv(12) || ciphertext+tag`

## Encrypted key storage location

`Context.noBackupFilesDir` / `identity` / `ed25519_identity.v1.enc`

## Backup / restore behavior

- `android:allowBackup="false"`
- Identity blobs under `noBackupFilesDir` (excluded from auto backup)
- Cloud / device-transfer backup rules exclude app files and databases
- **Restore UI** in this phase is an informational placeholder — no fake UUID restore
- Secure recovery / export is **postponed**

## Corrupted / missing KEK behavior

If Keystore is unavailable, KEK is missing/unusable, or decrypt fails:

- Throw a typed `IdentityStorageException` (`KeystoreUnavailable`, `KekUnusable`, `DecryptFailed`, `Corrupted`, `InconsistentState`)
- **Never** plaintext fallback
- **Never** silently create a new identity

If Room metadata exists but key material cannot be decrypted, surface the error — do not replace the identity.

## Limitations / NOT complete E2EE

- No messaging encryption
- No Double Ratchet / session keys
- No relay, FCM, or contact sync
- No audited recovery / seed export
- CryptoEngine remains unimplemented for messages

## Postponed

- Secure backup & recovery
- Messaging & ratchet
- X25519 session keys
- Contact scan UI beyond parser/validator readiness
