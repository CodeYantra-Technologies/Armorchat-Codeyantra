use super::*;
use std::time::Instant;

fn setup() -> (Device, Device, Vec<u8>) {
    let mut a = Device::new(1).unwrap();
    let mut b = Device::new(2).unwrap();
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
    let mut a = Device::new(1).unwrap();
    let b = Device::new(2).unwrap();
    a.create_group().unwrap();
    let wrong = a.pin.clone();
    assert!(a.add_member(&b.binding, &wrong).is_err());
}

#[test]
fn modified_credential_binding_rejected() {
    let mut a = Device::new(1).unwrap();
    let b = Device::new(2).unwrap();
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
    let mut a2 = Device::restore(&key, &wrapped_a).unwrap();
    let mut b2 = Device::restore(&key, &wrapped_b).unwrap();
    let ct = a2.encrypt(b"Hello ArmorChat").unwrap();
    assert_eq!(b2.decrypt(&ct).unwrap(), b"Hello ArmorChat");
}

#[test]
fn sizes_and_timings() {
    let t0 = Instant::now();
    let mut a = Device::new(1).unwrap();
    let mut b = Device::new(2).unwrap();
    let create_us = t0.elapsed().as_micros();
    let kp = tls_codec::Serialize::tls_serialize_detached(&MlsMessageOut::from(
        b.key_package().unwrap(),
    ))
    .unwrap();
    let t1 = Instant::now();
    let welcome = establish(&mut a, &mut b).unwrap();
    let join_us = t1.elapsed().as_micros();
    let t2 = Instant::now();
    let ct = a.encrypt(b"Hello ArmorChat").unwrap();
    let enc_us = t2.elapsed().as_micros();
    let t3 = Instant::now();
    assert_eq!(b.decrypt(&ct).unwrap(), b"Hello ArmorChat");
    let dec_us = t3.elapsed().as_micros();
    eprintln!(
        "openmls sizes: keypackage={} welcome={} app_ct={} storage_a={} storage_b={} (create_pair={}us join={}us enc={}us dec={}us)",
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
