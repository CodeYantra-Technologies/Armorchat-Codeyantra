# ArmorChat

ArmorChat is a privacy-focused messaging application by CodeYantra Technologies.

This repository is a **fresh implementation** of ArmorChat and is **not** based on
the old BChat-derived ArmorChat source tree. The previous BChat-derived ArmorChat
repository is a separate legacy codebase and is not this tree.

**Secured by Veltrix** — Veltrix is the backend trust/security layer.
It is not a surveillance layer.

```
ArmorChat
by CodeYantra Technologies
Secured by Veltrix
```

## Current status — Phases 0–1C (foundation, identity, backup, contacts)

Completed in this tree:

- **Phase 0** — Application shell, clean architecture boundaries, branding
- **Phase 1A** — Local Ed25519 identity (Google Tink), Android Keystore KEK–wrapped
  private key storage, ArmorChat ID (`ac1:`), public QR exchange
- **Phase 1B** — Encrypted identity backup / restore (password-protected recovery file)
- **Phase 1C** — Verified QR contact exchange and local contacts persistence

See [docs/IDENTITY_SECURITY.md](docs/IDENTITY_SECURITY.md),
[docs/CONTACT_EXCHANGE.md](docs/CONTACT_EXCHANGE.md), and [SECURITY.md](SECURITY.md).

Still **not** claimed / not implemented:

- End-to-end encrypted messaging
- Double Ratchet / X25519 session keys
- Decentralized / relayed messaging
- Voice or video calls
- Mesh networking
- Attachments, voice notes, or groups

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

Those implementations are **not** in this phase.

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
- OkHttp (reserved for future networking)
- WorkManager (reserved for future background work)

Minimum SDK: 26. Target / compile SDK: 36.

## Roadmap

| Phase | Scope |
| --- | --- |
| **Phase 0** | Foundation (complete in this repository) |
| **Phase 1** | Identity + contacts (1A–1C complete); E2EE text messaging still ahead |
| **Phase 2** | Attachments |
| **Phase 3** | Voice notes |
| **Phase 4** | Groups |
| **Phase 5** | Calls |
| **Phase 6** | Advanced private routing / optional mesh research |

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

This repository is a fresh implementation of ArmorChat and is licensed under the
Apache License 2.0.

See:

- [LICENSE](LICENSE) — Apache License Version 2.0 (full text)
- [NOTICE](NOTICE) — copyright and required third-party notices
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dependency / asset inventory

This is **not** a relicensing of any GPL, BChat, or Session codebase. The previous
BChat-derived ArmorChat repository remains a separate legacy tree.

### Trademarks

The Apache License 2.0 does **not** grant trademark rights to the ArmorChat,
CodeYantra Technologies, or Veltrix names or logos.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
