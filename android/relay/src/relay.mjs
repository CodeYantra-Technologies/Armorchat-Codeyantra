import { createServer } from "node:https";
import { canonicalize, parseStrict, utf8FromBuffer, ParseError } from "./json.mjs";
import {
  BINDING_MAX,
  BUNDLE_MAX,
  CREDENTIAL_MAX,
  INVITE_TTL_SECONDS,
  PAYLOAD_MAX,
  PAYLOAD_MIN,
  PEER_GRANT_TTL_SECONDS,
  REQUEST_MAX,
  SUITE,
  TTL_SECONDS,
} from "./store.mjs";
import {
  CLAIM_ID_BYTES,
  DEVICE_ID_BYTES,
  GRANT_ID_BYTES,
  INVITE_TOKEN_BYTES,
  KEY_PACKAGE_ID_BYTES,
  MAILBOX_BYTES,
  MESSAGE_ID_BYTES,
  decodeB64UrlExact,
  sha256B64,
  shortRef,
} from "./ids.mjs";
import { assertWakePayloadSafe, isPlausibleFcmToken, syncHintData } from "./push.mjs";

const FORBIDDEN_STATES = new Set(["delivered", "read", "DELIVERED", "READ"]);

export class RelayError extends Error {
  constructor(status, code, extra = {}) {
    super(code);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

function logEvent(event, fields = {}) {
  const rec = { event, ...fields };
  const blob = JSON.stringify(rec);
  if (/owner_token|submit_capability|Authorization|encrypted_payload|binding|grant_token|"token":|fcm_token|fcmtoken|registration_token/i.test(blob)) {
    process.stderr.write(JSON.stringify({ event: "log_rejected" }) + "\n");
    return;
  }
  process.stderr.write(blob + "\n");
}

function jsonResponse(res, status, body, headers = {}) {
  const raw = canonicalize(body);
  if (FORBIDDEN_STATES.has(body.state) || /"state":"(delivered|read)"/i.test(raw)) {
    throw new Error("relay must not emit delivered/read");
  }
  const buf = Buffer.from(raw, "utf8");
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "content-length": buf.length,
    ...headers,
  });
  res.end(buf);
}

function errorResponse(res, err) {
  if (err instanceof RelayError) {
    const body = { protocol_version: 1, error: err.code, ...err.extra };
    const headers = {};
    if (err.status === 503 && err.extra.retry_after != null) {
      headers["retry-after"] = String(err.extra.retry_after);
    }
    jsonResponse(res, err.status, body, headers);
    return;
  }
  if (err instanceof ParseError) {
    jsonResponse(res, 400, { protocol_version: 1, error: "MALFORMED" });
    return;
  }
  logEvent("internal_error");
  jsonResponse(res, 500, { protocol_version: 1, error: "INTERNAL" });
}

function readBody(req, max = REQUEST_MAX) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let n = 0;
    req.on("data", (c) => {
      n += c.length;
      if (n > max) {
        req.destroy();
        reject(new RelayError(413, "PAYLOAD_TOO_LARGE"));
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", () => reject(new RelayError(400, "MALFORMED")));
  });
}

function requireJson(req) {
  const ct = String(req.headers["content-type"] || "");
  if (!/^application\/json(?:\s*;.*)?$/i.test(ct)) {
    throw new RelayError(400, "MALFORMED");
  }
  if (req.headers["content-encoding"]) {
    throw new RelayError(400, "MALFORMED");
  }
}

function bearer(req) {
  const h = req.headers.authorization;
  if (typeof h !== "string" || !h.startsWith("Bearer ")) return null;
  const token = h.slice(7).trim();
  if (!token || /\s/.test(token)) return null;
  return token;
}

function auth(store, req, kind) {
  const token = bearer(req);
  if (!token) throw new RelayError(401, "UNAUTHORIZED");
  const rec = store.lookupToken(token);
  if (!rec || rec.kind !== kind) throw new RelayError(401, "UNAUTHORIZED");
  return rec;
}

function authWrite(store, req) {
  const token = bearer(req);
  if (!token) throw new RelayError(401, "UNAUTHORIZED");
  const rec = store.lookupToken(token);
  if (!rec || !["submit", "bootstrap", "peer"].includes(rec.kind)) {
    throw new RelayError(401, "UNAUTHORIZED");
  }
  return rec;
}

function requireSuite(obj) {
  if (obj.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
  if (obj.suite !== SUITE) throw new RelayError(400, "MALFORMED");
}

function exactObject(obj, keys) {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) throw new RelayError(400, "MALFORMED");
  const have = Object.keys(obj);
  if (have.length !== keys.length) throw new RelayError(400, "MALFORMED");
  for (const k of keys) {
    if (!Object.prototype.hasOwnProperty.call(obj, k)) throw new RelayError(400, "MALFORMED");
  }
}

function takeToken(buckets, key, ratePerMin, burst) {
  const t = Date.now();
  let b = buckets.get(key);
  if (!b) {
    b = { tokens: burst, t };
    buckets.set(key, b);
  }
  const elapsed = (t - b.t) / 1000;
  const currentTokens = Math.min(burst, b.tokens + elapsed * (ratePerMin / 60));
  if (currentTokens < 1) {
    return false;
  }
  b.tokens = currentTokens - 1;
  b.t = t;
  return true;
}

function clientIp(req) {
  return req.socket.remoteAddress || "unknown";
}

export function createRelay({ store, tls, mailboxLimitPerHour = 1000, pushSender = null }) {
  const mailboxCreates = new Map();
  const buckets = new Map();

  async function wakeMailbox(mailboxId) {
    if (!pushSender) return;
    const regs = store.listPushTokens(mailboxId);
    for (const rec of regs) {
      try {
        const payload = syncHintData();
        if (!assertWakePayloadSafe(payload)) {
          logEvent("push_payload_rejected", { fp: rec.fingerprint });
          continue;
        }
        const result = await pushSender.sendSyncHint(rec.token);
        if (result === "invalid") {
          await store.deletePushByTokenHash(rec.token_hash);
          logEvent("push_token_dropped", { fp: rec.fingerprint });
        }
      } catch {
        logEvent("push_wake_failed", { mailbox: shortRef(mailboxId), fp: rec.fingerprint });
      }
    }
  }

  function scheduleWake(mailboxId) {
    if (!pushSender || !mailboxId) return;
    queueMicrotask(() => {
      wakeMailbox(mailboxId).catch(() => {
        logEvent("push_wake_failed", { mailbox: shortRef(mailboxId) });
      });
    });
  }

  function checkMailboxQuota(ip) {
    const t = store.now();
    const windowStart = t - 3600;
    const list = (mailboxCreates.get(ip) || []).filter((x) => x > windowStart);
    if (list.length >= mailboxLimitPerHour) throw new RelayError(429, "THROTTLED", { retry_after: 60 });
    list.push(t);
    mailboxCreates.set(ip, list);
  }

  async function handle(req, res) {
    try {
      const url = new URL(req.url || "/", "https://relay.invalid");
      if (/[?#]/.test(req.url) && (url.search || url.hash)) {
        // Tokens must never appear in URLs. Any query/fragment is rejected.
        if (url.searchParams.toString() || url.hash) throw new RelayError(400, "MALFORMED");
      }
      const path = url.pathname;
      await store.sweep();
      if (req.method === "GET" && path === "/v1/network-policy") {
        const policy = store.networkPolicy();
        if (!policy) throw new RelayError(404, "NOT_FOUND");
        jsonResponse(res, 200, policy);
        return;
      }
      if (path.startsWith("/v1/blobs/")) {
        const blobId = path.slice("/v1/blobs/".length);
        const decoded = decodeB64UrlExact(blobId);
        if (!decoded || decoded.length < 16 || decoded.length > 64) throw new RelayError(400, "MALFORMED");
        if (req.method === "PUT") {
          const who = auth(store, req, "owner");
          if (store.isSuspended()) throw new RelayError(503, "SERVICE_SUSPENDED", { retry_after: 60 });
          const raw = await readBody(req, 25 * 1024 * 1024);
          if (raw.length < 1) throw new RelayError(400, "MALFORMED");
          const result = await store.putBlob(who.mailbox_id, blobId, raw);
          if (result.status === "conflict") throw new RelayError(409, "CONFLICT");
          jsonResponse(res, 201, { protocol_version: 1, state: "accepted" });
          return;
        }
        if (req.method === "GET") {
          const blob = store.getBlob(blobId);
          if (!blob) throw new RelayError(404, "NOT_FOUND");
          res.writeHead(200, {
            "content-type": "application/octet-stream",
            "cache-control": "no-store",
            "content-length": blob.length,
          });
          res.end(blob);
          return;
        }
        throw new RelayError(400, "MALFORMED");
      }
      if (req.method !== "POST" && req.method !== "PUT") {
        throw new RelayError(400, "MALFORMED");
      }
      if (store.isSuspended()) {
        try {
          await readBody(req);
        } catch {
          // drain
        }
        throw new RelayError(503, "SERVICE_SUSPENDED", { retry_after: 60 });
      }
      requireJson(req);
      const raw = await readBody(req);
      const text = utf8FromBuffer(raw);
      const body = parseStrict(text);
      const canonical = canonicalize(body);
      if (canonical !== text) throw new RelayError(400, "MALFORMED");

      if (req.method === "POST" && path === "/v1/mailboxes") {
        exactObject(body, ["protocol_version"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        checkMailboxQuota(clientIp(req));
        const created = await store.createMailbox();
        logEvent("mailbox_created", { mailbox: shortRef(created.mailbox_id) });
        jsonResponse(res, 201, {
          protocol_version: 1,
          mailbox_id: created.mailbox_id,
          owner_token: created.owner_token,
          submit_capability: created.submit_capability,
        });
        return;
      }

      if ((req.method === "PUT" && path === "/v1/key-packages") || (req.method === "PUT" && path === "/v1/prekeys")) {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "suite", "key_packages"]);
        requireSuite(body);
        if (!Array.isArray(body.key_packages) || body.key_packages.length < 1 || body.key_packages.length > 50) {
          throw new RelayError(400, "MALFORMED");
        }
        const stored = [];
        for (const item of body.key_packages) {
          exactObject(item, ["key_package_id", "expires_at", "binding"]);
          if (!decodeB64UrlExact(item.key_package_id, KEY_PACKAGE_ID_BYTES)) throw new RelayError(400, "MALFORMED");
          if (typeof item.binding !== "string" || item.binding.length < 1 || item.binding.length > BINDING_MAX) {
            throw new RelayError(400, "MALFORMED");
          }
          if (typeof item.expires_at !== "number" || item.expires_at <= store.now() || item.expires_at > store.now() + TTL_SECONDS) {
            throw new RelayError(400, "MALFORMED");
          }
          const result = await store.putKeyPackage(who.mailbox_id, item);
          if (result.status === "conflict") throw new RelayError(409, "CONFLICT");
          stored.push(item.key_package_id);
        }
        jsonResponse(res, 200, { protocol_version: 1, stored: stored.length, state: "accepted" });
        return;
      }

      if (
        (req.method === "POST" && path === "/v1/key-packages/claim") ||
        (req.method === "POST" && path === "/v1/prekeys/claim")
      ) {
        const who = auth(store, req, "submit");
        exactObject(body, ["protocol_version", "claim_id"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.claim_id, CLAIM_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        const submitKey = `claim:${who.mailbox_id}`;
        if (!takeToken(buckets, submitKey, 10 / 60, 10)) throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        const result = await store.claimKeyPackage(who.mailbox_id, body.claim_id);
        if (result.status === "unavailable") throw new RelayError(404, "KEY_PACKAGE_UNAVAILABLE");
        jsonResponse(res, 200, {
          protocol_version: 1,
          suite: SUITE,
          key_package_id: result.item.key_package_id,
          binding: result.item.binding,
          expires_at: result.item.expires_at,
        });
        return;
      }

      if (req.method === "POST" && path === "/v1/envelopes") {
        const who = authWrite(store, req);
        const keys = Object.keys(body);
        const allowed = new Set([
          "protocol_version",
          "suite",
          "message_id",
          "recipient_mailbox",
          "expires_at",
          "payload_type",
          "encrypted_payload",
          "sender_credential",
        ]);
        for (const k of keys) {
          if (!allowed.has(k)) throw new RelayError(400, "MALFORMED");
        }
        for (const k of [
          "protocol_version",
          "suite",
          "message_id",
          "recipient_mailbox",
          "expires_at",
          "payload_type",
          "encrypted_payload",
        ]) {
          if (!Object.prototype.hasOwnProperty.call(body, k)) throw new RelayError(400, "MALFORMED");
        }
        requireSuite(body);
        if (!decodeB64UrlExact(body.message_id, MESSAGE_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.recipient_mailbox, MAILBOX_BYTES)) throw new RelayError(400, "MALFORMED");
        if (body.recipient_mailbox !== who.mailbox_id) throw new RelayError(401, "UNAUTHORIZED");
        if (body.payload_type !== "handshake" && body.payload_type !== "application") {
          throw new RelayError(400, "MALFORMED");
        }
        const payload = decodeB64UrlExact(body.encrypted_payload);
        if (!payload) throw new RelayError(400, "MALFORMED");
        if (payload.length < PAYLOAD_MIN || payload.length > PAYLOAD_MAX) {
          throw new RelayError(413, "PAYLOAD_TOO_LARGE");
        }
        const t = store.now();
        if (typeof body.expires_at !== "number") throw new RelayError(400, "MALFORMED");
        if (body.payload_type === "handshake") {
          if (!body.sender_credential || typeof body.sender_credential !== "object") {
            throw new RelayError(400, "MALFORMED");
          }
          const credBytes = Buffer.byteLength(canonicalize(body.sender_credential), "utf8");
          if (credBytes > CREDENTIAL_MAX) throw new RelayError(413, "PAYLOAD_TOO_LARGE");
        } else if (Object.prototype.hasOwnProperty.call(body, "sender_credential")) {
          throw new RelayError(400, "MALFORMED");
        }
        const idem = req.headers["idempotency-key"];
        if (idem !== body.message_id) throw new RelayError(400, "MALFORMED");
        const envelopeSha = sha256B64(Buffer.from(canonical, "utf8"));
        const existing = store.peekEnvelope(body.recipient_mailbox, body.message_id);
        if (existing) {
          if (existing.envelope_sha256 !== envelopeSha) throw new RelayError(409, "DIGEST_MISMATCH");
          jsonResponse(res, 200, {
            protocol_version: 1,
            message_id: existing.message_id,
            recipient_mailbox: existing.recipient_mailbox,
            envelope_sha256: existing.envelope_sha256,
            accepted_at: existing.accepted_at,
            expires_at: existing.expires_at,
            state: existing.state === "queued" ? "accepted" : existing.state,
          });
          if (existing.state === "queued") scheduleWake(body.recipient_mailbox);
          return;
        }
        if (body.expires_at <= t || body.expires_at > t + TTL_SECONDS) {
          throw new RelayError(400, "MALFORMED");
        }
        const writeKey = who.kind === "bootstrap"
          ? `boot:${who.grant_id || who.mailbox_id}`
          : who.kind === "peer"
            ? `peer:${who.grant_id || who.mailbox_id}`
            : `sub:${who.mailbox_id}`;
        const writeBudget = who.kind === "bootstrap"
          ? takeToken(buckets, writeKey, 20 / 60, 5)
          : takeToken(buckets, writeKey, 60, 20);
        if (!writeBudget) {
          throw new RelayError(429, "THROTTLED", { retry_after: who.kind === "bootstrap" ? 60 : 1 });
        }
        const result = await store.submitEnvelope({
          message_id: body.message_id,
          recipient_mailbox: body.recipient_mailbox,
          envelope_sha256: envelopeSha,
          accepted_at: t,
          expires_at: body.expires_at,
          payload_type: body.payload_type,
          encrypted_payload: body.encrypted_payload,
          sender_credential: body.sender_credential || null,
          canonical_body: canonical,
        });
        if (result.status === "mismatch") throw new RelayError(409, "DIGEST_MISMATCH");
        if (result.status === "full") throw new RelayError(429, "QUEUE_FULL", { retry_after: 60 });
        const status = result.status === "created" ? 201 : 200;
        logEvent("envelope_accepted", {
          mailbox: shortRef(body.recipient_mailbox),
          message: shortRef(body.message_id),
          replay: result.status === "replay",
        });
        jsonResponse(res, status, result.acceptance);
        if (result.status === "created" || result.status === "replay") {
          scheduleWake(body.recipient_mailbox);
        }
        return;
      }

      if (req.method === "POST" && path === "/v1/inbox/fetch") {
        const who = auth(store, req, "owner");
        const keys = Object.keys(body);
        for (const k of keys) {
          if (!["protocol_version", "cursor", "limit"].includes(k)) throw new RelayError(400, "MALFORMED");
        }
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (body.limit != null && (typeof body.limit !== "number" || body.limit < 1 || body.limit > 10)) {
          throw new RelayError(400, "MALFORMED");
        }
        if (body.cursor != null && typeof body.cursor !== "string") throw new RelayError(400, "MALFORMED");
        const fetched = await store.fetchInbox(who.mailbox_id, {
          cursor: body.cursor || "",
          limit: body.limit || 10,
        });
        jsonResponse(res, 200, {
          protocol_version: 1,
          envelopes: fetched.items,
          cursor: fetched.cursor,
        });
        return;
      }

      if (req.method === "POST" && path === "/v1/inbox/consume") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "items"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!Array.isArray(body.items) || body.items.length < 1 || body.items.length > 10) {
          throw new RelayError(400, "MALFORMED");
        }
        const items = [];
        for (const item of body.items) {
          exactObject(item, ["message_id", "envelope_sha256", "lease_token"]);
          if (!decodeB64UrlExact(item.message_id, MESSAGE_ID_BYTES)) throw new RelayError(400, "MALFORMED");
          if (typeof item.envelope_sha256 !== "string" || !decodeB64UrlExact(item.envelope_sha256, 32)) {
            throw new RelayError(400, "MALFORMED");
          }
          if (typeof item.lease_token !== "string" || !decodeB64UrlExact(item.lease_token, 16)) {
            throw new RelayError(400, "MALFORMED");
          }
          items.push(item);
        }
        const result = await store.consume(who.mailbox_id, items);
        if (result.status === "mismatch") throw new RelayError(409, "DIGEST_MISMATCH");
        if (result.status === "lease") throw new RelayError(409, "LEASE_INVALID");
        jsonResponse(res, 200, { protocol_version: 1, results: result.results });
        return;
      }

      if (req.method === "POST" && path === "/v1/invites") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "token", "bundle", "expires_at"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.token, INVITE_TOKEN_BYTES)) throw new RelayError(400, "MALFORMED");
        if (typeof body.bundle !== "string" || body.bundle.length < 1) throw new RelayError(400, "MALFORMED");
        if (Buffer.byteLength(body.bundle, "utf8") > BUNDLE_MAX) throw new RelayError(413, "PAYLOAD_TOO_LARGE");
        if (typeof body.expires_at !== "number") throw new RelayError(400, "MALFORMED");
        if (body.expires_at <= store.now() || body.expires_at > store.now() + INVITE_TTL_SECONDS) {
          throw new RelayError(400, "MALFORMED");
        }
        if (!takeToken(buckets, `inv-put:${who.mailbox_id}`, 60, 30)) {
          throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        }
        const result = await store.putInvite(who.mailbox_id, body.token, body.bundle, body.expires_at);
        if (result.status !== "ok") throw new RelayError(400, "MALFORMED");
        jsonResponse(res, 201, { protocol_version: 1, state: "accepted" });
        return;
      }

      if (req.method === "POST" && path === "/v1/invites/lookup") {
        exactObject(body, ["protocol_version", "token"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.token, INVITE_TOKEN_BYTES)) throw new RelayError(404, "INVITE_UNKNOWN");
        const ip = clientIp(req);
        if (!takeToken(buckets, `inv-lookup:${ip}`, 120, 60)) {
          throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        }
        const bundle = store.lookupInvite(body.token);
        if (!bundle) throw new RelayError(404, "INVITE_UNKNOWN");
        jsonResponse(res, 200, { protocol_version: 1, bundle });
        return;
      }

      if (req.method === "POST" && path === "/v1/invites/revoke") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "token"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.token, INVITE_TOKEN_BYTES)) throw new RelayError(400, "MALFORMED");
        const result = await store.revokeInvite(who.mailbox_id, body.token);
        if (result.status === "forbidden") throw new RelayError(401, "UNAUTHORIZED");
        jsonResponse(res, 200, { protocol_version: 1, state: "accepted" });
        return;
      }

      if (req.method === "POST" && path === "/v1/invites/redeem") {
        exactObject(body, ["protocol_version", "token", "claim_id"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.token, INVITE_TOKEN_BYTES)) throw new RelayError(404, "INVITE_UNKNOWN");
        if (!decodeB64UrlExact(body.claim_id, CLAIM_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        const ip = clientIp(req);
        if (!takeToken(buckets, `inv-redeem:${ip}`, 10, 5)) {
          throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        }
        const result = await store.redeemInvite(body.token, body.claim_id);
        if (result.status === "unknown") throw new RelayError(404, "INVITE_UNKNOWN");
        if (result.status === "malformed") throw new RelayError(400, "MALFORMED");
        if (result.status === "throttled") throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        if (result.status === "unavailable") throw new RelayError(404, "INVITE_UNKNOWN");
        jsonResponse(res, result.replay ? 200 : 201, {
          protocol_version: 1,
          bundle: result.bundle,
          key_package_id: result.item.key_package_id,
          binding: result.item.binding,
          bootstrap_grant: {
            expires_at: result.grant.expires_at,
            grant_id: result.grant.grant_id,
            kind: result.grant.kind,
            mailbox_id: result.grant.mailbox_id,
            token: result.grant.token,
          },
        });
        return;
      }

      if (req.method === "POST" && path === "/v1/grants") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "kind", "expires_at"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (body.kind !== "peer") throw new RelayError(400, "MALFORMED");
        if (typeof body.expires_at !== "number") throw new RelayError(400, "MALFORMED");
        if (!takeToken(buckets, `grant-put:${who.mailbox_id}`, 20, 10)) {
          throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        }
        const result = await store.createPeerGrant(who.mailbox_id, body.expires_at);
        if (result.status !== "ok") throw new RelayError(400, "MALFORMED");
        jsonResponse(res, 201, {
          protocol_version: 1,
          expires_at: result.grant.expires_at,
          grant_id: result.grant.grant_id,
          kind: result.grant.kind,
          mailbox_id: result.grant.mailbox_id,
          token: result.grant.token,
        });
        return;
      }

      if (req.method === "POST" && path === "/v1/grants/rotate") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "grant_id"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.grant_id, GRANT_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        const result = await store.rotatePeerGrant(who.mailbox_id, body.grant_id);
        if (result.status !== "ok") throw new RelayError(404, "UNKNOWN");
        jsonResponse(res, 200, {
          protocol_version: 1,
          expires_at: result.grant.expires_at,
          grant_id: result.grant.grant_id,
          kind: result.grant.kind,
          mailbox_id: result.grant.mailbox_id,
          previous_grant_id: result.previous_grant_id,
          token: result.grant.token,
        });
        return;
      }

      if (req.method === "POST" && path === "/v1/grants/revoke") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version", "grant_id"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.grant_id, GRANT_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        const result = await store.revokeGrant(who.mailbox_id, body.grant_id);
        if (result.status === "forbidden") throw new RelayError(401, "UNAUTHORIZED");
        jsonResponse(res, 200, { protocol_version: 1, state: "accepted" });
        return;
      }

      if (req.method === "POST" && path === "/v1/push/register") {
        const who = auth(store, req, "owner");
        exactObject(body, ["device_id", "fcm_token", "protocol_version"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.device_id, DEVICE_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        if (!isPlausibleFcmToken(body.fcm_token)) throw new RelayError(400, "MALFORMED");
        if (!takeToken(buckets, `push-reg:${who.mailbox_id}`, 10, 5)) {
          throw new RelayError(429, "THROTTLED", { retry_after: 60 });
        }
        const result = await store.registerPush(who.mailbox_id, body.device_id, body.fcm_token);
        if (result.status !== "ok") throw new RelayError(400, "MALFORMED");
        logEvent("push_registered", { mailbox: shortRef(who.mailbox_id), fp: result.fingerprint });
        jsonResponse(res, 200, { protocol_version: 1, state: "accepted" });
        return;
      }

      if (req.method === "POST" && path === "/v1/push/unregister") {
        const who = auth(store, req, "owner");
        exactObject(body, ["device_id", "protocol_version"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!decodeB64UrlExact(body.device_id, DEVICE_ID_BYTES)) throw new RelayError(400, "MALFORMED");
        const result = await store.unregisterPush(who.mailbox_id, body.device_id);
        if (result.status !== "ok") throw new RelayError(400, "MALFORMED");
        logEvent("push_unregistered", { mailbox: shortRef(who.mailbox_id) });
        jsonResponse(res, 200, { protocol_version: 1, state: "accepted" });
        return;
      }

      if (req.method === "POST" && path === "/v1/mailboxes/rotate-submit") {
        const who = auth(store, req, "owner");
        exactObject(body, ["protocol_version"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        const result = await store.rotateSubmit(who.mailbox_id);
        if (result.status !== "ok") throw new RelayError(401, "UNAUTHORIZED");
        jsonResponse(res, 200, {
          protocol_version: 1,
          submit_capability: result.submit_capability,
        });
        return;
      }

      if (req.method === "POST" && path === "/v1/envelopes/status") {
        const who = authWrite(store, req);
        exactObject(body, ["protocol_version", "message_ids"]);
        if (body.protocol_version !== 1) throw new RelayError(400, "MALFORMED");
        if (!Array.isArray(body.message_ids) || body.message_ids.length < 1 || body.message_ids.length > 20) {
          throw new RelayError(400, "MALFORMED");
        }
        const statuses = [];
        for (const id of body.message_ids) {
          if (!decodeB64UrlExact(id, MESSAGE_ID_BYTES)) throw new RelayError(400, "MALFORMED");
          statuses.push(store.statusOf(who.mailbox_id, id));
        }
        jsonResponse(res, 200, { protocol_version: 1, statuses });
        return;
      }

      throw new RelayError(400, "MALFORMED");
    } catch (err) {
      try {
        errorResponse(res, err);
      } catch {
        if (!res.headersSent) {
          res.writeHead(500, { "content-type": "application/json" });
          res.end('{"protocol_version":1,"error":"INTERNAL"}');
        }
      }
    }
  }

  const server = createServer(
    {
      key: tls.key,
      cert: tls.cert,
      minVersion: "TLSv1.2",
    },
    handle,
  );

  return {
    server,
    store,
    listen(host, port) {
      return new Promise((resolve) => {
        server.listen(port, host, () => resolve(server.address()));
      });
    },
    close() {
      return new Promise((resolve, reject) => {
        server.close((err) => (err ? reject(err) : resolve()));
      });
    },
  };
}
