//! Isolated mls-rs 0.56.0 A↔B harness. Not production messaging.
//! MLS HPKE/signature keys are library-generated and independent of ArmorChat identity.

use aes_gcm::{aead::Aead, Aes256Gcm, KeyInit, Nonce};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
use ed25519_dalek::{Signature as DalekSig, Signer as DalekSigner, Verifier, VerifyingKey};
use std::collections::HashMap;
use mls_rs::{
    client_builder::MlsConfig,
    identity::{
        basic::{BasicCredential, BasicIdentityProvider},
        SigningIdentity,
    },
    storage_provider::in_memory::{InMemoryGroupStateStorage, InMemoryKeyPackageStorage},
    group::ReceivedMessage,
    CipherSuite, CipherSuiteProvider, Client, CryptoProvider, Group, MlsMessage,
};
use mls_rs_core::{
    crypto::SignatureSecretKey,
    group::{GroupState, GroupStateStorage},
    key_package::KeyPackageData,
};
use mls_rs_crypto_rustcrypto::RustCryptoProvider;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

const CIPHERSUITE: CipherSuite = CipherSuite::CURVE25519_AES128;
const BIND_CTX: &str = "ArmorChat/mls-keypackage/v1";
const SUITE: &str = "ac-mls-v1";
const WRAP_AAD: &[u8] = b"ArmorChat/mls-mlsrs/state/v1";

type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;

fn check(ok: bool) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err("Rejected invalid or unauthenticated input".into())
    }
}

/// Collapse secrets/blobs so a debug exception can name the failure without
/// leaking key material. Safe to put in logcat.
fn sanitize_err(raw: &str) -> String {
    let mut out = String::new();
    let mut run = 0usize;
    for c in raw.chars() {
        if c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '+' | '/' | '=') {
            run += 1;
            if run <= 20 {
                out.push(c);
            } else if run == 21 {
                out.push('…');
            }
        } else if c.is_control() {
            run = 0;
            out.push(' ');
        } else {
            run = 0;
            out.push(c);
        }
        if out.len() >= 160 {
            break;
        }
    }
    let lower = out.to_ascii_lowercase();
    const FORBIDDEN: &[&str] = &[
        "private",
        "secret",
        "seed",
        "hpke",
        "signer",
        "identity_sk",
        "owner_token",
        "grant",
        "plaintext",
        "snapshot",
        "credential",
        "init_key",
        "leaf_node",
    ];
    if FORBIDDEN.iter().any(|tok| lower.contains(tok)) {
        return "mls_op_rejected".into();
    }
    if out.trim().is_empty() {
        "mls_op_rejected".into()
    } else {
        out
    }
}

fn frame<T: Serialize>(ctx: &str, body: &T) -> Result<Vec<u8>> {
    let mut b = ctx.as_bytes().to_vec();
    b.push(0);
    b.extend(serde_json_canonicalizer::to_vec(body)?);
    Ok(b)
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BindingBody {
    v: u64,
    suite: String,
    identity_key: String,
    key_package: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SignedBinding {
    body: BindingBody,
    signature: String,
}

fn verify_binding(raw: &str, pin: &str) -> Result<MlsMessage> {
    let signed: SignedBinding = serde_json::from_str(raw)?;
    check(signed.body.v == 1 && signed.body.suite == SUITE)?;
    check(signed.body.identity_key == pin)?;
    let pin_bytes = B64.decode(pin)?;
    check(pin_bytes.len() == 32)?;
    let vk = VerifyingKey::from_bytes(pin_bytes.as_slice().try_into()?)?;
    let sig_bytes = B64.decode(&signed.signature)?;
    check(sig_bytes.len() == 64)?;
    let mut sig_arr = [0u8; 64];
    sig_arr.copy_from_slice(&sig_bytes);
    vk.verify(
        &frame(BIND_CTX, &signed.body)?,
        &DalekSig::from_bytes(&sig_arr),
    )?;
    let kp_bytes = B64.decode(&signed.body.key_package)?;
    Ok(MlsMessage::from_bytes(&kp_bytes)?)
}

pub struct Device<C: MlsConfig> {
    client: Client<C>,
    storage: InMemoryGroupStateStorage,
    key_packages: InMemoryKeyPackageStorage,
    signer: SignatureSecretKey,
    pub pin: String,
    identity_sk: Option<ed25519_dalek::SigningKey>,
    pub binding: String,
    key_package_b64: String,
    mls_public: Vec<u8>,
    groups: HashMap<Vec<u8>, Group<C>>,
    active: Option<Vec<u8>>,
}

fn make_client(
    storage: InMemoryGroupStateStorage,
    key_packages: InMemoryKeyPackageStorage,
    signing_identity: SigningIdentity,
    secret: SignatureSecretKey,
) -> Client<impl MlsConfig> {
    Client::builder()
        .identity_provider(BasicIdentityProvider)
        .crypto_provider(RustCryptoProvider::new())
        .group_state_storage(storage)
        .key_package_repo(key_packages)
        .signing_identity(signing_identity, secret, CIPHERSUITE)
        .build()
}

pub fn new_device(seed: u8) -> Result<Device<impl MlsConfig>> {
    let identity_sk = ed25519_dalek::SigningKey::from_bytes(&[seed; 32]);
    let pin = B64.encode(identity_sk.verifying_key().as_bytes());
    let crypto = RustCryptoProvider::new();
    let cs = crypto
        .cipher_suite_provider(CIPHERSUITE)
        .ok_or("ciphersuite")?;
    let (secret, public) = cs.signature_key_generate()?;
    let basic = BasicCredential::new(B64.decode(&pin)?);
    let signing_identity = SigningIdentity::new(basic.into_credential(), public.clone());
    let storage = InMemoryGroupStateStorage::new();
    let key_packages = InMemoryKeyPackageStorage::new();
    let client = make_client(
        storage.clone(),
        key_packages.clone(),
        signing_identity,
        secret.clone(),
    );
    let kp_msg =
        client.generate_key_package_message(Default::default(), Default::default(), None)?;
    let body = BindingBody {
        v: 1,
        suite: SUITE.into(),
        identity_key: pin.clone(),
        key_package: B64.encode(kp_msg.to_bytes()?),
    };
    let sig = DalekSigner::sign(&identity_sk, &frame(BIND_CTX, &body)?);
    let binding = serde_json::to_string(&SignedBinding {
        body,
        signature: B64.encode(sig.to_bytes()),
    })?;
    Ok(Device {
        client,
        storage,
        key_packages,
        signer: secret,
        pin,
        identity_sk: Some(identity_sk),
        binding,
        key_package_b64: B64.encode(kp_msg.to_bytes()?),
        mls_public: Vec::<u8>::from(public),
        groups: HashMap::new(),
        active: None,
    })
}

/// MLS device bound to an existing ArmorChat pin. Does not hold identity private keys.
pub fn from_pin(pin: &str) -> Result<Device<impl MlsConfig>> {
    let pin_bytes = B64.decode(pin)?;
    check(pin_bytes.len() == 32)?;
    let crypto = RustCryptoProvider::new();
    let cs = crypto
        .cipher_suite_provider(CIPHERSUITE)
        .ok_or("ciphersuite")?;
    let (secret, public) = cs.signature_key_generate()?;
    let basic = BasicCredential::new(pin_bytes);
    let signing_identity = SigningIdentity::new(basic.into_credential(), public.clone());
    let storage = InMemoryGroupStateStorage::new();
    let key_packages = InMemoryKeyPackageStorage::new();
    let client = make_client(
        storage.clone(),
        key_packages.clone(),
        signing_identity,
        secret.clone(),
    );
    let kp_msg =
        client.generate_key_package_message(Default::default(), Default::default(), None)?;
    Ok(Device {
        client,
        storage,
        key_packages,
        signer: secret,
        pin: pin.to_string(),
        identity_sk: None,
        binding: String::new(),
        key_package_b64: B64.encode(kp_msg.to_bytes()?),
        mls_public: Vec::<u8>::from(public),
        groups: HashMap::new(),
        active: None,
    })
}

impl<C: MlsConfig> Device<C> {
    pub fn unsigned_body(&self) -> BindingBody {
        BindingBody {
            v: 1,
            suite: SUITE.into(),
            identity_key: self.pin.clone(),
            key_package: self.key_package_b64.clone(),
        }
    }

    pub fn confirm_binding(&mut self, binding: &str) -> Result<()> {
        verify_binding(binding, &self.pin)?;
        self.binding = binding.to_string();
        Ok(())
    }

    pub fn key_package(&self) -> Result<MlsMessage> {
        check(!self.binding.is_empty())?;
        verify_binding(&self.binding, &self.pin)
    }

    pub fn generate_key_package_body(&mut self) -> Result<BindingBody> {
        let kp_msg =
            self.client
                .generate_key_package_message(Default::default(), Default::default(), None)?;
        self.key_package_b64 = B64.encode(kp_msg.to_bytes()?);
        Ok(self.unsigned_body())
    }

    fn select_active(&mut self, session_b64: Option<&str>) -> Result<()> {
        if let Some(raw) = session_b64 {
            let id = B64.decode(raw)?;
            check(self.groups.contains_key(&id))?;
            self.active = Some(id);
        }
        Ok(())
    }

    fn active_group(&mut self) -> Result<&mut Group<C>> {
        let id = self.active.clone().ok_or("group required")?;
        self.groups.get_mut(&id).ok_or_else(|| "group required".into())
    }

    fn remember_group(&mut self, group: Group<C>) {
        let id = group.group_id().to_vec();
        self.groups.insert(id.clone(), group);
        self.active = Some(id);
    }

    pub fn create_group(&mut self) -> Result<()> {
        let group = self.client.create_group(
            Default::default(),
            Default::default(),
            None,
        )?;
        self.remember_group(group);
        Ok(())
    }

    pub fn add_member(&mut self, peer_binding: &str, peer_pin: &str) -> Result<Vec<u8>> {
        let kp = verify_binding(peer_binding, peer_pin)?;
        let group = self.active_group()?;
        let commit = group.commit_builder().add_member(kp)?.build()?;
        group.apply_pending_commit()?;
        Ok(commit.welcome_messages[0].to_bytes()?)
    }

    pub fn join(&mut self, welcome_bytes: &[u8]) -> Result<()> {
        let welcome = MlsMessage::from_bytes(welcome_bytes)?;
        let (group, _) = self.client.join_group(None, &welcome, None)?;
        self.remember_group(group);
        Ok(())
    }

    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let group = self.active_group()?;
        let msg = group.encrypt_application_message(plaintext, vec![])?;
        Ok(msg.to_bytes()?)
    }

    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        let msg = MlsMessage::from_bytes(ciphertext)?;
        let group = self.active_group()?;
        match group.process_incoming_message(msg)? {
            ReceivedMessage::ApplicationMessage(app) => Ok(app.data().to_vec()),
            ReceivedMessage::Commit(_) => Err("handshake commit, not application data".into()),
            _ => Err("unexpected MLS content".into()),
        }
    }

    pub fn self_update(&mut self) -> Result<Vec<u8>> {
        let group = self.active_group()?;
        let commit = group.commit_builder().build()?;
        group.apply_pending_commit()?;
        Ok(commit.commit_message.to_bytes()?)
    }

    pub fn apply_commit(&mut self, commit: &[u8]) -> Result<()> {
        let msg = MlsMessage::from_bytes(commit)?;
        let group = self.active_group()?;
        match group.process_incoming_message(msg)? {
            ReceivedMessage::Commit(_) => Ok(()),
            _ => Err("expected commit".into()),
        }
    }

    pub fn epoch(&self) -> Result<u64> {
        let id = self.active.as_ref().ok_or("group required")?;
        let group = self.groups.get(id).ok_or("group required")?;
        Ok(group.current_epoch())
    }

    pub fn session_id(&self) -> Result<String> {
        let id = self.active.as_ref().ok_or("group required")?;
        Ok(B64.encode(id))
    }

    pub fn storage_bytes(&self) -> usize {
        self.groups
            .keys()
            .filter_map(|id| self.storage.state(id).ok().flatten())
            .map(|b| b.len())
            .sum()
    }

    pub fn persist(&mut self, data_key: &[u8; 32]) -> Result<Vec<u8>> {
        let snap = self.snapshot_persist()?;
        let plain = serde_json::to_vec(&snap)?;
        let cipher = Aes256Gcm::new(data_key.into());
        let nonce_bytes = [1u8; 12];
        let ct = cipher
            .encrypt(
                Nonce::from_slice(&nonce_bytes),
                aes_gcm::aead::Payload {
                    msg: &plain,
                    aad: WRAP_AAD,
                },
            )
            .map_err(|e| e.to_string())?;
        Ok([&nonce_bytes[..], &ct[..]].concat())
    }

    fn snapshot_persist(&mut self) -> Result<Persist> {
        let mut stored_groups = Vec::new();
        let ids: Vec<Vec<u8>> = self.groups.keys().cloned().collect();
        for id in ids {
            if let Some(g) = self.groups.get_mut(&id) {
                g.write_to_storage()?;
            }
            let data = self
                .storage
                .state(&id)?
                .ok_or("missing group state")?
                .to_vec();
            stored_groups.push(StoredGroup {
                id: id.clone(),
                state: data,
            });
        }
        let (gid, state) = if let Some(id) = &self.active {
            let found = stored_groups.iter().find(|g| &g.id == id);
            (
                Some(id.clone()),
                found.map(|g| g.state.clone()),
            )
        } else {
            (None, None)
        };
        let key_packages = self
            .key_packages
            .key_packages()
            .into_iter()
            .map(|(id, data)| StoredKeyPackage {
                id,
                key_package_bytes: data.key_package_bytes,
                init_key: data.init_key.to_vec(),
                leaf_node_key: data.leaf_node_key.to_vec(),
                expiration: data.expiration,
            })
            .collect();
        Ok(Persist {
            schema: Persist::SCHEMA,
            group_id: gid,
            group_state: state,
            groups: stored_groups,
            signer: self.signer.as_bytes().to_vec(),
            pin: self.pin.clone(),
            binding: self.binding.clone(),
            identity_sk: self
                .identity_sk
                .as_ref()
                .map(|k| k.to_bytes().to_vec())
                .unwrap_or_default(),
            public: self.mls_public.clone(),
            key_package: self.key_package_b64.clone(),
            key_packages,
        })
    }
}

#[derive(Serialize, Deserialize)]
struct Persist {
    /// 0 = pre-versioned snapshots (missing field); 1 = current.
    #[serde(default)]
    schema: u32,
    group_id: Option<Vec<u8>>,
    group_state: Option<Vec<u8>>,
    #[serde(default)]
    groups: Vec<StoredGroup>,
    signer: Vec<u8>,
    pin: String,
    binding: String,
    identity_sk: Vec<u8>,
    public: Vec<u8>,
    #[serde(default)]
    key_package: String,
    #[serde(default)]
    key_packages: Vec<StoredKeyPackage>,
}

impl Persist {
    const SCHEMA: u32 = 1;
}

#[derive(Serialize, Deserialize, Clone)]
struct StoredGroup {
    id: Vec<u8>,
    state: Vec<u8>,
}

#[derive(Serialize, Deserialize, Clone)]
struct StoredKeyPackage {
    id: Vec<u8>,
    key_package_bytes: Vec<u8>,
    init_key: Vec<u8>,
    leaf_node_key: Vec<u8>,
    expiration: u64,
}

pub fn restore(data_key: &[u8; 32], wrapped: &[u8]) -> Result<Device<impl MlsConfig>> {
    check(wrapped.len() >= 28)?;
    let cipher = Aes256Gcm::new(data_key.into());
    let plain = cipher
        .decrypt(
            Nonce::from_slice(&wrapped[..12]),
            aes_gcm::aead::Payload {
                msg: &wrapped[12..],
                aad: WRAP_AAD,
            },
        )
        .map_err(|e| e.to_string())?;
    let snap: Persist = serde_json::from_slice(&plain)?;
    restore_from_snap(snap)
}

fn restore_from_snap(snap: Persist) -> Result<Device<impl MlsConfig>> {
    if snap.schema > Persist::SCHEMA {
        return Err("persist_schema_unsupported".into());
    }
    if snap.signer.is_empty() || snap.public.is_empty() || snap.pin.is_empty() {
        return Err("restore_missing_signing_state".into());
    }
    let mut storage = InMemoryGroupStateStorage::new();
    let mut group_records = snap.groups.clone();
    if group_records.is_empty() {
        if let (Some(id), Some(data)) = (snap.group_id.clone(), snap.group_state.clone()) {
            group_records.push(StoredGroup { id, state: data });
        }
    }
    for rec in &group_records {
        storage.write(
            GroupState {
                id: rec.id.clone(),
                data: Zeroizing::new(rec.state.clone()),
            },
            vec![],
            vec![],
        )?;
    }
    let key_packages = InMemoryKeyPackageStorage::new();
    for kp in snap.key_packages {
        key_packages.insert(
            kp.id,
            KeyPackageData::new(
                kp.key_package_bytes,
                kp.init_key.into(),
                kp.leaf_node_key.into(),
                kp.expiration,
            ),
        );
    }
    let secret = SignatureSecretKey::new(snap.signer);
    let crypto = RustCryptoProvider::new();
    let cs = crypto
        .cipher_suite_provider(CIPHERSUITE)
        .ok_or("ciphersuite")?;
    let derived_public = cs
        .signature_key_derive_public(&secret)
        .map_err(|_| "restore_derive_public")?;
    let public = if snap.public.as_slice() == derived_public.as_bytes() {
        snap.public.clone()
    } else {
        Vec::<u8>::from(derived_public)
    };
    let basic = BasicCredential::new(B64.decode(&snap.pin)?);
    let signing_identity =
        SigningIdentity::new(basic.into_credential(), public.clone().into());
    let client = make_client(
        storage.clone(),
        key_packages.clone(),
        signing_identity,
        secret.clone(),
    );
    let mut groups = HashMap::new();
    for rec in &group_records {
        groups.insert(rec.id.clone(), client.load_group(&rec.id)?);
    }
    let active = snap
        .group_id
        .clone()
        .filter(|id| groups.contains_key(id))
        .or_else(|| groups.keys().next().cloned());
    let identity_sk = if snap.identity_sk.len() == 32 {
        Some(ed25519_dalek::SigningKey::from_bytes(
            snap.identity_sk.as_slice().try_into()?,
        ))
    } else {
        None
    };
    Ok(Device {
        client,
        storage,
        key_packages,
        signer: secret,
        pin: snap.pin,
        identity_sk,
        binding: snap.binding,
        key_package_b64: snap.key_package,
        mls_public: public,
        groups,
        active,
    })
}

/// Stateless JNI/test entry: snapshot in, snapshot+output out. No identity private keys.
pub fn transact(state: &[u8], request: &str) -> Result<Vec<u8>> {
    #[derive(Deserialize)]
    struct Req {
        op: String,
        pin: Option<String>,
        binding: Option<String>,
        peer_binding: Option<String>,
        peer_pin: Option<String>,
        welcome: Option<String>,
        plaintext: Option<String>,
        ciphertext: Option<String>,
        commit: Option<String>,
        session_id: Option<String>,
        diagnostic_bindings: Option<Vec<String>>,
    }
    let req: Req = serde_json::from_str(request).map_err(|_| "request_decode")?;
    if req.op == "Create" {
        let mut device = from_pin(req.pin.as_deref().ok_or("pin required")?)?;
        let body = device.unsigned_body();
        let output = serde_json::json!({ "body": body, "pin": device.pin });
        let snap = device.snapshot_persist()?;
        let state_out = serde_json::to_vec(&snap)?;
        return Ok(serde_json::to_vec(&serde_json::json!({
            "state": B64.encode(state_out),
            "output": output,
        }))?);
    }
    if state.is_empty() {
        return Err("empty_snapshot".into());
    }
    let snap: Persist =
        serde_json::from_slice(state).map_err(|e| format!("persist_decode:{e}"))?;
    let mut device = restore_from_snap(snap).map_err(|e| format!("restore:{e}"))?;
    // Read-only diagnostic response: never joins, consumes, or updates the caller snapshot.
    if req.op == "InspectWelcome" {
        let bytes = B64.decode(req.welcome.as_deref().ok_or("welcome")?)?;
        let welcome = MlsMessage::from_bytes(&bytes)?;
        let refs = welcome.welcome_key_package_references();
        let stored = device.key_packages.key_packages();
        let stored_match = refs.iter().any(|r| stored.iter().any(|(id, _)| id.as_slice() == r.as_ref()));
        let provider = RustCryptoProvider::default().cipher_suite_provider(CIPHERSUITE).ok_or("suite")?;
        let mut fresh_match = false;
        for binding in req.diagnostic_bindings.as_deref().unwrap_or_default() {
            let kp = verify_binding(binding, req.pin.as_deref().ok_or("pin")?)?;
            if let Some(reference) = kp.key_package_reference(&provider)? {
                fresh_match |= refs.iter().any(|r| **r == reference);
            }
        }
        return Ok(serde_json::to_vec(&serde_json::json!({
            "output": { "stored_match": stored_match, "fresh_match": fresh_match }
        }))?);
    }
    device
        .select_active(req.session_id.as_deref())
        .map_err(|e| format!("select_session:{e}"))?;
    let output = match req.op.as_str() {
        "Bind" => {
            device.confirm_binding(req.binding.as_deref().ok_or("binding required")?)?;
            serde_json::json!({ "ok": 1 })
        }
        "GenerateKeyPackage" => {
            let body = device
                .generate_key_package_body()
                .map_err(|e| format!("generate_key_package:{e}"))?;
            serde_json::json!({ "body": body, "pin": device.pin })
        }
        "CreateGroup" => {
            device.create_group()?;
            serde_json::json!({
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "AddMember" => {
            let welcome = device.add_member(
                req.peer_binding.as_deref().ok_or("peer_binding")?,
                req.peer_pin.as_deref().ok_or("peer_pin")?,
            )?;
            serde_json::json!({
                "welcome": B64.encode(welcome),
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "Join" => {
            let bytes = B64.decode(req.welcome.as_deref().ok_or("welcome")?)?;
            device.join(&bytes)?;
            serde_json::json!({
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "Encrypt" => {
            let pt = req.plaintext.as_deref().ok_or("plaintext")?.as_bytes();
            let ct = device.encrypt(pt)?;
            serde_json::json!({
                "ciphertext": B64.encode(ct),
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "Decrypt" => {
            let ct = B64.decode(req.ciphertext.as_deref().ok_or("ciphertext")?)?;
            let pt = device.decrypt(&ct)?;
            serde_json::json!({
                "plaintext": String::from_utf8(pt)?,
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "SelfUpdate" => {
            let commit = device.self_update()?;
            serde_json::json!({
                "commit": B64.encode(commit),
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "ApplyCommit" => {
            let bytes = B64.decode(req.commit.as_deref().ok_or("commit")?)?;
            device.apply_commit(&bytes)?;
            serde_json::json!({
                "epoch": device.epoch()?,
                "session_id": device.session_id()?,
            })
        }
        "Epoch" => serde_json::json!({
            "epoch": device.epoch()?,
            "session_id": device.session_id()?,
        }),
        "Session" => serde_json::json!({
            "epoch": device.epoch()?,
            "session_id": device.session_id()?,
        }),
        _ => return Err("unknown op".into()),
    };
    let snap = device.snapshot_persist()?;
    let state_out = serde_json::to_vec(&snap)?;
    Ok(serde_json::to_vec(&serde_json::json!({
        "state": B64.encode(state_out),
        "output": output,
    }))?)
}

pub fn establish<C: MlsConfig, D: MlsConfig>(
    a: &mut Device<C>,
    b: &mut Device<D>,
) -> Result<Vec<u8>> {
    a.create_group()?;
    let welcome = a.add_member(&b.binding, &b.pin)?;
    b.join(&welcome)?;
    Ok(welcome)
}

#[cfg(test)]
mod tests;

use jni::{
    objects::{JByteArray, JClass, JString},
    sys::jbyteArray,
    JNIEnv,
};

#[no_mangle]
pub extern "system" fn Java_com_codeyantra_armorchat_crypto_MlsRsFeasibility_nativeProbe(
    _env: JNIEnv,
    _: JClass,
) -> i32 {
    26
}

#[no_mangle]
pub extern "system" fn Java_com_codeyantra_armorchat_crypto_MlsRsFeasibility_nativeSuite(
    env: JNIEnv,
    _: JClass,
) -> jbyteArray {
    let s = u16::from(CIPHERSUITE).to_be_bytes();
    env.byte_array_from_slice(&s)
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn debug_errors_enabled(env: &mut JNIEnv) -> bool {
    match env.get_static_field("com/codeyantra/armorchat/BuildConfig", "DEBUG", "Z") {
        Ok(value) => value.z().unwrap_or(false),
        Err(_) => {
            let _ = env.exception_clear();
            false
        }
    }
}

fn throw_rejected(env: &mut JNIEnv, detail: Option<&str>) {
    let message = match detail {
        Some(detail) if !detail.is_empty() => {
            format!("Messaging operation rejected: {detail}")
        }
        _ => "Messaging operation rejected".into(),
    };
    let _ = env.throw_new("java/lang/SecurityException", message);
}

#[no_mangle]
pub extern "system" fn Java_com_codeyantra_armorchat_crypto_MlsRsFeasibility_transact(
    mut env: JNIEnv,
    _: JClass,
    snapshot: JByteArray,
    request: JString,
) -> jbyteArray {
    let debug = debug_errors_enabled(&mut env);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Vec<u8>> {
        check(env.get_array_length(&snapshot)? as usize <= 2 * 1024 * 1024)?;
        check(env.call_method(&request, "length", "()I", &[])?.i()? <= 65536)?;
        let state = env.convert_byte_array(&snapshot)?;
        let req: String = env.get_string(&request)?.into();
        transact(&state, &req)
    }));
    match result {
        Ok(Ok(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(a) => a.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(err)) => {
            let detail = debug.then(|| sanitize_err(&err.to_string()));
            throw_rejected(&mut env, detail.as_deref());
            std::ptr::null_mut()
        }
        Err(panic) => {
            let detail = debug.then(|| {
                let raw = panic
                    .downcast_ref::<&str>()
                    .copied()
                    .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
                    .unwrap_or("panic");
                format!("native_{}", sanitize_err(raw))
            });
            throw_rejected(&mut env, detail.as_deref());
            std::ptr::null_mut()
        }
    }
}
