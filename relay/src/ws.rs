//! WebSocket-обработчики для агентов и клиентов.

use std::time::Duration;

use axum::extract::ws::{Message as AxWsMessage, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use futures_util::{SinkExt, StreamExt};
use prost::Message as _;
use tokio::sync::mpsc;
use tokio::time::timeout;
use tracing::{info, warn};

use crate::db::Alert;
use crate::pb::msg::Payload;
use crate::pb::{EventAlert, HelloAck, Msg};
use crate::state::AppState;

/// Если от пира нет ни одного сообщения за это время — считаем соединение
/// мертвым (half-open TCP) и закрываем. Агент и клиент шлют аппаратные Ping.
const IDLE_TIMEOUT: Duration = Duration::from_secs(120);

fn bearer(headers: &HeaderMap) -> Option<String> {
    headers
        .get("Authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
        .map(str::to_owned)
}

fn encode_msg(msg: &Msg) -> Vec<u8> {
    let mut buf = Vec::with_capacity(msg.encoded_len());
    // encode в Vec не может провалиться
    let _ = msg.encode(&mut buf);
    buf
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

    st.register_agent(agent_id.clone(), String::new(), String::new(), tx.clone());

    let ack = Msg {
        id: 0,
        target: String::new(),
        payload: Some(Payload::HelloAck(HelloAck {
            agent_id: agent_id.clone(),
            relay_version: crate::RELAY_VERSION.into(),
        })),
    };
    if sink
        .send(AxWsMessage::Binary(encode_msg(&ack)))
        .await
        .is_err()
    {
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

    loop {
        let next = timeout(IDLE_TIMEOUT, stream.next()).await;
        let Ok(Some(Ok(msg))) = next else {
            if next.is_err() {
                warn!(%agent_id, "agent idle timeout, dropping connection");
            }
            break;
        };
        let AxWsMessage::Binary(bytes) = msg else {
            continue;
        };
        let Ok(msg) = Msg::decode(bytes.as_ref()) else {
            warn!(%agent_id, "undecodable message from agent");
            continue;
        };
        match msg.payload {
            Some(Payload::Hello(hello)) => {
                st.register_agent(
                    agent_id.clone(),
                    hello.hostname.clone(),
                    hello.agent_version.clone(),
                    tx.clone(),
                );
                info!(%agent_id, hostname = %hello.hostname, "agent hello");
                notify_event(
                    &st,
                    &agent_id,
                    crate::pb::EventKind::ServerOnline,
                    "server online",
                    None,
                );
            }
            Some(Payload::Ping(_)) => {
                // ответ не нужен: tungstenite сам отвечает на протокольные ping,
                // а аппаратный Ping агента служит доказательством живости
            }
            Some(Payload::EventAlert(mut ev)) => {
                let kind_name = crate::pb::EventKind::try_from(ev.kind)
                    .map(|k| k.as_str_name())
                    .unwrap_or("EVENT_UNKNOWN");
                let alert = Alert {
                    agent_id: agent_id.clone(),
                    kind: kind_name.into(),
                    container_name: ev.container_name.clone(),
                    message: ev.message.clone(),
                    at_unix: if ev.at_unix > 0 {
                        ev.at_unix
                    } else {
                        crate::db::now_unix() as i64
                    },
                };
                if let Err(e) = st.db.insert_alert(&alert) {
                    warn!(%agent_id, error = %e, "failed to store alert");
                }
                ev.at_unix = alert.at_unix;
                st.forward_to_clients(
                    &agent_id,
                    Msg {
                        id: msg.id,
                        target: String::new(),
                        payload: Some(Payload::EventAlert(ev)),
                    },
                );
                // пуш не блокирует обработку дальнейших событий
                let st2 = st.clone();
                let agent = agent_id.clone();
                tokio::spawn(async move {
                    st2.fcm
                        .push_container_alert(
                            &st2.db.device_tokens(&agent).unwrap_or_default(),
                            &agent,
                            &alert.container_name,
                            &alert.message,
                            kind_name,
                        )
                        .await;
                });
            }
            Some(other) => st.forward_to_clients(
                &agent_id,
                Msg {
                    id: msg.id,
                    target: String::new(),
                    payload: Some(other),
                },
            ),
            None => {}
        }
    }

    st.unregister_agent(&agent_id);
    writer.abort();

    // watchdog: агент отвалился (упал сервер) — алерт клиентам + push
    notify_event(
        &st,
        &agent_id,
        crate::pb::EventKind::ServerOffline,
        "server unreachable",
        None,
    );
}

/// Алерт о жизненном цикле сервера: в историю, клиентам и в push.
fn notify_event(
    st: &AppState,
    agent_id: &str,
    kind: crate::pb::EventKind,
    message: &str,
    container: Option<&str>,
) {
    let alert = Alert {
        agent_id: agent_id.to_owned(),
        kind: kind.as_str_name().into(),
        container_name: container.unwrap_or_default().into(),
        message: message.into(),
        at_unix: crate::db::now_unix() as i64,
    };
    if let Err(e) = st.db.insert_alert(&alert) {
        warn!(%agent_id, error = %e, "failed to store server alert");
    }
    st.forward_to_clients(
        agent_id,
        Msg {
            id: 0,
            target: String::new(),
            payload: Some(Payload::EventAlert(EventAlert {
                kind: kind as i32,
                container_id: String::new(),
                container_name: String::new(),
                at_unix: alert.at_unix,
                message: message.into(),
            })),
        },
    );
    let st2 = st.clone();
    let agent = agent_id.to_owned();
    let msg = message.to_owned();
    tokio::spawn(async move {
        st2.fcm
            .push_container_alert(
                &st2.db.device_tokens(&agent).unwrap_or_default(),
                &agent,
                &agent,
                &msg,
                kind.as_str_name(),
            )
            .await;
    });
}

#[derive(serde::Deserialize, Default)]
pub struct ClientQuery {
    /// Не задан или "*" — подписка на всех агентов.
    pub agent_id: Option<String>,
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
    let filter = q.agent_id.filter(|a| a != "*" && !a.is_empty());
    ws.on_upgrade(move |socket| client_socket(st, filter, socket))
}

async fn client_socket(st: AppState, filter: Option<String>, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let client_id = st.register_client(filter.clone(), tx.clone());

    // история алертов: клиент, пришедший после падения контейнера, видит событие
    let history_limit = 50;
    let history = match filter.as_deref() {
        Some(agent) => st.db.recent_alerts(agent, history_limit),
        None => st.db.recent_alerts_any(history_limit),
    };
    for alert in history.unwrap_or_default() {
        let kind = match alert.kind.as_str() {
            "CONTAINER_DIED" => crate::pb::EventKind::ContainerDied,
            "CONTAINER_STARTED" => crate::pb::EventKind::ContainerStarted,
            "OOM" => crate::pb::EventKind::Oom,
            _ => crate::pb::EventKind::EventUnknown,
        };
        let msg = Msg {
            id: 0,
            target: alert.agent_id.clone(),
            payload: Some(Payload::EventAlert(EventAlert {
                kind: kind as i32,
                container_id: String::new(),
                container_name: alert.container_name,
                at_unix: alert.at_unix,
                message: alert.message,
            })),
        };
        let _ = tx.send(encode_msg(&msg));
    }

    let writer = tokio::spawn(async move {
        while let Some(bytes) = rx.recv().await {
            if sink.send(AxWsMessage::Binary(bytes)).await.is_err() {
                break;
            }
        }
    });

    loop {
        let next = timeout(IDLE_TIMEOUT, stream.next()).await;
        let Ok(Some(Ok(msg))) = next else {
            break; // таймаут или закрытие
        };
        let AxWsMessage::Binary(bytes) = msg else {
            continue;
        };
        let Ok(decoded) = Msg::decode(bytes.as_ref()) else {
            warn!("undecodable message from client");
            continue;
        };
        // Ping от клиента доказывает живость соединения
        if matches!(decoded.payload, Some(Payload::Ping(_))) {
            continue;
        }
        if let Err(e) = st.forward_to_agent(Some(&decoded.target), bytes.as_ref()) {
            warn!(target = %decoded.target, error = e, "forward to agent failed");
        }
    }

    st.unregister_client(client_id);
    writer.abort();
}
