use super::*;
use std::time::Instant;

#[test]
fn diagnostic_welcome_match_is_read_only_and_distinguishes_fresh_pool() {
    let mut a = new_device(1).unwrap();
    let mut b = new_device(2).unwrap();
    let c = new_device(3).unwrap();
    a.create_group().unwrap();
    let welcome = a.add_member(&b.binding, &b.pin).unwrap();
    let before = serde_json::to_vec(&b.snapshot_persist().unwrap()).unwrap();
    for (bindings, fresh) in [(vec![b.binding.clone()], true), (vec![], false)] {
        let req = serde_json::json!({"op":"InspectWelcome", "welcome":B64.encode(&welcome),
            "pin":b.pin, "diagnostic_bindings":bindings});
        let result: serde_json::Value = serde_json::from_slice(&transact(&before, &req.to_string()).unwrap()).unwrap();
        assert_eq!(result["output"]["stored_match"], true);
        assert_eq!(result["output"]["fresh_match"], fresh);
        assert!(result.get("state").is_none());
    }
    let mut c = c;
    let other = serde_json::to_vec(&c.snapshot_persist().unwrap()).unwrap();
    let req = serde_json::json!({"op":"InspectWelcome", "welcome":B64.encode(&welcome),
        "pin":c.pin, "diagnostic_bindings":[c.binding]});
    let result: serde_json::Value = serde_json::from_slice(&transact(&other, &req.to_string()).unwrap()).unwrap();
    assert_eq!(result["output"]["stored_match"], false);
    assert_eq!(result["output"]["fresh_match"], false);
    // Inspection has not consumed the package or joined on the original device.
    assert!(b.session_id().is_err());
    b.join(&welcome).unwrap();
    assert_eq!(a.session_id().unwrap(), b.session_id().unwrap());
}

fn setup() -> (Device<impl MlsConfig>, Device<impl MlsConfig>, Vec<u8>) {
    let mut a = new_device(1).unwrap();
    let mut b = new_device(2).unwrap();
    let welcome = establish(&mut a, &mut b).unwrap();
    (a, b, welcome)
}

#[test]
fn hello_armorchat_round_trip_and_reply() {
    let (mut a, mut b, _) = setup();
    let ct = a.encrypt(b"Hello ArmorChat").unwrap();
    assert_eq!(b.decrypt(&ct).unwrap(), b"Hello ArmorChat");
    let reply = b.encrypt(b"Hello Alice").unwrap();
    assert_eq!(a.decrypt(&reply).unwrap(), b"Hello Alice");
}

#[test]
fn wrong_pinned_identity_rejected() {
    let mut a = new_device(1).unwrap();
    let b = new_device(2).unwrap();
    a.create_group().unwrap();
    let wrong = a.pin.clone();
    assert!(a.add_member(&b.binding, &wrong).is_err());
}

#[test]
fn modified_credential_binding_rejected() {
    let mut a = new_device(1).unwrap();
    let b = new_device(2).unwrap();
    a.create_group().unwrap();
    let mut signed: serde_json::Value = serde_json::from_str(&b.binding).unwrap();
    signed["body"]["identity_key"] = serde_json::json!(a.pin);
    assert!(a.add_member(&signed.to_string(), &b.pin).is_err());
}

#[test]
fn tampered_ciphertext_rejected() {
    let (mut a, mut b, _) = setup();
    let mut ct = a.encrypt(b"Hello ArmorChat").unwrap();
    let last = ct.len() - 1;
    ct[last] ^= 1;
    assert!(b.decrypt(&ct).is_err());
}

#[test]
fn replay_application_message_rejected() {
    let (mut a, mut b, _) = setup();
    let ct = a.encrypt(b"Hello ArmorChat").unwrap();
    assert_eq!(b.decrypt(&ct).unwrap(), b"Hello ArmorChat");
    assert!(b.decrypt(&ct).is_err());
}

#[test]
fn stale_epoch_ciphertext_rejected() {
    let (mut a, mut b, _) = setup();
    let stale = a.encrypt(b"old").unwrap();
    let commit = a.self_update().unwrap();
    b.apply_commit(&commit).unwrap();
    assert_ne!(a.epoch().unwrap(), 0);
    assert!(b.decrypt(&stale).is_err());
}

#[test]
fn persist_reload_continues() {
    let (mut a, mut b, _) = setup();
    let key = [9u8; 32];
    let wrapped_a = a.persist(&key).unwrap();
    let wrapped_b = b.persist(&key).unwrap();
    let mut a2 = restore(&key, &wrapped_a).unwrap();
    let mut b2 = restore(&key, &wrapped_b).unwrap();
    let ct = a2.encrypt(b"Hello ArmorChat").unwrap();
    assert_eq!(b2.decrypt(&ct).unwrap(), b"Hello ArmorChat");
}

#[test]
fn sizes_and_timings() {
    let t0 = Instant::now();
    let mut a = new_device(1).unwrap();
    let mut b = new_device(2).unwrap();
    let create_us = t0.elapsed().as_micros();
    let kp = b.key_package().unwrap().to_bytes().unwrap();
    let t1 = Instant::now();
    let welcome = establish(&mut a, &mut b).unwrap();
    let join_us = t1.elapsed().as_micros();
    let t2 = Instant::now();
    let ct = a.encrypt(b"Hello ArmorChat").unwrap();
    let enc_us = t2.elapsed().as_micros();
    let t3 = Instant::now();
    assert_eq!(b.decrypt(&ct).unwrap(), b"Hello ArmorChat");
    let dec_us = t3.elapsed().as_micros();
    a.persist(&[1u8; 32]).unwrap();
    b.persist(&[1u8; 32]).unwrap();
    eprintln!(
        "mls-rs sizes: keypackage={} welcome={} app_ct={} storage_a={} storage_b={} (create_pair={}us join={}us enc={}us dec={}us)",
        kp.len(),
        welcome.len(),
        ct.len(),
        a.storage_bytes(),
        b.storage_bytes(),
        create_us,
        join_us,
        enc_us,
        dec_us
    );
    assert!(kp.len() > 32);
    assert!(welcome.len() > 32);
    assert!(ct.len() > "Hello ArmorChat".len() + 16);
}

fn sign_binding(sk: &ed25519_dalek::SigningKey, pin: &str, key_package: &str) -> String {
    let body = BindingBody {
        v: 1,
        suite: "ac-mls-v1".into(),
        identity_key: pin.into(),
        key_package: key_package.into(),
    };
    let sig = ed25519_dalek::Signer::sign(sk, &frame("ArmorChat/mls-keypackage/v1", &body).unwrap());
    serde_json::to_string(&SignedBinding {
        body,
        signature: B64.encode(sig.to_bytes()),
    })
    .unwrap()
}

fn call(state: &[u8], req: &str) -> (Vec<u8>, serde_json::Value) {
    let raw = super::transact(state, req).unwrap();
    let v: serde_json::Value = serde_json::from_slice(&raw).unwrap();
    let next = B64.decode(v["state"].as_str().unwrap()).unwrap();
    (next, v["output"].clone())
}

#[test]
fn transact_external_identity_and_hello() {
    let sk_a = ed25519_dalek::SigningKey::from_bytes(&[3u8; 32]);
    let sk_b = ed25519_dalek::SigningKey::from_bytes(&[4u8; 32]);
    let pin_a = B64.encode(sk_a.verifying_key().as_bytes());
    let pin_b = B64.encode(sk_b.verifying_key().as_bytes());
    let (mut sa, out_a) = call(&[], &format!(r#"{{"op":"Create","pin":"{pin_a}"}}"#));
    let (mut sb, out_b) = call(&[], &format!(r#"{{"op":"Create","pin":"{pin_b}"}}"#));
    let kp_a = out_a["body"]["key_package"].as_str().unwrap();
    let kp_b = out_b["body"]["key_package"].as_str().unwrap();
    let bind_a = sign_binding(&sk_a, &pin_a, kp_a);
    let bind_b = sign_binding(&sk_b, &pin_b, kp_b);
    sa = call(&sa, &format!(r#"{{"op":"Bind","binding":{}}}"#, serde_json::to_string(&bind_a).unwrap())).0;
    sb = call(&sb, &format!(r#"{{"op":"Bind","binding":{}}}"#, serde_json::to_string(&bind_b).unwrap())).0;
    sa = call(&sa, r#"{"op":"CreateGroup"}"#).0;
    let (sa2, add) = call(
        &sa,
        &format!(
            r#"{{"op":"AddMember","peer_binding":{},"peer_pin":{}}}"#,
            serde_json::to_string(&bind_b).unwrap(),
            serde_json::to_string(&pin_b).unwrap()
        ),
    );
    sa = sa2;
    let welcome = add["welcome"].as_str().unwrap();
    sb = call(&sb, &format!(r#"{{"op":"Join","welcome":"{welcome}"}}"#)).0;
    let (sa2, enc) = call(&sa, r#"{"op":"Encrypt","plaintext":"Hello ArmorChat"}"#);
    sa = sa2;
    let ct = enc["ciphertext"].as_str().unwrap();
    let session = enc["session_id"].as_str().unwrap();
    assert!(!session.is_empty());
    let (_, dec) = call(&sb, &format!(r#"{{"op":"Decrypt","ciphertext":"{ct}"}}"#));
    assert_eq!(dec["plaintext"].as_str().unwrap(), "Hello ArmorChat");
    assert_eq!(dec["session_id"].as_str().unwrap(), session);
    let (_, reply) = call(&sb, r#"{"op":"Encrypt","plaintext":"Hello from B"}"#);
    let ct2 = reply["ciphertext"].as_str().unwrap();
    let (_, dec2) = call(&sa, &format!(r#"{{"op":"Decrypt","ciphertext":"{ct2}"}}"#));
    assert_eq!(dec2["plaintext"].as_str().unwrap(), "Hello from B");
}

#[test]
fn distinct_key_packages_for_independent_peers() {
    let sk_owner = ed25519_dalek::SigningKey::from_bytes(&[11u8; 32]);
    let pin_owner = B64.encode(sk_owner.verifying_key().as_bytes());
    let (mut s_owner, out_owner) = call(&[], &format!(r#"{{"op":"Create","pin":"{pin_owner}"}}"#));
    let mut owner_kps = vec![out_owner["body"]["key_package"].as_str().unwrap().to_string()];
    for _ in 0..2 {
        let (next, extra) = call(&s_owner, r#"{"op":"GenerateKeyPackage"}"#);
        s_owner = next;
        owner_kps.push(extra["body"]["key_package"].as_str().unwrap().to_string());
    }
    assert_eq!(owner_kps.len(), 3);
    assert_eq!(owner_kps.iter().collect::<std::collections::HashSet<_>>().len(), 3);

    let mut sessions = Vec::new();
    for (i, kp) in owner_kps.iter().enumerate() {
        let seed = [20u8 + i as u8; 32];
        let sk = ed25519_dalek::SigningKey::from_bytes(&seed);
        let pin = B64.encode(sk.verifying_key().as_bytes());
        let (mut s_peer, out_peer) = call(&[], &format!(r#"{{"op":"Create","pin":"{pin}"}}"#));
        let bind_peer = sign_binding(&sk, &pin, out_peer["body"]["key_package"].as_str().unwrap());
        s_peer = call(
            &s_peer,
            &format!(r#"{{"op":"Bind","binding":{}}}"#, serde_json::to_string(&bind_peer).unwrap()),
        )
        .0;
        let bind_owner = sign_binding(&sk_owner, &pin_owner, kp);
        s_peer = call(&s_peer, r#"{"op":"CreateGroup"}"#).0;
        let (s_peer2, add) = call(
            &s_peer,
            &format!(
                r#"{{"op":"AddMember","peer_binding":{},"peer_pin":{}}}"#,
                serde_json::to_string(&bind_owner).unwrap(),
                serde_json::to_string(&pin_owner).unwrap()
            ),
        );
        s_peer = s_peer2;
        let welcome = add["welcome"].as_str().unwrap();
        let (s_owner2, joined) = call(
            &s_owner,
            &format!(r#"{{"op":"Join","welcome":"{welcome}"}}"#),
        );
        s_owner = s_owner2;
        let session = joined["session_id"].as_str().unwrap().to_string();
        sessions.push((s_peer, pin, session));
    }

    let session_ids: std::collections::HashSet<_> = sessions.iter().map(|s| s.2.clone()).collect();
    assert_eq!(session_ids.len(), 3);

    for (idx, (s_peer, _, session)) in sessions.iter().enumerate() {
        let text = format!("hello-from-peer-{idx}");
        let (_, enc) = call(
            s_peer,
            &format!(r#"{{"op":"Encrypt","plaintext":"{text}","session_id":"{session}"}}"#),
        );
        let ct = enc["ciphertext"].as_str().unwrap();
        let (_, dec) = call(
            &s_owner,
            &format!(r#"{{"op":"Decrypt","ciphertext":"{ct}","session_id":"{session}"}}"#),
        );
        assert_eq!(dec["plaintext"].as_str().unwrap(), text);

        for (other_idx, (_, _, other_session)) in sessions.iter().enumerate() {
            if other_idx == idx {
                continue;
            }
            let result = super::transact(
                &s_owner,
                &format!(
                    r#"{{"op":"Decrypt","ciphertext":"{ct}","session_id":"{other_session}"}}"#
                ),
            );
            assert!(result.is_err(), "peer {idx} ciphertext must not decrypt on session {other_idx}");
        }
    }
}

#[test]
fn restore_snapshot_then_generate_key_package() {
    let sk = ed25519_dalek::SigningKey::from_bytes(&[7u8; 32]);
    let pin = B64.encode(sk.verifying_key().as_bytes());
    let (created, first) = call(&[], &format!(r#"{{"op":"Create","pin":"{pin}"}}"#));
    let first_kp = first["body"]["key_package"].as_str().unwrap().to_string();
    let (after_gen, second) = call(&created, r#"{"op":"GenerateKeyPackage"}"#);
    let second_kp = second["body"]["key_package"].as_str().unwrap().to_string();
    assert_ne!(first_kp, second_kp);

    drop(created);
    let restored = after_gen.clone();
    drop(after_gen);

    let (after_restore, third) = call(&restored, r#"{"op":"GenerateKeyPackage"}"#);
    let third_kp = third["body"]["key_package"].as_str().unwrap().to_string();
    assert_ne!(third_kp, second_kp);
    assert_ne!(third_kp, first_kp);
    assert!(third_kp.len() > 32);
    assert!(!after_restore.is_empty());
}

#[test]
fn restore_pre_schema_snapshot_without_groups_field() {
    let sk = ed25519_dalek::SigningKey::from_bytes(&[8u8; 32]);
    let pin = B64.encode(sk.verifying_key().as_bytes());
    let (created, _) = call(&[], &format!(r#"{{"op":"Create","pin":"{pin}"}}"#));
    let mut persist: serde_json::Value = serde_json::from_slice(&created).unwrap();
    let obj = persist.as_object_mut().unwrap();
    obj.remove("groups");
    obj.remove("schema");
    let older = serde_json::to_vec(&persist).unwrap();
    let (_, generated) = call(&older, r#"{"op":"GenerateKeyPackage"}"#);
    assert!(generated["body"]["key_package"].as_str().unwrap().len() > 32);
}
