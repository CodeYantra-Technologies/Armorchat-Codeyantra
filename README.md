# ArmorChat

ArmorChat is a privacy-focused messaging application by CodeYantra Technologies.

The new codebase is a clean implementation and is **not** based on the old
BChat-derived ArmorChat source tree.

**Secured by Veltrix** — Veltrix is the backend trust/security layer.
It is not a surveillance layer.

```
ArmorChat
by CodeYantra Technologies
Secured by Veltrix
```

## Current status — Phase 1A Secure Local Identity

Local Ed25519 identity (Google Tink), Android Keystore–wrapped private key
storage, ArmorChat ID (`ac1:`), and public QR exchange are implemented.
See [docs/IDENTITY_SECURITY.md](docs/IDENTITY_SECURITY.md) and [SECURITY.md](SECURITY.md).

Still **not** claimed / not implemented:

- End-to-end encrypted messaging
- Double Ratchet / X25519 session keys
- Secure identity backup / recovery
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
- Android Keystore (`security-crypto`)
- Coroutines / Flow
- OkHttp (reserved for future networking)
- WorkManager (reserved for future background work)

Minimum SDK: 26. Target / compile SDK: 36.

## Roadmap

| Phase | Scope |
| --- | --- |
| **Phase 0** | Foundation (this repository) |
| **Phase 1** | Identity + E2EE text messaging |
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

## License

License selection is pending. See [LICENSE](LICENSE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
