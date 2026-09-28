#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { createStore } from "./store.mjs";
import { createRelay } from "./relay.mjs";
import { createBlobTransport } from "./blob-transport.mjs";
import { createFirebasePushSender } from "./push.mjs";

const dataDir = process.env.RELAY_DATA_DIR;
const certPath = process.env.RELAY_TLS_CERT;
const keyPath = process.env.RELAY_TLS_KEY;
const listen = process.env.RELAY_LISTEN || "127.0.0.1:8443";

if (!dataDir || !certPath || !keyPath) {
  process.stderr.write(
    "TLS-only relay requires RELAY_DATA_DIR, RELAY_TLS_CERT, and RELAY_TLS_KEY.\n",
  );
  process.exit(1);
}

const [host, portStr] = listen.split(":");
const port = Number(portStr);
if (!host || !Number.isInteger(port)) {
  process.stderr.write("RELAY_LISTEN must be host:port\n");
  process.exit(1);
}

const [cert, key] = await Promise.all([readFile(certPath), readFile(keyPath)]);
const store = await createStore({
  dir: dataDir,
  nowSeconds: () => Math.floor(Date.now() / 1000),
});
if (process.env.RELAY_SUSPENDED === "1") {
  await store.setSuspended(true);
}
if (process.env.RELAY_NETWORK_POLICY) {
  const policyRaw = await readFile(process.env.RELAY_NETWORK_POLICY, "utf8");
  store.setNetworkPolicy(JSON.parse(policyRaw));
}
const mailboxLimit = Number(process.env.RELAY_MAILBOX_LIMIT || 1000);
const pushSender = await createFirebasePushSender();
const blobTransport = await createBlobTransport({
  dir: join(dataDir, "blob-chunks"),
  nowSeconds: () => Math.floor(Date.now() / 1000),
});
const relay = createRelay({
  store,
  tls: { cert, key },
  mailboxLimitPerHour: mailboxLimit,
  pushSender,
  blobTransport,
});
const addr = await relay.listen(host, port);
process.stderr.write(JSON.stringify({ event: "listen", address: addr }) + "\n");
