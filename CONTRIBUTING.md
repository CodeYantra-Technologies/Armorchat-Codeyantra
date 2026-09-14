# Contributing to ArmorChat

Thank you for your interest in ArmorChat, a project of CodeYantra Technologies.

## Before you start

Please keep contributions original to this project. Do **not** copy source,
resources, protocol constants, database schemas, or implementation details from
other projects or external implementations. Maintain the project’s clean-room
architecture and keep changes aligned with the existing design boundaries.

## Architecture

Keep the layering intact:

```
UI  →  Domain / Use Cases  →  Repositories  →  Crypto / Database / Transport
```

The UI must never depend on WebSocket, HTTP, Room entities, or cryptographic
implementation details.

Transport must remain replaceable through `MessageTransport`.

## What this phase is

Phases 0–1D.2 cover the application shell, local identity, encrypted backup,
verified contact exchange, the isolated MLS harness, and the CodeYantra
Privacy Relay foundation (`android/relay/`). Please do **not** submit:

- A custom encryption protocol for messaging sessions
- FCM, WebSocket wake hints, or enabling chat send / `CryptoEngine`
- Calls, WebRTC, mesh, or onion routing
- Telemetry or analytics
- Payment integration
- Hard-coded secrets or API keys
- Debug backdoors

## Code style

- Kotlin only
- Jetpack Compose + Material 3
- Hilt for injection
- Coroutines / Flow for async
- Prefer small, explicit types over speculative abstractions

## Tests

Add JVM unit tests for domain logic when you change use cases or models.
Do not add instrumented tests that require a network.

## License

Contributions are accepted under the **Apache License 2.0**. See `LICENSE`,
`NOTICE`, and `THIRD_PARTY_NOTICES.md`.

## Pull requests

1. Keep changes focused.
2. Do not mix unrelated refactors with feature work.
3. Call out any `NON-PRODUCTION` code in the description.
4. Update `SECURITY.md` if the trust boundary changes.
