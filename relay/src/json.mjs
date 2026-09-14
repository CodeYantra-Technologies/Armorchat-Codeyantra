/** Strict JSON + RFC 8785 JCS for the ArmorChat relay envelope subset. */

export class ParseError extends Error {
  constructor(message = "malformed") {
    super(message);
    this.name = "ParseError";
  }
}

const MAX_DEPTH = 4;
const MAX_SAFE = Number.MAX_SAFE_INTEGER;

export function canonicalize(value) {
  if (typeof value === "number") {
    if (!Number.isInteger(value) || value < 0 || value > MAX_SAFE) {
      throw new ParseError("invalid number");
    }
    return String(value);
  }
  if (typeof value === "string") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalize).join(",")}]`;
  }
  if (value && typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalize(value[k])}`).join(",")}}`;
  }
  throw new ParseError("unsupported json value");
}

export function parseStrict(text, { maxDepth = MAX_DEPTH } = {}) {
  if (typeof text !== "string") throw new ParseError("invalid utf-8");
  if (text.length === 0) throw new ParseError("empty");
  const p = { s: text, i: 0, maxDepth };
  const value = readValue(p, 1);
  skipWs(p);
  if (p.i !== p.s.length) throw new ParseError("trailing input");
  return value;
}

function skipWs(p) {
  // Relay JCS bodies contain no whitespace. Allow none.
  if (p.i < p.s.length) {
    const c = p.s.charCodeAt(p.i);
    if (c === 0x20 || c === 0x0a || c === 0x0d || c === 0x09) {
      throw new ParseError("noncanonical whitespace");
    }
  }
}

function peek(p) {
  return p.s[p.i];
}

function readValue(p, depth) {
  if (depth > p.maxDepth) throw new ParseError("nesting");
  const c = peek(p);
  if (c === "{") return readObject(p, depth);
  if (c === "[") return readArray(p, depth);
  if (c === '"') return readString(p);
  if (c === "-" || (c >= "0" && c <= "9")) return readNumber(p);
  if (p.s.startsWith("null", p.i) || p.s.startsWith("true", p.i) || p.s.startsWith("false", p.i)) {
    throw new ParseError("null or boolean");
  }
  throw new ParseError("value");
}

function readObject(p, depth) {
  if (p.s[p.i++] !== "{") throw new ParseError("object");
  const obj = Object.create(null);
  const seen = new Set();
  if (peek(p) === "}") {
    p.i += 1;
    return obj;
  }
  while (true) {
    const key = readString(p);
    if (seen.has(key)) throw new ParseError("duplicate key");
    seen.add(key);
    if (p.s[p.i++] !== ":") throw new ParseError("colon");
    obj[key] = readValue(p, depth + 1);
    const n = peek(p);
    if (n === "}") {
      p.i += 1;
      return obj;
    }
    if (n === ",") {
      p.i += 1;
      continue;
    }
    throw new ParseError("object");
  }
}

function readArray(p, depth) {
  if (p.s[p.i++] !== "[") throw new ParseError("array");
  const arr = [];
  if (peek(p) === "]") {
    p.i += 1;
    return arr;
  }
  while (true) {
    arr.push(readValue(p, depth + 1));
    const n = peek(p);
    if (n === "]") {
      p.i += 1;
      return arr;
    }
    if (n === ",") {
      p.i += 1;
      continue;
    }
    throw new ParseError("array");
  }
}

function readString(p) {
  if (p.s[p.i++] !== '"') throw new ParseError("string");
  let out = "";
  while (p.i < p.s.length) {
    const ch = p.s[p.i++];
    if (ch === '"') return out;
    if (ch === "\\") {
      const e = p.s[p.i++];
      const map = { '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t" };
      if (map[e]) {
        out += map[e];
        continue;
      }
      if (e === "u") {
        const hex = p.s.slice(p.i, p.i + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(hex)) throw new ParseError("unicode");
        out += String.fromCharCode(parseInt(hex, 16));
        p.i += 4;
        continue;
      }
      throw new ParseError("escape");
    }
    if (ch.charCodeAt(0) < 0x20) throw new ParseError("control");
    out += ch;
  }
  throw new ParseError("unterminated string");
}

function readNumber(p) {
  if (p.s[p.i] === "-") throw new ParseError("negative");
  const start = p.i;
  if (p.s[p.i] === "0") {
    p.i += 1;
    const n = peek(p);
    if (n >= "0" && n <= "9") throw new ParseError("leading zero");
  } else if (p.s[p.i] >= "1" && p.s[p.i] <= "9") {
    p.i += 1;
    while (p.s[p.i] >= "0" && p.s[p.i] <= "9") p.i += 1;
  } else {
    throw new ParseError("number");
  }
  const next = peek(p);
  if (next === "." || next === "e" || next === "E") throw new ParseError("float");
  const raw = p.s.slice(start, p.i);
  if (raw.length > 16) throw new ParseError("number range");
  const num = Number(raw);
  if (!Number.isInteger(num) || num > MAX_SAFE) throw new ParseError("number range");
  return num;
}

export function utf8FromBuffer(buf) {
  const dec = new TextDecoder("utf-8", { fatal: true });
  try {
    return dec.decode(buf);
  } catch {
    throw new ParseError("invalid utf-8");
  }
}
