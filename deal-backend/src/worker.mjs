import { createHash, createHmac, createPublicKey, randomBytes, timingSafeEqual, verify } from "node:crypto";

const ID = /^[A-Za-z0-9_-]{22,43}$/;
const IDEMPOTENCY = /^[A-Za-z0-9_-]{8,128}$/;
const MAX_JSON = 16 * 1024;
const MAX_OBJECT = 1024 * 1024;

export class DealError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export function backupKey(dealId, versionId, partyId) {
  return `deals/${dealId}/versions/${versionId}/parties/${partyId}/backup.enc`;
}

export function signaturePayload({ action, dealId, versionId, partyId, publicKey, documentHash }) {
  const out = [];
  out.push(Buffer.from("ArmorDeal/sign/v1\0", "utf8"));
  out.push(lp(action));
  out.push(u16(1));
  out.push(lp(dealId));
  out.push(lp(versionId));
  out.push(lp(partyId));
  out.push(u16(publicKey.length));
  out.push(publicKey);
  out.push(u16(documentHash.length));
  out.push(documentHash);
  return Buffer.concat(out);
}

export function mintLocalTestToken(secret, participantId, nowMs, lifetimeSec = 300) {
  const exp = Math.floor(nowMs / 1000) + lifetimeSec;
  const nonce = randomBytes(16).toString("base64url");
  const mac = createHmac("sha256", secret).update(tokenMaterial(participantId, exp, nonce)).digest("hex");
  return `v1.${participantId}.${exp}.${nonce}.${mac}`;
}

function tokenMaterial(participantId, exp, nonce) {
  return `v1\n${participantId}\n${exp}\n${nonce}`;
}

function u16(value) {
  const out = Buffer.alloc(2);
  out.writeUInt16BE(value);
  return out;
}

function lp(text) {
  const body = Buffer.from(text, "utf8");
  if (body.length > 65535) throw new DealError(400, "invalid_field");
  return Buffer.concat([u16(body.length), body]);
}

function b64(bytes) {
  return Buffer.from(bytes).toString("base64url");
}

function fromB64(value, expectedLength) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new DealError(400, "invalid_field");
  }
  const decoded = Buffer.from(value, "base64url");
  if (expectedLength != null && decoded.length !== expectedLength) throw new DealError(400, "invalid_field");
  return decoded;
}

function requireId(value) {
  if (typeof value !== "string" || !ID.test(value)) throw new DealError(400, "invalid_field");
  return value;
}

function exact(body, keys) {
  const present = Object.keys(body);
  if (present.length !== keys.length || present.some((key) => !keys.includes(key))) {
    throw new DealError(400, "invalid_field");
  }
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest();
}

function assertConfigured(env) {
  if (env.ENVIRONMENT === "production" || env.AUTH_MODE !== "local-test") {
    throw new DealError(500, "auth_not_configured");
  }
  if (typeof env.LOCAL_TEST_AUTH_SECRET !== "string" || env.LOCAL_TEST_AUTH_SECRET.length < 32) {
    throw new DealError(500, "auth_not_configured");
  }
}

function authenticate(request, env) {
  assertConfigured(env);
  const header = request.headers.get("authorization") || "";
  const matched = header.match(/^Bearer ([A-Za-z0-9_.-]+)$/);
  if (!matched) throw new DealError(401, "unauthenticated");
  const parts = matched[1].split(".");
  if (parts.length !== 5 || parts[0] !== "v1") throw new DealError(401, "unauthenticated");
  const participantId = parts[1];
  const exp = Number(parts[2]);
  const nonce = parts[3];
  const mac = parts[4];
  if (!ID.test(participantId) || !/^[A-Za-z0-9_-]{16,64}$/.test(nonce) || !/^[0-9a-f]{64}$/.test(mac)) {
    throw new DealError(401, "unauthenticated");
  }
  const nowSec = Math.floor(env.clock.nowMs() / 1000);
  if (!Number.isSafeInteger(exp) || exp <= nowSec || exp > nowSec + 3600) {
    throw new DealError(401, "unauthenticated");
  }
  const expected = createHmac("sha256", env.LOCAL_TEST_AUTH_SECRET)
    .update(tokenMaterial(participantId, exp, nonce))
    .digest("hex");
  const left = Buffer.from(mac);
  const right = Buffer.from(expected);
  if (left.length !== right.length || !timingSafeEqual(left, right)) {
    throw new DealError(401, "unauthenticated");
  }
  return { participantId, nonce };
}

function consumeNonce(env, actor) {
  try {
    env.DB.prepare("INSERT INTO used_nonces (nonce, participant_id, used_at_ms) VALUES (?, ?, ?)")
      .bind(actor.nonce, actor.participantId, env.clock.nowMs())
      .run();
  } catch {
    throw new DealError(401, "replayed");
  }
}

function audit(env, actor, dealId, action, outcome) {
  try {
    env.DB.prepare(
      "INSERT INTO audit_events (event_id, deal_id, actor_participant_id, action, outcome, created_at_ms) VALUES (?, ?, ?, ?, ?, ?)",
    )
      .bind(randomBytes(16).toString("base64url"), dealId, actor?.participantId || "anonymous", action, outcome, env.clock.nowMs())
      .run();
  } catch {
    // Audit failure must not replace the caller's authorization result.
  }
}

function json(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", ...extraHeaders },
  });
}

function member(env, dealId, participantId) {
  const deal = env.DB.prepare("SELECT deal_id, status, current_version_id FROM deals WHERE deal_id = ?")
    .bind(dealId)
    .first();
  if (!deal) throw new DealError(404, "not_found");
  const party = env.DB.prepare("SELECT * FROM deal_parties WHERE deal_id = ? AND participant_id = ?")
    .bind(dealId, participantId)
    .first();
  if (!party) throw new DealError(403, "forbidden");
  return { deal, party };
}

function publicKey(raw) {
  return createPublicKey({
    key: { kty: "OKP", crv: "Ed25519", x: b64(raw) },
    format: "jwk",
  });
}

function verifySignature(env, dealId, versionId, party, action, signature) {
  const version = env.DB.prepare(
    "SELECT document_sha256 FROM deal_versions WHERE deal_id = ? AND version_id = ?",
  )
    .bind(dealId, versionId)
    .first();
  if (!version) throw new DealError(404, "not_found");
  const payload = signaturePayload({
    action,
    dealId,
    versionId,
    partyId: party.party_id,
    publicKey: fromB64(party.signing_public_key, 32),
    documentHash: fromB64(version.document_sha256, 32),
  });
  const ok = verify(null, payload, publicKey(fromB64(party.signing_public_key, 32)), signature);
  if (!ok) throw new DealError(400, "invalid_signature");
}

async function readBody(request, limit) {
  const raw = Buffer.from(await request.arrayBuffer());
  if (raw.length > limit) throw new DealError(413, "too_large");
  return raw;
}

function readJson(raw) {
  let parsed;
  try {
    parsed = JSON.parse(raw.toString("utf8"));
  } catch {
    throw new DealError(400, "invalid_json");
  }
  if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new DealError(400, "invalid_json");
  }
  return parsed;
}

function keysOf(body) {
  const signing = fromB64(body.signingPublicKey, 32);
  const encryption = fromB64(body.encryptionPublicKey, 32);
  if (signing.equals(encryption)) throw new DealError(400, "invalid_field");
  return { signing: b64(signing), encryption: b64(encryption) };
}

function createDeal(env, actor, body) {
  exact(body, ["dealId", "partyId", "signingPublicKey", "encryptionPublicKey"]);
  const dealId = requireId(body.dealId);
  const partyId = requireId(body.partyId);
  const keys = keysOf(body);
  if (env.DB.prepare("SELECT deal_id FROM deals WHERE deal_id = ?").bind(dealId).first()) {
    throw new DealError(409, "conflict");
  }
  const now = env.clock.nowMs();
  env.DB.prepare(
    "INSERT INTO deals (deal_id, status, current_version_id, created_at_ms, updated_at_ms) VALUES (?, 'OPEN', NULL, ?, ?)",
  )
    .bind(dealId, now, now)
    .run();
  env.DB.prepare(
    `INSERT INTO deal_parties (deal_id, party_id, participant_id, role, signing_public_key, encryption_public_key)
     VALUES (?, ?, ?, 'PROPOSER', ?, ?)`,
  )
    .bind(dealId, partyId, actor.participantId, keys.signing, keys.encryption)
    .run();
  return json(201, { dealId, partyId, role: "PROPOSER", status: "OPEN" });
}

function joinDeal(env, actor, dealId, body) {
  exact(body, ["partyId", "signingPublicKey", "encryptionPublicKey"]);
  requireId(dealId);
  const deal = env.DB.prepare("SELECT deal_id FROM deals WHERE deal_id = ?").bind(dealId).first();
  if (!deal) throw new DealError(404, "not_found");
  if (env.DB.prepare("SELECT party_id FROM deal_parties WHERE deal_id = ? AND participant_id = ?").bind(dealId, actor.participantId).first()) {
    throw new DealError(409, "conflict");
  }
  const existing = env.DB.prepare("SELECT party_id, signing_public_key, encryption_public_key FROM deal_parties WHERE deal_id = ?").bind(dealId).all().results;
  if (existing.length !== 1) throw new DealError(409, "conflict");
  const partyId = requireId(body.partyId);
  if (existing.some((row) => row.party_id === partyId)) throw new DealError(409, "conflict");
  const keys = keysOf(body);
  if (existing.some((row) => row.signing_public_key === keys.signing || row.encryption_public_key === keys.encryption)) {
    throw new DealError(400, "invalid_field");
  }
  env.DB.prepare(
    `INSERT INTO deal_parties (deal_id, party_id, participant_id, role, signing_public_key, encryption_public_key)
     VALUES (?, ?, ?, 'COUNTERPARTY', ?, ?)`,
  )
    .bind(dealId, partyId, actor.participantId, keys.signing, keys.encryption)
    .run();
  return json(201, { dealId, partyId, role: "COUNTERPARTY" });
}

function addVersion(env, actor, dealId, body) {
  exact(body, ["versionId", "parentVersionId", "documentSha256", "ancestry"]);
  const { deal } = member(env, dealId, actor.participantId);
  const versionId = requireId(body.versionId);
  const parent = body.parentVersionId == null ? null : requireId(body.parentVersionId);
  if (!Array.isArray(body.ancestry)) throw new DealError(400, "invalid_field");
  body.ancestry.forEach(requireId);
  if (parent == null && body.ancestry.length !== 0) throw new DealError(400, "invalid_field");
  if (parent != null && (body.ancestry.length === 0 || body.ancestry.at(-1) !== parent)) {
    throw new DealError(400, "invalid_field");
  }
  const hash = b64(fromB64(body.documentSha256, 32));
  if (deal.current_version_id) {
    const current = env.DB.prepare(
      "SELECT immutable FROM deal_versions WHERE deal_id = ? AND version_id = ?",
    )
      .bind(dealId, deal.current_version_id)
      .first();
    if (current?.immutable) throw new DealError(409, "immutable_version");
  }
  if (env.DB.prepare("SELECT version_id FROM deal_versions WHERE deal_id = ? AND version_id = ?").bind(dealId, versionId).first()) {
    throw new DealError(409, "conflict");
  }
  const now = env.clock.nowMs();
  env.DB.prepare(
    `INSERT INTO deal_versions (deal_id, version_id, parent_version_id, document_sha256, superseded, immutable, created_at_ms)
     VALUES (?, ?, ?, ?, 0, 0, ?)`,
  )
    .bind(dealId, versionId, parent, hash, now)
    .run();
  if (parent) {
    env.DB.prepare("UPDATE deal_versions SET superseded = 1 WHERE deal_id = ? AND version_id = ?")
      .bind(dealId, parent)
      .run();
  }
  env.DB.prepare("UPDATE deals SET current_version_id = ?, updated_at_ms = ? WHERE deal_id = ?")
    .bind(versionId, now, dealId)
    .run();
  return json(201, { dealId, versionId, documentSha256: hash });
}

function addSignature(env, actor, dealId, versionId, body) {
  exact(body, ["action", "signature"]);
  requireId(versionId);
  const { party } = member(env, dealId, actor.participantId);
  if (body.action !== "SIGN" && body.action !== "REJECT") throw new DealError(400, "invalid_field");
  const version = env.DB.prepare(
    "SELECT immutable FROM deal_versions WHERE deal_id = ? AND version_id = ?",
  )
    .bind(dealId, versionId)
    .first();
  if (!version) throw new DealError(404, "not_found");
  if (version.immutable) throw new DealError(409, "immutable_version");
  const signature = fromB64(body.signature, 64);
  verifySignature(env, dealId, versionId, party, body.action, signature);
  const existing = env.DB.prepare(
    "SELECT signature_b64 FROM deal_signatures WHERE deal_id = ? AND version_id = ? AND party_id = ? AND action = ?",
  )
    .bind(dealId, versionId, party.party_id, body.action)
    .first();
  if (existing) {
    if (existing.signature_b64 !== b64(signature)) throw new DealError(409, "conflict");
    return json(200, { dealId, versionId, partyId: party.party_id, action: body.action });
  }
  env.DB.prepare(
    `INSERT INTO deal_signatures (deal_id, version_id, party_id, action, signature_b64, created_at_ms)
     VALUES (?, ?, ?, ?, ?, ?)`,
  )
    .bind(dealId, versionId, party.party_id, body.action, b64(signature), env.clock.nowMs())
    .run();
  const signs = env.DB.prepare(
    "SELECT party_id FROM deal_signatures WHERE deal_id = ? AND version_id = ? AND action = 'SIGN'",
  )
    .bind(dealId, versionId)
    .all().results;
  if (signs.length === 2) {
    env.DB.prepare("UPDATE deal_versions SET immutable = 1 WHERE deal_id = ? AND version_id = ?")
      .bind(dealId, versionId)
      .run();
    env.DB.prepare("UPDATE deals SET status = 'FULLY_SIGNED', updated_at_ms = ? WHERE deal_id = ?")
      .bind(env.clock.nowMs(), dealId)
      .run();
  }
  return json(201, { dealId, versionId, partyId: party.party_id, action: body.action });
}

function prepareBackup(env, actor, dealId, versionId) {
  const { party } = member(env, dealId, actor.participantId);
  const version = env.DB.prepare(
    "SELECT immutable FROM deal_versions WHERE deal_id = ? AND version_id = ?",
  )
    .bind(dealId, versionId)
    .first();
  if (!version) throw new DealError(404, "not_found");
  if (!version.immutable) throw new DealError(409, "not_final");
  const existing = env.DB.prepare(
    "SELECT upload_id, object_key, status FROM backup_uploads WHERE deal_id = ? AND version_id = ? AND party_id = ?",
  )
    .bind(dealId, versionId, party.party_id)
    .first();
  if (existing) {
    return json(200, { uploadId: existing.upload_id, objectKey: existing.object_key, status: existing.status });
  }
  const uploadId = randomBytes(16).toString("base64url");
  const objectKey = backupKey(dealId, versionId, party.party_id);
  env.DB.prepare(
    `INSERT INTO backup_uploads (upload_id, deal_id, version_id, party_id, object_key, ciphertext_sha256, status, created_at_ms)
     VALUES (?, ?, ?, ?, ?, NULL, 'pending', ?)`,
  )
    .bind(uploadId, dealId, versionId, party.party_id, objectKey, env.clock.nowMs())
    .run();
  return json(201, { uploadId, objectKey, status: "pending", maxBytes: MAX_OBJECT });
}

async function putBackup(env, actor, dealId, versionId, request) {
  if (new URL(request.url).searchParams.has("objectKey") || new URL(request.url).searchParams.has("partyId")) {
    throw new DealError(400, "untrusted_field");
  }
  const { party } = member(env, dealId, actor.participantId);
  const uploadId = request.headers.get("x-upload-id");
  if (!uploadId || !/^[A-Za-z0-9_-]{8,64}$/.test(uploadId)) throw new DealError(400, "invalid_field");
  const declared = fromB64(request.headers.get("x-ciphertext-sha256") || "", 32);
  const raw = await readBody(request, MAX_OBJECT);
  if (raw.length < 16) throw new DealError(400, "invalid_field");
  const actual = sha256(raw);
  if (!actual.equals(declared)) throw new DealError(400, "hash_mismatch");
  const upload = env.DB.prepare("SELECT * FROM backup_uploads WHERE upload_id = ?").bind(uploadId).first();
  if (!upload) throw new DealError(404, "not_found");
  if (upload.deal_id !== dealId || upload.version_id !== versionId || upload.party_id !== party.party_id) {
    throw new DealError(403, "cross_party");
  }
  const objectKey = backupKey(dealId, versionId, party.party_id);
  if (upload.object_key !== objectKey) throw new DealError(403, "cross_party");
  if (upload.status === "confirmed") {
    const current = await env.BUCKET.get(objectKey);
    if (!current || !sha256(current).equals(actual)) throw new DealError(409, "immutable_object");
    return json(200, { uploadId, objectKey, status: "confirmed" });
  }
  await env.BUCKET.put(objectKey, raw);
  env.DB.prepare("UPDATE backup_uploads SET ciphertext_sha256 = ? WHERE upload_id = ?")
    .bind(b64(actual), uploadId)
    .run();
  return json(200, { uploadId, objectKey, status: "pending" });
}

async function confirmBackup(env, actor, dealId, versionId) {
  const { party } = member(env, dealId, actor.participantId);
  const upload = env.DB.prepare(
    "SELECT * FROM backup_uploads WHERE deal_id = ? AND version_id = ? AND party_id = ?",
  )
    .bind(dealId, versionId, party.party_id)
    .first();
  if (!upload) throw new DealError(404, "not_found");
  const objectKey = backupKey(dealId, versionId, party.party_id);
  const bytes = await env.BUCKET.get(objectKey);
  if (!bytes) throw new DealError(409, "object_missing");
  const actual = b64(sha256(bytes));
  if (!upload.ciphertext_sha256 || upload.ciphertext_sha256 !== actual) {
    throw new DealError(400, "corrupt_backup");
  }
  const existing = env.DB.prepare(
    "SELECT receipt_id, ciphertext_sha256, object_key, confirmed_at_ms FROM backup_receipts WHERE deal_id = ? AND version_id = ? AND party_id = ?",
  )
    .bind(dealId, versionId, party.party_id)
    .first();
  if (existing) {
    if (existing.ciphertext_sha256 !== actual || existing.object_key !== objectKey) {
      throw new DealError(409, "conflict");
    }
    return json(200, {
      receiptId: existing.receipt_id,
      dealId,
      versionId,
      partyId: party.party_id,
      objectKey,
      ciphertextSha256: actual,
      confirmedAtMs: existing.confirmed_at_ms,
    });
  }
  const receiptId = randomBytes(16).toString("base64url");
  const now = env.clock.nowMs();
  env.DB.prepare(
    `INSERT INTO backup_receipts (receipt_id, deal_id, version_id, party_id, object_key, ciphertext_sha256, confirmed_at_ms)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(receiptId, dealId, versionId, party.party_id, objectKey, actual, now)
    .run();
  env.DB.prepare("UPDATE backup_uploads SET status = 'confirmed' WHERE upload_id = ?").bind(upload.upload_id).run();
  return json(201, {
    receiptId,
    dealId,
    versionId,
    partyId: party.party_id,
    objectKey,
    ciphertextSha256: actual,
    confirmedAtMs: now,
  });
}

async function deleteBackup(env, actor, dealId, versionId, request) {
  const url = new URL(request.url);
  if (url.searchParams.has("objectKey")) throw new DealError(400, "untrusted_field");
  const { party } = member(env, dealId, actor.participantId);
  const requested = url.searchParams.get("partyId");
  if (requested != null && requested !== party.party_id) throw new DealError(403, "cross_party");
  const receipt = env.DB.prepare(
    "SELECT receipt_id FROM backup_receipts WHERE deal_id = ? AND version_id = ? AND party_id = ?",
  )
    .bind(dealId, versionId, party.party_id)
    .first();
  if (receipt) throw new DealError(409, "immutable_object");
  const objectKey = backupKey(dealId, versionId, party.party_id);
  await env.BUCKET.delete(objectKey);
  env.DB.prepare("DELETE FROM backup_uploads WHERE deal_id = ? AND version_id = ? AND party_id = ? AND status = 'pending'")
    .bind(dealId, versionId, party.party_id)
    .run();
  return json(200, { deleted: "pending_self_only" });
}

function lookupBackup(env, actor, dealId, versionId) {
  const { party } = member(env, dealId, actor.participantId);
  const receipt = env.DB.prepare(
    "SELECT receipt_id, object_key, ciphertext_sha256, confirmed_at_ms FROM backup_receipts WHERE deal_id = ? AND version_id = ? AND party_id = ?",
  )
    .bind(dealId, versionId, party.party_id)
    .first();
  if (!receipt) throw new DealError(404, "not_found");
  return json(200, {
    receiptId: receipt.receipt_id,
    dealId,
    versionId,
    partyId: party.party_id,
    objectKey: receipt.object_key,
    ciphertextSha256: receipt.ciphertext_sha256,
    confirmedAtMs: receipt.confirmed_at_ms,
  });
}

function executeDeal(env, actor, dealId) {
  const { deal, party } = member(env, dealId, actor.participantId);
  if (deal.status === "EXECUTED") return json(200, { dealId, status: "EXECUTED" });
  const versionId = deal.current_version_id;
  if (!versionId) throw new DealError(409, "not_final");
  const version = env.DB.prepare(
    "SELECT immutable, document_sha256 FROM deal_versions WHERE deal_id = ? AND version_id = ?",
  )
    .bind(dealId, versionId)
    .first();
  if (!version?.immutable) throw new DealError(409, "not_final");
  const parties = env.DB.prepare("SELECT * FROM deal_parties WHERE deal_id = ?").bind(dealId).all().results;
  const signatures = env.DB.prepare(
    "SELECT * FROM deal_signatures WHERE deal_id = ? AND version_id = ? AND action = 'SIGN'",
  )
    .bind(dealId, versionId)
    .all().results;
  if (parties.length !== 2 || signatures.length !== 2) throw new DealError(409, "not_final");
  for (const row of signatures) {
    const signer = parties.find((item) => item.party_id === row.party_id);
    verifySignature(env, dealId, versionId, signer, "SIGN", fromB64(row.signature_b64, 64));
  }
  const receipts = env.DB.prepare(
    "SELECT party_id, ciphertext_sha256, object_key FROM backup_receipts WHERE deal_id = ? AND version_id = ?",
  )
    .bind(dealId, versionId)
    .all().results;
  if (receipts.length !== 2) throw new DealError(409, "backup_missing");
  const hashes = new Set(receipts.map((row) => row.ciphertext_sha256));
  if (hashes.size !== 1) throw new DealError(409, "backup_mismatch");
  for (const receipt of receipts) {
    const owner = parties.find((item) => item.party_id === receipt.party_id);
    if (!owner || receipt.object_key !== backupKey(dealId, versionId, owner.party_id)) {
      throw new DealError(409, "backup_mismatch");
    }
  }
  env.DB.prepare("UPDATE deals SET status = 'EXECUTED', updated_at_ms = ? WHERE deal_id = ?")
    .bind(env.clock.nowMs(), dealId)
    .run();
  return json(200, { dealId, versionId, status: "EXECUTED", partyId: party.party_id });
}

function getDeal(env, actor, dealId) {
  const { deal } = member(env, dealId, actor.participantId);
  return json(200, { dealId, status: deal.status, currentVersionId: deal.current_version_id });
}

function idempotencyLookup(env, actor, key, requestHash) {
  if (!key) throw new DealError(400, "invalid_field");
  if (!IDEMPOTENCY.test(key)) throw new DealError(400, "invalid_field");
  const existing = env.DB.prepare(
    "SELECT request_sha256, status_code, response_json FROM idempotency_keys WHERE participant_id = ? AND idempotency_key = ?",
  )
    .bind(actor.participantId, key)
    .first();
  if (!existing) return null;
  if (existing.request_sha256 !== requestHash) throw new DealError(409, "idempotency_conflict");
  return json(existing.status_code, JSON.parse(existing.response_json));
}

function storeIdempotency(env, actor, key, requestHash, response, body) {
  env.DB.prepare(
    `INSERT INTO idempotency_keys (participant_id, idempotency_key, request_sha256, status_code, response_json, created_at_ms)
     VALUES (?, ?, ?, ?, ?, ?)`,
  )
    .bind(actor.participantId, key, requestHash, response.status, JSON.stringify(body), env.clock.nowMs())
    .run();
}

async function route(request, env, actor, raw) {
  const url = new URL(request.url);
  const parts = url.pathname.split("/").filter(Boolean);
  const method = request.method;
  if (parts[0] !== "v1" || parts[1] !== "deals") throw new DealError(404, "not_found");

  if (method === "POST" && parts.length === 2) return createDeal(env, actor, readJson(raw));
  const dealId = parts[2];
  if (dealId) requireId(dealId);

  if (method === "POST" && parts.length === 4 && parts[3] === "join") return joinDeal(env, actor, dealId, readJson(raw));
  if (method === "GET" && parts.length === 3) return getDeal(env, actor, dealId);
  if (method === "POST" && parts.length === 4 && parts[3] === "execute") {
    exact(readJson(raw), []);
    return executeDeal(env, actor, dealId);
  }
  if (method === "POST" && parts.length === 4 && parts[3] === "versions") return addVersion(env, actor, dealId, readJson(raw));

  const versionId = parts[4];
  if (parts[3] === "versions" && versionId) requireId(versionId);
  if (method === "POST" && parts.length === 6 && parts[5] === "signatures") {
    return addSignature(env, actor, dealId, versionId, readJson(raw));
  }
  if (method === "POST" && parts.length === 6 && parts[5] === "backups:prepare") {
    exact(readJson(raw), []);
    return prepareBackup(env, actor, dealId, versionId);
  }
  if (method === "PUT" && parts.length === 7 && parts[5] === "backups" && parts[6] === "object") {
    return putBackup(env, actor, dealId, versionId, request);
  }
  if (method === "POST" && parts.length === 6 && parts[5] === "backups:confirm") {
    exact(readJson(raw), []);
    return confirmBackup(env, actor, dealId, versionId);
  }
  if (method === "GET" && parts.length === 6 && parts[5] === "backup") return lookupBackup(env, actor, dealId, versionId);
  if (method === "DELETE" && parts.length === 6 && parts[5] === "backup") {
    return deleteBackup(env, actor, dealId, versionId, request);
  }
  throw new DealError(404, "not_found");
}

export async function handleRequest(request, env) {
  const url = new URL(request.url);
  let actor = null;
  let action = `${request.method} ${url.pathname}`;
  try {
    actor = authenticate(request, env);
    const mutating = request.method !== "GET" && request.method !== "HEAD";
    if (mutating) consumeNonce(env, actor);
    const raw = request.method === "PUT" ? null : Buffer.from(await request.clone().arrayBuffer());
    if (raw && raw.length > MAX_JSON && request.method !== "PUT") throw new DealError(413, "too_large");
    let response;
    if (request.method === "POST") {
      const key = request.headers.get("idempotency-key");
      const requestHash = b64(sha256(Buffer.concat([Buffer.from(`${request.method}\n${url.pathname}\n`), raw])));
      const replay = idempotencyLookup(env, actor, key, requestHash);
      if (replay) {
        audit(env, actor, url.pathname.split("/")[3] || null, action, "idempotent");
        return replay;
      }
      response = await route(request, env, actor, raw);
      if (response.status < 400) {
        const body = await response.clone().json();
        storeIdempotency(env, actor, key, requestHash, response, body);
      }
    } else {
      response = await route(request, env, actor, raw || Buffer.alloc(0));
    }
    audit(env, actor, url.pathname.split("/")[3] || null, action, response.status < 400 ? "ok" : "error");
    return response;
  } catch (error) {
    if (error instanceof DealError) {
      audit(env, actor, url.pathname.split("/")[3] || null, action, error.code);
      return json(error.status, { error: error.code });
    }
    const body = env.ENVIRONMENT === "test"
      ? { error: "internal", detail: String(error?.message || "error").slice(0, 200) }
      : { error: "internal" };
    return json(500, body);
  }
}

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },
};
