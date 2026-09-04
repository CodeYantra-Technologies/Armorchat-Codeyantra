# Contributing to ArmorChat

Thank you for your interest in ArmorChat, a project of CodeYantra Technologies.

## Before you start

This is a clean-room Android implementation. Do **not** copy source, resources,
protocol constants, database schemas, or implementation details from:

- BChat
- Session
- the previous GPL-derived ArmorChat repository

Those trees may be used later as behavioral reference only.

## Architecture

Keep the layering intact:

```
UI  →  Domain / Use Cases  →  Repositories  →  Crypto / Database / Transport
```

The UI must never depend on WebSocket, HTTP, Room entities, or cryptographic
implementation details.

Transport must remain replaceable through `MessageTransport`.

## What this phase is

Phase 0 is an application shell plus architectural boundaries.

Please do **not** submit:

- A custom encryption protocol
- Relay / WebSocket / FCM implementations
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

## Pull requests

1. Keep changes focused.
2. Do not mix unrelated refactors with feature work.
3. Call out any `NON-PRODUCTION` code in the description.
4. Update `SECURITY.md` if the trust boundary changes.

License selection is pending. See `LICENSE`.
