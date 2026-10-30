//! WS-транспорт агента: исходящее соединение к релею, автопереподключение
//! с экспоненциальным backoff. Агент никогда не слушает входящие порты.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result};
use futures_util::{SinkExt, StreamExt};
use prost::Message as _;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::Connector;
use tokio_tungstenite::{
    connect_async_tls_with_config, tungstenite::protocol::Message as WsMessage,
};
use tokio_util::sync::CancellationToken;
use tracing::{info, warn};

use agent_core::pb::{
    msg::Payload, ContainerAction, ContainerActionResult, ContainerList, LogsChunk, Msg, Ping, Pong,
};
use agent_core::{DockerClient, MetricsSampler};

use crate::config::Config;

const MAX_BACKOFF: Duration = Duration::from_secs(60);
const PING_INTERVAL: Duration = Duration::from_secs(30);
const MAX_STREAM_CHUNK_LINES: usize = 200;

type OutboundTx = mpsc::UnboundedSender<Vec<u8>>;

/// TLS-коннектор: при заданном ca_cert доверяем только самоподписанному
/// сертификату релея (звенья публичных CA не нужны). Без ca_cert — None,
/// tokio-tungstenite использует webpki-roots.
fn tls_connector(cfg: &Config) -> Result<Option<Connector>> {
    let Some(ca_path) = cfg.ca_cert.as_deref() else {
        return Ok(None);
    };
    let pem = std::fs::read(ca_path).with_context(|| format!("read ca_cert {ca_path}"))?;
    let mut roots = rustls::RootCertStore::empty();
    for cert in rustls_pemfile::certs(&mut pem.as_slice()) {
        roots.add(cert?)?;
    }
    if roots.is_empty() {
        anyhow::bail!("no certificates parsed from {ca_path}");
    }
    let tls = rustls::ClientConfig::builder_with_provider(
        rustls::crypto::ring::default_provider().into(),
    )
    .with_safe_default_protocol_versions()?
    .with_root_certificates(roots)
    .with_no_client_auth();
    Ok(Some(Connector::Rustls(Arc::new(tls))))
}

/// Бесконечный цикл: соединение -> сессия -> backoff -> повтор.
pub async fn run(cfg: Config) -> Result<()> {
    let mut backoff = Duration::from_secs(2);
    loop {
        let started = std::time::Instant::now();
        if let Err(e) = session(&cfg).await {
            warn!(error = %format!("{e:#}"), "session ended with error");
        } else {
            info!("session ended");
        }
        // успешная сессия дольше минуты сбрасывает backoff
        if started.elapsed() > Duration::from_secs(60) {
            backoff = Duration::from_secs(2);
        }
        warn!(?backoff, "reconnecting");
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

async fn session(cfg: &Config) -> Result<()> {
    let session_cancel = CancellationToken::new();
    let docker = DockerClient::new(&cfg.docker_socket);
    let sampler = Arc::new(Mutex::new(MetricsSampler::new()));

    let mut request = cfg.relay_url.as_str().into_client_request()?;
    request.headers_mut().insert(
        "Authorization",
        HeaderValue::from_str(&format!("Bearer {}", cfg.token))?,
    );

    let (ws, _resp) = connect_async_tls_with_config(request, None, false, tls_connector(cfg)?)
        .await
        .with_context(|| format!("connect to {}", cfg.relay_url))?;
    info!("connected to relay");
    let (mut sink, mut incoming) = ws.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();

    let writer: JoinHandle<()> = tokio::spawn(async move {
        while let Some(bytes) = rx.recv().await {
            if sink.send(WsMessage::Binary(bytes)).await.is_err() {
                break;
            }
        }
    });

    // Hello: статическая информация о хосте
    let hello = {
        let s = sampler.clone();
        tokio::task::spawn_blocking(move || s.lock().unwrap().host_info())
            .await
            .unwrap()
    };
    send(
        &tx,
        Msg {
            id: next_id(),
            target: String::new(),
            payload: Some(Payload::Hello(hello)),
        },
    )?;

    // Метрики: sampler блокируется на ~200 мс на тик, поэтому в spawn_blocking
    let metrics_task = {
        let tx = tx.clone();
        let cancel = session_cancel.clone();
        let interval = Duration::from_secs(cfg.metrics_interval_secs);
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            while !cancel.is_cancelled() {
                ticker.tick().await;
                let report = {
                    let s = sampler.clone();
                    tokio::task::spawn_blocking(move || s.lock().unwrap().collect())
                        .await
                        .unwrap()
                };
                if send(
                    &tx,
                    Msg {
                        id: next_id(),
                        target: String::new(),
                        payload: Some(Payload::Metrics(report)),
                    },
                )
                .is_err()
                {
                    break;
                }
            }
        })
    };

    // Список контейнеров по таймеру (MVP: периодический snapshot; потом
    // можно инвалидировать по событиям docker)
    let containers_task = {
        let tx = tx.clone();
        let cancel = session_cancel.clone();
        let docker = docker.clone();
        let interval = Duration::from_secs(cfg.containers_interval_secs);
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            ticker.tick().await; // первый тик сразу
            while !cancel.is_cancelled() {
                if let Ok(mut list) = docker.list_containers().await {
                    // статистика для запущенных контейнеров (лимит, чтобы не душить docker)
                    for c in list
                        .iter_mut()
                        .filter(|c| c.state == agent_core::pb::ContainerState::Running as i32)
                        .take(20)
                    {
                        if let Ok(st) = docker.stats(&c.id).await {
                            c.cpu_percent = st.cpu_percent;
                            c.mem_used = st.mem_used;
                            c.mem_limit = st.mem_limit;
                        }
                    }
                    let _ = send(
                        &tx,
                        Msg {
                            id: next_id(),
                            target: String::new(),
                            payload: Some(Payload::ContainerList(ContainerList {
                                containers: list,
                            })),
                        },
                    );
                }
                tokio::select! {
                    _ = ticker.tick() => {}
                    _ = cancel.cancelled() => break,
                }
            }
        })
    };

    // События docker -> алерты (event-driven, без polling)
    let events_task = {
        let tx = tx.clone();
        let cancel = session_cancel.clone();
        let docker = docker.clone();
        tokio::spawn(async move {
            let Ok(events) = docker.events().await else {
                warn!("docker events stream unavailable");
                return;
            };
            tokio::pin!(events);
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => break,
                    item = events.next() => {
                        match item {
                            Some(Ok(ev)) => {
                                let _ = send(&tx, Msg { id: next_id(), target: String::new(), payload: Some(Payload::EventAlert(ev)) });
                            }
                            Some(Err(e)) => warn!(error = %format!("{e:#}"), "docker events error"),
                            None => break,
                        }
                    }
                }
            }
        })
    };

    // Аппаратный пинг релею
    let ping_task = {
        let tx = tx.clone();
        let cancel = session_cancel.clone();
        let mut ticker = tokio::time::interval(PING_INTERVAL);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut seq = 0u64;
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => break,
                    _ = ticker.tick() => {
                        seq += 1;
                        if send(&tx, Msg { id: next_id(), target: String::new(), payload: Some(Payload::Ping(Ping { seq })) }).is_err() {
                            break;
                        }
                    }
                }
            }
        })
    };

    // Активные стримы логов: stream_id -> токен отмены
    let mut log_streams: HashMap<String, CancellationToken> = HashMap::new();

    // если релей молчит дольше 2 минут (мы шлем Ping каждые 30с) —
    // соединение считаем мертвым и переподключаемся
    const IDLE_TIMEOUT: Duration = Duration::from_secs(120);

    let mut session_err: Option<anyhow::Error> = None;
    loop {
        let next = tokio::time::timeout(IDLE_TIMEOUT, incoming.next());
        let msg = tokio::select! {
            _ = session_cancel.cancelled() => break,
            item = next => match item {
                Ok(Some(Ok(m))) => m,
                Ok(Some(Err(e))) => { session_err = Some(e.into()); break; }
                Ok(None) => break,
                Err(_elapsed) => {
                    warn!("relay idle timeout, dropping connection");
                    break;
                }
            },
        };
        let WsMessage::Binary(bytes) = msg else {
            continue;
        };
        let Ok(msg) = Msg::decode(bytes.as_slice()) else {
            warn!("undecodable message from relay");
            continue;
        };
        match msg.payload {
            Some(Payload::HelloAck(ack)) => {
                info!(agent_id = %ack.agent_id, relay = %ack.relay_version, "registered with relay")
            }
            Some(Payload::Ping(p)) => {
                let _ = send(
                    &tx,
                    Msg {
                        id: msg.id,
                        target: String::new(),
                        payload: Some(Payload::Pong(Pong { seq: p.seq })),
                    },
                );
            }
            Some(Payload::ContainerAction(action)) => {
                handle_action(&tx, &docker, msg.id, action);
            }
            Some(Payload::LogsRequest(req)) => {
                // новый запрос с тем же stream_id заменяет активный стрим
                if let Some(old) = log_streams.remove(&req.stream_id) {
                    old.cancel();
                }
                let token = session_cancel.child_token();
                log_streams.insert(req.stream_id.clone(), token.clone());
                spawn_log_stream(tx.clone(), docker.clone(), req, token);
            }
            Some(Payload::Pong(_)) => {}
            Some(other) => warn!(?other, "unexpected payload from relay"),
            None => {}
        }
    }

    session_cancel.cancel();
    // Прерываем и заблокированные сетевые операции; живой tx не должен
    // удерживать writer в rx.recv() при завершении сессии.
    writer.abort();
    metrics_task.abort();
    containers_task.abort();
    events_task.abort();
    ping_task.abort();
    let _ = tokio::join!(
        writer,
        metrics_task,
        containers_task,
        events_task,
        ping_task
    );
    log_streams.clear();

    match session_err {
        Some(e) => Err(e),
        None => Ok(()),
    }
}

fn handle_action(tx: &OutboundTx, docker: &DockerClient, request_id: u64, action: ContainerAction) {
    let tx = tx.clone();
    let docker = docker.clone();
    tokio::spawn(async move {
        let Ok(kind) = agent_core::pb::ContainerActionKind::try_from(action.kind) else {
            return;
        };
        let (ok, error) = match docker.action(&action.container_id, kind).await {
            Ok(()) => (true, String::new()),
            Err(e) => (false, format!("{e:#}")),
        };
        let _ = send(
            &tx,
            Msg {
                id: request_id,
                target: String::new(),
                payload: Some(Payload::ContainerActionResult(ContainerActionResult {
                    container_id: action.container_id,
                    kind: action.kind,
                    ok,
                    error,
                })),
            },
        );
    });
}

fn spawn_log_stream(
    tx: OutboundTx,
    docker: DockerClient,
    req: agent_core::pb::LogsRequest,
    cancel: CancellationToken,
) {
    tokio::spawn(async move {
        let stream_id = req.stream_id.clone();
        let container_id = req.container_id.clone();
        let logs = match docker
            .logs(&container_id, req.tail_lines.max(1), req.follow)
            .await
        {
            Ok(l) => l,
            Err(e) => {
                warn!(error = %format!("{e:#}"), "logs request failed");
                let _ = send(
                    &tx,
                    Msg {
                        id: next_id(),
                        target: String::new(),
                        payload: Some(Payload::LogsChunk(LogsChunk {
                            container_id,
                            stream_id,
                            lines: vec![],
                            eof: true,
                        })),
                    },
                );
                return;
            }
        };
        forward_logs(tx, logs, container_id, stream_id, cancel).await;
    });
}

async fn forward_logs(
    tx: OutboundTx,
    logs: impl futures_util::Stream<Item = Result<agent_core::pb::LogLine>>,
    container_id: String,
    stream_id: String,
    cancel: CancellationToken,
) {
    tokio::pin!(logs);
    let mut buffer: Vec<agent_core::pb::LogLine> = Vec::new();
    let mut flush = tokio::time::interval(Duration::from_millis(250));
    flush.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {
            _ = cancel.cancelled() => break,
            _ = flush.tick() => {
                if !buffer.is_empty() && send(&tx, Msg {
                    id: next_id(),
                    target: String::new(),
                    payload: Some(Payload::LogsChunk(LogsChunk {
                        container_id: container_id.clone(),
                        stream_id: stream_id.clone(),
                        lines: std::mem::take(&mut buffer),
                        eof: false,
                    })),
                }).is_err() {
                    break;
                }
            }
            item = logs.next() => match item {
                Some(Ok(line)) => {
                    buffer.push(line);
                    if buffer.len() >= MAX_STREAM_CHUNK_LINES {
                        let lines = std::mem::take(&mut buffer);
                        if send(&tx, Msg {
                            id: next_id(),
                            target: String::new(),
                            payload: Some(Payload::LogsChunk(LogsChunk {
                                container_id: container_id.clone(),
                                stream_id: stream_id.clone(),
                                lines,
                                eof: false,
                            })),
                        }).is_err() { break; }
                    }
                }
                Some(Err(e)) => {
                    warn!(error = %format!("{e:#}"), "log stream error");
                    break;
                }
                None => {
                    let _ = send(&tx, Msg {
                        id: next_id(),
                        target: String::new(),
                        payload: Some(Payload::LogsChunk(LogsChunk {
                            container_id,
                            stream_id,
                            lines: buffer,
                            eof: true,
                        })),
                    });
                    break;
                }
            },
        }
    }
}

fn send(tx: &OutboundTx, msg: Msg) -> Result<()> {
    let mut buf = Vec::with_capacity(msg.encoded_len());
    msg.encode(&mut buf)?;
    tx.send(buf)
        .map_err(|_| anyhow::anyhow!("outbound channel closed"))
}

fn next_id() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    COUNTER.fetch_add(1, Ordering::Relaxed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn action_result_preserves_request_id() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let docker = DockerClient::new("/nonexistent/pulse-test.sock");
        handle_action(
            &tx,
            &docker,
            987654,
            ContainerAction {
                container_id: "test".into(),
                kind: agent_core::pb::ContainerActionKind::ActionUnspecified as i32,
            },
        );
        let bytes = tokio::time::timeout(Duration::from_secs(2), rx.recv())
            .await
            .unwrap()
            .unwrap();
        let msg = Msg::decode(bytes.as_slice()).unwrap();
        assert_eq!(msg.id, 987654);
        assert!(matches!(msg.payload, Some(Payload::ContainerActionResult(r)) if !r.ok));
    }

    #[tokio::test]
    async fn sparse_follow_logs_are_flushed_before_eof() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let logs = futures_util::stream::iter(vec![Ok(agent_core::pb::LogLine {
            text: "one line".into(),
            ..Default::default()
        })])
        .chain(futures_util::stream::pending());
        let cancel = CancellationToken::new();
        let task = tokio::spawn(forward_logs(
            tx,
            logs,
            "c".into(),
            "s".into(),
            cancel.clone(),
        ));
        let bytes = tokio::time::timeout(Duration::from_secs(2), rx.recv())
            .await
            .unwrap()
            .unwrap();
        let msg = Msg::decode(bytes.as_slice()).unwrap();
        assert!(
            matches!(msg.payload, Some(Payload::LogsChunk(c)) if c.lines.len() == 1 && c.lines[0].text == "one line" && !c.eof)
        );
        cancel.cancel();
        task.await.unwrap();
    }

    #[tokio::test]
    async fn session_sends_heartbeat_and_exits_after_peer_closes() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let mut ws = tokio_tungstenite::accept_async(tcp).await.unwrap();
            while let Some(Ok(frame)) = ws.next().await {
                if let WsMessage::Binary(bytes) = frame {
                    let msg = Msg::decode(bytes.as_slice()).unwrap();
                    if matches!(msg.payload, Some(Payload::Ping(_))) {
                        ws.close(None).await.unwrap();
                        return;
                    }
                }
            }
            panic!("agent disconnected before heartbeat");
        });
        let cfg = Config {
            relay_url: format!("ws://{addr}/ws/agent"),
            token: "test".into(),
            docker_socket: "/nonexistent/pulse-test.sock".into(),
            metrics_interval_secs: 5,
            containers_interval_secs: 10,
            ca_cert: None,
        };
        tokio::time::timeout(Duration::from_secs(5), session(&cfg))
            .await
            .unwrap()
            .unwrap();
        peer.await.unwrap();
    }
}
