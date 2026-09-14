//! Isolated Phase 1D.1 harness. Stateless JNI: never publish state before durable commit.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::jbyteArray,
    JNIEnv,
};
use serde::de::{self, DeserializeOwned, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::fmt;
use vodozemac::{
    olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle},
    Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature,
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
const MAX_STATE: usize = 2 * 1024 * 1024;
const MAX_SAFE: u64 = (1u64 << 53) - 1;
fn check(ok: bool) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err("Rejected invalid or unauthenticated input".into())
    }
}
fn decode(s: &str, size: usize) -> Result<Vec<u8>> {
    let b = B64.decode(s)?;
    check(b.len() == size && B64.encode(&b) == s)?;
    Ok(b)
}
fn curve(s: &str) -> Result<Curve25519PublicKey> {
    Ok(Curve25519PublicKey::from_slice(&decode(s, 32)?)?)
}
fn ed(s: &str) -> Result<Ed25519PublicKey> {
    let bytes: [u8; 32] = decode(s, 32)?
        .try_into()
        .map_err(|_| "invalid ed25519 length")?;
    Ok(Ed25519PublicKey::from_slice(&bytes)?)
}
/// Reject duplicate keys, floats, and trailing JSON before typed deserialize.
struct StrictValue;
impl<'de> DeserializeSeed<'de> for StrictValue {
    type Value = Value;
    fn deserialize<D: serde::Deserializer<'de>>(
        self,
        deserializer: D,
    ) -> std::result::Result<Value, D::Error> {
        deserializer.deserialize_any(StrictVisitor)
    }
}
struct StrictVisitor;
impl<'de> Visitor<'de> for StrictVisitor {
    type Value = Value;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "JSON without duplicate fields or floats")
    }
    fn visit_bool<E: de::Error>(self, v: bool) -> std::result::Result<Value, E> {
        Ok(Value::Bool(v))
    }
    fn visit_i64<E: de::Error>(self, v: i64) -> std::result::Result<Value, E> {
        Ok(v.into())
    }
    fn visit_u64<E: de::Error>(self, v: u64) -> std::result::Result<Value, E> {
        Ok(v.into())
    }
    fn visit_f64<E: de::Error>(self, _v: f64) -> std::result::Result<Value, E> {
        Err(de::Error::custom("floats rejected"))
    }
    fn visit_str<E: de::Error>(self, v: &str) -> std::result::Result<Value, E> {
        Ok(Value::String(v.to_owned()))
    }
    fn visit_none<E: de::Error>(self) -> std::result::Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_unit<E: de::Error>(self) -> std::result::Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> std::result::Result<Value, A::Error> {
        let mut out = Vec::new();
        while let Some(v) = seq.next_element_seed(StrictValue)? {
            out.push(v);
        }
        Ok(Value::Array(out))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> std::result::Result<Value, A::Error> {
        let mut seen = HashSet::new();
        let mut obj = serde_json::Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if !seen.insert(key.clone()) {
                return Err(de::Error::custom("duplicate field"));
            }
            obj.insert(key, map.next_value_seed(StrictValue)?);
        }
        Ok(Value::Object(obj))
    }
}
fn parse_strict<T: DeserializeOwned>(raw: &str) -> Result<T> {
    let mut de = serde_json::Deserializer::from_str(raw);
    let value = StrictValue.deserialize(&mut de)?;
    de.end()?;
    Ok(serde_json::from_value(value)?)
}
fn frame<T: Serialize>(context: &str, body: &T) -> Result<Vec<u8>> {
    let mut b = context.as_bytes().to_vec();
    b.push(0);
    b.extend(serde_json_canonicalizer::to_vec(body)?);
    Ok(b)
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Device {
    v: u64,
    suite: String,
    identity_key: String,
    device_id: String,
    generation: u64,
    device_signing_key: String,
    device_agreement_key: String,
    issued_at: u64,
    expires_at: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Prekey {
    v: u64,
    suite: String,
    device_id: String,
    generation: u64,
    device_agreement_key: String,
    prekey_id: String,
    prekey_public: String,
    issued_at: u64,
    expires_at: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Signed<T> {
    body: T,
    signature: String,
}
fn window(start: u64, end: u64, now: u64, limit: u64) -> Result<()> {
    check(start <= now && now < end && end <= MAX_SAFE && end > start && end - start <= limit)
}
fn verify_device(raw: &str, pin: &str, now: u64) -> Result<Device> {
    let s: Signed<Device> = parse_strict(raw)?;
    let d = s.body;
    decode(pin, 32)?;
    check(d.identity_key == pin && d.v == 1 && d.suite == "ac-olm-v1")?;
    check(d.generation > 0 && d.generation <= MAX_SAFE)?;
    decode(&d.device_id, 16)?;
    ed(&d.device_signing_key)?;
    curve(&d.device_agreement_key)?;
    window(d.issued_at, d.expires_at, now, 90 * 86400)?;
    ed(pin)?.verify(
        &frame("ArmorChat/device/v1", &d)?,
        &Ed25519Signature::from_slice(&decode(&s.signature, 64)?)?,
    )?;
    Ok(d)
}
fn verify_prekey(raw: &str, d: &Device, now: u64) -> Result<Prekey> {
    let s: Signed<Prekey> = parse_strict(raw)?;
    let p = s.body;
    check(
        p.v == 1
            && p.suite == d.suite
            && p.device_id == d.device_id
            && p.generation == d.generation
            && p.device_agreement_key == d.device_agreement_key
            && p.expires_at <= d.expires_at
            && p.issued_at >= d.issued_at,
    )?;
    // vodozemac KeyId is a canonical base64 encoding of an eight-byte counter.
    use base64::engine::general_purpose::STANDARD_NO_PAD;
    let id = STANDARD_NO_PAD.decode(&p.prekey_id)?;
    check(id.len() == 8 && STANDARD_NO_PAD.encode(&id) == p.prekey_id)?;
    curve(&p.prekey_public)?;
    window(p.issued_at, p.expires_at, now, 7 * 86400)?;
    ed(&d.device_signing_key)?.verify(
        &frame("ArmorChat/prekey/v1", &p)?,
        &Ed25519Signature::from_slice(&decode(&s.signature, 64)?)?,
    )?;
    Ok(p)
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct State {
    account: AccountPickle,
    session: Option<SessionPickle>,
    local: Option<Device>,
    peer: Option<Device>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Packet {
    message_type: usize,
    ciphertext: String,
}
impl Packet {
    fn from_message(m: OlmMessage) -> Self {
        let (message_type, b) = m.to_parts();
        Self {
            message_type,
            ciphertext: B64.encode(b),
        }
    }
    fn message(&self) -> Result<OlmMessage> {
        let b = B64.decode(&self.ciphertext)?;
        check(B64.encode(&b) == self.ciphertext)?;
        Ok(OlmMessage::from_parts(self.message_type, &b)?)
    }
}
#[derive(Deserialize)]
#[serde(tag = "op", deny_unknown_fields)]
enum Request {
    Create,
    DeviceBody {
        identity: String,
        device_id: String,
        generation: u64,
        now: u64,
    },
    Bind {
        credential: String,
        pin: String,
        now: u64,
    },
    Prekey {
        now: u64,
    },
    Outbound {
        credential: String,
        prekey: String,
        pin: String,
        now: u64,
    },
    Inbound {
        credential: String,
        pin: String,
        now: u64,
        packet: Packet,
    },
    Encrypt {
        plaintext: String,
    },
    Decrypt {
        packet: Packet,
    },
}
/// All changes are local to this invocation. Errors leave caller's committed snapshot intact.
pub fn transact(snapshot: &[u8], request: &str) -> Result<Vec<u8>> {
    check(snapshot.len() <= MAX_STATE && request.len() <= 65536)?;
    let req: Request = parse_strict(request)?;
    let mut s = if matches!(req, Request::Create) {
        check(snapshot.is_empty())?;
        State {
            account: Account::new().pickle(),
            session: None,
            local: None,
            peer: None,
        }
    } else {
        serde_json::from_slice(snapshot)?
    };
    let mut a = Account::from_pickle(s.account);
    let mut session = s.session.map(Session::from_pickle);
    let output: Value = match req {
        Request::Create => json!({}),
        Request::DeviceBody {
            identity,
            device_id,
            generation,
            now,
        } => {
            decode(&identity, 32)?;
            decode(&device_id, 16)?;
            check(generation > 0 && generation <= MAX_SAFE && now <= MAX_SAFE - 90 * 86400)?;
            let body = Device {
                v: 1,
                suite: "ac-olm-v1".into(),
                identity_key: identity,
                device_id,
                generation,
                device_signing_key: B64.encode(a.ed25519_key().as_bytes()),
                device_agreement_key: B64.encode(a.curve25519_key().to_bytes()),
                issued_at: now,
                expires_at: now + 90 * 86400,
            };
            json!({"body":body,"signing_bytes":B64.encode(frame("ArmorChat/device/v1",&body)?)})
        }
        Request::Bind {
            credential,
            pin,
            now,
        } => {
            check(s.local.is_none() && session.is_none())?;
            let d = verify_device(&credential, &pin, now)?;
            check(
                d.device_signing_key == B64.encode(a.ed25519_key().as_bytes())
                    && d.device_agreement_key == B64.encode(a.curve25519_key().to_bytes()),
            )?;
            s.local = Some(d);
            json!({})
        }
        Request::Prekey { now } => {
            let d = s.local.as_ref().ok_or("Local credential required")?;
            check(a.one_time_keys().is_empty())?;
            a.generate_one_time_keys(1);
            let keys = a.one_time_keys();
            let (id, key) = keys.iter().next().ok_or("No prekey")?;
            let p = Prekey {
                v: 1,
                suite: d.suite.clone(),
                device_id: d.device_id.clone(),
                generation: d.generation,
                device_agreement_key: d.device_agreement_key.clone(),
                prekey_id: id.to_base64(),
                prekey_public: B64.encode(key.to_bytes()),
                issued_at: now,
                expires_at: now
                    .checked_add(7 * 86400)
                    .ok_or("Time overflow")?
                    .min(d.expires_at),
            };
            window(p.issued_at, p.expires_at, now, 7 * 86400)?;
            json!({"prekey":serde_json::to_string(&Signed {signature:B64.encode(a.sign(frame("ArmorChat/prekey/v1",&p)?).to_bytes()),body:p})?})
        }
        Request::Outbound {
            credential,
            prekey,
            pin,
            now,
        } => {
            check(s.local.is_some() && session.is_none())?;
            let d = verify_device(&credential, &pin, now)?;
            let p = verify_prekey(&prekey, &d, now)?;
            session = Some(a.create_outbound_session(
                SessionConfig::version_1(),
                curve(&d.device_agreement_key)?,
                curve(&p.prekey_public)?,
            )?);
            s.peer = Some(d);
            json!({})
        }
        Request::Inbound {
            credential,
            pin,
            now,
            packet,
        } => {
            check(s.local.is_some() && session.is_none())?;
            let d = verify_device(&credential, &pin, now)?;
            let OlmMessage::PreKey(m) = packet.message()? else {
                return Err("Expected library prekey message".into());
            };
            let result = a.create_inbound_session(
                SessionConfig::version_1(),
                curve(&d.device_agreement_key)?,
                &m,
            )?;
            let plaintext = String::from_utf8(result.plaintext)?;
            session = Some(result.session);
            s.peer = Some(d);
            json!({"plaintext":plaintext})
        }
        Request::Encrypt { plaintext } => {
            check(plaintext.len() <= 4096 && !plaintext.is_empty())?;
            let m = session
                .as_mut()
                .ok_or("Session required")?
                .encrypt(plaintext.as_bytes())?;
            json!({"packet":Packet::from_message(m)})
        }
        Request::Decrypt { packet } => {
            let plaintext = session
                .as_mut()
                .ok_or("Session required")?
                .decrypt(&packet.message()?)?;
            json!({"plaintext":String::from_utf8(plaintext)?})
        }
    };
    s.account = a.pickle();
    s.session = session.map(|v| v.pickle());
    let state = serde_json::to_string(&s)?;
    check(state.len() <= MAX_STATE)?;
    Ok(serde_json::to_vec(&json!({"state":state,"output":output}))?)
}

#[no_mangle]
pub extern "system" fn Java_com_codeyantra_armorchat_crypto_VodozemacFeasibility_transact(
    mut env: JNIEnv,
    _: JClass,
    snapshot: JByteArray,
    request: JString,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Vec<u8>> {
        check(env.get_array_length(&snapshot)? as usize <= MAX_STATE)?;
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
        _ => {
            let _ = env.throw_new(
                "java/lang/SecurityException",
                "Messaging operation rejected",
            );
            std::ptr::null_mut()
        }
    }
}
#[cfg(test)]
mod tests;
