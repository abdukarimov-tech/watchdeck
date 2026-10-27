//! Общее состояние релея: соединения агентов и клиентов, БД, FCM.

use std::sync::Arc;

use dashmap::DashMap;
use tokio::sync::mpsc;

use crate::db::Db;
use crate::fcm::FcmSender;

pub type OutTx = mpsc::UnboundedSender<Vec<u8>>;

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
    /// client conn id -> (agent_id, канал клиенту)
    clients: DashMap<u64, (String, OutTx)>,
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
                next_client_id: std::sync::atomic::AtomicU64::new(0),
            }),
        }
    }

    pub fn register_agent(&self, agent_id: String, hostname: String, tx: OutTx) {
        tracing::info!(%agent_id, %hostname, "agent connected");
        self.inner.agents.insert(agent_id.clone(), tx);
        self.inner.agent_hosts.insert(agent_id, hostname);
    }

    pub fn unregister_agent(&self, agent_id: &str) {
        tracing::info!(%agent_id, "agent disconnected");
        // при переподключении новое соединение вставляется после удаления —
        // в худшем случае мгновение считается офлайн
        self.inner.agents.remove(agent_id);
        self.inner.agent_hosts.remove(agent_id);
    }

    pub fn register_client(&self, agent_id: String, tx: OutTx) -> u64 {
        let id = self
            .inner
            .next_client_id
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.inner.clients.insert(id, (agent_id, tx));
        id
    }

    pub fn unregister_client(&self, id: u64) {
        self.inner.clients.remove(&id);
    }

    /// Сообщение от агента: разослать всем подписанным клиентам.
    pub fn forward_to_clients(&self, agent_id: &str, bytes: &[u8]) {
        for entry in self.inner.clients.iter() {
            let (subscribed, tx) = entry.value();
            if subscribed == agent_id {
                let _ = tx.send(bytes.to_vec());
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

    pub fn connected_agents(&self) -> Vec<(String, String)> {
        self.inner
            .agents
            .iter()
            .filter_map(|e| {
                self.inner
                    .agent_hosts
                    .get(e.key())
                    .map(|h| (e.key().clone(), h.clone()))
            })
            .collect()
    }
}
