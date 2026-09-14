import { createCipheriv, createDecipheriv, createHmac, randomBytes } from "node:crypto";
import { mkdir, open, readFile, readdir, rename, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import {
  CLAIM_ID_BYTES,
  DEVICE_ID_BYTES,
  GRANT_ID_BYTES,
  INVITE_TOKEN_BYTES,
  KEY_PACKAGE_ID_BYTES,
  LEASE_BYTES,
  MAILBOX_BYTES,
  MESSAGE_ID_BYTES,
  TOKEN_BYTES,
  b64url,
  bootstrapGrantToken,
  decodeB64UrlExact,
  hmacToken,
  randomId,
  sha256B64,
} from "./ids.mjs";
import { isPlausibleFcmToken, tokenFingerprint } from "./push.mjs";

export const TTL_SECONDS = 7 * 24 * 60 * 60;
export const TOMBSTONE_EXTRA_SECONDS = TTL_SECONDS + 5 * 60;
export const LEASE_SECONDS = 60;
export const MAX_QUEUED = 1000;
export const MAX_QUEUE_BYTES = 10 * 1024 * 1024;
export const MAX_FETCH = 10;
export const MAX_FETCH_BYTES = 512 * 1024;
export const PAYLOAD_MIN = 1;
export const PAYLOAD_MAX = 24 * 1024;
export const BINDING_MAX = 8 * 1024;
export const CREDENTIAL_MAX = 8 * 1024;
export const REQUEST_MAX = 48 * 1024;
export const SUITE = "ac-mls-v1";
export const INVITE_TTL_SECONDS = 48 * 60 * 60;
export const BUNDLE_MAX = 8 * 1024;
export const PEER_GRANT_TTL_SECONDS = 90 * 24 * 60 * 60;
export const MAX_REDEEMS_PER_INVITE = 32;

async function atomicWrite(path, bytes) {
  await mkdir(dirname(path), { recursive: true });
  const tmp = `${path}.tmp`;
  const fh = await open(tmp, "w");
  try {
    await fh.writeFile(bytes);
    await fh.sync();
  } finally {
    await fh.close();
  }
  await rename(tmp, path);
  try {
    const dir = await open(dirname(path), "r");
    try {
      await dir.sync();
    } finally {
      await dir.close();
    }
  } catch {
    // Directory fsync is best-effort on some filesystems.
  }
}

async function readJson(path) {
  const raw = await readFile(path);
  return JSON.parse(raw.toString("utf8"));
}

export async function createStore({ dir, nowSeconds, pepper }) {
  const keysDir = join(dir, "keys");
  const queueDir = join(dir, "queue");
  const blobDir = join(dir, "blobs");
  await mkdir(keysDir, { recursive: true });
  await mkdir(queueDir, { recursive: true });
  await mkdir(blobDir, { recursive: true });
  const blobs = new Map();

  let tokenPepper = pepper;
  const pepperPath = join(keysDir, "pepper");
  if (!tokenPepper) {
    try {
      tokenPepper = await readFile(pepperPath);
    } catch {
      const { randomBytes } = await import("node:crypto");
      tokenPepper = randomBytes(32);
      await atomicWrite(pepperPath, tokenPepper);
    }
  }

  const mailboxes = new Map();
  const tokens = new Map();
  const envelopes = new Map();
  const keyPackages = new Map();
  const claims = new Map();
  const invites = new Map();
  const mailboxInvite = new Map();
  const grants = new Map();
  const pushRegs = new Map();
  let suspended = false;
  let networkPolicy = null;
  const suspendedPath = join(keysDir, "suspended");
  try {
    const flag = (await readFile(suspendedPath, "utf8")).trim();
    suspended = flag === "1";
  } catch {
    suspended = false;
  }

  async function loadAll() {
    const mbDir = join(queueDir, "mailboxes");
    try {
      for (const name of await readdir(mbDir)) {
        if (!name.endsWith(".json")) continue;
        const rec = await readJson(join(mbDir, name));
        mailboxes.set(rec.mailbox_id, rec);
        tokens.set(rec.owner_hash, { kind: "owner", mailbox_id: rec.mailbox_id });
        tokens.set(rec.submit_hash, { kind: "submit", mailbox_id: rec.mailbox_id });
      }
    } catch {
      // empty
    }
    const envRoot = join(queueDir, "envelopes");
    try {
      for (const mailbox of await readdir(envRoot)) {
        const d = join(envRoot, mailbox);
        for (const name of await readdir(d)) {
          if (!name.endsWith(".json")) continue;
          const rec = await readJson(join(d, name));
          envelopes.set(`${rec.recipient_mailbox}:${rec.message_id}`, rec);
        }
      }
    } catch {
      // empty
    }
    const kpRoot = join(queueDir, "keypackages");
    try {
      for (const mailbox of await readdir(kpRoot)) {
        const d = join(kpRoot, mailbox);
        const list = [];
        for (const name of await readdir(d)) {
          if (!name.endsWith(".json")) continue;
          list.push(await readJson(join(d, name)));
        }
        keyPackages.set(mailbox, list);
      }
    } catch {
      // empty
    }
    const claimRoot = join(queueDir, "claims");
    try {
      for (const mailbox of await readdir(claimRoot)) {
        const d = join(claimRoot, mailbox);
        for (const name of await readdir(d)) {
          if (!name.endsWith(".json")) continue;
          const rec = await readJson(join(d, name));
          claims.set(`${mailbox}:${rec.claim_id}`, rec);
        }
      }
    } catch {
      // empty
    }
    const inviteRoot = join(queueDir, "invites");
    try {
      for (const name of await readdir(inviteRoot)) {
        if (!name.endsWith(".json")) continue;
        const rec = await readJson(join(inviteRoot, name));
        invites.set(rec.token_hash, rec);
        if (!rec.revoked) mailboxInvite.set(rec.mailbox_id, rec.token_hash);
      }
    } catch {
      // empty
    }
    const grantRoot = join(queueDir, "grants");
    try {
      for (const mailbox of await readdir(grantRoot)) {
        const d = join(grantRoot, mailbox);
        for (const name of await readdir(d)) {
          if (!name.endsWith(".json")) continue;
          const rec = await readJson(join(d, name));
          grants.set(rec.grant_id, rec);
          if (!rec.revoked) {
            tokens.set(rec.token_hash, {
              kind: rec.kind,
              mailbox_id: rec.mailbox_id,
              grant_id: rec.grant_id,
              expires_at: rec.expires_at,
              invite_hash: rec.invite_hash || null,
              revoked: false,
            });
          }
        }
      }
    } catch {
      // empty
    }
    const pushRoot = join(keysDir, "push");
    try {
      for (const mailbox of await readdir(pushRoot)) {
        const d = join(pushRoot, mailbox);
        for (const name of await readdir(d)) {
          if (!name.endsWith(".json")) continue;
          const rec = await readJson(join(d, name));
          if (rec && rec.mailbox_id && rec.device_id) {
            pushRegs.set(`${rec.mailbox_id}:${rec.device_id}`, rec);
          }
        }
      }
    } catch {
      // empty
    }
  }

  await loadAll();

  const now = () => nowSeconds();
  let chain = Promise.resolve();
  function locked(fn) {
    const run = chain.then(fn, fn);
    chain = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  function mbPath(id) {
    return join(queueDir, "mailboxes", `${id}.json`);
  }
  function envPath(mailbox, messageId) {
    return join(queueDir, "envelopes", mailbox, `${messageId}.json`);
  }
  function kpPath(mailbox, id) {
    return join(queueDir, "keypackages", mailbox, `${id}.json`);
  }
  function claimPath(mailbox, claimId) {
    return join(queueDir, "claims", mailbox, `${claimId}.json`);
  }
  function invitePath(hash) {
    const safe = String(hash).replace(/[^A-Za-z0-9_-]/g, "_");
    return join(queueDir, "invites", `${safe}.json`);
  }
  function grantPath(mailbox, grantId) {
    const safe = String(grantId).replace(/[^A-Za-z0-9_-]/g, "_");
    return join(queueDir, "grants", mailbox, `${safe}.json`);
  }
  function pushPath(mailboxId, deviceId) {
    const mb = String(mailboxId).replace(/[^A-Za-z0-9_-]/g, "_");
    const dev = String(deviceId).replace(/[^A-Za-z0-9_-]/g, "_");
    return join(keysDir, "push", mb, `${dev}.json`);
  }

  function wrapToken(token) {
    const key = createHmac("sha256", tokenPepper).update("ac-fcm-v1").digest();
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", key, iv);
    const enc = Buffer.concat([cipher.update(token, "utf8"), cipher.final()]);
    return { iv: b64url(iv), tag: b64url(cipher.getAuthTag()), token_enc: b64url(enc) };
  }

  function unwrapToken(rec) {
    const key = createHmac("sha256", tokenPepper).update("ac-fcm-v1").digest();
    const decipher = createDecipheriv("aes-256-gcm", key, Buffer.from(rec.iv, "base64url"));
    decipher.setAuthTag(Buffer.from(rec.tag, "base64url"));
    return Buffer.concat([
      decipher.update(Buffer.from(rec.token_enc, "base64url")),
      decipher.final(),
    ]).toString("utf8");
  }

  async function persistPush(rec) {
    pushRegs.set(`${rec.mailbox_id}:${rec.device_id}`, rec);
    const stored = {
      mailbox_id: rec.mailbox_id,
      device_id: rec.device_id,
      token_hash: rec.token_hash,
      iv: rec.iv,
      tag: rec.tag,
      token_enc: rec.token_enc,
      updated_at: rec.updated_at,
    };
    await atomicWrite(pushPath(rec.mailbox_id, rec.device_id), Buffer.from(JSON.stringify(stored)));
  }

  async function dropPush(mailboxId, deviceId) {
    const key = `${mailboxId}:${deviceId}`;
    pushRegs.delete(key);
    try {
      await rm(pushPath(mailboxId, deviceId), { force: true });
    } catch {
      // ignore
    }
  }

  async function persistEnvelope(rec) {
    await atomicWrite(envPath(rec.recipient_mailbox, rec.message_id), Buffer.from(JSON.stringify(rec)));
  }

  async function persistMailbox(rec) {
    const stored = {
      mailbox_id: rec.mailbox_id,
      owner_hash: rec.owner_hash,
      submit_hash: rec.submit_hash,
      created_at: rec.created_at,
    };
    await atomicWrite(mbPath(rec.mailbox_id), Buffer.from(JSON.stringify(stored)));
  }

  function lookupToken(raw) {
    if (!decodeB64UrlExact(raw, TOKEN_BYTES)) return null;
    const hash = hmacToken(tokenPepper, raw);
    const rec = tokens.get(hash);
    if (!rec) return null;
    if (rec.revoked) return null;
    if ((rec.kind === "bootstrap" || rec.kind === "peer") && rec.expires_at && rec.expires_at <= now()) {
      return null;
    }
    return rec;
  }

  async function setSuspended(value) {
    return locked(async () => {
      suspended = Boolean(value);
      await atomicWrite(suspendedPath, Buffer.from(suspended ? "1" : "0"));
    });
  }

  async function createMailbox() {
    return locked(async () => {
    const mailbox_id = randomId(MAILBOX_BYTES);
    const owner = randomId(TOKEN_BYTES);
    const submit = randomId(TOKEN_BYTES);
    const rec = {
      mailbox_id,
      owner_hash: hmacToken(tokenPepper, owner),
      submit_hash: hmacToken(tokenPepper, submit),
      created_at: now(),
    };
    mailboxes.set(mailbox_id, rec);
    tokens.set(rec.owner_hash, { kind: "owner", mailbox_id });
    tokens.set(rec.submit_hash, { kind: "submit", mailbox_id });
    await persistMailbox(rec);
    return { mailbox_id, owner_token: owner, submit_capability: submit };
    });
  }

  function mailboxOf(id) {
    return mailboxes.get(id) || null;
  }

  function peekEnvelope(mailboxId, messageId) {
    return envelopes.get(`${mailboxId}:${messageId}`) || null;
  }

  function queuedFor(mailbox) {
    const list = [];
    for (const rec of envelopes.values()) {
      if (rec.recipient_mailbox === mailbox && rec.state === "queued") list.push(rec);
    }
    return list;
  }

  async function putKeyPackage(mailboxId, item) {
    return locked(async () => {
    const list = keyPackages.get(mailboxId) || [];
    const existing = list.find((x) => x.key_package_id === item.key_package_id);
    if (existing) {
      if (existing.binding === item.binding && existing.expires_at === item.expires_at) {
        return { status: "ok", item: existing };
      }
      return { status: "conflict" };
    }
    const rec = {
      key_package_id: item.key_package_id,
      binding: item.binding,
      expires_at: item.expires_at,
      claimed: false,
      claim_id: null,
    };
    list.push(rec);
    keyPackages.set(mailboxId, list);
    await atomicWrite(kpPath(mailboxId, rec.key_package_id), Buffer.from(JSON.stringify(rec)));
    return { status: "ok", item: rec };
    });
  }

  async function claimKeyPackage(mailboxId, claimId) {
    return locked(async () => {
    const existing = claims.get(`${mailboxId}:${claimId}`);
    if (existing) {
      const kp = (keyPackages.get(mailboxId) || []).find((x) => x.key_package_id === existing.key_package_id);
      return { status: "ok", item: kp, replay: true };
    }
    const t = now();
    const list = keyPackages.get(mailboxId) || [];
    const next = list.find((x) => !x.claimed && x.expires_at > t);
    if (!next) return { status: "unavailable" };
    next.claimed = true;
    next.claim_id = claimId;
    const claim = { claim_id: claimId, key_package_id: next.key_package_id, claimed_at: t };
    claims.set(`${mailboxId}:${claimId}`, claim);
    await atomicWrite(kpPath(mailboxId, next.key_package_id), Buffer.from(JSON.stringify(next)));
    await atomicWrite(claimPath(mailboxId, claimId), Buffer.from(JSON.stringify(claim)));
    return { status: "ok", item: next, replay: false };
    });
  }

  function grantPublic(rec, token) {
    return {
      grant_id: rec.grant_id,
      kind: rec.kind,
      mailbox_id: rec.mailbox_id,
      expires_at: rec.expires_at,
      token,
    };
  }

  async function persistGrant(rec) {
    grants.set(rec.grant_id, rec);
    if (!rec.revoked) {
      tokens.set(rec.token_hash, {
        kind: rec.kind,
        mailbox_id: rec.mailbox_id,
        grant_id: rec.grant_id,
        expires_at: rec.expires_at,
        invite_hash: rec.invite_hash || null,
        revoked: false,
      });
    }
    await atomicWrite(grantPath(rec.mailbox_id, rec.grant_id), Buffer.from(JSON.stringify({
      grant_id: rec.grant_id,
      mailbox_id: rec.mailbox_id,
      token_hash: rec.token_hash,
      kind: rec.kind,
      invite_hash: rec.invite_hash || null,
      claim_id: rec.claim_id || null,
      expires_at: rec.expires_at,
      revoked: rec.revoked,
      created_at: rec.created_at,
    })));
  }

  async function revokeGrantRecord(rec) {
    rec.revoked = true;
    tokens.delete(rec.token_hash);
    await persistGrant(rec);
  }

  function findGrantByClaim(mailboxId, claimId) {
    for (const rec of grants.values()) {
      if (rec.mailbox_id === mailboxId && rec.claim_id === claimId && rec.kind === "bootstrap") return rec;
    }
    return null;
  }

  function countActiveRedemptions(inviteHash) {
    let n = 0;
    for (const rec of grants.values()) {
      if (rec.invite_hash === inviteHash && rec.kind === "bootstrap" && !rec.revoked) n += 1;
    }
    return n;
  }

  async function redeemInvite(token, claimId) {
    return locked(async () => {
      if (!decodeB64UrlExact(token, INVITE_TOKEN_BYTES)) return { status: "unknown" };
      if (!decodeB64UrlExact(claimId, CLAIM_ID_BYTES)) return { status: "malformed" };
      const t = now();
      const inviteHash = hmacToken(tokenPepper, token);
      const invite = invites.get(inviteHash);
      if (!invite || invite.revoked || invite.expires_at <= t) return { status: "unknown" };

      const existingClaim = claims.get(`${invite.mailbox_id}:${claimId}`);
      if (existingClaim) {
        const kp = (keyPackages.get(invite.mailbox_id) || []).find((x) => x.key_package_id === existingClaim.key_package_id);
        const grant = findGrantByClaim(invite.mailbox_id, claimId);
        if (!kp || !grant || grant.revoked || grant.expires_at <= t) return { status: "unknown" };
        const grantToken = bootstrapGrantToken(tokenPepper, invite.mailbox_id, inviteHash, claimId);
        return {
          status: "ok",
          bundle: invite.bundle,
          item: kp,
          grant: grantPublic(grant, grantToken),
          replay: true,
        };
      }

      if (countActiveRedemptions(inviteHash) >= MAX_REDEEMS_PER_INVITE) {
        return { status: "throttled" };
      }

      const list = keyPackages.get(invite.mailbox_id) || [];
      const next = list.find((x) => !x.claimed && x.expires_at > t);
      if (!next) return { status: "unavailable" };
      next.claimed = true;
      next.claim_id = claimId;
      const claim = { claim_id: claimId, key_package_id: next.key_package_id, claimed_at: t };
      claims.set(`${invite.mailbox_id}:${claimId}`, claim);
      await atomicWrite(kpPath(invite.mailbox_id, next.key_package_id), Buffer.from(JSON.stringify(next)));
      await atomicWrite(claimPath(invite.mailbox_id, claimId), Buffer.from(JSON.stringify(claim)));

      const grantToken = bootstrapGrantToken(tokenPepper, invite.mailbox_id, inviteHash, claimId);
      const grant = {
        grant_id: randomId(GRANT_ID_BYTES),
        mailbox_id: invite.mailbox_id,
        token_hash: hmacToken(tokenPepper, grantToken),
        kind: "bootstrap",
        invite_hash: inviteHash,
        claim_id: claimId,
        expires_at: invite.expires_at,
        revoked: false,
        created_at: t,
      };
      await persistGrant(grant);
      return {
        status: "ok",
        bundle: invite.bundle,
        item: next,
        grant: grantPublic(grant, grantToken),
        replay: false,
      };
    });
  }

  async function createPeerGrant(mailboxId, expiresAt) {
    return locked(async () => {
      const t = now();
      if (typeof expiresAt !== "number" || expiresAt <= t || expiresAt > t + PEER_GRANT_TTL_SECONDS) {
        return { status: "malformed" };
      }
      const token = randomId(TOKEN_BYTES);
      const rec = {
        grant_id: randomId(GRANT_ID_BYTES),
        mailbox_id: mailboxId,
        token_hash: hmacToken(tokenPepper, token),
        kind: "peer",
        invite_hash: null,
        claim_id: null,
        expires_at: expiresAt,
        revoked: false,
        created_at: t,
      };
      await persistGrant(rec);
      return { status: "ok", grant: grantPublic(rec, token) };
    });
  }

  async function rotatePeerGrant(mailboxId, grantId) {
    return locked(async () => {
      const rec = grants.get(grantId);
      if (!rec || rec.mailbox_id !== mailboxId) return { status: "unknown" };
      if (rec.kind !== "peer") return { status: "malformed" };
      const t = now();
      const remaining = rec.expires_at - t;
      if (rec.revoked || remaining <= 0) return { status: "unknown" };
      await revokeGrantRecord(rec);
      const token = randomId(TOKEN_BYTES);
      const next = {
        grant_id: randomId(GRANT_ID_BYTES),
        mailbox_id: mailboxId,
        token_hash: hmacToken(tokenPepper, token),
        kind: "peer",
        invite_hash: null,
        claim_id: null,
        expires_at: rec.expires_at,
        revoked: false,
        created_at: t,
      };
      await persistGrant(next);
      return { status: "ok", grant: grantPublic(next, token), previous_grant_id: grantId };
    });
  }

  async function revokeGrant(mailboxId, grantId) {
    return locked(async () => {
      const rec = grants.get(grantId);
      if (!rec) return { status: "ok" };
      if (rec.mailbox_id !== mailboxId) return { status: "forbidden" };
      await revokeGrantRecord(rec);
      return { status: "ok" };
    });
  }

  async function rotateSubmit(mailboxId) {
    return locked(async () => {
      const rec = mailboxes.get(mailboxId);
      if (!rec) return { status: "unknown" };
      tokens.delete(rec.submit_hash);
      const submit = randomId(TOKEN_BYTES);
      rec.submit_hash = hmacToken(tokenPepper, submit);
      tokens.set(rec.submit_hash, { kind: "submit", mailbox_id: mailboxId });
      await persistMailbox(rec);
      return { status: "ok", submit_capability: submit };
    });
  }

  async function submitEnvelope(rec) {
    return locked(async () => {
    const key = `${rec.recipient_mailbox}:${rec.message_id}`;
    const existing = envelopes.get(key);
    if (existing) {
      if (existing.envelope_sha256 === rec.envelope_sha256) {
        return { status: "replay", acceptance: toAcceptance(existing) };
      }
      return { status: "mismatch", acceptance: toAcceptance(existing) };
    }
    const queued = queuedFor(rec.recipient_mailbox);
    const bytes = queued.reduce((n, e) => n + Buffer.byteLength(e.encrypted_payload, "utf8"), 0);
    if (queued.length >= MAX_QUEUED || bytes + Buffer.byteLength(rec.encrypted_payload, "utf8") > MAX_QUEUE_BYTES) {
      return { status: "full" };
    }
    const stored = {
      ...rec,
      state: "queued",
      lease_token: null,
      lease_until: 0,
    };
    envelopes.set(key, stored);
    await persistEnvelope(stored);
    return { status: "created", acceptance: toAcceptance(stored) };
    });
  }

  function toAcceptance(rec) {
    return {
      protocol_version: 1,
      message_id: rec.message_id,
      recipient_mailbox: rec.recipient_mailbox,
      envelope_sha256: rec.envelope_sha256,
      accepted_at: rec.accepted_at,
      expires_at: rec.expires_at,
      state: rec.state === "queued" ? "accepted" : rec.state,
    };
  }

  async function fetchInbox(mailboxId, { cursor, limit }) {
    return locked(async () => {
    const t = now();
    const cap = Math.min(Math.max(1, limit || MAX_FETCH), MAX_FETCH);
    let items = queuedFor(mailboxId)
      .filter((e) => e.expires_at > t)
      .sort((a, b) => a.accepted_at - b.accepted_at || a.message_id.localeCompare(b.message_id));
    if (cursor) {
      const idx = items.findIndex((e) => e.message_id === cursor);
      if (idx >= 0) items = items.slice(idx + 1);
    }
    const out = [];
    let bytes = 0;
    for (const rec of items) {
      if (out.length >= cap) break;
      const payloadBytes = Buffer.byteLength(rec.encrypted_payload, "utf8");
      if (bytes + payloadBytes > MAX_FETCH_BYTES) break;
      if (!rec.lease_token || rec.lease_until <= t) {
        rec.lease_token = randomId(LEASE_BYTES);
        rec.lease_until = t + LEASE_SECONDS;
        await persistEnvelope(rec);
      }
      const item = {
        message_id: rec.message_id,
        envelope_sha256: rec.envelope_sha256,
        accepted_at: rec.accepted_at,
        expires_at: rec.expires_at,
        payload_type: rec.payload_type,
        encrypted_payload: rec.encrypted_payload,
        lease_token: rec.lease_token,
        lease_until: rec.lease_until,
      };
      if (rec.sender_credential) item.sender_credential = rec.sender_credential;
      out.push(item);
      bytes += payloadBytes;
    }
    const nextCursor = out.length ? out[out.length - 1].message_id : cursor || "";
    return { items: out, cursor: nextCursor };
    });
  }

  async function consume(mailboxId, items) {
    return locked(async () => {
    const t = now();
    const results = [];
    for (const item of items) {
      const rec = envelopes.get(`${mailboxId}:${item.message_id}`);
      if (!rec) {
        results.push({ message_id: item.message_id, status: "unknown" });
        continue;
      }
      if (rec.envelope_sha256 !== item.envelope_sha256) {
        return { status: "mismatch" };
      }
      if (rec.state === "consumed" || rec.state === "expired") {
        results.push({ message_id: rec.message_id, status: rec.state });
        continue;
      }
      if (rec.state !== "queued") {
        results.push({ message_id: rec.message_id, status: rec.state });
        continue;
      }
      if (rec.lease_token !== item.lease_token || rec.lease_until <= t) {
        return { status: "lease" };
      }
      rec.state = "consumed";
      rec.consumed_at = t;
      rec.lease_token = null;
      rec.lease_until = 0;
      rec.encrypted_payload = "";
      rec.sender_credential = null;
      rec.canonical_body = "";
      await persistEnvelope(rec);
      results.push({ message_id: rec.message_id, status: "consumed" });
    }
    return { status: "ok", results };
    });
  }

  async function putInvite(mailboxId, token, bundle, expiresAt) {
    return locked(async () => {
      if (!decodeB64UrlExact(token, INVITE_TOKEN_BYTES)) return { status: "malformed" };
      const t = now();
      if (typeof expiresAt !== "number" || expiresAt <= t || expiresAt > t + INVITE_TTL_SECONDS) {
        return { status: "malformed" };
      }
      if (typeof bundle !== "string" || bundle.length < 1 || Buffer.byteLength(bundle, "utf8") > BUNDLE_MAX) {
        return { status: "malformed" };
      }
      const token_hash = hmacToken(tokenPepper, token);
      const previous = mailboxInvite.get(mailboxId);
      if (previous && previous !== token_hash) {
        const old = invites.get(previous);
        if (old) {
          old.revoked = true;
          old.bundle = "";
          await atomicWrite(invitePath(previous), Buffer.from(JSON.stringify(old)));
        }
      }
      const rec = {
        token_hash,
        mailbox_id: mailboxId,
        bundle,
        expires_at: expiresAt,
        revoked: false,
        created_at: t,
      };
      invites.set(token_hash, rec);
      mailboxInvite.set(mailboxId, token_hash);
      await atomicWrite(invitePath(token_hash), Buffer.from(JSON.stringify(rec)));
      return { status: "ok" };
    });
  }

  function lookupInvite(token) {
    if (!decodeB64UrlExact(token, INVITE_TOKEN_BYTES)) return null;
    const hash = hmacToken(tokenPepper, token);
    const rec = invites.get(hash);
    if (!rec || rec.revoked) return null;
    if (rec.expires_at <= now()) return null;
    return rec.bundle;
  }

  async function revokeInvite(mailboxId, token) {
    return locked(async () => {
      if (!decodeB64UrlExact(token, INVITE_TOKEN_BYTES)) return { status: "ok" };
      const hash = hmacToken(tokenPepper, token);
      const rec = invites.get(hash);
      if (!rec) return { status: "ok" };
      if (rec.mailbox_id !== mailboxId) return { status: "forbidden" };
      rec.revoked = true;
      rec.bundle = "";
      await atomicWrite(invitePath(hash), Buffer.from(JSON.stringify(rec)));
      if (mailboxInvite.get(mailboxId) === hash) mailboxInvite.delete(mailboxId);
      for (const grant of grants.values()) {
        if (grant.invite_hash === hash && grant.kind === "bootstrap" && !grant.revoked) {
          await revokeGrantRecord(grant);
        }
      }
      return { status: "ok" };
    });
  }

  async function registerPush(mailboxId, deviceId, token) {
    return locked(async () => {
      if (!decodeB64UrlExact(deviceId, DEVICE_ID_BYTES)) return { status: "malformed" };
      if (!isPlausibleFcmToken(token)) return { status: "malformed" };
      const token_hash = hmacToken(tokenPepper, token);
      const wrapped = wrapToken(token);
      const stale = [];
      for (const rec of pushRegs.values()) {
        if (rec.mailbox_id === mailboxId && rec.token_hash === token_hash && rec.device_id !== deviceId) {
          stale.push(rec);
        }
      }
      for (const rec of stale) {
        await dropPush(rec.mailbox_id, rec.device_id);
      }
      const rec = {
        mailbox_id: mailboxId,
        device_id: deviceId,
        token_hash,
        iv: wrapped.iv,
        tag: wrapped.tag,
        token_enc: wrapped.token_enc,
        updated_at: now(),
      };
      await persistPush(rec);
      return { status: "ok", fingerprint: tokenFingerprint(token) };
    });
  }

  async function unregisterPush(mailboxId, deviceId) {
    return locked(async () => {
      if (!decodeB64UrlExact(deviceId, DEVICE_ID_BYTES)) return { status: "malformed" };
      await dropPush(mailboxId, deviceId);
      return { status: "ok" };
    });
  }

  async function deletePushByTokenHash(tokenHash) {
    return locked(async () => {
      const doomed = [];
      for (const rec of pushRegs.values()) {
        if (rec.token_hash === tokenHash) doomed.push(rec);
      }
      for (const rec of doomed) {
        await dropPush(rec.mailbox_id, rec.device_id);
      }
      return { status: "ok", removed: doomed.length };
    });
  }

  function listPushTokens(mailboxId) {
    const out = [];
    for (const rec of pushRegs.values()) {
      if (rec.mailbox_id !== mailboxId) continue;
      try {
        const token = unwrapToken(rec);
        out.push({
          device_id: rec.device_id,
          token_hash: rec.token_hash,
          fingerprint: tokenFingerprint(token),
          token,
        });
      } catch {
        // Corrupt mapping is dropped on the next write path.
      }
    }
    return out;
  }

  function statusOf(mailboxId, messageId) {
    const rec = envelopes.get(`${mailboxId}:${messageId}`);
    if (!rec) return { protocol_version: 1, message_id: messageId, state: "unknown" };
    return {
      protocol_version: 1,
      message_id: rec.message_id,
      recipient_mailbox: rec.recipient_mailbox,
      envelope_sha256: rec.envelope_sha256,
      accepted_at: rec.accepted_at,
      expires_at: rec.expires_at,
      state: rec.state === "queued" ? "accepted" : rec.state,
    };
  }

  async function sweep() {
    return locked(async () => {
    const t = now();
    for (const [key, rec] of envelopes.entries()) {
      if (rec.state === "queued" && rec.expires_at <= t) {
        rec.state = "expired";
        rec.encrypted_payload = "";
        rec.sender_credential = null;
        rec.canonical_body = "";
        rec.lease_token = null;
        rec.lease_until = 0;
        await persistEnvelope(rec);
      }
      const dropAfter = rec.expires_at + TOMBSTONE_EXTRA_SECONDS;
      if ((rec.state === "expired" || rec.state === "consumed") && t > dropAfter) {
        envelopes.delete(key);
        try {
          await rm(envPath(rec.recipient_mailbox, rec.message_id), { force: true });
        } catch {
          // ignore
        }
      }
    }
    for (const [hash, rec] of invites.entries()) {
      if ((rec.revoked || rec.expires_at <= t) && t > rec.expires_at + TOMBSTONE_EXTRA_SECONDS) {
        invites.delete(hash);
        if (mailboxInvite.get(rec.mailbox_id) === hash) mailboxInvite.delete(rec.mailbox_id);
        try {
          await rm(invitePath(hash), { force: true });
        } catch {
          // ignore
        }
      }
    }
    for (const [mailbox, list] of keyPackages.entries()) {
      const keep = [];
      for (const kp of list) {
        if (kp.expires_at + TOMBSTONE_EXTRA_SECONDS < t) {
          try {
            await rm(kpPath(mailbox, kp.key_package_id), { force: true });
          } catch {
            // ignore
          }
        } else {
          keep.push(kp);
        }
      }
      keyPackages.set(mailbox, keep);
    }
    for (const rec of grants.values()) {
      if (!rec.revoked && rec.expires_at <= t) {
        rec.revoked = true;
        tokens.delete(rec.token_hash);
        await persistGrant(rec);
      }
      if (rec.revoked && t > rec.expires_at + TOMBSTONE_EXTRA_SECONDS) {
        grants.delete(rec.grant_id);
        try {
          await rm(grantPath(rec.mailbox_id, rec.grant_id), { force: true });
        } catch {
          // ignore
        }
      }
    }
    });
  }

  async function putBlob(mailboxId, blobId, bytes) {
    unused(mailboxId);
    if (blobs.has(blobId)) return { status: "conflict" };
    const path = join(blobDir, blobId);
    await atomicWrite(path, bytes);
    blobs.set(blobId, bytes);
    return { status: "ok" };
  }

  function getBlob(blobId) {
    if (blobs.has(blobId)) return blobs.get(blobId);
    return null;
  }

  function unused() {}

  return {
    TTL_SECONDS,
    lookupToken,
    putBlob,
    getBlob,
    createMailbox,
    mailboxOf,
    peekEnvelope,
    putKeyPackage,
    claimKeyPackage,
    submitEnvelope,
    fetchInbox,
    consume,
    putInvite,
    lookupInvite,
    revokeInvite,
    redeemInvite,
    createPeerGrant,
    rotatePeerGrant,
    revokeGrant,
    rotateSubmit,
    registerPush,
    unregisterPush,
    deletePushByTokenHash,
    listPushTokens,
    statusOf,
    sweep,
    setSuspended,
    isSuspended: () => suspended,
    networkPolicy: () => networkPolicy,
    setNetworkPolicy(value) {
      networkPolicy = value && typeof value === "object" ? value : null;
    },
    now,
    decodeB64UrlExact,
    ids: {
      MAILBOX_BYTES,
      INVITE_TOKEN_BYTES,
      TOKEN_BYTES,
      MESSAGE_ID_BYTES,
      CLAIM_ID_BYTES,
      KEY_PACKAGE_ID_BYTES,
      LEASE_BYTES,
      DEVICE_ID_BYTES,
    },
    sha256B64,
    b64url,
  };
}
