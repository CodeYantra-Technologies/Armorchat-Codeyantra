import { createHmac, randomBytes, createHash } from "node:crypto";

const B64 = "base64url";

export function b64url(bytes) {
  return Buffer.from(bytes).toString(B64);
}

export function randomId(size) {
  return b64url(randomBytes(size));
}

export function sha256(bytes) {
  return createHash("sha256").update(bytes).digest();
}

export function sha256B64(bytes) {
  return b64url(sha256(bytes));
}

export function hmacToken(pepper, token) {
  return createHmac("sha256", pepper).update(token, "utf8").digest("base64url");
}

export function shortRef(value) {
  return sha256B64(Buffer.from(String(value), "utf8")).slice(0, 8);
}

const B64URL_RE = /^[A-Za-z0-9_-]+$/;

export function decodeB64UrlExact(text, expectedBytes) {
  if (typeof text !== "string" || !B64URL_RE.test(text)) return null;
  let buf;
  try {
    buf = Buffer.from(text, "base64url");
  } catch {
    return null;
  }
  if (expectedBytes != null && buf.length !== expectedBytes) return null;
  if (b64url(buf) !== text) return null;
  return buf;
}

export const INVITE_TOKEN_BYTES = 32;
export const MAILBOX_BYTES = 32;
export const TOKEN_BYTES = 32;
export const MESSAGE_ID_BYTES = 16;
export const CLAIM_ID_BYTES = 16;
export const KEY_PACKAGE_ID_BYTES = 16;
export const LEASE_BYTES = 16;
export const GRANT_ID_BYTES = 16;
export const DEVICE_ID_BYTES = 16;

/** Deterministic bootstrap grant: replay of the same claim_id returns the same token. */
export function bootstrapGrantToken(pepper, mailboxId, inviteHash, claimId) {
  return createHmac("sha256", pepper)
    .update("ac-bootstrap-v1")
    .update(Buffer.from([0]))
    .update(String(mailboxId))
    .update(Buffer.from([0]))
    .update(String(inviteHash))
    .update(Buffer.from([0]))
    .update(String(claimId))
    .digest("base64url");
}

export const MAILBOX_CHARS = 43;
export const TOKEN_CHARS = 43;
export const MESSAGE_ID_CHARS = 22;
export const CLAIM_ID_CHARS = 22;
export const KEY_PACKAGE_ID_CHARS = 22;
export const LEASE_CHARS = 22;
