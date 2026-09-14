import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import https from "node:https";
import test from "node:test";
import { canonicalize } from "../src/json.mjs";
import { b64url } from "../src/ids.mjs";
import { createStore, TTL_SECONDS, LEASE_SECONDS } from "../src/store.mjs";
import { createRelay } from "../src/relay.mjs";

function tmpDir() {
  return mkdtempSync(join(tmpdir(), "armorchat-relay-"));
}

function makeTls() {
  const dir = tmpDir();
  const key = join(dir, "key.pem");
  const cert = join(dir, "cert.pem");
  execFileSync(
    "openssl",
    [
      "req",
      "-x509",
      "-newkey",
      "rsa:2048",
      "-sha256",
      "-days",
      "1",
      "-nodes",
      "-subj",
      "/CN=localhost",
      "-keyout",
      key,
      "-out",
      cert,
    ],
    { stdio: "pipe" },
  );
  return { dir, key: readFileSync(key), cert: readFileSync(cert) };
}

function assertNoDeliveryState(obj) {
  const raw = JSON.stringify(obj);
  assert.equal(/delivered|read/i.test(raw), false, raw);
  if (obj && typeof obj === "object" && obj.state) {
    assert.ok(["accepted", "consumed", "expired", "unknown"].includes(obj.state) || obj.state === undefined);
  }
}

async function startRelay(opts = {}) {
  let t = opts.now || 1_700_000_000;
  const dir = opts.dir || tmpDir();
  const tls = opts.tls || makeTls();
  const store = await createStore({
    dir,
    nowSeconds: () => t,
  });
  const relay = createRelay({
    store,
    tls,
    mailboxLimitPerHour: opts.mailboxLimitPerHour || 20,
    pushSender: opts.pushSender || null,
  });
  const addr = await relay.listen("127.0.0.1", 0);
  return {
    dir,
    store,
    relay,
    addr,
    now: () => t,
    advance(s) {
      t += s;
    },
    async close() {
      await relay.close();
      if (!opts.keepDir) rmSync(dir, { recursive: true, force: true });
    },
  };
}

function call(ctx, { method, path, token, body, headers = {} }) {
  const payload = Buffer.from(typeof body === "string" ? body : canonicalize(body), "utf8");
  const hdrs = {
    "content-type": "application/json",
    "content-length": payload.length,
    ...headers,
  };
  if (token) hdrs.authorization = `Bearer ${token}`;
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: "127.0.0.1",
        port: ctx.addr.port,
        method,
        path,
        rejectUnauthorized: false,
        headers: hdrs,
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          let parsed;
          try {
            parsed = JSON.parse(raw);
          } catch {
            parsed = null;
          }
          if (parsed) assertNoDeliveryState(parsed);
          resolve({ status: res.statusCode, raw, body: parsed, headers: res.headers });
        });
      },
    );
    req.on("error", reject);
    req.write(payload);
    req.end();
  });
}

function zeros(n) {
  return b64url(Buffer.alloc(n));
}

function envelope({ mailbox, messageId, payload, payloadType = "application", expiresAt, credential }) {
  const body = {
    encrypted_payload: b64url(Buffer.from(payload || "Hello ArmorChat")),
    expires_at: expiresAt,
    message_id: messageId || zeros(16),
    payload_type: payloadType,
    protocol_version: 1,
    recipient_mailbox: mailbox,
    suite: "ac-mls-v1",
  };
  if (credential) body.sender_credential = credential;
  return body;
}

async function register(ctx) {
  const res = await call(ctx, { method: "POST", path: "/v1/mailboxes", body: { protocol_version: 1 } });
  assert.equal(res.status, 201);
  assert.ok(res.body.mailbox_id);
  assert.ok(res.body.owner_token);
  assert.ok(res.body.submit_capability);
  assert.equal(res.body.mailbox_id.length, 43);
  return res.body;
}

test("mailbox creation issues separate owner and submit secrets", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  assert.notEqual(box.owner_token, box.submit_capability);
  assert.equal(box.owner_token.length, 43);
});

test("unauthorized submit and fetch", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const a = await register(ctx);
  const b = await register(ctx);
  const env = envelope({ mailbox: a.mailbox_id, expiresAt: ctx.now() + 3600 });
  const noAuth = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(noAuth.status, 401);
  const wrong = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: b.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(wrong.status, 401);
  const ownerSubmit = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: a.owner_token,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(ownerSubmit.status, 401);
  const fetchSubmit = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: a.submit_capability,
    body: { protocol_version: 1 },
  });
  assert.equal(fetchSubmit.status, 401);
});

test("duplicate retry returns original acceptance; digest mismatch conflicts", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const first = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(first.status, 201);
  assert.equal(first.body.state, "accepted");
  assert.equal(first.body.message_id, env.message_id);
  const replay = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(replay.status, 200);
  assert.equal(replay.body.envelope_sha256, first.body.envelope_sha256);
  assert.equal(replay.body.accepted_at, first.body.accepted_at);
  const mutated = { ...env, encrypted_payload: b64url(Buffer.from("Hello ArmorChat!")) };
  const conflict = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: mutated,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(conflict.status, 409);
  assert.equal(conflict.body.error, "DIGEST_MISMATCH");
});

test("fetch leases without deleting; consume is explicit", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const sent = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(sent.status, 201);
  const fetch1 = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch1.status, 200);
  assert.equal(fetch1.body.envelopes.length, 1);
  const item = fetch1.body.envelopes[0];
  assert.equal(item.message_id, env.message_id);
  assert.ok(item.lease_token);
  const fetch2 = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch2.body.envelopes.length, 1);
  assert.equal(fetch2.body.envelopes[0].lease_token, item.lease_token);
  const badLease = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/consume",
    token: box.owner_token,
    body: {
      protocol_version: 1,
      items: [
        {
          envelope_sha256: item.envelope_sha256,
          lease_token: zeros(16),
          message_id: item.message_id,
        },
      ],
    },
  });
  assert.equal(badLease.status, 409);
  const consume = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/consume",
    token: box.owner_token,
    body: {
      protocol_version: 1,
      items: [
        {
          envelope_sha256: item.envelope_sha256,
          lease_token: item.lease_token,
          message_id: item.message_id,
        },
      ],
    },
  });
  assert.equal(consume.status, 200);
  assert.equal(consume.body.results[0].status, "consumed");
  const replayConsume = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/consume",
    token: box.owner_token,
    body: {
      protocol_version: 1,
      items: [
        {
          envelope_sha256: item.envelope_sha256,
          lease_token: item.lease_token,
          message_id: item.message_id,
        },
      ],
    },
  });
  assert.equal(replayConsume.status, 200);
  const fetch3 = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch3.body.envelopes.length, 0);
  const st = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes/status",
    token: box.submit_capability,
    body: { message_ids: [env.message_id], protocol_version: 1 },
  });
  assert.equal(st.body.statuses[0].state, "consumed");
});

test("lease expires and redelivers", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  const fetch1 = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  const lease1 = fetch1.body.envelopes[0].lease_token;
  ctx.advance(LEASE_SECONDS + 1);
  const fetch2 = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch2.body.envelopes.length, 1);
  assert.notEqual(fetch2.body.envelopes[0].lease_token, lease1);
});

test("ttl expiry deletes ciphertext and keeps tombstone state", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 10 });
  await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  ctx.advance(11);
  const fetch = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch.body.envelopes.length, 0);
  const st = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes/status",
    token: box.submit_capability,
    body: { message_ids: [env.message_id], protocol_version: 1 },
  });
  assert.equal(st.body.statuses[0].state, "expired");
  const retry = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(retry.status, 200);
  assert.equal(retry.body.state, "expired");
});

test("malformed envelope rejected", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const cases = [
    '{"protocol_version":1, "suite":"ac-mls-v1"}',
    canonicalize({ ...env, protocol_version: 2 }),
    canonicalize({ ...env, suite: "ac-olm-v1" }),
    canonicalize({ ...env, conversationId: "local" }),
    canonicalize({ ...env, payload_type: "normal" }),
    canonicalize({ ...env, extra: 1 }),
  ];
  for (const body of cases) {
    const res = await call(ctx, {
      method: "POST",
      path: "/v1/envelopes",
      token: box.submit_capability,
      body,
      headers: { "idempotency-key": env.message_id },
    });
    assert.equal(res.status, 400, body);
  }
  const dup = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: '{"encrypted_payload":"QQ","expires_at":1,"message_id":"AAAAAAAAAAAAAAAAAAAAAA","payload_type":"application","protocol_version":1,"protocol_version":1,"recipient_mailbox":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","suite":"ac-mls-v1"}',
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(dup.status, 400);
});

test("oversized payload rejected", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({
    mailbox: box.mailbox_id,
    expiresAt: ctx.now() + 3600,
    payload: Buffer.alloc(24 * 1024 + 1, 7),
  });
  const res = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(res.status, 413);
});

test("service suspension returns 503 and preserves queued ciphertext", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const sent = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(sent.status, 201);
  const originalExpiry = sent.body.expires_at;
  await ctx.store.setSuspended(true);
  const blocked = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(blocked.status, 503);
  assert.equal(blocked.body.error, "SERVICE_SUSPENDED");
  assert.ok(blocked.headers["retry-after"]);
  const blockedSubmit = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(blockedSubmit.status, 503);
  await ctx.store.setSuspended(false);
  const fetch = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch.status, 200);
  assert.equal(fetch.body.envelopes.length, 1);
  assert.equal(fetch.body.envelopes[0].expires_at, originalExpiry);
});

test("network policy GET is served while suspended and never includes delivered/read", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const missing = await call(ctx, { method: "GET", path: "/v1/network-policy", body: "" });
  assert.equal(missing.status, 404);
  ctx.store.setNetworkPolicy({
    p: "armorchat-service-policy",
    policy: {
      body: {
        applies_to: "codeyantra-authorized-relays",
        expires_at: ctx.now() + 3600,
        issued_at: ctx.now(),
        network: "codeyantra",
        policy_id: "pol1",
        state: "SUSPENDED",
        v: 1,
      },
      signature: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    },
    v: 1,
  });
  await ctx.store.setSuspended(true);
  const served = await call(ctx, { method: "GET", path: "/v1/network-policy", body: "" });
  assert.equal(served.status, 200);
  assert.equal(served.body.policy.body.state, "SUSPENDED");
  assert.equal(served.body.p, "armorchat-service-policy");
});

test("relay never produces delivered or read state", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const sent = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  const fetch = await call(ctx, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  const st = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes/status",
    token: box.submit_capability,
    body: { message_ids: [env.message_id], protocol_version: 1 },
  });
  for (const obj of [sent.body, fetch.body, st.body]) {
    assertNoDeliveryState(obj);
    const raw = JSON.stringify(obj).toLowerCase();
    assert.equal(raw.includes("delivered"), false);
    assert.equal(raw.includes('"read"'), false);
  }
});

test("keypackage publish and claim are opaque to the relay", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const kpId = zeros(16);
  const binding = JSON.stringify({
    body: { identity_key: "not-verified-here", key_package: "YQ", suite: "ac-mls-v1", v: 1 },
    signature: "Yg",
  });
  const put = await call(ctx, {
    method: "PUT",
    path: "/v1/key-packages",
    token: box.owner_token,
    body: {
      key_packages: [{ binding, expires_at: ctx.now() + 3600, key_package_id: kpId }],
      protocol_version: 1,
      suite: "ac-mls-v1",
    },
  });
  assert.equal(put.status, 200);
  const claimId = b64url(Buffer.alloc(16, 3));
  const claim = await call(ctx, {
    method: "POST",
    path: "/v1/key-packages/claim",
    token: box.submit_capability,
    body: { claim_id: claimId, protocol_version: 1 },
  });
  assert.equal(claim.status, 200);
  assert.equal(claim.body.binding, binding);
  const replay = await call(ctx, {
    method: "POST",
    path: "/v1/key-packages/claim",
    token: box.submit_capability,
    body: { claim_id: claimId, protocol_version: 1 },
  });
  assert.equal(replay.body.key_package_id, kpId);
  const other = await call(ctx, {
    method: "POST",
    path: "/v1/key-packages/claim",
    token: box.submit_capability,
    body: { claim_id: b64url(Buffer.alloc(16, 4)), protocol_version: 1 },
  });
  assert.equal(other.status, 404);
});

test("durable queue survives process restart", async (t) => {
  const tls = makeTls();
  const dir = tmpDir();
  const first = await startRelay({ dir, tls, keepDir: true });
  const box = await register(first);
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: first.now() + 3600 });
  const sent = await call(first, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(sent.status, 201);
  await first.relay.close();
  const second = await startRelay({ dir, tls, now: first.now(), keepDir: true });
  t.after(() => {
    second.close();
    rmSync(dir, { recursive: true, force: true });
    rmSync(tls.dir, { recursive: true, force: true });
  });
  const fetch = await call(second, {
    method: "POST",
    path: "/v1/inbox/fetch",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(fetch.body.envelopes.length, 1);
  assert.equal(fetch.body.envelopes[0].envelope_sha256, sent.body.envelope_sha256);
});

test("handshake requires sender_credential; application forbids it", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const missing = envelope({
    mailbox: box.mailbox_id,
    expiresAt: ctx.now() + 3600,
    payloadType: "handshake",
  });
  const res1 = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: missing,
    headers: { "idempotency-key": missing.message_id },
  });
  assert.equal(res1.status, 400);
  const ok = envelope({
    mailbox: box.mailbox_id,
    expiresAt: ctx.now() + 3600,
    payloadType: "handshake",
    credential: { suite: "ac-mls-v1", v: 1 },
  });
  const res2 = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: ok,
    headers: { "idempotency-key": ok.message_id },
  });
  assert.equal(res2.status, 201);
  const app = envelope({
    mailbox: box.mailbox_id,
    messageId: b64url(Buffer.alloc(16, 9)),
    expiresAt: ctx.now() + 3600,
    credential: { v: 1 },
  });
  const res3 = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: app,
    headers: { "idempotency-key": app.message_id },
  });
  assert.equal(res3.status, 400);
});

test("default ttl window is seven days", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const tooFar = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + TTL_SECONDS + 1 });
  const res = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: tooFar,
    headers: { "idempotency-key": tooFar.message_id },
  });
  assert.equal(res.status, 400);
  const ok = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + TTL_SECONDS });
  const res2 = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: ok,
    headers: { "idempotency-key": ok.message_id },
  });
  assert.equal(res2.status, 201);
});

test("v3 invite publish lookup and revoke", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const token = b64url(Buffer.alloc(32, 7));
  const bundle = '{"p":"armorchat","v":3,"contact_bundle":{"body":{"v":3},"signature":"x"}}';
  const put = await call(ctx, {
    method: "POST",
    path: "/v1/invites",
    token: box.owner_token,
    body: {
      bundle,
      expires_at: ctx.now() + 3600,
      protocol_version: 1,
      token,
    },
  });
  assert.equal(put.status, 201);
  const found = await call(ctx, {
    method: "POST",
    path: "/v1/invites/lookup",
    body: { protocol_version: 1, token },
  });
  assert.equal(found.status, 200);
  assert.equal(found.body.bundle, bundle);
  const revoke = await call(ctx, {
    method: "POST",
    path: "/v1/invites/revoke",
    token: box.owner_token,
    body: { protocol_version: 1, token },
  });
  assert.equal(revoke.status, 200);
  const missing = await call(ctx, {
    method: "POST",
    path: "/v1/invites/lookup",
    body: { protocol_version: 1, token },
  });
  assert.equal(missing.status, 404);
  assert.equal(missing.body.error, "INVITE_UNKNOWN");
});

test("v3 invite unknown and expired are uniform", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const token = b64url(Buffer.alloc(32, 9));
  const unknown = await call(ctx, {
    method: "POST",
    path: "/v1/invites/lookup",
    body: { protocol_version: 1, token },
  });
  assert.equal(unknown.status, 404);
  assert.equal(unknown.body.error, "INVITE_UNKNOWN");
  const box = await register(ctx);
  const put = await call(ctx, {
    method: "POST",
    path: "/v1/invites",
    token: box.owner_token,
    body: {
      bundle: '{"p":"armorchat","v":3}',
      expires_at: ctx.now() + 5,
      protocol_version: 1,
      token,
    },
  });
  assert.equal(put.status, 201);
  ctx.advance(10);
  const expired = await call(ctx, {
    method: "POST",
    path: "/v1/invites/lookup",
    body: { protocol_version: 1, token },
  });
  assert.equal(expired.status, 404);
  assert.equal(expired.body.error, "INVITE_UNKNOWN");
});

test("v3 invite token is not accepted in the URL", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const token = b64url(Buffer.alloc(32, 3));
  const res = await call(ctx, {
    method: "POST",
    path: `/v1/invites/lookup?token=${token}`,
    body: { protocol_version: 1, token },
  });
  assert.equal(res.status, 400);
});

function kpBinding(label) {
  return JSON.stringify({
    body: { identity_key: "not-verified-here", key_package: label, suite: "ac-mls-v1", v: 1 },
    signature: "Yg",
  });
}

async function publishKeyPackages(ctx, box, count) {
  const key_packages = [];
  for (let i = 0; i < count; i += 1) {
    const id = Buffer.alloc(16, i + 1);
    key_packages.push({
      binding: kpBinding(`kp-${i}`),
      expires_at: ctx.now() + 3600,
      key_package_id: b64url(id),
    });
  }
  const put = await call(ctx, {
    method: "PUT",
    path: "/v1/key-packages",
    token: box.owner_token,
    body: { key_packages, protocol_version: 1, suite: "ac-mls-v1" },
  });
  assert.equal(put.status, 200);
  return key_packages;
}

async function publishInvite(ctx, box, token) {
  const bundle = '{"p":"armorchat","v":3,"contact_bundle":{"body":{"v":3},"signature":"x"}}';
  const put = await call(ctx, {
    method: "POST",
    path: "/v1/invites",
    token: box.owner_token,
    body: { bundle, expires_at: ctx.now() + 3600, protocol_version: 1, token },
  });
  assert.equal(put.status, 201);
  return bundle;
}

test("same invite token redeems independently for A B C with distinct key packages", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  await publishKeyPackages(ctx, box, 3);
  const token = b64url(Buffer.alloc(32, 11));
  const bundle = await publishInvite(ctx, box, token);
  const kps = new Set();
  const grants = new Set();
  for (let i = 0; i < 3; i += 1) {
    const claimId = b64url(Buffer.alloc(16, 20 + i));
    const redeemed = await call(ctx, {
      method: "POST",
      path: "/v1/invites/redeem",
      body: { claim_id: claimId, protocol_version: 1, token },
    });
    assert.equal(redeemed.status, 201);
    assert.equal(redeemed.body.bundle, bundle);
    assert.ok(redeemed.body.binding);
    assert.equal(redeemed.body.bootstrap_grant.kind, "bootstrap");
    assert.equal(redeemed.body.bootstrap_grant.mailbox_id, box.mailbox_id);
    assert.notEqual(redeemed.body.bootstrap_grant.token, box.submit_capability);
    assert.notEqual(redeemed.body.bootstrap_grant.token, box.owner_token);
    kps.add(redeemed.body.key_package_id);
    grants.add(redeemed.body.bootstrap_grant.token);
  }
  assert.equal(kps.size, 3);
  assert.equal(grants.size, 3);
});

test("redeem claim_id is idempotent and does not reuse a key package across peers", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  await publishKeyPackages(ctx, box, 2);
  const token = b64url(Buffer.alloc(32, 12));
  await publishInvite(ctx, box, token);
  const claimA = b64url(Buffer.alloc(16, 31));
  const first = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: claimA, protocol_version: 1, token },
  });
  const replay = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: claimA, protocol_version: 1, token },
  });
  assert.equal(replay.status, 200);
  assert.equal(replay.body.key_package_id, first.body.key_package_id);
  assert.equal(replay.body.bootstrap_grant.token, first.body.bootstrap_grant.token);
  const claimB = b64url(Buffer.alloc(16, 32));
  const other = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: claimB, protocol_version: 1, token },
  });
  assert.equal(other.status, 201);
  assert.notEqual(other.body.key_package_id, first.body.key_package_id);
  assert.notEqual(other.body.bootstrap_grant.token, first.body.bootstrap_grant.token);
});

test("bootstrap grant cannot impersonate another grant or mailbox", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const a = await register(ctx);
  const b = await register(ctx);
  await publishKeyPackages(ctx, a, 1);
  const token = b64url(Buffer.alloc(32, 13));
  await publishInvite(ctx, a, token);
  const redeemed = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: b64url(Buffer.alloc(16, 41)), protocol_version: 1, token },
  });
  const grant = redeemed.body.bootstrap_grant.token;
  const envB = envelope({ mailbox: b.mailbox_id, expiresAt: ctx.now() + 3600 });
  const cross = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: grant,
    body: envB,
    headers: { "idempotency-key": envB.message_id },
  });
  assert.equal(cross.status, 401);
  const envA = envelope({ mailbox: a.mailbox_id, expiresAt: ctx.now() + 3600 });
  const ok = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: grant,
    body: envA,
    headers: { "idempotency-key": envA.message_id },
  });
  assert.equal(ok.status, 201);
});

test("invite revoke and expiry kill lookup redeem and bootstrap grants", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  await publishKeyPackages(ctx, box, 2);
  const token = b64url(Buffer.alloc(32, 14));
  await publishInvite(ctx, box, token);
  const redeemed = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: b64url(Buffer.alloc(16, 51)), protocol_version: 1, token },
  });
  const grant = redeemed.body.bootstrap_grant.token;
  const revoke = await call(ctx, {
    method: "POST",
    path: "/v1/invites/revoke",
    token: box.owner_token,
    body: { protocol_version: 1, token },
  });
  assert.equal(revoke.status, 200);
  const lookup = await call(ctx, {
    method: "POST",
    path: "/v1/invites/lookup",
    body: { protocol_version: 1, token },
  });
  assert.equal(lookup.status, 404);
  assert.equal(lookup.body.error, "INVITE_UNKNOWN");
  const redeem = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: b64url(Buffer.alloc(16, 52)), protocol_version: 1, token },
  });
  assert.equal(redeem.status, 404);
  assert.equal(redeem.body.error, "INVITE_UNKNOWN");
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const leaked = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: grant,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(leaked.status, 401);

  const token2 = b64url(Buffer.alloc(32, 15));
  await publishKeyPackages(ctx, box, 1);
  await publishInvite(ctx, box, token2);
  ctx.advance(4000);
  const expiredLookup = await call(ctx, {
    method: "POST",
    path: "/v1/invites/lookup",
    body: { protocol_version: 1, token: token2 },
  });
  assert.equal(expiredLookup.status, 404);
  assert.equal(expiredLookup.body.error, "INVITE_UNKNOWN");
  const expiredRedeem = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: b64url(Buffer.alloc(16, 53)), protocol_version: 1, token: token2 },
  });
  assert.equal(expiredRedeem.status, 404);
  assert.equal(expiredRedeem.body.error, "INVITE_UNKNOWN");
});

test("peer grant rotation revocation and leaked old token", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const created = await call(ctx, {
    method: "POST",
    path: "/v1/grants",
    token: box.owner_token,
    body: { expires_at: ctx.now() + 86400, kind: "peer", protocol_version: 1 },
  });
  assert.equal(created.status, 201);
  const oldToken = created.body.token;
  const grantId = created.body.grant_id;
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const first = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: oldToken,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(first.status, 201);
  const rotated = await call(ctx, {
    method: "POST",
    path: "/v1/grants/rotate",
    token: box.owner_token,
    body: { grant_id: grantId, protocol_version: 1 },
  });
  assert.equal(rotated.status, 200);
  assert.notEqual(rotated.body.token, oldToken);
  const leaked = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: oldToken,
    body: envelope({ mailbox: box.mailbox_id, messageId: b64url(Buffer.alloc(16, 9)), expiresAt: ctx.now() + 3600 }),
    headers: { "idempotency-key": b64url(Buffer.alloc(16, 9)) },
  });
  assert.equal(leaked.status, 401);
  const nextEnv = envelope({ mailbox: box.mailbox_id, messageId: b64url(Buffer.alloc(16, 8)), expiresAt: ctx.now() + 3600 });
  const next = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: rotated.body.token,
    body: nextEnv,
    headers: { "idempotency-key": nextEnv.message_id },
  });
  assert.equal(next.status, 201);
  await call(ctx, {
    method: "POST",
    path: "/v1/grants/revoke",
    token: box.owner_token,
    body: { grant_id: rotated.body.grant_id, protocol_version: 1 },
  });
  const afterRevoke = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: rotated.body.token,
    body: envelope({ mailbox: box.mailbox_id, messageId: b64url(Buffer.alloc(16, 7)), expiresAt: ctx.now() + 3600 }),
    headers: { "idempotency-key": b64url(Buffer.alloc(16, 7)) },
  });
  assert.equal(afterRevoke.status, 401);
});

test("rotate long-lived submit does not revoke peer grants", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const peer = await call(ctx, {
    method: "POST",
    path: "/v1/grants",
    token: box.owner_token,
    body: { expires_at: ctx.now() + 86400, kind: "peer", protocol_version: 1 },
  });
  const rotated = await call(ctx, {
    method: "POST",
    path: "/v1/mailboxes/rotate-submit",
    token: box.owner_token,
    body: { protocol_version: 1 },
  });
  assert.equal(rotated.status, 200);
  assert.notEqual(rotated.body.submit_capability, box.submit_capability);
  const oldSubmit = envelope({ mailbox: box.mailbox_id, messageId: b64url(Buffer.alloc(16, 1)), expiresAt: ctx.now() + 3600 });
  const dead = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: oldSubmit,
    headers: { "idempotency-key": oldSubmit.message_id },
  });
  assert.equal(dead.status, 401);
  const still = envelope({ mailbox: box.mailbox_id, messageId: b64url(Buffer.alloc(16, 2)), expiresAt: ctx.now() + 3600 });
  const ok = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: peer.body.token,
    body: still,
    headers: { "idempotency-key": still.message_id },
  });
  assert.equal(ok.status, 201);
});

test("mailbox spam rate limits apply to bootstrap grants", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  await publishKeyPackages(ctx, box, 1);
  const token = b64url(Buffer.alloc(32, 16));
  await publishInvite(ctx, box, token);
  const redeemed = await call(ctx, {
    method: "POST",
    path: "/v1/invites/redeem",
    body: { claim_id: b64url(Buffer.alloc(16, 61)), protocol_version: 1, token },
  });
  const grant = redeemed.body.bootstrap_grant.token;
  let sawThrottle = false;
  for (let i = 0; i < 12; i += 1) {
    const id = b64url(Buffer.from([i, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9]));
    const env = envelope({ mailbox: box.mailbox_id, messageId: id, expiresAt: ctx.now() + 3600 });
    const res = await call(ctx, {
      method: "POST",
      path: "/v1/envelopes",
      token: grant,
      body: env,
      headers: { "idempotency-key": id },
    });
    if (res.status === 429) {
      sawThrottle = true;
      break;
    }
  }
  assert.equal(sawThrottle, true);
});

function fakeFcmToken(label = "a") {
  return `test-fcm-token-APA91b-${label}-not-a-real-device-token`;
}

function recordingSender(sent, opts = {}) {
  return {
    enabled: true,
    async sendSyncHint(token) {
      if (opts.gate) await opts.gate;
      if (opts.throwError) throw new Error("fcm down");
      if (opts.invalid && opts.invalid.has(token)) {
        sent.push({ token, result: "invalid", data: { type: "sync_hint", v: "1" } });
        return "invalid";
      }
      sent.push({ token, result: "ok", data: { type: "sync_hint", v: "1" } });
      return "ok";
    },
  };
}

async function flushMicrotasks() {
  await Promise.resolve();
  await Promise.resolve();
  await new Promise((r) => setImmediate(r));
}

test("owner can register and unregister a push token without echoing it", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const deviceId = b64url(Buffer.alloc(16, 9));
  const token = fakeFcmToken("reg");
  const noAuth = await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    body: { device_id: deviceId, fcm_token: token, protocol_version: 1 },
  });
  assert.equal(noAuth.status, 401);
  const submit = await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.submit_capability,
    body: { device_id: deviceId, fcm_token: token, protocol_version: 1 },
  });
  assert.equal(submit.status, 401);
  const ok = await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: deviceId, fcm_token: token, protocol_version: 1 },
  });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.state, "accepted");
  assert.equal(JSON.stringify(ok.body).includes(token), false);
  assert.equal(/fcm_token/i.test(JSON.stringify(ok.body)), false);
  const listed = ctx.store.listPushTokens(box.mailbox_id);
  assert.equal(listed.length, 1);
  assert.equal(listed[0].token, token);
  const gone = await call(ctx, {
    method: "POST",
    path: "/v1/push/unregister",
    token: box.owner_token,
    body: { device_id: deviceId, protocol_version: 1 },
  });
  assert.equal(gone.status, 200);
  assert.equal(ctx.store.listPushTokens(box.mailbox_id).length, 0);
});

test("token refresh overwrites the same device mapping", async (t) => {
  const ctx = await startRelay();
  t.after(() => ctx.close());
  const box = await register(ctx);
  const deviceId = b64url(Buffer.alloc(16, 4));
  await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: deviceId, fcm_token: fakeFcmToken("old"), protocol_version: 1 },
  });
  await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: deviceId, fcm_token: fakeFcmToken("new"), protocol_version: 1 },
  });
  const listed = ctx.store.listPushTokens(box.mailbox_id);
  assert.equal(listed.length, 1);
  assert.equal(listed[0].token, fakeFcmToken("new"));
});

test("accepted envelope wakes FCM asynchronously without blocking SENT", async (t) => {
  let release;
  const gate = new Promise((r) => {
    release = r;
  });
  const sent = [];
  const ctx = await startRelay({ pushSender: recordingSender(sent, { gate }) });
  t.after(() => ctx.close());
  const box = await register(ctx);
  const deviceId = b64url(Buffer.alloc(16, 5));
  const token = fakeFcmToken("wake");
  await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: deviceId, fcm_token: token, protocol_version: 1 },
  });
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const first = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(first.status, 201);
  assert.equal(first.body.state, "accepted");
  assert.equal(sent.length, 0);
  release();
  await flushMicrotasks();
  await flushMicrotasks();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].token, token);
  assert.deepEqual(sent[0].data, { type: "sync_hint", v: "1" });
});

test("push failure does not fail envelope acceptance", async (t) => {
  const sent = [];
  const ctx = await startRelay({ pushSender: recordingSender(sent, { throwError: true }) });
  t.after(() => ctx.close());
  const box = await register(ctx);
  await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: b64url(Buffer.alloc(16, 6)), fcm_token: fakeFcmToken("fail"), protocol_version: 1 },
  });
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const first = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(first.status, 201);
  assert.equal(first.body.state, "accepted");
  await flushMicrotasks();
  const st = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes/status",
    token: box.submit_capability,
    body: { message_ids: [env.message_id], protocol_version: 1 },
  });
  assert.equal(st.body.statuses[0].state, "accepted");
});

test("stale FCM tokens are dropped after invalid wake", async (t) => {
  const sent = [];
  const token = fakeFcmToken("stale");
  const ctx = await startRelay({
    pushSender: recordingSender(sent, { invalid: new Set([token]) }),
  });
  t.after(() => ctx.close());
  const box = await register(ctx);
  await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: b64url(Buffer.alloc(16, 7)), fcm_token: token, protocol_version: 1 },
  });
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  const first = await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  assert.equal(first.status, 201);
  await flushMicrotasks();
  await flushMicrotasks();
  assert.equal(ctx.store.listPushTokens(box.mailbox_id).length, 0);
});

test("push endpoints and stored mappings never log the full FCM token", async (t) => {
  const logs = [];
  const orig = process.stderr.write.bind(process.stderr);
  process.stderr.write = (chunk, ...args) => {
    logs.push(String(chunk));
    return orig(chunk, ...args);
  };
  t.after(() => {
    process.stderr.write = orig;
  });
  const sent = [];
  const ctx = await startRelay({ pushSender: recordingSender(sent) });
  t.after(() => ctx.close());
  const box = await register(ctx);
  const token = fakeFcmToken("nolog");
  await call(ctx, {
    method: "POST",
    path: "/v1/push/register",
    token: box.owner_token,
    body: { device_id: b64url(Buffer.alloc(16, 8)), fcm_token: token, protocol_version: 1 },
  });
  const env = envelope({ mailbox: box.mailbox_id, expiresAt: ctx.now() + 3600 });
  await call(ctx, {
    method: "POST",
    path: "/v1/envelopes",
    token: box.submit_capability,
    body: env,
    headers: { "idempotency-key": env.message_id },
  });
  await flushMicrotasks();
  const blob = logs.join("");
  assert.equal(blob.includes(token), false);
});

