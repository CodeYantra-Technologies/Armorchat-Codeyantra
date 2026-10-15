PRAGMA foreign_keys = ON;

CREATE TABLE deals (
  deal_id TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  current_version_id TEXT,
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

CREATE TABLE deal_parties (
  deal_id TEXT NOT NULL,
  party_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  role TEXT NOT NULL,
  signing_public_key TEXT NOT NULL,
  encryption_public_key TEXT NOT NULL,
  PRIMARY KEY (deal_id, party_id),
  UNIQUE (deal_id, participant_id),
  UNIQUE (deal_id, role),
  FOREIGN KEY (deal_id) REFERENCES deals(deal_id)
);

CREATE TABLE deal_versions (
  deal_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  parent_version_id TEXT,
  document_sha256 TEXT NOT NULL,
  superseded INTEGER NOT NULL DEFAULT 0,
  immutable INTEGER NOT NULL DEFAULT 0,
  created_at_ms INTEGER NOT NULL,
  PRIMARY KEY (deal_id, version_id),
  FOREIGN KEY (deal_id) REFERENCES deals(deal_id)
);

CREATE TABLE deal_signatures (
  deal_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  party_id TEXT NOT NULL,
  action TEXT NOT NULL,
  signature_b64 TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  PRIMARY KEY (deal_id, version_id, party_id, action),
  FOREIGN KEY (deal_id, version_id) REFERENCES deal_versions(deal_id, version_id)
);

CREATE TABLE backup_uploads (
  upload_id TEXT PRIMARY KEY,
  deal_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  party_id TEXT NOT NULL,
  object_key TEXT NOT NULL UNIQUE,
  ciphertext_sha256 TEXT,
  status TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  UNIQUE (deal_id, version_id, party_id)
);

CREATE TABLE backup_receipts (
  receipt_id TEXT PRIMARY KEY,
  deal_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  party_id TEXT NOT NULL,
  object_key TEXT NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  confirmed_at_ms INTEGER NOT NULL,
  UNIQUE (deal_id, version_id, party_id)
);

CREATE TABLE idempotency_keys (
  participant_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_sha256 TEXT NOT NULL,
  status_code INTEGER NOT NULL,
  response_json TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  PRIMARY KEY (participant_id, idempotency_key)
);

CREATE TABLE used_nonces (
  nonce TEXT PRIMARY KEY,
  participant_id TEXT NOT NULL,
  used_at_ms INTEGER NOT NULL
);

CREATE TABLE audit_events (
  event_id TEXT PRIMARY KEY,
  deal_id TEXT,
  actor_participant_id TEXT NOT NULL,
  action TEXT NOT NULL,
  outcome TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);
