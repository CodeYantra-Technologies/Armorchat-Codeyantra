import { createRequire } from "node:module";
import { readFileSync } from "node:fs";

const require = createRequire(import.meta.url);

/**
 * Minimal D1-shaped adapter over sql.js so route tests run the real migration
 * SQL without Cloudflare credentials.
 */
export async function openSqlite(migrationSql) {
  const initSqlJs = require("sql.js");
  const wasm = readFileSync(require.resolve("sql.js/dist/sql-wasm.wasm"));
  const SQL = await initSqlJs({ wasmBinary: wasm });
  const db = new SQL.Database();
  db.exec(migrationSql);
  return {
    exec(sql) {
      db.exec(sql);
    },
    prepare(sql) {
      return {
        bind(...params) {
          return {
            run() {
              db.run(sql, params);
              return { success: true, meta: { changes: db.getRowsModified() } };
            },
            first() {
              const stmt = db.prepare(sql);
              try {
                if (params.length > 0) stmt.bind(params);
                if (!stmt.step()) return null;
                return stmt.getAsObject();
              } finally {
                stmt.free();
              }
            },
            all() {
              const stmt = db.prepare(sql);
              try {
                if (params.length > 0) stmt.bind(params);
                const results = [];
                while (stmt.step()) results.push(stmt.getAsObject());
                return { results };
              } finally {
                stmt.free();
              }
            },
          };
        },
      };
    },
  };
}

export class MemoryR2 {
  constructor() {
    this.objects = new Map();
  }

  async put(key, bytes) {
    this.objects.set(key, Buffer.from(bytes));
  }

  async get(key) {
    const value = this.objects.get(key);
    if (!value) return null;
    return value;
  }

  async head(key) {
    const value = this.objects.get(key);
    if (!value) return null;
    return { key, size: value.length };
  }

  async delete(key) {
    this.objects.delete(key);
  }
}
