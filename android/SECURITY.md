# Security Policy

ArmorChat is a privacy-focused messaging application by CodeYantra Technologies.
Veltrix is the backend trust/security layer. Veltrix is not a surveillance layer.

## Current status

ArmorChat 0.6.0 is the public statement of this tree. See the repository README.

Implemented and in the production path:

- Local Ed25519 identity via Google Tink (`tink-android`)
- Android Keystore AES-256-GCM KEK wrapping the private keyset
- ArmorChat ID (`ac1:`) fingerprint + QR public payload with binding checks
- Encrypted identity blob under `noBackupFilesDir` (`allowBackup=false`)
- One-to-one MLS messaging (RFC 9420, mls-rs). The relay carries ciphertext.
- Encrypted photos, files, and voice notes, including chunked large-file transport
- Emergency Mesh as a direct sealed handoff between nearby phones

The following must not be claimed as working:

- Groups or calls
- Armor Deal in the production app. Production UI stays off. It is not a legal e-sign.
- Anonymous routing, untraceable delivery, or a finished multi-hop mesh
- Safety against a compromised, unlocked, or rooted phone

## Security goals (this and future phases)

- Private keys remain on the user device.
- CodeYantra Technologies holds no master decryption key.
- Veltrix must never need plaintext messages.
- Future backends should relay ciphertext only.
- Sensitive data must never be logged.
- No plaintext secrets in SharedPreferences.
- No hard-coded API keys.
- No debug backdoors.
- No remote master key.
- No remote user-message decryption capability.
- No silent identity regeneration when Keystore/KEK fails.

## Data handling in this phase

- Cloud backup of application data is disabled.
- Identity private keysets are sealed with a non-exportable Android Keystore KEK.
- The Room schema stores public identity metadata only (no private keys, no message plaintext).

See [docs/IDENTITY_SECURITY.md](docs/IDENTITY_SECURITY.md) for the full identity specification.

## Local contact exchange trust boundary

Untrusted QR input is size-bounded, strictly parsed, and bound to a valid Ed25519
public key by recomputing `ac1`. Contacts store public material only; a UNIQUE ID
index and insert-without-replacement behavior preserve the pinned key. Scanning
uses on-device CameraX/ZXing with no network service or address-book access.

QR validation verifies the ArmorChat cryptographic identifier, not the real-world
identity of the person presenting it. A copied QR is not proof of private-key
possession. Local labels are unauthenticated metadata. Contacts remain outside the
identity-only `.armorbackup` format. See [contact exchange](docs/CONTACT_EXCHANGE.md)
for validation, migration, privacy, limitations and physical acceptance checks.

## Reporting a vulnerability

Please report security issues privately to CodeYantra Technologies.
Do not open a public GitHub issue for vulnerabilities.

Include:

- A description of the issue
- Affected version / commit
- Steps to reproduce
- Impact assessment if known

We will acknowledge reports and work on a fix before any public disclosure.

## Production crypto

Do not invent a custom cryptographic protocol in this codebase.
Long-term identity uses Google Tink Ed25519. One-to-one messages use MLS
through mls-rs. Do not describe that as anonymous, untraceable, or proof
against a compromised phone.
