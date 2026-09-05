# Phase 1C: local contact exchange

## Public QR format

Phase 1A's wire format is unchanged:

```json
{"p":"armorchat","v":1,"id":"ac1:…","pk":"<unpadded Base64URL Ed25519 public key>"}
```

Only these four fields are allowed. Key order and JSON whitespace/escapes are
supported. Duplicate keys, nested values, extra fields, trailing content,
non-integer versions and unsupported versions are rejected. No secret material,
device identifiers, recovery material, passwords or KEKs are included. The
existing My ArmorChat ID QR remains usable; the new share-payload action uses
Android's user-initiated share sheet with exactly this public JSON. Sharing sends
it to the application the user selects; scanning itself never uploads it.

## Validation pipeline

1. Bound input to 1,024 UTF-8 bytes (with a character bound before allocation).
2. Parse the entire flat JSON object with strict JSON string handling and reject
   duplicate/unknown fields and trailing input; no partial regex extraction.
3. Require protocol `armorchat` and integer version `1`.
4. Require a 43-character unpadded Base64URL public key, decode exactly 32 bytes,
   and re-encode to reject noncanonical padding bits.
5. Parse the supplied `ac1` and verify its CRC-32 checksum.
6. Validate the Ed25519 point using Bouncy Castle's existing
   `Ed25519.validatePublicKeyFull` (reject invalid/neutral/non-prime-order points).
7. Recompute `ac1` from the decoded public key and compare with the normalized
   supplied ID. A checksum alone is never treated as a cryptographic binding.
8. Read only public local identity metadata to reject a self-scan. Reject if no
   local identity exists. Check the saved ID/key binding for duplicates/conflicts.
9. Ask for confirmation and an optional local name. Re-run validation and
   duplicate/self checks in the repository at save time; enforce uniqueness in Room.

Existing `ac1` derivation: SHA-256 of the 32 public-key bytes, first 16 digest
bytes, followed by four big-endian CRC-32 bytes over that fingerprint; Crockford
Base32 encodes those 20 bytes to 32 characters after `ac1:`. CRC detects corruption;
the public-key hash binds the identifier. This phase does not change derivation.

The parser takes only public text and has no Keystore, private-key, identity
repository, transport, Android context or recovery dependency. Contact self-checks
query the public Room ID directly, avoiding identity unsealing.

## Trust model

**QR validation verifies the ArmorChat cryptographic identifier, not the
real-world identity of the person presenting it.**

The UI says “ArmorChat ID verified”: the scanned public key corresponds to that
ID. A copied QR does not prove possession of its private key. Establish who is
presenting it through an appropriate independent/in-person check. This phase has
no signed challenge, person verification, revocation, directory or messaging.
Local names are user-chosen labels, not authenticated identity claims.

## Contacts and database migration

Room database version increases from 2 to 3 through `CONTACT_MIGRATION_2_3`.
`contacts` has a stable random UUID primary key, a UNIQUE `armorChatId` index,
canonical Base64URL `publicIdentityKey`, local `displayName` (maximum 100 UTF-16
code units), `createdAtEpochMs`, and `verificationMethod = QR_SCAN`.

The migration preserves identity, conversation and message tables. The old
placeholder `contacts` table is renamed to `legacy_contacts` to preserve its rows;
those rows lack public keys and must not be presented as verified contacts. A new
verified contacts table is created. No destructive v2 migration is permitted.
The pre-existing v1 development-only fallback remains unchanged.

No private keys are stored in contacts. Contacts are local app-private Room data,
not separately encrypted; app cloud backup remains disabled. The existing
`.armorbackup` identity backup does **not** include contacts. Reinstalling/clearing
data loses contacts; ordinary process/device restarts preserve them.

## Duplicate, self-scan and key pinning

- Same verified ID/key: show “This ArmorChat ID is already in your contacts.” and
  offer the existing contact, preserving its primary key, name and timestamp.
- Own ID: show “This is your ArmorChat ID.” and never insert it.
- ID/key mismatch in the payload: reject before storage.
- Existing saved ID with different key material: reject an identity conflict.
- Insertion uses `IGNORE`, never `REPLACE` or an identity upsert. A concurrent
  uniqueness conflict is read back and classified as duplicate/key conflict.
- The only update operation changes the local name by primary key. It cannot
  change the pinned ID, public key, creation date or verification method.

## Scanner dependencies and privacy

| Dependency | Version | License | Selection / maintenance |
| --- | --- | --- | --- |
| CameraX camera-camera2, camera-lifecycle, camera-view (via `libs.versions.toml` `camerax`) | 1.5.3 | Apache-2.0 | Chosen over ML Kit for fully on-device Apache-2.0 stack with Tink/ZXing; actively maintained AndroidX lifecycle-aware capture/preview |
| ZXing Core (already present) | 3.5.3 | Apache-2.0 | Offline Java QR decoder; maintenance mode, not active feature development; no service or model download |
| Bouncy Castle (already present) | 1.81 | Bouncy Castle license (MIT-style) | Existing cryptographic dependency; full Ed25519 point validation |
| Robolectric (test only) | 4.16.1 | MIT | Maintained Android host tests for actual Room reopen/migration |
| AndroidX Test runner / ext JUnit (test only) | 1.7.0 / 1.3.0 | Apache-2.0 | Maintained device instrumentation infrastructure |

Sources: [CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera),
[AndroidX license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt),
[ZXing](https://github.com/zxing/zxing), [Bouncy Castle license](https://www.bouncycastle.org/licence.html),
[Robolectric](https://github.com/robolectric/robolectric),
[AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test).

CAMERA permission is added, requested only after tapping Scan ArmorChat QR.
Gallery import uses GetContent and does not request storage/media permissions.
Camera hardware is optional. No microphone/location/storage/contacts permissions,
address-book integration, scanner analytics, uploads or network service are added.
Frames and decoded text are not logged or written to disk. ZXing is restricted to
QR codes. Analysis runs on a dedicated executor at up to five frames per second,
uses keep-latest backpressure, accounts for luminance row/pixel strides, and closes
every frame. The first decoded QR stops scanning, including invalid QR payloads;
retry is explicit. Leaving the scanner unbinds camera use cases and shuts down the
executor. Validation and Room work run off the main thread, not in recomposition.

## Gallery QR import

Add Contact also offers **Choose from Gallery**. The app uses Android’s
`ActivityResultContracts.GetContent` (`image/*`) so the system photo picker / SAF
grants one-time URI access — no `READ_MEDIA_IMAGES` or storage permission is
requested. Camera permission remains required only for live Scan ArmorChat QR.

The selected image is decoded on-device with ZXing (`RGBLuminanceSource` /
`HybridBinarizer`, QR-only hints). Large images are downsampled before decode.
Decoded text is passed through the same `ContactsViewModel.scan` →
`ContactRepository.inspectQr` / `addQr` pipeline as camera frames: recompute
`ac1`, reject mismatch, self-scan, duplicates, and key conflicts. Frames/images
and decoded text are not logged or persisted by the decoder.

## Automated tests

- `ContactQrSecurityTest`: valid identities, malformed JSON, protocol/version,
  Base64URL/canonical bits, lengths, ID mismatch/checksum, curve points, size,
  duplicate/nested/unknown fields, reordered/escaped JSON, public-only operation,
  3,000 seeded garbage inputs and 500 mutated payloads.
- `ContactRepositoryTest`: duplicate prevention, self-scan, invalid-save boundary,
  pinned-key conflict, simulated insert races, name-only updates, missing identity.
- `ContactRoomTest`: actual on-disk Room close/reopen, UNIQUE index enforcement,
  unchanged pinned key after conflict, name-only mutation and v2-to-v3 migration.
- `ContactPersistenceTest`: equivalent tests for Android device instrumentation.
- Existing identity and backup JVM tests remain part of the regression suite.

Database reopen tests model process persistence; they do not substitute for the
physical restart or camera test below. Device-only tests require an attached
Android device/emulator. Build/test results are recorded in the delivery report.

## Required physical acceptance test

1. Upgrade both phones using the APK; confirm pre-existing IDs and backup flows
   still work. Confirm no camera permission prompt before selecting Scan QR.
2. A shows My ArmorChat ID. B opens Contacts → Add Contact → Scan ArmorChat QR,
   grants camera permission, scans A, checks the displayed ID, names A, and saves.
3. Confirm details show the local name, full ID, date, “Added via: QR scan” and
   accurate verification wording. Test Copy ID and optional security details.
4. Force-stop/restart B and reboot B; confirm the identical contact and key remain.
5. Scan A again on B; confirm no extra row and navigate to the existing contact.
6. Show B's QR; A scans and saves B. Both phones retain each other's pinned key.
7. Scan your own QR from another display; confirm self-scan rejection.
8. Test malformed/unsupported/tampered QR, including a matching-checksum ID paired
   with a different key; no contact may be saved.
9. Test camera denial, permanent denial, revocation, background/foreground,
   rotation, low light and cancel/retry. Confirm the camera indicator turns off
   when leaving scanning and works after returning. Check large font/small screen.
10. Test in airplane mode. No message should be sent; no messaging action exists.

Stop before git push. Suggested commit:
`feat(contacts): add verified QR contact exchange`.
