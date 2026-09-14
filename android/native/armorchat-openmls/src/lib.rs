//! Isolated OpenMLS 0.9.0 A↔B harness. Not production messaging.
//! MLS HPKE/signature keys are library-generated and independent of ArmorChat identity.

use aes_gcm::{aead::Aead, Aes256Gcm, KeyInit, Nonce};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
use ed25519_dalek::{Signature as DalekSig, Signer as DalekSigner, Verifier, VerifyingKey};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use serde::{Deserialize, Serialize};
use tls_codec::{Deserialize as TlsLoad, Serialize as TlsDump};

const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
const BIND_CTX: &str = "ArmorChat/mls-keypackage/v1";
const SUITE: &str = "ac-mls-v1";
const WRAP_AAD: &[u8] = b"ArmorChat/mls-openmls/state/v1";

type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;

fn check(ok: bool) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err("Rejected invalid or unauthenticated input".into())
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
struct BindingBody {
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

fn verify_binding(raw: &str, pin: &str) -> Result<KeyPackage> {
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
    vk.verify(&frame(BIND_CTX, &signed.body)?, &DalekSig::from_bytes(&sig_arr))?;
    let kp_bytes = B64.decode(&signed.body.key_package)?;
    let msg: MlsMessageIn =
        TlsLoad::tls_deserialize(&mut kp_bytes.as_slice()).map_err(|e| e.to_string())?;
    match msg.extract() {
        MlsMessageBodyIn::KeyPackage(kp) => {
            let crypto = OpenMlsRustCrypto::default();
            Ok(kp.validate(crypto.crypto(), ProtocolVersion::Mls10)?)
        }
        _ => Err("expected MLS KeyPackage".into()),
    }
}

fn group_config() -> MlsGroupCreateConfig {
    MlsGroupCreateConfig::builder()
        .ciphersuite(CIPHERSUITE)
        .use_ratchet_tree_extension(true)
        .build()
}

fn snapshot_storage(provider: &OpenMlsRustCrypto) -> Vec<(Vec<u8>, Vec<u8>)> {
    provider
        .storage()
        .values
        .read()
        .unwrap()
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect()
}

fn restore_storage(provider: &OpenMlsRustCrypto, rows: &[(Vec<u8>, Vec<u8>)]) {
    let mut map = provider.storage().values.write().unwrap();
    map.clear();
    for (k, v) in rows {
        map.insert(k.clone(), v.clone());
    }
}

/// One MLS device bound to an ArmorChat Ed25519 pin.
pub struct Device {
    provider: OpenMlsRustCrypto,
    signer: SignatureKeyPair,
    pub pin: String,
    identity_sk: ed25519_dalek::SigningKey,
    pub binding: String,
    group: Option<MlsGroup>,
}

impl Device {
    pub fn new(seed: u8) -> Result<Self> {
        let identity_sk = ed25519_dalek::SigningKey::from_bytes(&[seed; 32]);
        let pin = B64.encode(identity_sk.verifying_key().as_bytes());
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm())?;
        signer.store(provider.storage())?;
        let credential = BasicCredential::new(B64.decode(&pin)?);
        let credential_with_key = CredentialWithKey {
            credential: credential.into(),
            signature_key: signer.public().into(),
        };
        let bundle = KeyPackage::builder().build(
            CIPHERSUITE,
            &provider,
            &signer,
            credential_with_key,
        )?;
        let kp = bundle.key_package().clone();
        let body = BindingBody {
            v: 1,
            suite: SUITE.into(),
            identity_key: pin.clone(),
            key_package: B64.encode(TlsDump::tls_serialize_detached(&MlsMessageOut::from(kp))?),
        };
        let sig = DalekSigner::sign(&identity_sk, &frame(BIND_CTX, &body)?);
        let binding = serde_json::to_string(&SignedBinding {
            body,
            signature: B64.encode(sig.to_bytes()),
        })?;
        Ok(Self {
            provider,
            signer,
            pin,
            identity_sk,
            binding,
            group: None,
        })
    }

    pub fn key_package(&self) -> Result<KeyPackage> {
        verify_binding(&self.binding, &self.pin)
    }

    pub fn create_group(&mut self) -> Result<()> {
        let credential = BasicCredential::new(B64.decode(&self.pin)?);
        let credential_with_key = CredentialWithKey {
            credential: credential.into(),
            signature_key: self.signer.public().into(),
        };
        self.group = Some(MlsGroup::new(
            &self.provider,
            &self.signer,
            &group_config(),
            credential_with_key,
        )?);
        Ok(())
    }

    /// RFC 9420 add: Commit + Welcome. Caller must deliver Welcome to the joiner.
    pub fn add_member(&mut self, peer_binding: &str, peer_pin: &str) -> Result<Vec<u8>> {
        let kp = verify_binding(peer_binding, peer_pin)?;
        let group = self.group.as_mut().ok_or("group required")?;
        let (commit, welcome, _) = group.add_members(&self.provider, &self.signer, &[kp])?;
        group.merge_pending_commit(&self.provider)?;
        let _ = commit;
        Ok(TlsDump::tls_serialize_detached(&welcome)?)
    }

    pub fn join(&mut self, welcome_bytes: &[u8]) -> Result<()> {
        let welcome = match {
            let m: MlsMessageIn =
                TlsLoad::tls_deserialize(&mut &welcome_bytes[..]).map_err(|e| e.to_string())?;
            m.extract()
        } {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => return Err("expected Welcome".into()),
        };
        let staged = StagedWelcome::new_from_welcome(
            &self.provider,
            group_config().join_config(),
            welcome,
            None,
        )?;
        self.group = Some(staged.into_group(&self.provider)?);
        Ok(())
    }

    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let group = self.group.as_mut().ok_or("group required")?;
        let msg = group.create_message(&self.provider, &self.signer, plaintext)?;
        Ok(TlsDump::tls_serialize_detached(&msg)?)
    }

    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        let msg: MlsMessageIn =
            TlsLoad::tls_deserialize(&mut &ciphertext[..]).map_err(|e| e.to_string())?;
        let protocol = msg.try_into_protocol_message()?;
        let group = self.group.as_mut().ok_or("group required")?;
        let processed = group.process_message(&self.provider, protocol)?;
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => Ok(app.into_bytes()),
            ProcessedMessageContent::StagedCommitMessage(commit) => {
                group.merge_staged_commit(&self.provider, *commit)?;
                Err("handshake commit, not application data".into())
            }
            _ => Err("unexpected MLS content".into()),
        }
    }

    pub fn self_update(&mut self) -> Result<Vec<u8>> {
        let group = self.group.as_mut().ok_or("group required")?;
        let bundle = group.self_update(
            &self.provider,
            &self.signer,
            LeafNodeParameters::default(),
        )?;
        let commit = bundle.commit().clone();
        group.merge_pending_commit(&self.provider)?;
        Ok(TlsDump::tls_serialize_detached(&commit)?)
    }

    pub fn apply_commit(&mut self, commit: &[u8]) -> Result<()> {
        let msg: MlsMessageIn =
            TlsLoad::tls_deserialize(&mut &commit[..]).map_err(|e| e.to_string())?;
        let protocol = msg.try_into_protocol_message()?;
        let group = self.group.as_mut().ok_or("group required")?;
        let processed = group.process_message(&self.provider, protocol)?;
        match processed.into_content() {
            ProcessedMessageContent::StagedCommitMessage(c) => {
                group.merge_staged_commit(&self.provider, *c)?;
                Ok(())
            }
            _ => Err("expected commit".into()),
        }
    }

    pub fn epoch(&self) -> Result<u64> {
        Ok(self.group.as_ref().ok_or("group required")?.epoch().as_u64())
    }

    pub fn group_id(&self) -> Result<GroupId> {
        Ok(self.group.as_ref().ok_or("group required")?.group_id().clone())
    }

    pub fn storage_bytes(&self) -> usize {
        snapshot_storage(&self.provider)
            .into_iter()
            .map(|(k, v)| k.len() + v.len())
            .sum()
    }

    /// AEAD-wrap storage + signer using a 32-byte data key (same construction as session storage).
    pub fn persist(&self, data_key: &[u8; 32]) -> Result<Vec<u8>> {
        let snap = Persist {
            rows: snapshot_storage(&self.provider),
            signer: TlsDump::tls_serialize_detached(&self.signer)?,
            group_id: self.group.as_ref().map(|g| g.group_id().as_slice().to_vec()),
            pin: self.pin.clone(),
            binding: self.binding.clone(),
            identity_sk: self.identity_sk.to_bytes().to_vec(),
        };
        let plain = serde_json::to_vec(&snap)?;
        let cipher = Aes256Gcm::new(data_key.into());
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[0] = 1;
        let nonce = Nonce::from_slice(&nonce_bytes);
        let ct = cipher
            .encrypt(
                nonce,
                aes_gcm::aead::Payload {
                    msg: &plain,
                    aad: WRAP_AAD,
                },
            )
            .map_err(|e| e.to_string())?;
        Ok([nonce_bytes.as_slice(), ct.as_slice()].concat())
    }

    pub fn restore(data_key: &[u8; 32], wrapped: &[u8]) -> Result<Self> {
        check(wrapped.len() >= 28)?;
        let cipher = Aes256Gcm::new(data_key.into());
        let nonce = Nonce::from_slice(&wrapped[..12]);
        let plain = cipher
            .decrypt(
                nonce,
                aes_gcm::aead::Payload {
                    msg: &wrapped[12..],
                    aad: WRAP_AAD,
                },
            )
            .map_err(|e| e.to_string())?;
        let snap: Persist = serde_json::from_slice(&plain)?;
        let provider = OpenMlsRustCrypto::default();
        restore_storage(&provider, &snap.rows);
        let signer = TlsLoad::tls_deserialize(&mut snap.signer.as_slice()).map_err(|e| e.to_string())?;
        let identity_sk = ed25519_dalek::SigningKey::from_bytes(snap.identity_sk.as_slice().try_into()?);
        let group = if let Some(id) = snap.group_id {
            MlsGroup::load(provider.storage(), &GroupId::from_slice(&id))?
        } else {
            None
        };
        Ok(Self {
            provider,
            signer,
            pin: snap.pin,
            identity_sk,
            binding: snap.binding,
            group,
        })
    }
}

#[derive(Serialize, Deserialize)]
struct Persist {
    rows: Vec<(Vec<u8>, Vec<u8>)>,
    signer: Vec<u8>,
    group_id: Option<Vec<u8>>,
    pin: String,
    binding: String,
    identity_sk: Vec<u8>,
}

pub fn establish(a: &mut Device, b: &mut Device) -> Result<Vec<u8>> {
    a.create_group()?;
    let welcome = a.add_member(&b.binding, &b.pin)?;
    b.join(&welcome)?;
    Ok(welcome)
}

#[cfg(test)]
mod tests;

use jni::{objects::JClass, sys::jbyteArray, JNIEnv};

/// JNI probe so the Android .so exports a real symbol and can be loaded.
#[no_mangle]
pub extern "system" fn Java_com_codeyantra_armorchat_crypto_OpenMlsFeasibility_nativeProbe(
    _env: JNIEnv,
    _: JClass,
) -> i32 {
    26
}

#[no_mangle]
pub extern "system" fn Java_com_codeyantra_armorchat_crypto_OpenMlsFeasibility_nativeSuite(
    env: JNIEnv,
    _: JClass,
) -> jbyteArray {
    let s = (CIPHERSUITE as u16).to_be_bytes();
    env.byte_array_from_slice(&s)
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
