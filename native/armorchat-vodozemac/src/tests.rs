use super::*;
use vodozemac::Ed25519SecretKey;
struct Peer {
    state: Vec<u8>,
    credential: String,
    pin: String,
}
impl Peer {
    fn run(&mut self, req: Value) -> Result<Value> {
        let result: Value = serde_json::from_slice(&transact(&self.state, &req.to_string())?)?;
        self.state = result["state"].as_str().unwrap().as_bytes().to_vec();
        Ok(result["output"].clone())
    }
    fn new(seed: u8) -> Self {
        let id = Ed25519SecretKey::from_slice(&[seed; 32]);
        let mut p = Self {
            state: vec![],
            credential: String::new(),
            pin: B64.encode(id.public_key().as_bytes()),
        };
        p.run(json!({"op":"Create"})).unwrap();
        let output=p.run(json!({"op":"DeviceBody","identity":p.pin,"device_id":B64.encode([seed;16]),"generation":1,"now":1000})).unwrap();
        p.credential=json!({"body":output["body"],"signature":B64.encode(id.sign(&B64.decode(output["signing_bytes"].as_str().unwrap()).unwrap()).to_bytes())}).to_string();
        p.run(json!({"op":"Bind","credential":p.credential,"pin":p.pin,"now":1000}))
            .unwrap();
        p
    }
}
fn setup() -> (Peer, Peer, Value) {
    let mut a = Peer::new(1);
    let mut b = Peer::new(2);
    let prekey = b.run(json!({"op":"Prekey","now":1000})).unwrap()["prekey"].clone();
    a.run(
        json!({"op":"Outbound","credential":b.credential,"prekey":prekey,"pin":b.pin,"now":1000}),
    )
    .unwrap();
    let packet = a
        .run(json!({"op":"Encrypt","plaintext":"Hello ArmorChat"}))
        .unwrap()["packet"]
        .clone();
    (a, b, packet)
}
fn receive(b: &mut Peer, a: &Peer, packet: Value) -> Result<Value> {
    b.run(json!({"op":"Inbound","credential":a.credential,"pin":a.pin,"now":1000,"packet":packet}))
}
#[test]
fn real_round_trip_and_serialized_reload() {
    let (mut a, mut b, packet) = setup();
    assert_eq!(packet["message_type"], 0);
    assert_eq!(
        receive(&mut b, &a, packet).unwrap()["plaintext"],
        "Hello ArmorChat"
    );
    // Every invocation deserializes fresh Account/Session objects; no global state exists.
    let reply = b
        .run(json!({"op":"Encrypt","plaintext":"Hello Alice"}))
        .unwrap()["packet"]
        .clone();
    assert_eq!(reply["message_type"], 1);
    assert_eq!(
        a.run(json!({"op":"Decrypt","packet":reply})).unwrap()["plaintext"],
        "Hello Alice"
    );
    let next = a
        .run(json!({"op":"Encrypt","plaintext":"After reload"}))
        .unwrap()["packet"]
        .clone();
    assert_eq!(next["message_type"], 1);
    assert_eq!(
        b.run(json!({"op":"Decrypt","packet":next})).unwrap()["plaintext"],
        "After reload"
    );
}
#[test]
fn wrong_pinned_armorchat_identity() {
    let mut a = Peer::new(1);
    let mut b = Peer::new(2);
    let pre = b.run(json!({"op":"Prekey","now":1000})).unwrap()["prekey"].clone();
    let before = a.state.clone();
    assert!(a
        .run(json!({"op":"Outbound","credential":b.credential,"prekey":pre,"pin":a.pin,"now":1000}))
        .is_err());
    assert_eq!(a.state, before);
    let (a, mut b, packet) = setup();
    assert!(b
        .run(
            json!({"op":"Inbound","credential":a.credential,"pin":b.pin,"now":1000,"packet":packet})
        )
        .is_err());
}
#[test]
fn modified_device_credential_and_prekey() {
    for field in [
        "device_agreement_key",
        "device_signing_key",
        "device_id",
        "generation",
    ] {
        let mut a = Peer::new(1);
        let mut b = Peer::new(2);
        let pre = b.run(json!({"op":"Prekey","now":1000})).unwrap()["prekey"].clone();
        let mut credential: Value = serde_json::from_str(&b.credential).unwrap();
        credential["body"][field] = match field {
            "generation" => json!(2),
            "device_id" => json!(B64.encode([3; 16])),
            _ => json!(a.pin),
        };
        assert!(a.run(json!({"op":"Outbound","credential":credential.to_string(),"prekey":pre,"pin":b.pin,"now":1000})).is_err());
    }
    let mut a = Peer::new(1);
    let mut b = Peer::new(2);
    let raw = b.run(json!({"op":"Prekey","now":1000})).unwrap();
    let mut pre: Value = serde_json::from_str(raw["prekey"].as_str().unwrap()).unwrap();
    pre["body"]["prekey_public"] = json!(a.pin);
    assert!(a.run(json!({"op":"Outbound","credential":b.credential,"prekey":pre.to_string(),"pin":b.pin,"now":1000})).is_err());
}
#[test]
fn tamper_and_replay_fail_without_state_change() {
    let (mut a, mut b, packet) = setup();
    let before = b.state.clone();
    let mut bad = packet.clone();
    let mut bytes = B64.decode(bad["ciphertext"].as_str().unwrap()).unwrap();
    *bytes.last_mut().unwrap() ^= 1;
    bad["ciphertext"] = json!(B64.encode(bytes));
    assert!(receive(&mut b, &a, bad).is_err());
    assert_eq!(before, b.state);
    let mut wrong_type = packet.clone();
    wrong_type["message_type"] = json!(1);
    assert!(receive(&mut b, &a, wrong_type).is_err());
    assert_eq!(before, b.state);
    receive(&mut b, &a, packet.clone()).unwrap();
    let before = b.state.clone();
    assert!(receive(&mut b, &a, packet.clone()).is_err());
    assert!(b.run(json!({"op":"Decrypt","packet":packet})).is_err());
    assert_eq!(before, b.state);
    let reply = b.run(json!({"op":"Encrypt","plaintext":"Reply"})).unwrap()["packet"].clone();
    a.run(json!({"op":"Decrypt","packet":reply.clone()}))
        .unwrap();
    assert!(a.run(json!({"op":"Decrypt","packet":reply})).is_err());
}
#[test]
fn strict_credential_parser_and_framing() {
    let p = Peer::new(1);
    assert!(verify_device(
        &p.credential.replace("\"v\":1", "\"v\":1,\"v\":1"),
        &p.pin,
        1000
    )
    .is_err());
    assert!(verify_device(&p.credential.replace("\"v\":1", "\"v\":1.0"), &p.pin, 1000).is_err());
    assert!(verify_device(
        &p.credential.replace("\"v\":1", "\"v\":1,\"unknown\":0"),
        &p.pin,
        1000
    )
    .is_err());
    assert_eq!(
        frame("ArmorChat/device/v1", &json!({"z":1,"a":"test"})).unwrap(),
        b"ArmorChat/device/v1\0{\"a\":\"test\",\"z\":1}"
    );
}

#[test]
fn normal_ciphertext_tamper_does_not_consume_ratchet() {
    let (mut a, mut b, packet) = setup();
    receive(&mut b, &a, packet).unwrap();
    let reply = b
        .run(json!({"op":"Encrypt","plaintext":"Authenticated normal message"}))
        .unwrap()["packet"]
        .clone();
    let before = a.state.clone();
    let mut bad = reply.clone();
    let mut bytes = B64.decode(bad["ciphertext"].as_str().unwrap()).unwrap();
    *bytes.last_mut().unwrap() ^= 1;
    bad["ciphertext"] = json!(B64.encode(bytes));
    assert!(a.run(json!({"op":"Decrypt","packet":bad})).is_err());
    assert_eq!(a.state, before);
    assert_eq!(
        a.run(json!({"op":"Decrypt","packet":reply})).unwrap()["plaintext"],
        "Authenticated normal message"
    );
}
