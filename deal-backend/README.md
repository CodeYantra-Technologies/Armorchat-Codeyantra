# Armor Deal backend (Phase 1, local only)

Cloudflare Worker-shaped API for encrypted deal backups. This package is not deployed.

- `migrations/0001_init.sql` is the D1 schema. Tests apply it to sql.js.
- `src/worker.mjs` is the Worker entry (`/v1/deals`). It stores hashes and opaque ciphertext metadata. It does not store contract terms.
- `src/sqlite.js` is the local D1 stand-in plus an in-memory R2 bucket.
- `wrangler.toml.example` is an example only. Do not copy a secret into it and do not run `wrangler deploy`.

Authentication in this package is a local-test HMAC adapter. The worker returns `auth_not_configured` when `ENVIRONMENT` is `production` or `AUTH_MODE` is not `local-test`. Do not ship that adapter as production authentication.

```sh
npm test
```

No Cloudflare account or production credential is required.
