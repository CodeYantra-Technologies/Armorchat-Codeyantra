# Security Policy

ArmorChat is a privacy-focused messaging application by CodeYantra Technologies.
Veltrix is the backend trust/security layer. Veltrix is not a surveillance layer.

## Current status

This repository is **Phase 1A — Secure Local Identity Foundation**.

Implemented in this phase:

- Local Ed25519 identity via Google Tink (`tink-android`)
- Android Keystore AES-256-GCM KEK wrapping the private keyset
- ArmorChat ID (`ac1:`) fingerprint + QR public payload with binding checks
- Encrypted identity blob under `noBackupFilesDir` (`allowBackup=false`)

The following are **not implemented** and must not be claimed as working:

- End-to-end encryption of messages
- Double Ratchet / session (X25519) keys
- Message relay / transport
- Secure backup / recovery of identity
- Calls, attachments, mesh, or private routing

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
Long-term identity uses Google Tink Ed25519. Messaging E2EE will sit behind
`CryptoEngine` in a later audited phase. Until then, message crypto must not
be presented as production security.
