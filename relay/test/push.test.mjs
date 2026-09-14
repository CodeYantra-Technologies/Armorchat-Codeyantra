import assert from "node:assert/strict";
import test from "node:test";
import {
  assertWakePayloadSafe,
  isPlausibleFcmToken,
  syncHintData,
  syncHintMessage,
  tokenFingerprint,
} from "../src/push.mjs";

test("sync hint payload is only type and v", () => {
  const data = syncHintData();
  assert.deepEqual(data, { type: "sync_hint", v: "1" });
  assert.equal(assertWakePayloadSafe(data), true);
  const raw = JSON.stringify(data);
  assert.equal(/plaintext|sender|mailbox|armorchat|mls|credential|preview|message/i.test(raw), false);
  assert.equal(Object.keys(data).sort().join(","), "type,v");
});

test("FCM message builder never adds notification or identifiers", () => {
  const token = "test-fcm-token-APA91b-not-a-real-token";
  const msg = syncHintMessage(token);
  assert.deepEqual(Object.keys(msg).sort(), ["android", "data", "token"]);
  assert.deepEqual(msg.data, { type: "sync_hint", v: "1" });
  assert.equal(msg.notification, undefined);
  assert.equal(msg.android.priority, "high");
  const raw = JSON.stringify(msg.data);
  assert.equal(/mailbox|sender|plaintext|preview|credential|identity/i.test(raw), false);
});

test("token fingerprint is short and not the token", () => {
  const token = "test-fcm-token-APA91b-not-a-real-token";
  const fp = tokenFingerprint(token);
  assert.equal(fp.length, 12);
  assert.notEqual(fp, token);
  assert.equal(token.includes(fp), false);
});

test("implausible FCM tokens are rejected", () => {
  assert.equal(isPlausibleFcmToken(""), false);
  assert.equal(isPlausibleFcmToken("short"), false);
  assert.equal(isPlausibleFcmToken("has whitespace in tokenvalue"), false);
  assert.equal(isPlausibleFcmToken("x".repeat(4097)), false);
  assert.equal(isPlausibleFcmToken("test-fcm-token-APA91b-ok"), true);
});
