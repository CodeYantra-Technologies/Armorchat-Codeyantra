# Contributing to ArmorChat

Thank you for your interest in ArmorChat, a project of CodeYantra Technologies.

## Before you start

This is a clean-room Android implementation. Do **not** copy source, resources,
protocol constants, database schemas, or implementation details from:

- BChat
- Session
- Signal / WhisperSystems trees used as implementation sources
- the previous GPL-derived ArmorChat repository

Those trees may be used later as behavioral reference only. Mentions of BChat,
Session, or GPL in project docs are **do-not-copy** clean-room warnings — not
permission to relicense or import that code.

## Architecture

Keep the layering intact:

```
UI  →  Domain / Use Cases  →  Repositories  →  Crypto / Database / Transport
```

The UI must never depend on WebSocket, HTTP, Room entities, or cryptographic
implementation details.

Transport must remain replaceable through `MessageTransport`.

## What this phase is

Phases 0–1C cover the application shell, local identity, encrypted backup, and
verified contact exchange. Please do **not** submit:

- A custom encryption protocol for messaging sessions
- Relay / WebSocket / FCM implementations
- Calls, WebRTC, mesh, or onion routing
- Telemetry or analytics
- Payment integration
- Hard-coded secrets or API keys
- Debug backdoors
- Copied Signal / Session / BChat source

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
