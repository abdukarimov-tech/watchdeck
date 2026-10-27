//! SQLite-хранилище релея: токены, история алертов, FCM-устройства.

use std::sync::Mutex;

use anyhow::Result;
use rand::RngCore;
use sha2::{Digest, Sha256};

#[derive(Clone)]
pub struct Db {
    conn: std::sync::Arc<Mutex<rusqlite::Connection>>,
}

pub struct Token {
    pub token: String,
    pub role: String,
    pub label: String,
}

pub struct Alert {
    pub agent_id: String,
    pub kind: String,
    pub container_name: String,
    pub message: String,
    pub at_unix: i64,
}

fn hash(token: &str) -> String {
    let mut h = Sha256::new();
    h.update(token.as_bytes());
    hex(&h.finalize())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn generate_token() -> String {
    let mut buf = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut buf);
    format!("pulse_{}", hex(&buf))
}

pub struct TokenRow {
    pub id: i64,
    pub label: String,
    pub role: String,
    pub revoked: bool,
}

impl Db {
    pub fn open(path: &str) -> Result<Self> {
        let conn = rusqlite::Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        Ok(Self {
            conn: std::sync::Arc::new(Mutex::new(conn)),
        })
    }

    pub fn migrate(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS tokens (
                token_hash TEXT PRIMARY KEY,
                label      TEXT NOT NULL,
                role       TEXT NOT NULL CHECK (role IN ('agent','client')),
                created_unix INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                agent_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                container_name TEXT NOT NULL,
                message TEXT NOT NULL,
                at_unix INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS alerts_agent_idx ON alerts(agent_id, at_unix DESC);
            CREATE TABLE IF NOT EXISTS devices (
                fcm_token TEXT PRIMARY KEY,
                agent_id  TEXT NOT NULL,
                created_unix INTEGER NOT NULL
            );",
        )?;
        // миграция: колонка revoked для отзыва токенов
        let _ = conn.execute_batch("ALTER TABLE tokens ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0");
        Ok(())
    }

    pub fn create_token(&self, role: &str, label: &str) -> Result<Token> {
        let token = generate_token();
        self.conn.lock().unwrap().execute(
            "INSERT INTO tokens (token_hash, label, role, created_unix) VALUES (?1, ?2, ?3, ?4)",
            rusqlite::params![hash(&token), label, role, now_unix() as i64,],
        )?;
        Ok(Token {
            token,
            role: role.into(),
            label: label.into(),
        })
    }

    /// Возвращает (label, role) если токен валиден.
    pub fn verify_token(&self, token: &str) -> Option<(String, String)> {
        self.conn
            .lock()
            .unwrap()
            .query_row(
                "SELECT label, role FROM tokens WHERE token_hash = ?1 AND revoked = 0",
                [&hash(token)],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .ok()
    }

    pub fn list_tokens(&self) -> Result<Vec<TokenRow>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT rowid, label, role, revoked FROM tokens ORDER BY created_unix DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(TokenRow { id: r.get(0)?, label: r.get(1)?, role: r.get(2)?, revoked: r.get::<_, i64>(3)? != 0 })
        })?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub fn revoke_token(&self, id: i64) -> Result<()> {
        self.conn.lock().unwrap()
            .execute("UPDATE tokens SET revoked = 1 WHERE rowid = ?1", [id])?;
        Ok(())
    }

    pub fn insert_alert(&self, alert: &Alert) -> Result<()> {
        self.conn.lock().unwrap().execute(
            "INSERT INTO alerts (agent_id, kind, container_name, message, at_unix)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            rusqlite::params![
                alert.agent_id,
                alert.kind,
                alert.container_name,
                alert.message,
                alert.at_unix
            ],
        )?;
        Ok(())
    }

    pub fn recent_alerts(&self, agent_id: &str, limit: i64) -> Result<Vec<Alert>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT agent_id, kind, container_name, message, at_unix
             FROM alerts WHERE agent_id = ?1 ORDER BY at_unix DESC LIMIT ?2",
        )?;
        let rows = stmt.query_map([agent_id, &limit.to_string()], |r| {
            Ok(Alert {
                agent_id: r.get(0)?,
                kind: r.get(1)?,
                container_name: r.get(2)?,
                message: r.get(3)?,
                at_unix: r.get(4)?,
            })
        })?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    /// Последние алерты по всем агентам (для клиентов с подпиской "все").
    pub fn recent_alerts_any(&self, limit: i64) -> Result<Vec<Alert>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT agent_id, kind, container_name, message, at_unix
             FROM alerts ORDER BY at_unix DESC LIMIT ?1",
        )?;
        let rows = stmt.query_map([limit], |r| {
            Ok(Alert {
                agent_id: r.get(0)?,
                kind: r.get(1)?,
                container_name: r.get(2)?,
                message: r.get(3)?,
                at_unix: r.get(4)?,
            })
        })?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub fn add_device(&self, fcm_token: &str, agent_id: &str) -> Result<()> {
        self.conn.lock().unwrap().execute(
            "INSERT INTO devices (fcm_token, agent_id, created_unix) VALUES (?1, ?2, ?3)
             ON CONFLICT(fcm_token) DO UPDATE SET agent_id = excluded.agent_id",
            rusqlite::params![fcm_token, agent_id, now_unix() as i64],
        )?;
        Ok(())
    }

    pub fn device_tokens(&self, agent_id: &str) -> Result<Vec<String>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT fcm_token FROM devices WHERE agent_id = ?1")?;
        let rows = stmt.query_map([agent_id], |r| r.get(0))?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }
}

pub fn now_unix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Db {
        let db = Db::open(":memory:").unwrap();
        db.migrate().unwrap();
        db
    }

    #[test]
    fn token_roundtrip() {
        let db = db();
        let t = db.create_token("agent", "my-vps").unwrap();
        assert!(t.token.starts_with("pulse_"));
        assert_eq!(
            db.verify_token(&t.token),
            Some(("my-vps".into(), "agent".into()))
        );
        assert_eq!(db.verify_token("garbage"), None);
    }

    #[test]
    fn token_not_stored_in_plaintext() {
        let db = db();
        let t = db.create_token("agent", "x").unwrap();
        let conn = db.conn.lock().unwrap();
        let stored: String = conn
            .query_row("SELECT token_hash FROM tokens", [], |r| r.get(0))
            .unwrap();
        assert_ne!(stored, t.token);
    }

    #[test]
    fn alerts_history() {
        let db = db();
        db.insert_alert(&Alert {
            agent_id: "a1".into(),
            kind: "CONTAINER_DIED".into(),
            container_name: "web".into(),
            message: "exit 137".into(),
            at_unix: 100,
        })
        .unwrap();
        db.insert_alert(&Alert {
            agent_id: "a2".into(),
            kind: "OOM".into(),
            container_name: "job".into(),
            message: String::new(),
            at_unix: 200,
        })
        .unwrap();
        let for_a1 = db.recent_alerts("a1", 10).unwrap();
        assert_eq!(for_a1.len(), 1);
        assert_eq!(for_a1[0].container_name, "web");
    }

    #[test]
    fn device_upsert() {
        let db = db();
        db.add_device("tok1", "a1").unwrap();
        db.add_device("tok1", "a2").unwrap();
        assert_eq!(db.device_tokens("a2").unwrap(), vec!["tok1".to_string()]);
        assert!(db.device_tokens("a3").unwrap().is_empty());
    }
}
