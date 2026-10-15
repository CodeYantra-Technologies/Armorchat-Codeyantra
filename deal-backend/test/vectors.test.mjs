import assert from "node:assert/strict";
import { createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import test from "node:test";
import { signaturePayload } from "../src/worker.mjs";

const PAYLOAD =
  "41726d6f724465616c2f7369676e2f76310000045349474e00010016646464646464646464646464646464646464646464640016767676767676767676767676767676767676767676760016616161616161616161616161616161616161616161610020d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a0020114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95";
const SIGNATURE =
  "0645b3927bfdf047fd9c818c673150a1c973a522a5f93c7c474e27158464b7fe938abb163b69ed7ab34e8f9c587dcd9acf83e07a014ae5ac99131bf66aa7f305";

test("signature payload matches the pinned Kotlin vector", () => {
  const publicKey = Buffer.from("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "hex");
  const documentHash = Buffer.from("114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95", "hex");
  const payload = signaturePayload({
    action: "SIGN",
    dealId: "d".repeat(22),
    versionId: "v".repeat(22),
    partyId: "a".repeat(22),
    publicKey,
    documentHash,
  });
  assert.equal(payload.toString("hex"), PAYLOAD);
  const seed = Buffer.from("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", "hex");
  const privateKey = createPrivateKey({
    key: { kty: "OKP", crv: "Ed25519", d: seed.toString("base64url"), x: publicKey.toString("base64url") },
    format: "jwk",
  });
  const signature = sign(null, payload, privateKey);
  assert.equal(signature.toString("hex"), SIGNATURE);
  const ok = verify(
    null,
    payload,
    createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: publicKey.toString("base64url") }, format: "jwk" }),
    signature,
  );
  assert.equal(ok, true);
});
