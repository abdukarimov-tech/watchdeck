//! WebSocket-обработчики для агентов и клиентов.

use axum::extract::ws::{Message as AxWsMessage, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use futures_util::{SinkExt, StreamExt};
use prost::Message as _;
use tokio::sync::mpsc;
use tracing::{info, warn};

use crate::db::Alert;
use crate::pb::msg::Payload;
use crate::pb::{
    ContainerActionResult, ContainerList, EventKind, HelloAck, LogsChunk, MetricsReport, Msg,
};
use crate::state::AppState;

fn bearer(headers: &HeaderMap) -> Option<String> {
    headers
        .get("Authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
        .map(str::to_owned)
}

/// GET /ws/agent — подключение агента (роль `agent` в токене).
pub async fn agent_ws(
    State(st): State<AppState>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let Some(token) = bearer(&headers) else {
        return (StatusCode::UNAUTHORIZED, "missing bearer token").into_response();
    };
    let Some((label, role)) = st.db.verify_token(&token) else {
        return (StatusCode::UNAUTHORIZED, "invalid token").into_response();
    };
    if role != "agent" {
        return (StatusCode::FORBIDDEN, "agent role required").into_response();
    }
    ws.on_upgrade(move |socket| agent_socket(st, label, socket))
}

async fn agent_socket(st: AppState, agent_id: String, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();

    st.register_agent(agent_id.clone(), String::new(), tx.clone());

    let ack = Msg {
        id: 0,
        target: String::new(),
        payload: Some(Payload::HelloAck(HelloAck {
            agent_id: agent_id.clone(),
            relay_version: crate::RELAY_VERSION.into(),
        })),
    };
    let mut buf = Vec::with_capacity(ack.encoded_len());
    // encode в буфер не может провалиться для Vec
    let _ = ack.encode(&mut buf);
    if sink.send(AxWsMessage::Binary(buf)).await.is_err() {
        st.unregister_agent(&agent_id);
        return;
    }

    let writer = tokio::spawn(async move {
        while let Some(bytes) = rx.recv().await {
            if sink.send(AxWsMessage::Binary(bytes)).await.is_err() {
                break;
            }
        }
    });

    while let Some(Ok(msg)) = stream.next().await {
        let AxWsMessage::Binary(bytes) = msg else {
            continue;
        };
        let Ok(msg) = Msg::decode(bytes.as_ref()) else {
            warn!(%agent_id, "undecodable message from agent");
            continue;
        };
        match msg.payload {
            Some(Payload::Hello(hello)) => {
                st.register_agent(agent_id.clone(), hello.hostname.clone(), tx.clone());
                info!(%agent_id, hostname = %hello.hostname, "agent hello");
            }
            Some(Payload::Metrics(MetricsReport { .. }))
            | Some(Payload::ContainerList(ContainerList { .. }))
            | Some(Payload::ContainerActionResult(ContainerActionResult { .. }))
            | Some(Payload::LogsChunk(LogsChunk { .. })) => {
                st.forward_to_clients(&agent_id, bytes.as_ref());
            }
            Some(Payload::EventAlert(ev)) => {
                let kind_name = EventKind::try_from(ev.kind)
                    .map(|k| k.as_str_name())
                    .unwrap_or("EVENT_UNKNOWN");
                let alert = Alert {
                    agent_id: agent_id.clone(),
                    kind: kind_name.into(),
                    container_name: ev.container_name.clone(),
                    message: ev.message.clone(),
                    at_unix: ev.at_unix,
                };
                if let Err(e) = st.db.insert_alert(&alert) {
                    warn!(%agent_id, error = %e, "failed to store alert");
                }
                st.forward_to_clients(&agent_id, bytes.as_ref());
                // пуш не блокирует обработку дальнейших событий
                let st2 = st.clone();
                let agent = agent_id.clone();
                tokio::spawn(async move {
                    st2.fcm
                        .push_container_alert(
                            &st2.db.device_tokens(&agent).unwrap_or_default(),
                            &agent,
                            &ev.container_name,
                            &ev.message,
                            kind_name,
                        )
                        .await;
                });
            }
            Some(Payload::Pong(_)) => {}
            Some(other) => warn!(%agent_id, ?other, "unexpected payload from agent"),
            None => {}
        }
    }

    st.unregister_agent(&agent_id);
    writer.abort();
}

#[derive(serde::Deserialize)]
pub struct ClientQuery {
    pub agent_id: String,
}

/// GET /ws/client?agent_id=... — подключение клиента-приложения.
pub async fn client_ws(
    State(st): State<AppState>,
    Query(q): Query<ClientQuery>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let Some(token) = bearer(&headers) else {
        return (StatusCode::UNAUTHORIZED, "missing bearer token").into_response();
    };
    let Some((_, role)) = st.db.verify_token(&token) else {
        return (StatusCode::UNAUTHORIZED, "invalid token").into_response();
    };
    if role != "client" {
        return (StatusCode::FORBIDDEN, "client role required").into_response();
    }
    ws.on_upgrade(move |socket| client_socket(st, q.agent_id, socket))
}

async fn client_socket(st: AppState, agent_id: String, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let client_id = st.register_client(agent_id.clone(), tx);

    let writer = tokio::spawn(async move {
        while let Some(bytes) = rx.recv().await {
            if sink.send(AxWsMessage::Binary(bytes)).await.is_err() {
                break;
            }
        }
    });

    while let Some(Ok(msg)) = stream.next().await {
        let AxWsMessage::Binary(bytes) = msg else {
            continue;
        };
        // target из сообщения имеет приоритет над подпиской; для MVP
        // подписка определяет маршрутизацию, target — уточнение
        let target = Msg::decode(bytes.as_ref())
            .ok()
            .and_then(|m| {
                let t = m.target;
                if t.is_empty() {
                    None
                } else {
                    Some(t)
                }
            })
            .unwrap_or_else(|| agent_id.clone());
        if let Err(e) = st.forward_to_agent(Some(&target), bytes.as_ref()) {
            warn!(%agent_id, error = e, "forward to agent failed");
        }
    }

    st.unregister_client(client_id);
    writer.abort();
}
