import { sha256B64 } from "./ids.mjs";

export const SYNC_HINT_TYPE = "sync_hint";
export const SYNC_HINT_VERSION = "1";
export const FCM_TOKEN_MIN = 16;
export const FCM_TOKEN_MAX = 4096;

const STALE_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/** Data-only wake. Never include plaintext, IDs, credentials, or previews. */
export function syncHintData() {
  return { type: SYNC_HINT_TYPE, v: SYNC_HINT_VERSION };
}

export function syncHintMessage(token) {
  return {
    token,
    data: syncHintData(),
    android: { priority: "high" },
  };
}

export function tokenFingerprint(token) {
  return sha256B64(Buffer.from(String(token), "utf8")).slice(0, 12);
}

export function isPlausibleFcmToken(token) {
  if (typeof token !== "string") return false;
  if (token.length < FCM_TOKEN_MIN || token.length > FCM_TOKEN_MAX) return false;
  if (/[\s\x00-\x1f]/.test(token)) return false;
  return true;
}

export function assertWakePayloadSafe(data) {
  if (!data || typeof data !== "object" || Array.isArray(data)) return false;
  const keys = Object.keys(data);
  if (keys.length !== 2) return false;
  return data.type === SYNC_HINT_TYPE && data.v === SYNC_HINT_VERSION;
}

function logSafe(event, fields = {}) {
  const rec = { event, ...fields };
  const blob = JSON.stringify(rec);
  if (/fcm_token|fcmtoken|registration_token|"token":/i.test(blob)) {
    process.stderr.write(JSON.stringify({ event: "log_rejected" }) + "\n");
    return;
  }
  process.stderr.write(blob + "\n");
}

export function disabledPushSender() {
  return {
    enabled: false,
    async sendSyncHint() {
      return "disabled";
    },
  };
}

/**
 * Firebase Admin via Application Default Credentials
 * (`GOOGLE_APPLICATION_CREDENTIALS` on the production relay).
 * Missing/unreadable credentials disable push; they never block the queue.
 */
export async function createFirebasePushSender() {
  try {
    const appMod = await import("firebase-admin/app");
    const msgMod = await import("firebase-admin/messaging");
    if (appMod.getApps().length === 0) {
      appMod.initializeApp({ credential: appMod.applicationDefault() });
    }
    const messaging = msgMod.getMessaging();
    logSafe("fcm_ready");
    return {
      enabled: true,
      async sendSyncHint(token) {
        const fp = tokenFingerprint(token);
        try {
          const message = syncHintMessage(token);
          if (!assertWakePayloadSafe(message.data)) {
            logSafe("push_payload_rejected", { fp });
            return "error";
          }
          await messaging.send(message);
          logSafe("push_wake", { fp, result: "ok" });
          return "ok";
        } catch (err) {
          const code = typeof err?.code === "string" ? err.code : "";
          if (STALE_CODES.has(code)) {
            logSafe("push_wake", { fp, result: "invalid" });
            return "invalid";
          }
          logSafe("push_wake", { fp, result: "error" });
          return "error";
        }
      },
    };
  } catch {
    logSafe("fcm_disabled");
    return disabledPushSender();
  }
}
