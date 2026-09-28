import { createHash, timingSafeEqual } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, open, readdir, readFile, rename, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { b64url, decodeB64UrlExact, sha256B64 } from "./ids.mjs";

/** Ciphertext slice size. Android BlobLayout.CHUNK_SIZE must stay equal to this. */
export const BLOB_CHUNK_SIZE = 256 * 1024;
/** Plaintext cap is 100 MiB. AES-GCM appends a 16-byte tag, so ciphertext may be 16 bytes larger. */
export const BLOB_MAX_BYTES = 100 * 1024 * 1024 + 16;
export const BLOB_SESSION_TTL_SECONDS = 48 * 60 * 60;
export const BLOB_SEALED_TTL_SECONDS = 7 * 24 * 60 * 60;

export class BlobProtocolError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

async function atomicWrite(path, bytes) {
  await mkdir(join(path, ".."), { recursive: true });
  const tmp = `${path}.part`;
  const fh = await open(tmp, "w");
  try {
    await fh.writeFile(bytes);
    await fh.sync();
  } finally {
    await fh.close();
  }
  await rename(tmp, path);
}

function sameBytes(a, b) {
  if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b) || a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

function sameHash(a, b) {
  const left = decodeB64UrlExact(a, 32);
  const right = decodeB64UrlExact(b, 32);
  if (!left || !right) return false;
  return timingSafeEqual(left, right);
}

function chunkLength(meta, index) {
  if (index === meta.chunk_count - 1) {
    return meta.total_bytes - (meta.chunk_count - 1) * meta.chunk_size;
  }
  return meta.chunk_size;
}

/**
 * Disk-only ciphertext sessions. This store never sees attachment keys or plaintext.
 * Its lock is not the mailbox lock: sealing a large blob must not block envelopes.
 */
export async function createBlobTransport({ dir, nowSeconds, chunkSize = BLOB_CHUNK_SIZE }) {
  if (!Number.isInteger(chunkSize) || chunkSize < 16 || chunkSize > 1024 * 1024) {
    throw new Error("blob chunk size");
  }
  const root = dir;
  const sessionsRoot = join(root, "sessions");
  await mkdir(sessionsRoot, { recursive: true });
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

  function sessionDir(blobId) {
    return join(sessionsRoot, blobId);
  }
  function metaPath(blobId) {
    return join(sessionDir(blobId), "meta.json");
  }
  function chunkPath(blobId, index) {
    return join(sessionDir(blobId), "chunks", String(index));
  }
  function sealedPath(blobId) {
    return join(sessionDir(blobId), "sealed");
  }

  async function readMeta(blobId) {
    try {
      const raw = await readFile(metaPath(blobId), "utf8");
      const meta = JSON.parse(raw);
      if (!meta || meta.blob_id !== blobId) return null;
      return meta;
    } catch {
      return null;
    }
  }

  async function writeMeta(meta) {
    await atomicWrite(metaPath(meta.blob_id), Buffer.from(JSON.stringify(meta), "utf8"));
  }

  function publicSession(meta, created) {
    const received = meta.upload_state === "sealed"
      ? Array.from({ length: meta.chunk_count }, (_, i) => i)
      : meta.received.slice().sort((a, b) => a - b);
    return {
      created,
      body: {
        protocol_version: 1,
        state: "accepted",
        upload_state: meta.upload_state,
        total_bytes: meta.total_bytes,
        chunk_size: meta.chunk_size,
        chunk_count: meta.chunk_count,
        ciphertext_sha256: meta.ciphertext_sha256,
        received,
      },
    };
  }

  function assertOwner(meta, mailboxId) {
    if (!meta) throw new BlobProtocolError(404, "NOT_FOUND");
    if (meta.mailbox_id !== mailboxId) throw new BlobProtocolError(401, "UNAUTHORIZED");
  }

  async function openSession({
    blobId,
    mailboxId,
    protocolVersion,
    totalBytes,
    chunkSize: requestedChunk,
    chunkCount,
    ciphertextSha256,
  }) {
    if (protocolVersion !== 1) throw new BlobProtocolError(400, "MALFORMED");
    if (!Number.isInteger(totalBytes) || totalBytes < 1 || totalBytes > BLOB_MAX_BYTES) {
      throw new BlobProtocolError(413, "PAYLOAD_TOO_LARGE");
    }
    if (requestedChunk !== chunkSize) throw new BlobProtocolError(400, "MALFORMED");
    if (!Number.isInteger(chunkCount) || chunkCount < 1 || chunkCount > 512) {
      throw new BlobProtocolError(400, "MALFORMED");
    }
    const expectCount = Math.ceil(totalBytes / chunkSize);
    if (chunkCount !== expectCount) throw new BlobProtocolError(400, "MALFORMED");
    const last = totalBytes - (chunkCount - 1) * chunkSize;
    if (last < 1 || last > chunkSize) throw new BlobProtocolError(400, "MALFORMED");
    if (!decodeB64UrlExact(ciphertextSha256, 32)) throw new BlobProtocolError(400, "MALFORMED");
    return locked(async () => {
      const existing = await readMeta(blobId);
      if (existing) {
        assertOwner(existing, mailboxId);
        const same = existing.total_bytes === totalBytes &&
          existing.chunk_size === chunkSize &&
          existing.chunk_count === chunkCount &&
          sameHash(existing.ciphertext_sha256, ciphertextSha256);
        if (!same) throw new BlobProtocolError(409, "CONFLICT");
        return publicSession(existing, false);
      }
      const meta = {
        blob_id: blobId,
        mailbox_id: mailboxId,
        total_bytes: totalBytes,
        chunk_size: chunkSize,
        chunk_count: chunkCount,
        ciphertext_sha256: ciphertextSha256,
        chunk_sha256: {},
        received: [],
        upload_state: "open",
        created_at: now(),
        updated_at: now(),
        sealed_at: 0,
      };
      await mkdir(join(sessionDir(blobId), "chunks"), { recursive: true });
      await writeMeta(meta);
      return publicSession(meta, true);
    });
  }

  async function readExact(fh, length, position) {
    const buf = Buffer.alloc(length);
    let off = 0;
    while (off < length) {
      const { bytesRead } = await fh.read(buf, off, length - off, position + off);
      if (bytesRead <= 0) return null;
      off += bytesRead;
    }
    return buf;
  }

  async function writeExact(fh, buf) {
    let off = 0;
    while (off < buf.length) {
      const { bytesWritten } = await fh.write(buf, off, buf.length - off);
      if (bytesWritten <= 0) throw new BlobProtocolError(500, "INTERNAL");
      off += bytesWritten;
    }
  }

  async function readSealedRange(meta, index) {
    const len = chunkLength(meta, index);
    const fh = await open(sealedPath(meta.blob_id), "r");
    try {
      return await readExact(fh, len, index * meta.chunk_size);
    } finally {
      await fh.close();
    }
  }

  async function putChunk({ blobId, mailboxId, index, bytes }) {
    return locked(async () => {
      const meta = await readMeta(blobId);
      assertOwner(meta, mailboxId);
      if (!Number.isInteger(index) || index < 0 || index >= meta.chunk_count) {
        throw new BlobProtocolError(400, "MALFORMED");
      }
      const expect = chunkLength(meta, index);
      if (!Buffer.isBuffer(bytes) || bytes.length !== expect) throw new BlobProtocolError(400, "MALFORMED");
      if (meta.upload_state === "sealed") {
        const have = await readSealedRange(meta, index);
        if (!have || !sameBytes(have, bytes)) throw new BlobProtocolError(409, "CONFLICT");
        return { created: false };
      }
      const hash = sha256B64(bytes);
      const dest = chunkPath(blobId, index);
      let created = true;
      try {
        const existing = await readFile(dest);
        if (!sameBytes(existing, bytes)) throw new BlobProtocolError(409, "CONFLICT");
        created = false;
      } catch (err) {
        if (err instanceof BlobProtocolError) throw err;
        await atomicWrite(dest, bytes);
      }
      if (meta.upload_state !== "sealed") {
        meta.chunk_sha256[String(index)] = hash;
        if (!meta.received.includes(index)) meta.received.push(index);
        meta.received.sort((a, b) => a - b);
        meta.updated_at = now();
        await writeMeta(meta);
      }
      return { created };
    });
  }

  async function assemble(meta) {
    const tmp = `${sealedPath(meta.blob_id)}.part`;
    const fh = await open(tmp, "w");
    const whole = createHash("sha256");
    try {
      for (let i = 0; i < meta.chunk_count; i += 1) {
        const file = chunkPath(meta.blob_id, i);
        let st;
        try {
          st = await stat(file);
        } catch {
          throw new BlobProtocolError(409, "CONFLICT");
        }
        if (st.size !== chunkLength(meta, i)) throw new BlobProtocolError(409, "CONFLICT");
        const part = createHash("sha256");
        const rs = createReadStream(file);
        for await (const buf of rs) {
          part.update(buf);
          whole.update(buf);
          await writeExact(fh, buf);
        }
        const got = b64url(part.digest());
        if (got !== meta.chunk_sha256[String(i)]) throw new BlobProtocolError(400, "MALFORMED");
      }
      await fh.sync();
    } catch (err) {
      await fh.close().catch(() => undefined);
      await rm(tmp, { force: true });
      throw err;
    }
    await fh.close();
    const digest = b64url(whole.digest());
    if (!sameHash(digest, meta.ciphertext_sha256)) {
      await rm(tmp, { force: true });
      throw new BlobProtocolError(400, "MALFORMED");
    }
    await rename(tmp, sealedPath(meta.blob_id));
  }

  async function complete({ blobId, mailboxId, protocolVersion, ciphertextSha256 }) {
    if (protocolVersion !== 1) throw new BlobProtocolError(400, "MALFORMED");
    if (!sameHash(ciphertextSha256, ciphertextSha256)) throw new BlobProtocolError(400, "MALFORMED");
    const ready = await locked(async () => {
      const meta = await readMeta(blobId);
      assertOwner(meta, mailboxId);
      if (!sameHash(meta.ciphertext_sha256, ciphertextSha256)) throw new BlobProtocolError(400, "MALFORMED");
      if (meta.upload_state === "sealed") return { already: true };
      if (meta.upload_state === "sealing") return { busy: true };
      if (meta.received.length !== meta.chunk_count) throw new BlobProtocolError(409, "CONFLICT");
      for (let i = 0; i < meta.chunk_count; i += 1) {
        if (!meta.received.includes(i) || !meta.chunk_sha256[String(i)]) {
          throw new BlobProtocolError(409, "CONFLICT");
        }
      }
      meta.upload_state = "sealing";
      meta.updated_at = now();
      await writeMeta(meta);
      return { meta };
    });
    if (ready.already) return { created: false };
    if (ready.busy) throw new BlobProtocolError(409, "CONFLICT");
    try {
      await assemble(ready.meta);
    } catch (err) {
      await locked(async () => {
        const meta = await readMeta(blobId);
        if (meta && meta.upload_state === "sealing") {
          meta.upload_state = "open";
          meta.updated_at = now();
          await writeMeta(meta);
        }
      });
      throw err;
    }
    await locked(async () => {
      const meta = await readMeta(blobId);
      assertOwner(meta, mailboxId);
      meta.upload_state = "sealed";
      meta.sealed_at = now();
      meta.updated_at = meta.sealed_at;
      meta.received = Array.from({ length: meta.chunk_count }, (_, i) => i);
      await writeMeta(meta);
      await rm(join(sessionDir(blobId), "chunks"), { recursive: true, force: true });
    });
    return { created: true };
  }

  async function manifest(blobId) {
    const meta = await readMeta(blobId);
    if (!meta || meta.upload_state !== "sealed") return null;
    const hashes = [];
    for (let i = 0; i < meta.chunk_count; i += 1) {
      const hash = meta.chunk_sha256[String(i)];
      if (!hash) return null;
      hashes.push(hash);
    }
    return {
      protocol_version: 1,
      state: "accepted",
      upload_state: "sealed",
      total_bytes: meta.total_bytes,
      chunk_size: meta.chunk_size,
      chunk_count: meta.chunk_count,
      ciphertext_sha256: meta.ciphertext_sha256,
      chunk_sha256: hashes,
    };
  }

  async function getChunk(blobId, index) {
    const meta = await readMeta(blobId);
    if (!meta || meta.upload_state !== "sealed") return null;
    if (!Number.isInteger(index) || index < 0 || index >= meta.chunk_count) return null;
    return readSealedRange(meta, index);
  }

  async function deleteSession(blobId, mailboxId) {
    return locked(async () => {
      const meta = await readMeta(blobId);
      if (!meta) return false;
      if (meta.mailbox_id !== mailboxId) throw new BlobProtocolError(401, "UNAUTHORIZED");
      await rm(sessionDir(blobId), { recursive: true, force: true });
      return true;
    });
  }

  async function sweep() {
    return locked(async () => {
      const t = now();
      let names = [];
      try {
        names = await readdir(sessionsRoot);
      } catch {
        return;
      }
      for (const name of names) {
        if (!decodeB64UrlExact(name)) continue;
        const meta = await readMeta(name);
        if (!meta) {
          await rm(sessionDir(name), { recursive: true, force: true });
          continue;
        }
        const openExpired = meta.upload_state !== "sealed" && t > meta.updated_at + BLOB_SESSION_TTL_SECONDS;
        const sealedExpired = meta.upload_state === "sealed" && meta.sealed_at > 0 &&
          t > meta.sealed_at + BLOB_SEALED_TTL_SECONDS;
        if (openExpired || sealedExpired) {
          await rm(sessionDir(name), { recursive: true, force: true });
        }
      }
    });
  }

  return {
    chunkSize,
    openSession,
    putChunk,
    complete,
    manifest,
    getChunk,
    deleteSession,
    sweep,
  };
}
