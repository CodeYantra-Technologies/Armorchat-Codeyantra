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

## What stays out

ArmorChat 0.6.0 already ships one-to-one MLS chat, the ciphertext relay,
encrypted attachments, and a direct Emergency Mesh handoff. Please do **not** submit:

- A custom encryption protocol for messaging sessions
- Calls, WebRTC, or onion routing
- A change that turns Armor Deal on in the production app, or that describes it as a legal e-sign
- Telemetry or analytics
- Payment integration beyond the existing support link
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

Contributions are submitted under **AGPL-3.0-only**. See `LICENSE`, `NOTICE`,
and `THIRD_PARTY_NOTICES.md`, plus [commercial licensing](../COMMERCIAL_LICENSE.md).

Contributors retain copyright. An AGPL contribution alone does not authorize
CodeYantra Technologies to offer that contribution under proprietary terms.
Before merging an external contribution intended for the commercially licensed
code, maintainers must obtain a separate signed contributor agreement that
explicitly grants the necessary commercial sublicensing rights, and keep a
record of it. Use the [contributor agreement template](../legal/CONTRIBUTOR_AGREEMENT.md)
after completing the parties and contribution list. Do not assume opening a pull request grants those rights.
Contributors must identify third-party material and confirm they are authorized
to submit their contribution. Earlier contributions require their own rights
review; this policy does not retroactively grant permission.

## Pull requests

1. Keep changes focused.
2. Do not mix unrelated refactors with feature work.
3. Call out any `NON-PRODUCTION` code in the description.
4. Update `SECURITY.md` if the trust boundary changes.
