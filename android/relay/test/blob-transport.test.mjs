import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import https from "node:https";
import test from "node:test";
import { canonicalize } from "../src/json.mjs";
import { b64url, sha256B64 } from "../src/ids.mjs";
import { createStore, PAYLOAD_MAX, REQUEST_MAX } from "../src/store.mjs";
import { createRelay } from "../src/relay.mjs";
import { BLOB_CHUNK_SIZE, BLOB_MAX_BYTES, createBlobTransport } from "../src/blob-transport.mjs";

function tmpDir() {
  return mkdtempSync(join(tmpdir(), "armorchat-blob-"));
}

function makeTls() {
  const dir = tmpDir();
  const key = join(dir, "key.pem");
  const cert = join(dir, "cert.pem");
  execFileSync(
    "openssl",
    ["req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes", "-subj", "/CN=localhost", "-keyout", key, "-out", cert],
    { stdio: "pipe" },
  );
  return { dir, key: readFileSync(key), cert: readFileSync(cert) };
}

async function startRelay(opts = {}) {
  let t = opts.now || 1_700_000_000;
  const dir = tmpDir();
  const tls = makeTls();
  const store = await createStore({ dir, nowSeconds: () => t });
  const blobs = await createBlobTransport({
    dir: join(dir, "blob-chunks"),
    nowSeconds: () => t,
    chunkSize: opts.chunkSize,
  });
  const relay = createRelay({ store, tls, blobTransport: blobs });
  const addr = await relay.listen("127.0.0.1", 0);
  return {
    blobs,
    addr,
    advance(seconds) {
      t += seconds;
    },
    async close() {
      await relay.close();
      rmSync(dir, { recursive: true, force: true });
      rmSync(tls.dir, { recursive: true, force: true });
    },
  };
}

function rawCall(ctx, { method, path, token, body, contentType }) {
  const payload = Buffer.isBuffer(body) ? body : Buffer.alloc(0);
  const headers = { "content-length": payload.length };
  if (contentType) headers["content-type"] = contentType;
  if (token) headers.authorization = `Bearer ${token}`;
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: "127.0.0.1",
        port: ctx.addr.port,
        method,
        path,
        rejectUnauthorized: false,
        headers,
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const raw = Buffer.concat(chunks);
          const type = String(res.headers["content-type"] || "");
          let parsed = null;
          if (type.includes("json")) {
            parsed = JSON.parse(raw.toString("utf8"));
            assert.equal(/"state":"(delivered|read)"/i.test(raw.toString("utf8")), false);
          }
          resolve({ status: res.statusCode, raw, body: parsed });
        });
      },
    );
    req.on("error", reject);
    if (payload.length) req.write(payload);
    req.end();
  });
}

function jsonCall(ctx, { method, path, token, body }) {
  return rawCall(ctx, {
    method,
    path,
    token,
    contentType: "application/json",
    body: Buffer.from(canonicalize(body), "utf8"),
  });
}

async function register(ctx) {
  const res = await jsonCall(ctx, { method: "POST", path: "/v1/mailboxes", body: { protocol_version: 1 } });
  assert.equal(res.status, 201);
  return res.body;
}

test("message envelope limits are unchanged", () => {
  assert.equal(PAYLOAD_MAX, 24 * 1024);
  assert.equal(REQUEST_MAX, 48 * 1024);
  assert.equal(BLOB_CHUNK_SIZE, 256 * 1024);
  assert.equal(BLOB_MAX_BYTES, 100 * 1024 * 1024 + 16);
});

test("legacy full-body blob still works and chunk manifest stays absent", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const blobId = b64url(randomBytes(32));
  const put = await rawCall(ctx, {
    method: "PUT",
    path: `/v1/blobs/${blobId}`,
    token: box.owner_token,
    contentType: "application/octet-stream",
    body: Buffer.from([1, 2, 3, 4]),
  });
  assert.equal(put.status, 201);
  const got = await rawCall(ctx, { method: "GET", path: `/v1/blobs/${blobId}` });
  assert.equal(got.status, 200);
  assert.deepEqual([...got.raw], [1, 2, 3, 4]);
  const manifest = await rawCall(ctx, { method: "GET", path: `/v1/blobs/${blobId}/manifest` });
  assert.equal(manifest.status, 404);
});

test("chunked ciphertext upload resumes, seals, and does not enter the legacy blob map", async (t) => {
  const ctx = await startRelay({ chunkSize: 32 });
  t.after(() => ctx.close());
  const box = await register(ctx);
  const blobId = b64url(randomBytes(32));
  const cipher = randomBytes(70);
  const digest = sha256B64(cipher);
  const sessionBody = {
    chunk_count: 3,
    chunk_size: 32,
    ciphertext_sha256: digest,
    protocol_version: 1,
    total_bytes: cipher.length,
  };
  const early = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/complete`,
    token: box.owner_token,
    body: { ciphertext_sha256: digest, protocol_version: 1 },
  });
  assert.equal(early.status, 404);
  const opened = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/sessions`,
    token: box.owner_token,
    body: sessionBody,
  });
  assert.equal(opened.status, 201);
  assert.deepEqual(opened.body.received, []);
  assert.equal(opened.body.upload_state, "open");
  const incomplete = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/complete`,
    token: box.owner_token,
    body: { ciphertext_sha256: digest, protocol_version: 1 },
  });
  assert.equal(incomplete.status, 409);
  const slices = [cipher.subarray(0, 32), cipher.subarray(32, 64), cipher.subarray(64)];
  const order = [2, 0, 1];
  for (const index of order) {
    const put = await rawCall(ctx, {
      method: "PUT",
      path: `/v1/blobs/${blobId}/chunks/${index}`,
      token: box.owner_token,
      contentType: "application/octet-stream",
      body: slices[index],
    });
    assert.equal(put.status, 201, `chunk ${index}`);
  }
  const retry = await rawCall(ctx, {
    method: "PUT",
    path: `/v1/blobs/${blobId}/chunks/0`,
    token: box.owner_token,
    contentType: "application/octet-stream",
    body: slices[0],
  });
  assert.equal(retry.status, 200);
  const clash = await rawCall(ctx, {
    method: "PUT",
    path: `/v1/blobs/${blobId}/chunks/0`,
    token: box.owner_token,
    contentType: "application/octet-stream",
    body: Buffer.alloc(32, 9),
  });
  assert.equal(clash.status, 409);
  const resumed = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/sessions`,
    token: box.owner_token,
    body: sessionBody,
  });
  assert.equal(resumed.status, 200);
  assert.deepEqual(resumed.body.received, [0, 1, 2]);
  const badHash = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/complete`,
    token: box.owner_token,
    body: { ciphertext_sha256: sha256B64(Buffer.from("nope")), protocol_version: 1 },
  });
  assert.equal(badHash.status, 400);
  const sealed = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/complete`,
    token: box.owner_token,
    body: { ciphertext_sha256: digest, protocol_version: 1 },
  });
  assert.equal(sealed.status, 201);
  const again = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/complete`,
    token: box.owner_token,
    body: { ciphertext_sha256: digest, protocol_version: 1 },
  });
  assert.equal(again.status, 200);
  const manifest = await rawCall(ctx, { method: "GET", path: `/v1/blobs/${blobId}/manifest` });
  assert.equal(manifest.status, 200);
  assert.equal(manifest.body.ciphertext_sha256, digest);
  assert.equal(manifest.body.chunk_count, 3);
  const parts = [];
  for (let i = 0; i < 3; i += 1) {
    const chunk = await rawCall(ctx, { method: "GET", path: `/v1/blobs/${blobId}/chunks/${i}` });
    assert.equal(chunk.status, 200);
    assert.equal(sha256B64(chunk.raw), manifest.body.chunk_sha256[i]);
    parts.push(chunk.raw);
  }
  assert.deepEqual([...Buffer.concat(parts)], [...cipher]);
  const legacy = await rawCall(ctx, { method: "GET", path: `/v1/blobs/${blobId}` });
  assert.equal(legacy.status, 404);
  const mailbox = await register(ctx);
  assert.equal(mailbox.owner_token.length, 43);
  const envelopes = await jsonCall(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    body: { protocol_version: 1 },
  });
  assert.notEqual(envelopes.status, 500);
  assert.notEqual(envelopes.status, 201);
});

test("oversized session, wrong chunk size, and abandoned uploads are rejected or swept", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const blobId = b64url(randomBytes(32));
  const tooBig = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/sessions`,
    token: box.owner_token,
    body: {
      chunk_count: 1,
      chunk_size: BLOB_CHUNK_SIZE,
      ciphertext_sha256: sha256B64(Buffer.from("x")),
      protocol_version: 1,
      total_bytes: BLOB_MAX_BYTES + 1,
    },
  });
  assert.equal(tooBig.status, 413);
  const wrongSize = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/sessions`,
    token: box.owner_token,
    body: {
      chunk_count: 1,
      chunk_size: 32,
      ciphertext_sha256: sha256B64(Buffer.from("x")),
      protocol_version: 1,
      total_bytes: 8,
    },
  });
  assert.equal(wrongSize.status, 400);
  const opened = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/sessions`,
    token: box.owner_token,
    body: {
      chunk_count: 1,
      chunk_size: BLOB_CHUNK_SIZE,
      ciphertext_sha256: sha256B64(Buffer.alloc(8, 3)),
      protocol_version: 1,
      total_bytes: 8,
    },
  });
  assert.equal(opened.status, 201);
  ctx.advance(48 * 60 * 60 + 5);
  await ctx.blobs.sweep();
  const gone = await rawCall(ctx, { method: "GET", path: `/v1/blobs/${blobId}/manifest` });
  assert.equal(gone.status, 404);
  const reopened = await jsonCall(ctx, {
    method: "POST",
    path: `/v1/blobs/${blobId}/sessions`,
    token: box.owner_token,
    body: {
      chunk_count: 1,
      chunk_size: BLOB_CHUNK_SIZE,
      ciphertext_sha256: sha256B64(Buffer.alloc(8, 3)),
      protocol_version: 1,
      total_bytes: 8,
    },
  });
  assert.equal(reopened.status, 201);
});
