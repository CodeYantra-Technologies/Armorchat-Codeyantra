import assert from "node:assert/strict";
import test from "node:test";
import { canonicalize, parseStrict, ParseError } from "../src/json.mjs";

test("jcs sorts keys and rejects whitespace", () => {
  const obj = { suite: "ac-mls-v1", protocol_version: 1 };
  assert.equal(canonicalize(obj), '{"protocol_version":1,"suite":"ac-mls-v1"}');
  assert.throws(() => parseStrict('{ "a": 1 }'), ParseError);
});

test("duplicate keys, floats, nulls, trailing input", () => {
  assert.throws(() => parseStrict('{"a":1,"a":2}'), ParseError);
  assert.throws(() => parseStrict('{"a":1.5}'), ParseError);
  assert.throws(() => parseStrict('{"a":null}'), ParseError);
  assert.throws(() => parseStrict('{"a":true}'), ParseError);
  assert.throws(() => parseStrict('{"a":-1}'), ParseError);
  assert.throws(() => parseStrict('{"a":1} '), ParseError);
  assert.throws(() => parseStrict('{"a":01}'), ParseError);
});

test("nesting cap", () => {
  assert.throws(() => parseStrict('{"a":{"b":{"c":{"d":{"e":1}}}}}'), ParseError);
  const ok = parseStrict('{"a":{"b":{"c":1}}}');
  assert.equal(ok.a.b.c, 1);
});
