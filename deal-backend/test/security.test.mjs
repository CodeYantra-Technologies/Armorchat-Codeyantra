import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import { MemoryR2, openSqlite } from "../src/sqlite.js";
import { backupKey, handleRequest, mintLocalTestToken } from "../src/worker.mjs";
import { signaturePayload } from "../src/worker.mjs";

const SECRET = "local-test-secret-must-be-32-bytes-min";
const MIGRATION = readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8");
const NOW = 1_700_000_000_000;

function opaque(char) {
  return char.repeat(22);
}

async function freshEnv(overrides = {}) {
  return {
    ENVIRONMENT: "test",
    AUTH_MODE: "local-test",
    LOCAL_TEST_AUTH_SECRET: SECRET,
    clock: { nowMs: () => NOW },
    DB: await openSqlite(MIGRATION),
    BUCKET: new MemoryR2(),
    ...overrides,
  };
}

function ed25519() {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const raw = Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url");
  return { privateKey, raw };
}

function distinctKey(raw) {
  const other = randomBytes(32);
  if (other.equals(raw)) other[0] ^= 0x01;
  return other;
}

async function call(env, { method, path, participant, body, token, headers = {} }) {
  const headerToken = token ?? (participant ? mintLocalTestToken(SECRET, participant, env.clock.nowMs()) : null);
  const initHeaders = { ...headers };
  if (headerToken) initHeaders.authorization = `Bearer ${headerToken}`;
  let payload;
  if (body !== undefined) {
    payload = Buffer.isBuffer(body) ? body : Buffer.from(typeof body === "string" ? body : JSON.stringify(body));
    if (!Buffer.isBuffer(body) && !initHeaders["content-type"]) initHeaders["content-type"] = "application/json";
  }
  if (method === "POST" && !initHeaders["idempotency-key"]) {
    initHeaders["idempotency-key"] = randomBytes(9).toString("base64url");
  }
  const response = await handleRequest(new Request(`https://deal.invalid${path}`, {
    method,
    headers: initHeaders,
    body: payload,
  }), env);
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status, json };
}

async function openSignedDeal(env) {
  const signerA = ed25519();
  const signerB = ed25519();
  const dealId = opaque("d");
  const versionId = opaque("v");
  const partyA = opaque("a");
  const partyB = opaque("b");
  const participantA = opaque("A");
  const participantB = opaque("B");
  const documentHash = createHash("sha256").update("synthetic-agreement-v1").digest();
  const created = await call(env, {
    method: "POST",
    path: "/v1/deals",
    participant: participantA,
    body: {
      dealId,
      partyId: partyA,
      signingPublicKey: signerA.raw.toString("base64url"),
      encryptionPublicKey: distinctKey(signerA.raw).toString("base64url"),
    },
  });
  assert.equal(created.status, 201, JSON.stringify(created.json));
  const joined = await call(env, {
    method: "POST",
    path: `/v1/deals/${dealId}/join`,
    participant: participantB,
    body: {
      partyId: partyB,
      signingPublicKey: signerB.raw.toString("base64url"),
      encryptionPublicKey: distinctKey(signerB.raw).toString("base64url"),
    },
  });
  assert.equal(joined.status, 201, JSON.stringify(joined.json));
  const version = await call(env, {
    method: "POST",
    path: `/v1/deals/${dealId}/versions`,
    participant: participantA,
    body: {
      versionId,
      parentVersionId: null,
      documentSha256: documentHash.toString("base64url"),
      ancestry: [],
    },
  });
  assert.equal(version.status, 201, JSON.stringify(version.json));
  for (const signer of [
    { participant: participantA, partyId: partyA, key: signerA },
    { participant: participantB, partyId: partyB, key: signerB },
  ]) {
    const payload = signaturePayload({
      action: "SIGN",
      dealId,
      versionId,
      partyId: signer.partyId,
      publicKey: signer.key.raw,
      documentHash,
    });
    const signed = await call(env, {
      method: "POST",
      path: `/v1/deals/${dealId}/versions/${versionId}/signatures`,
      participant: signer.participant,
      body: { action: "SIGN", signature: sign(null, payload, signer.key.privateKey).toString("base64url") },
    });
    assert.equal(signed.status, 201, JSON.stringify(signed.json));
  }
  return { dealId, versionId, partyA, partyB, participantA, participantB };
}

async function backup(env, deal, participant, bytes) {
  const prepared = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:prepare`,
    participant,
    body: "{}",
  });
  assert.equal(prepared.status, 201, JSON.stringify(prepared.json));
  const hash = createHash("sha256").update(bytes).digest().toString("base64url");
  const uploaded = await call(env, {
    method: "PUT",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups/object`,
    participant,
    body: bytes,
    headers: {
      "x-upload-id": prepared.json.uploadId,
      "x-ciphertext-sha256": hash,
      "content-type": "application/octet-stream",
    },
  });
  assert.equal(uploaded.status, 200, JSON.stringify(uploaded.json));
  const confirmed = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:confirm`,
    participant,
    body: "{}",
  });
  assert.equal(confirmed.status, 201, JSON.stringify(confirmed.json));
  return { uploadId: prepared.json.uploadId, objectKey: prepared.json.objectKey, hash, confirmed: confirmed.json };
}

test("rejects unauthenticated, production-mode, and replayed tokens", async () => {
  const env = await freshEnv();
  const open = await call(env, { method: "POST", path: "/v1/deals", body: "{}" });
  assert.equal(open.status, 401);
  assert.equal(open.json.error, "unauthenticated");

  const production = await freshEnv({ ENVIRONMENT: "production" });
  const minted = mintLocalTestToken(SECRET, opaque("A"), NOW);
  const blocked = await call(production, {
    method: "GET",
    path: `/v1/deals/${opaque("d")}`,
    token: minted,
  });
  assert.equal(blocked.status, 500);
  assert.equal(blocked.json.error, "auth_not_configured");

  const token = mintLocalTestToken(SECRET, opaque("A"), NOW);
  const body = {
    dealId: opaque("d"),
    partyId: opaque("a"),
    signingPublicKey: randomBytes(32).toString("base64url"),
    encryptionPublicKey: randomBytes(32).toString("base64url"),
  };
  const first = await call(env, { method: "POST", path: "/v1/deals", token, body });
  assert.equal(first.status, 201);
  const replay = await call(env, { method: "POST", path: "/v1/deals", token, body });
  assert.equal(replay.status, 401);
  assert.equal(replay.json.error, "replayed");
});

test("party C and a foreign deal are forbidden", async () => {
  const env = await freshEnv();
  const deal = await openSignedDeal(env);
  const stranger = await call(env, {
    method: "GET",
    path: `/v1/deals/${deal.dealId}`,
    participant: opaque("C"),
  });
  assert.equal(stranger.status, 403);
  assert.equal(stranger.json.error, "forbidden");

  const otherDeal = opaque("e");
  const created = await call(env, {
    method: "POST",
    path: "/v1/deals",
    participant: opaque("C"),
    body: {
      dealId: otherDeal,
      partyId: opaque("c"),
      signingPublicKey: randomBytes(32).toString("base64url"),
      encryptionPublicKey: randomBytes(32).toString("base64url"),
    },
  });
  assert.equal(created.status, 201);
  const cross = await call(env, {
    method: "GET",
    path: `/v1/deals/${otherDeal}`,
    participant: deal.participantA,
  });
  assert.equal(cross.status, 403);
  const own = await call(env, {
    method: "GET",
    path: `/v1/deals/${deal.dealId}`,
    participant: deal.participantA,
  });
  assert.equal(own.status, 200);
  assert.equal(own.json.status, "FULLY_SIGNED");
});

test("party A cannot delete or overwrite party B backup", async () => {
  const env = await freshEnv();
  const deal = await openSignedDeal(env);
  const bytesA = Buffer.from("ciphertext-party-a-0123456789abcdef");
  const bytesB = Buffer.from("ciphertext-party-b-0123456789abcdef");
  const backupA = await backup(env, deal, deal.participantA, bytesA);
  const backupB = await backup(env, deal, deal.participantB, bytesB);
  assert.equal(backupA.objectKey, backupKey(deal.dealId, deal.versionId, deal.partyA));
  assert.equal(backupB.objectKey, backupKey(deal.dealId, deal.versionId, deal.partyB));
  assert.notEqual(backupA.objectKey, backupB.objectKey);

  const crossed = await call(env, {
    method: "DELETE",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backup?partyId=${deal.partyB}`,
    participant: deal.participantA,
  });
  assert.equal(crossed.status, 403);
  assert.equal(crossed.json.error, "cross_party");
  assert.ok(bytesB.equals(await env.BUCKET.get(backupB.objectKey)));

  const selfDelete = await call(env, {
    method: "DELETE",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backup`,
    participant: deal.participantA,
  });
  assert.equal(selfDelete.status, 409);
  assert.equal(selfDelete.json.error, "immutable_object");
  assert.ok(bytesA.equals(await env.BUCKET.get(backupA.objectKey)));
  assert.ok(bytesB.equals(await env.BUCKET.get(backupB.objectKey)));

  const overwrite = await call(env, {
    method: "PUT",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups/object`,
    participant: deal.participantA,
    body: Buffer.from("ciphertext-party-a-OVERWRITE-attempt!!"),
    headers: {
      "x-upload-id": backupB.uploadId,
      "x-ciphertext-sha256": createHash("sha256").update("ciphertext-party-a-OVERWRITE-attempt!!").digest("base64url"),
      "content-type": "application/octet-stream",
    },
  });
  assert.equal(overwrite.status, 403);
  assert.equal(overwrite.json.error, "cross_party");
  assert.ok(bytesB.equals(await env.BUCKET.get(backupB.objectKey)));

  const clientKey = await call(env, {
    method: "PUT",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups/object?objectKey=${encodeURIComponent(backupB.objectKey)}`,
    participant: deal.participantA,
    body: bytesA,
    headers: {
      "x-upload-id": backupA.uploadId,
      "x-ciphertext-sha256": backupA.hash,
      "content-type": "application/octet-stream",
    },
  });
  assert.equal(clientKey.status, 400);
  assert.equal(clientKey.json.error, "untrusted_field");
});

test("duplicate prepare and identical reupload are idempotent", async () => {
  const env = await freshEnv();
  const participant = opaque("A");
  const body = JSON.stringify({
    dealId: opaque("z"),
    partyId: opaque("y"),
    signingPublicKey: randomBytes(32).toString("base64url"),
    encryptionPublicKey: randomBytes(32).toString("base64url"),
  });
  const key = "idem-create-01";
  const first = await call(env, {
    method: "POST",
    path: "/v1/deals",
    participant,
    body,
    headers: { "idempotency-key": key },
  });
  const second = await call(env, {
    method: "POST",
    path: "/v1/deals",
    participant,
    body,
    headers: { "idempotency-key": key },
  });
  assert.equal(first.status, 201);
  assert.equal(second.status, 201);
  assert.deepEqual(second.json, first.json);
  const deals = env.DB.prepare("SELECT COUNT(*) AS n FROM deals").bind().all().results;
  assert.equal(deals[0].n, 1);
  const changed = await call(env, {
    method: "POST",
    path: "/v1/deals",
    participant,
    body: body.replace(opaque("z"), opaque("e")),
    headers: { "idempotency-key": key },
  });
  assert.equal(changed.status, 409);
  assert.equal(changed.json.error, "idempotency_conflict");

  const deal = await openSignedDeal(env);
  const prepared = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:prepare`,
    participant: deal.participantA,
    body: "{}",
  });
  const again = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:prepare`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(prepared.status, 201);
  assert.equal(again.status, 200);
  assert.equal(again.json.uploadId, prepared.json.uploadId);
  const withKey = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:prepare`,
    participant: deal.participantA,
    body: '{"objectKey":"deals/client/chosen"}',
  });
  assert.equal(withKey.status, 400);

  const bytes = Buffer.from("ciphertext-idempotent-0123456789abcd");
  const hash = createHash("sha256").update(bytes).digest("base64url");
  const put = await call(env, {
    method: "PUT",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups/object`,
    participant: deal.participantA,
    body: bytes,
    headers: { "x-upload-id": prepared.json.uploadId, "x-ciphertext-sha256": hash },
  });
  assert.equal(put.status, 200);
  const confirmed = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:confirm`,
    participant: deal.participantA,
    body: "{}",
  });
  const confirmedAgain = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:confirm`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(confirmed.status, 201);
  assert.equal(confirmedAgain.status, 200);
  assert.equal(confirmedAgain.json.receiptId, confirmed.json.receiptId);
  const sameObject = await call(env, {
    method: "PUT",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups/object`,
    participant: deal.participantA,
    body: bytes,
    headers: { "x-upload-id": prepared.json.uploadId, "x-ciphertext-sha256": hash },
  });
  assert.equal(sameObject.status, 200);
  assert.equal(sameObject.json.status, "confirmed");
});

test("interrupted upload can be completed and a corrupt object is rejected", async () => {
  const env = await freshEnv();
  const deal = await openSignedDeal(env);
  const prepared = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:prepare`,
    participant: deal.participantA,
    body: "{}",
  });
  const early = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:confirm`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(early.status, 409);
  assert.equal(early.json.error, "object_missing");
  const missing = await call(env, {
    method: "GET",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backup`,
    participant: deal.participantA,
  });
  assert.equal(missing.status, 404);

  const bytes = Buffer.from("ciphertext-recovered-0123456789abcdef");
  const hash = createHash("sha256").update(bytes).digest("base64url");
  const uploaded = await call(env, {
    method: "PUT",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups/object`,
    participant: deal.participantA,
    body: bytes,
    headers: { "x-upload-id": prepared.json.uploadId, "x-ciphertext-sha256": hash },
  });
  assert.equal(uploaded.status, 200);
  const key = backupKey(deal.dealId, deal.versionId, deal.partyA);
  const stored = Buffer.from(await env.BUCKET.get(key));
  stored[0] ^= 0x01;
  await env.BUCKET.put(key, stored);
  const corrupt = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:confirm`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(corrupt.status, 400);
  assert.equal(corrupt.json.error, "corrupt_backup");
  const stillMissing = await call(env, {
    method: "GET",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backup`,
    participant: deal.participantA,
  });
  assert.equal(stillMissing.status, 404);

  await env.BUCKET.put(key, bytes);
  const recovered = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/versions/${deal.versionId}/backups:confirm`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(recovered.status, 201);
  const tooSoon = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/execute`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(tooSoon.status, 409);
  assert.equal(tooSoon.json.error, "backup_missing");
  const status = await call(env, {
    method: "GET",
    path: `/v1/deals/${deal.dealId}`,
    participant: deal.participantB,
  });
  assert.equal(status.json.status, "FULLY_SIGNED");

  await backup(env, deal, deal.participantB, bytes);
  const executed = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/execute`,
    participant: deal.participantB,
    body: "{}",
  });
  assert.equal(executed.status, 200);
  assert.equal(executed.json.status, "EXECUTED");
  const again = await call(env, {
    method: "POST",
    path: `/v1/deals/${deal.dealId}/execute`,
    participant: deal.participantA,
    body: "{}",
  });
  assert.equal(again.status, 200);
  assert.equal(again.json.status, "EXECUTED");
});
