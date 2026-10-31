# ArmorChat

ArmorChat is the Android messenger from CodeYantra Technologies.

The product introduction for 0.6.0 is the repository [README](../README.md).
This directory is the app: identity, MLS messaging, encrypted attachments,
and the pilot Emergency Mesh path.

**Secured by Veltrix** — Veltrix is the backend trust/security layer.
It is not a surveillance layer.

```
ArmorChat
by CodeYantra Technologies
Secured by Veltrix
```

## Current status — 0.6.0

The product introduction is the repository [README](../README.md). This page is the Android tree.

In this tree:

- Local Ed25519 identity, Android Keystore wrap, ArmorChat ID (`ac1:`), and QR contact exchange
- Password-protected identity backup
- One-to-one MLS messaging (RFC 9420, mls-rs) over the CodeYantra ciphertext relay
- Encrypted photos, files, and voice notes, including chunked transport for large files
- Emergency Mesh as a direct phone-to-phone handoff when the network is down
- arm64-v8a pilot packages

Not in the production app, and not claimed here:

- Groups and calls
- Armor Deal. The production UI stays off. It is not a legal e-sign.
- Anonymous routing, or a finished multi-hop mesh

See [docs/IDENTITY_SECURITY.md](docs/IDENTITY_SECURITY.md),
[docs/CONTACT_EXCHANGE.md](docs/CONTACT_EXCHANGE.md), and [SECURITY.md](SECURITY.md).
Older design notes in `docs/` record how the tree was built. Where one of them
still says messaging is unimplemented, the repository README is the current statement.

## Brand hierarchy

| Name | Role |
| --- | --- |
| ArmorChat | Front-facing product |
| CodeYantra Technologies | Owner, developer, and publisher |
| Veltrix | Backend trust / security infrastructure |

## Architecture

```
UI (Jetpack Compose)
        ↓
Domain / Use Cases
        ↓
Repositories
        ↓
Crypto / Database / Transport implementations
```

Future transports are expected to implement `MessageTransport`:

- `CodeYantraRelayTransport`
- `PrivateRoutingTransport`
- `MeshTransport`

Chat send goes through the MLS session and the CodeYantra relay.
`NoOpMessageTransport` is not the production path.

## Stack

- Kotlin
- Jetpack Compose + Material 3
- MVVM + clean architecture
- Hilt
- Room
- DataStore
- Android Keystore KEK + Google Tink (Ed25519 identity)
- Bouncy Castle (scrypt backup KDF / Ed25519 public-key validation helpers)
- Coroutines / Flow
- CameraX + ZXing (QR)
- OkHttp (relay)
- WorkManager (background work)

Minimum SDK: 26. Target / compile SDK: 36.

## Roadmap

| Area | In 0.6.0 |
| --- | --- |
| Identity, backup, verified QR contacts | Yes |
| One-to-one MLS messaging and the ciphertext relay | Yes |
| Photos, files, voice notes | Yes |
| Emergency Mesh, direct handoff | Yes, as a pilot path |
| Groups, calls | No |
| Armor Deal in the production app | No |

## Building

Requirements: JDK 17, Android SDK 36, Gradle 8.14.3 (wrapper included).

```bash
./gradlew assembleDebug
./gradlew test
```

Debug APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Create `local.properties` with your SDK path:

```
sdk.dir=/path/to/Android/sdk
```

## Security

See [SECURITY.md](SECURITY.md). Private keys stay on device. There is no
remote master key and no capability for CodeYantra or Veltrix to decrypt
user messages — including in future phases.

### Crypto / export compliance checklist

- Before international Play Store / export distribution, review encryption
  export-compliance requirements with qualified counsel — **not legal advice.**

## License

This repository is licensed under the Apache License 2.0.

See:

- [LICENSE](LICENSE) — Apache License Version 2.0 (full text)
- [NOTICE](NOTICE) — copyright and required third-party notices
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dependency / asset inventory

### Trademarks

The Apache License 2.0 does **not** grant trademark rights to the ArmorChat,
CodeYantra Technologies, or Veltrix names or logos.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
