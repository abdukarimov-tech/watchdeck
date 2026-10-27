//! Общее состояние релея: соединения агентов и клиентов, БД, FCM.

use std::sync::Arc;

use dashmap::DashMap;
use prost::Message as _;
use tokio::sync::mpsc;

use crate::db::Db;
use crate::fcm::FcmSender;
use crate::pb::Msg;

pub type OutTx = mpsc::UnboundedSender<Vec<u8>>;

/// Подписка клиента: None = все агенты, Some(id) = конкретный агент.
#[derive(Clone)]
pub struct ClientConn {
    pub filter: Option<String>,
    pub tx: OutTx,
}

#[derive(Clone)]
pub struct AppState {
    pub admin_token: String,
    pub db: Db,
    pub fcm: FcmSender,
    inner: Arc<Inner>,
}

struct Inner {
    /// agent_id -> исходящий канал агента (в агент отправляем байты Msg)
    agents: DashMap<String, OutTx>,
    /// agent_id -> hostname (из Hello)
    agent_hosts: DashMap<String, String>,
    /// agent_id -> версия агента (из Hello)
    agent_versions: DashMap<String, String>,
    /// client conn id -> подписка + канал клиенту
    clients: DashMap<u64, ClientConn>,
    next_client_id: std::sync::atomic::AtomicU64,
}

impl AppState {
    pub fn new(admin_token: String, db: Db, fcm: FcmSender) -> Self {
        Self {
            admin_token,
            db,
            fcm,
            inner: Arc::new(Inner {
                agents: DashMap::new(),
                agent_hosts: DashMap::new(),
                clients: DashMap::new(),
                agent_versions: DashMap::new(),
                next_client_id: std::sync::atomic::AtomicU64::new(0),
            }),
        }
    }

    pub fn register_agent(&self, agent_id: String, hostname: String, version: String, tx: OutTx) {
        tracing::info!(%agent_id, %hostname, %version, "agent connected");
        self.inner.agents.insert(agent_id.clone(), tx);
        self.inner.agent_hosts.insert(agent_id.clone(), hostname);
        self.inner.agent_versions.insert(agent_id, version);
    }

    pub fn unregister_agent(&self, agent_id: &str) {
        tracing::info!(%agent_id, "agent disconnected");
        // при переподключении новое соединение вставляется после удаления —
        // в худшем случае мгновение считается офлайн
        self.inner.agents.remove(agent_id);
        self.inner.agent_hosts.remove(agent_id);
        self.inner.agent_versions.remove(agent_id);
    }

    pub fn register_client(&self, filter: Option<String>, tx: OutTx) -> u64 {
        let id = self
            .inner
            .next_client_id
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.inner.clients.insert(id, ClientConn { filter, tx });
        id
    }

    pub fn unregister_client(&self, id: u64) {
        self.inner.clients.remove(&id);
    }

    /// Сообщение от агента: проставляем target = agent_id (клиент различает
    /// серверы), фильтруем по подпискам и рассылаем.
    pub fn forward_to_clients(&self, agent_id: &str, msg: Msg) {
        let mut msg = msg;
        msg.target = agent_id.to_owned();
        let mut buf = Vec::with_capacity(msg.encoded_len());
        if msg.encode(&mut buf).is_err() {
            return;
        }
        for entry in self.inner.clients.iter() {
            let conn = entry.value();
            let matches = conn.filter.as_deref().map_or(true, |f| f == agent_id);
            if matches {
                let _ = conn.tx.send(buf.clone());
            }
        }
    }

    /// Сообщение от клиента: найти агента по target (или единственному
    /// подключенному) и отправить.
    pub fn forward_to_agent(&self, target: Option<&str>, bytes: &[u8]) -> Result<(), &'static str> {
        let resolved = match target.filter(|t| !t.is_empty()) {
            Some(t) => t.to_string(),
            None => {
                if self.inner.agents.len() == 1 {
                    self.inner.agents.iter().next().unwrap().key().clone()
                } else {
                    return Err("target agent required (multiple agents connected)");
                }
            }
        };
        match self.inner.agents.get(&resolved) {
            Some(tx) => tx.send(bytes.to_vec()).map_err(|_| "agent gone"),
            None => Err("agent not connected"),
        }
    }

    pub fn connected_agents(&self) -> Vec<(String, String, String)> {
        self.inner
            .agents
            .iter()
            .filter_map(|e| {
                let host = self.inner.agent_hosts.get(e.key())?.clone();
                let version = self.inner.agent_versions.get(e.key()).map(|v| v.clone()).unwrap_or_default();
                Some((e.key().clone(), host, version))
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::msg::Payload;
    use crate::pb::{Container, ContainerList};

    fn state() -> AppState {
        let db = Db::open(":memory:").unwrap();
        db.migrate().unwrap();
        AppState::new("admin".into(), db, FcmSender::Disabled)
    }

    fn container_list_msg() -> Msg {
        Msg {
            id: 1,
            target: String::new(),
            payload: Some(Payload::ContainerList(ContainerList {
                containers: vec![Container::default()],
            })),
        }
    }

    #[test]
    fn forward_sets_target_and_respects_filters() {
        let st = state();
        let (all_tx, mut all_rx) = mpsc::unbounded_channel();
        let (other_tx, mut other_rx) = mpsc::unbounded_channel();
        st.register_client(None, all_tx); // подписка на всех
        st.register_client(Some("other".into()), other_tx); // другой агент

        st.forward_to_clients("a1", container_list_msg());

        let got = all_rx.try_recv().unwrap();
        let decoded = Msg::decode(got.as_slice()).unwrap();
        assert_eq!(decoded.target, "a1");
        assert!(matches!(decoded.payload, Some(Payload::ContainerList(_))));
        // подписчик на "other" не получает сообщения от "a1"
        assert!(other_rx.try_recv().is_err());
    }

    #[test]
    fn forward_to_agent_single_agent_no_target() {
        let st = state();
        let (agent_tx, mut agent_rx) = mpsc::unbounded_channel();
        st.register_agent("only".into(), "host".into(), "0.1.0".into(), agent_tx);

        let bytes = {
            let m = container_list_msg();
            let mut b = Vec::new();
            m.encode(&mut b).unwrap();
            b
        };
        assert!(st.forward_to_agent(None, &bytes).is_ok());
        assert!(agent_rx.try_recv().is_ok());
    }

    #[test]
    fn forward_to_agent_requires_target_when_multiple() {
        let st = state();
        let (tx1, _rx1) = mpsc::unbounded_channel();
        let (tx2, _rx2) = mpsc::unbounded_channel();
        st.register_agent("a".into(), "h".into(), "0.1.0".into(), tx1);
        st.register_agent("b".into(), "h".into(), "0.1.0".into(), tx2);

        let bytes = {
            let m = container_list_msg();
            let mut b = Vec::new();
            m.encode(&mut b).unwrap();
            b
        };
        assert!(st.forward_to_agent(None, &bytes).is_err());
        assert!(st.forward_to_agent(Some("a"), &bytes).is_ok());
        assert!(st.forward_to_agent(Some("zzz"), &bytes).is_err());
    }
}
