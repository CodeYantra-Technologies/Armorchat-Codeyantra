# Phase 1C.1: Chat UI foundation

## Scope

This phase adds a local conversation shell and chat UI. It does **not** enable
messaging transport, end-to-end encryption, or delivery receipts.

Honest UI wording:

- Header shows display name + short `ac1` and **“ArmorChat identity pinned”**.
- The UI never claims live E2EE and does not show a lock icon as encryption proof.
- Send stays **disabled** while `MessageTransport.isAvailable` is false
  (`NoOpMessageTransport`).
- `MessageRepository.sendText` returns `TransportUnavailable` and never writes
  `SENT` / `DELIVERED` / `READ` rows.

## Domain / storage

- `Conversation` is unique per `contactId` (Room unique index).
- Conversations may exist with zero messages.
- `Message`, `MessageStatus`, and `MessageType.TEXT` exist for UI readiness.
- UI depends on repository interfaces only — not WebSocket, HTTP, Tink, or Room
  entities inside Composables.

## Navigation

Contact Details → **Message** finds or creates one conversation and opens
`ChatScreen`. The Chats tab lists real conversations only (no sample rows).
Empty copy: “No conversations yet” / “Add an ArmorChat contact to start a
private conversation.”

Composer layout: `[+]` · `Type a message…` · Send. The `+` menu lists Image /
File / Camera / Contact as non-functional placeholders. No new storage, camera,
or audio permissions are introduced for chat.
