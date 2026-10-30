//! Optional APNs provider. Signing keys stay on the relay; device tokens are never logged.
use anyhow::{Context, Result};
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine as _,
};
use ring::{
    rand::SystemRandom,
    signature::{EcdsaKeyPair, ECDSA_P256_SHA256_FIXED_SIGNING},
};
use std::sync::{Arc, Mutex};

#[derive(Clone, Default)]
pub struct ApnsSender(Option<Arc<Provider>>);
struct Provider {
    key: EcdsaKeyPair,
    key_id: String,
    team_id: String,
    topic: String,
    http: reqwest::Client,
    jwt: Mutex<(u64, String)>,
}
impl ApnsSender {
    pub fn from_env() -> Self {
        if std::env::var("PULSE_APNS_KEY_FILE").is_err() {
            return Self::default();
        }
        match Self::configured() {
            Ok(provider) => Self(Some(Arc::new(provider))),
            Err(e) => {
                tracing::error!(error=%e,"APNs configuration invalid; iOS push disabled");
                Self::default()
            }
        }
    }
    fn configured() -> Result<Provider> {
        let pem = std::fs::read_to_string(std::env::var("PULSE_APNS_KEY_FILE")?)?;
        let body: String = pem.lines().filter(|l| !l.starts_with("-----")).collect();
        let der = STANDARD.decode(body.trim()).context("APNs key PEM")?;
        let key =
            EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &der, &SystemRandom::new())
                .map_err(|_| anyhow::anyhow!("APNs key must be a P-256 PKCS#8 .p8 key"))?;
        let http = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(15))
            .build()?;
        Ok(Provider {
            key,
            key_id: std::env::var("PULSE_APNS_KEY_ID")?,
            team_id: std::env::var("PULSE_APNS_TEAM_ID")?,
            topic: std::env::var("PULSE_APNS_TOPIC").unwrap_or_else(|_| "app.pulse".into()),
            http,
            jwt: Mutex::new((0, String::new())),
        })
    }
    pub async fn push(
        &self,
        devices: &[(String, bool)],
        agent: &str,
        container: &str,
        message: &str,
        kind: &str,
    ) {
        let Some(p) = &self.0 else { return };
        if devices.is_empty() {
            return;
        }
        let token = match p.token() {
            Ok(t) => t,
            Err(e) => {
                tracing::warn!(error=%e,"APNs signing failed");
                return;
            }
        };
        let payload = serde_json::json!({"aps":{"alert":{"title":format!("WatchDeck · {agent}"),"body":format!("{container}: {message}")},"sound":"default"},"agent_id":agent,"container":container,"kind":kind,"message":message});
        for (device, sandbox) in devices {
            let host = if *sandbox {
                "api.sandbox.push.apple.com"
            } else {
                "api.push.apple.com"
            };
            let result = p
                .http
                .post(format!("https://{host}/3/device/{device}"))
                .version(reqwest::Version::HTTP_2)
                .bearer_auth(&token)
                .header("apns-topic", &p.topic)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .json(&payload)
                .send()
                .await;
            match result {
                Ok(r) if !r.status().is_success() => {
                    tracing::warn!(status=%r.status(),"APNs rejected notification")
                }
                Err(e) => tracing::warn!(error=%e,"APNs send failed"),
                _ => {}
            }
        }
    }
}
impl Provider {
    fn token(&self) -> Result<String> {
        let now = crate::db::now_unix();
        let mut cached = self.jwt.lock().unwrap();
        if !cached.1.is_empty() && now.saturating_sub(cached.0) < 3000 {
            return Ok(cached.1.clone());
        }
        let header = URL_SAFE_NO_PAD.encode(serde_json::to_vec(
            &serde_json::json!({"alg":"ES256","kid":self.key_id}),
        )?);
        let claims = URL_SAFE_NO_PAD.encode(serde_json::to_vec(
            &serde_json::json!({"iss":self.team_id,"iat":now}),
        )?);
        let unsigned = format!("{header}.{claims}");
        let signature = self
            .key
            .sign(&SystemRandom::new(), unsigned.as_bytes())
            .map_err(|_| anyhow::anyhow!("ES256 signing failed"))?;
        let token = format!("{unsigned}.{}", URL_SAFE_NO_PAD.encode(signature.as_ref()));
        *cached = (now, token.clone());
        Ok(token)
    }
}
impl crate::db::Db {
    pub fn add_apns_device(&self, token: &str, agent: &str, sandbox: bool) -> Result<()> {
        self.conn.lock().unwrap().execute(
            "INSERT OR IGNORE INTO apns_devices VALUES(?1,?2,?3)",
            rusqlite::params![token, agent, sandbox],
        )?;
        Ok(())
    }
    pub fn apns_devices(&self, agent: &str) -> Result<Vec<(String, bool)>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT token,sandbox FROM apns_devices WHERE agent_id=?1")?;
        let rows = stmt.query_map([agent], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::KeyPair;
    #[test]
    fn jwt_uses_es256_and_reuses_a_valid_provider_token() {
        let rng = SystemRandom::new();
        let doc = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &rng).unwrap();
        let key =
            EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, doc.as_ref(), &rng).unwrap();
        let provider = Provider {
            key,
            key_id: "KEY123".into(),
            team_id: "TEAM123".into(),
            topic: "app.pulse".into(),
            http: reqwest::Client::new(),
            jwt: Mutex::new((0, String::new())),
        };
        let token = provider.token().unwrap();
        assert_eq!(token, provider.token().unwrap());
        let parts: Vec<_> = token.split('.').collect();
        assert_eq!(parts.len(), 3);
        let header: serde_json::Value =
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[0]).unwrap()).unwrap();
        assert_eq!(header["alg"], "ES256");
        assert_eq!(header["kid"], "KEY123");
        let claims: serde_json::Value =
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1]).unwrap()).unwrap();
        assert_eq!(claims["iss"], "TEAM123");
        ring::signature::UnparsedPublicKey::new(
            &ring::signature::ECDSA_P256_SHA256_FIXED,
            provider.key.public_key().as_ref(),
        )
        .verify(
            format!("{}.{}", parts[0], parts[1]).as_bytes(),
            &URL_SAFE_NO_PAD.decode(parts[2]).unwrap(),
        )
        .unwrap();
    }
}
