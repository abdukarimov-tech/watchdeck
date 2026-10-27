//! Docker-клиент поверх unix-сокета (/var/run/docker.sock) на hyper + hyperlocal.
//!
//! Все ответы Docker API парсятся чистыми функциями (`parse_containers_json`,
//! `LogDemuxer`, `parse_event_json`) — они покрыты юнит-тестами на реальных
//! примерах ответов Docker Engine API v1.43.

use std::path::{Path, PathBuf};

use anyhow::{anyhow, Context, Result};
use futures_util::stream::Stream;
use futures_util::StreamExt;
use hyper::header::HOST;
use hyper::{Body, Client, Method, Request};
use hyperlocal::{UnixConnector, Uri};

use crate::pb::{Container, ContainerActionKind, ContainerState, EventAlert, EventKind, LogLine};

const API_PREFIX: &str = "/v1.43";

#[derive(Clone)]
pub struct DockerClient {
    socket: PathBuf,
    client: Client<UnixConnector, Body>,
}

impl DockerClient {
    pub fn new(socket: impl AsRef<Path>) -> Self {
        let socket = socket.as_ref().to_path_buf();
        let client = Client::builder().build::<UnixConnector, Body>(UnixConnector);
        Self { socket, client }
    }

    async fn request(&self, method: Method, path: &str) -> Result<hyper::Response<Body>> {
        let uri: hyper::Uri = Uri::new(&self.socket, &format!("{API_PREFIX}{path}")).into();
        let req = Request::builder()
            .method(method)
            .uri(uri)
            .header(HOST, "docker")
            .body(Body::empty())?;
        Ok(self.client.request(req).await?)
    }

    pub async fn list_containers(&self) -> Result<Vec<Container>> {
        let res = self
            .request(Method::GET, "/containers/json?all=1")
            .await
            .context("docker: list containers")?;
        let body = hyper::body::to_bytes(res.into_body()).await?;
        parse_containers_json(std::str::from_utf8(&body)?)
    }

    /// start/stop/restart контейнера. Ответ 204 = успех, 304 = уже в нужном
    /// состоянии (считаем успехом), остальное — ошибка с телом ответа.
    pub async fn action(&self, id: &str, kind: ContainerActionKind) -> Result<()> {
        let path = match kind {
            ContainerActionKind::ActionStart => format!("/containers/{id}/start"),
            ContainerActionKind::ActionStop => format!("/containers/{id}/stop"),
            ContainerActionKind::ActionRestart => format!("/containers/{id}/restart"),
            ContainerActionKind::ActionUnspecified => {
                return Err(anyhow!("docker: action not specified"))
            }
        };
        let res = self
            .request(Method::POST, &path)
            .await
            .context("docker: container action")?;
        match res.status().as_u16() {
            200..=299 | 304 => Ok(()),
            status => {
                let body = hyper::body::to_bytes(res.into_body()).await?;
                Err(anyhow!(
                    "docker: action failed ({status}): {}",
                    String::from_utf8_lossy(&body)
                ))
            }
        }
    }

    /// Одноразовая статистика контейнера (/stats?stream=false).
    pub async fn stats(&self, id: &str) -> Result<ContainerStats> {
        let res = self
            .request(Method::GET, &format!("/containers/{id}/stats?stream=false"))
            .await
            .context("docker: stats")?;
        let body = hyper::body::to_bytes(res.into_body()).await?;
        let v: serde_json::Value = serde_json::from_slice(&body)?;
        let cpu_delta = v["cpu_stats"]["cpu_usage"]["total_usage"]
            .as_f64()
            .unwrap_or(0.0)
            - v["precpu_stats"]["cpu_usage"]["total_usage"]
                .as_f64()
                .unwrap_or(0.0);
        let sys_delta = v["cpu_stats"]["system_cpu_usage"].as_f64().unwrap_or(0.0)
            - v["precpu_stats"]["system_cpu_usage"]
                .as_f64()
                .unwrap_or(0.0);
        let online = v["cpu_stats"]["online_cpus"].as_f64().unwrap_or(1.0);
        let cpu_percent = if sys_delta > 0.0 {
            (cpu_delta / sys_delta) * online * 100.0
        } else {
            0.0
        };
        Ok(ContainerStats {
            cpu_percent,
            mem_used: v["memory_stats"]["usage"].as_u64().unwrap_or(0),
            mem_limit: v["memory_stats"]["limit"].as_u64().unwrap_or(0),
        })
    }

    /// Стриминг логов контейнера (docker logs с follow).
    pub async fn logs(
        &self,
        id: &str,
        tail_lines: u32,
        follow: bool,
    ) -> Result<impl Stream<Item = Result<LogLine>>> {
        let follow_suffix = if follow { "&follow=1" } else { "" };
        let path = format!(
            "/containers/{id}/logs?stdout=1&stderr=1&timestamps=1&tail={tail_lines}{follow_suffix}",
        );
        let res = self
            .request(Method::GET, &path)
            .await
            .context("docker: logs")?;
        if !res.status().is_success() {
            anyhow::bail!("docker: logs request failed with {}", res.status());
        }
        let demuxer = std::sync::Arc::new(std::sync::Mutex::new(LogDemuxer::default()));
        Ok(res
            .into_body()
            .then(move |chunk| {
                let demuxer = demuxer.clone();
                async move {
                    match chunk {
                        Ok(bytes) => futures_util::stream::iter(
                            demuxer.lock().unwrap().feed(&bytes).into_iter().map(Ok),
                        )
                        .boxed(),
                        Err(e) => {
                            futures_util::stream::iter(vec![Err(anyhow::Error::new(e))]).boxed()
                        }
                    }
                }
            })
            .flatten())
    }

    /// Поток событий Docker (event-driven: агент не опрашивает состояние).
    pub async fn events(&self) -> Result<impl Stream<Item = Result<EventAlert>>> {
        let res = self
            .request(
                Method::GET,
                "/events?filters=%7B%22type%22%3A%5B%22container%22%5D%7D",
            )
            .await
            .context("docker: events")?;
        Ok(res
            .into_body()
            .then(|chunk| async {
                match chunk {
                    Ok(bytes) => {
                        let alerts = std::str::from_utf8(&bytes)
                            .map(|text| {
                                // события приходят по одному JSON на строку;
                                // чанк может содержать несколько событий
                                text.lines()
                                    .filter(|l| !l.trim().is_empty())
                                    .filter_map(|l| parse_event_json(l).transpose())
                                    .collect::<Result<Vec<_>>>()
                            })
                            .unwrap_or_else(|e| Err(e.into()));
                        match alerts {
                            Ok(list) => {
                                futures_util::stream::iter(list.into_iter().map(Ok)).boxed()
                            }
                            Err(e) => futures_util::stream::iter(vec![Err(e)]).boxed(),
                        }
                    }
                    Err(e) => futures_util::stream::iter(vec![Err(anyhow::Error::new(e))]).boxed(),
                }
            })
            .flatten())
    }
}

pub struct ContainerStats {
    pub cpu_percent: f64,
    pub mem_used: u64,
    pub mem_limit: u64,
}

pub fn parse_containers_json(json: &str) -> Result<Vec<Container>> {
    let items: Vec<serde_json::Value> = serde_json::from_str(json)?;
    Ok(items
        .into_iter()
        .map(|v| {
            let names = v["Names"].as_array().cloned().unwrap_or_default();
            let name = names
                .first()
                .and_then(|n| n.as_str())
                .map(|s| s.trim_start_matches('/').to_owned())
                .unwrap_or_default();
            Container {
                id: v["Id"]
                    .as_str()
                    .unwrap_or_default()
                    .chars()
                    .take(12)
                    .collect(),
                name,
                image: v["Image"].as_str().unwrap_or_default().into(),
                state: state_from_str(v["State"].as_str().unwrap_or_default()),
                status: v["Status"].as_str().unwrap_or_default().into(),
                created_unix: v["Created"].as_i64().unwrap_or_default(),
                // статистика заполняется отдельным вызовом /stats
                cpu_percent: 0.0,
                mem_used: 0,
                mem_limit: 0,
            }
        })
        .collect())
}

pub fn state_from_str(state: &str) -> i32 {
    (match state {
        "created" => ContainerState::Created,
        "running" => ContainerState::Running,
        "paused" => ContainerState::Paused,
        "restarting" => ContainerState::Restarting,
        "exited" => ContainerState::Exited,
        "dead" => ContainerState::Dead,
        _ => ContainerState::ContainerUnknown,
    }) as i32
}

/// Демультиплексор docker log stream: ответ мультиплексирован кадрами
/// `[stream_type, 0, 0, 0, size u32 BE][payload]`, кадры могут приходить
/// разорванными по границам чанков.
#[derive(Default)]
pub struct LogDemuxer {
    buf: Vec<u8>,
}

impl LogDemuxer {
    pub fn feed(&mut self, chunk: &[u8]) -> Vec<LogLine> {
        self.buf.extend_from_slice(chunk);
        let mut lines = Vec::new();
        loop {
            if self.buf.len() < 8 {
                break;
            }
            let size =
                u32::from_be_bytes([self.buf[4], self.buf[5], self.buf[6], self.buf[7]]) as usize;
            if self.buf.len() < 8 + size {
                break;
            }
            let is_stderr = self.buf[0] == 2;
            let payload = String::from_utf8_lossy(&self.buf[8..8 + size]).into_owned();
            self.buf.drain(..8 + size);
            for raw in payload.lines() {
                if raw.trim().is_empty() {
                    continue;
                }
                let (ts_unix, text) = parse_log_line(raw);
                lines.push(LogLine {
                    ts_unix,
                    stderr: is_stderr,
                    text,
                });
            }
        }
        lines
    }
}

/// Строка лога с timestamps=1: `2024-01-01T12:00:00.123456789Z actual text`.
pub fn parse_log_line(raw: &str) -> (i64, String) {
    let Some(idx) = raw.find(' ') else {
        return (0, raw.to_owned());
    };
    let (ts, text) = raw.split_at(idx);
    match chrono_like_parse_unix(ts) {
        Some(unix) => (unix, text.trim_start().into()),
        None => (0, raw.to_owned()),
    }
}

/// Мини-парсер ISO-8601 timestamp без внешних зависимостей (дни от эпохи
/// считаем григорианским алгоритмом).
fn chrono_like_parse_unix(ts: &str) -> Option<i64> {
    let (date, time) = ts.split_once('T')?;
    let time = time.trim_end_matches('Z');
    let mut parts = date.split('-');
    let year: i64 = parts.next()?.parse().ok()?;
    let month: i64 = parts.next()?.parse().ok()?;
    let day: i64 = parts.next()?.parse().ok()?;
    let mut tparts = time.split(':');
    let hour: i64 = tparts.next()?.parse().ok()?;
    let min: i64 = tparts.next()?.parse().ok()?;
    // секунды могут быть дробными ("00.123456789") — дробную часть отбрасываем
    let sec_raw = tparts.next().unwrap_or("0");
    let (sec_str, _) = sec_raw.split_once('.').unwrap_or((sec_raw, ""));
    let sec: i64 = sec_str.parse().ok()?;
    // days from civil (Howard Hinnant)
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(days * 86_400 + hour * 3600 + min * 60 + sec)
}

pub fn parse_event_json(json: &str) -> Result<Option<EventAlert>> {
    let v: serde_json::Value = serde_json::from_str(json)?;
    // интересуют только события о жизненном цикле контейнеров
    if v["Type"].as_str() != Some("container") {
        return Ok(None);
    }
    let action = v["Action"].as_str().unwrap_or_default();
    let kind = match action {
        "die" => EventKind::ContainerDied,
        "start" | "restart" => EventKind::ContainerStarted,
        "oom" => EventKind::Oom,
        _ => return Ok(None),
    };
    let attrs = &v["Actor"]["Attributes"];
    let exit_code = attrs["exitCode"].as_str().unwrap_or_default();
    let message = match kind {
        EventKind::ContainerDied if !exit_code.is_empty() => {
            format!("exit code {exit_code}")
        }
        EventKind::Oom => "out of memory".into(),
        _ => String::new(),
    };
    Ok(Some(EventAlert {
        kind: kind as i32,
        container_id: v["Actor"]["ID"]
            .as_str()
            .unwrap_or_default()
            .chars()
            .take(12)
            .collect(),
        container_name: attrs["name"].as_str().unwrap_or_default().into(),
        at_unix: v["time"].as_i64().unwrap_or_default(),
        message,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::ContainerState as S;

    const CONTAINERS_JSON: &str = r#"[
        {"Id":"a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef123456",
         "Names":["/web-nginx"],"Image":"nginx:1.25","State":"running",
         "Status":"Up 3 days (healthy)","Created":1729000000,"Ports":[]},
        {"Id":"ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
         "Names":["/batch-worker"],"Image":"worker:latest","State":"exited",
         "Status":"Exited (137) 2 minutes ago","Created":1729000100,"Ports":[]}
    ]"#;

    #[test]
    fn parse_containers() {
        let list = parse_containers_json(CONTAINERS_JSON).unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "web-nginx");
        assert_eq!(list[0].image, "nginx:1.25");
        assert_eq!(list[0].state, S::Running as i32);
        assert_eq!(list[0].id.len(), 12);
        assert_eq!(list[1].state, S::Exited as i32);
        assert!(list[1].status.contains("137"));
    }

    #[test]
    fn states_map_correctly() {
        assert_eq!(state_from_str("running"), S::Running as i32);
        assert_eq!(state_from_str("paused"), S::Paused as i32);
        assert_eq!(state_from_str("banana"), S::ContainerUnknown as i32);
    }

    #[test]
    fn demuxer_handles_split_frames() {
        let payload = b"2024-01-01T00:00:00.000000000Z hello world\n";
        // кадр stdout: [1,0,0,0][size BE][payload]
        let mut frame = vec![1, 0, 0, 0];
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);

        let mut d = LogDemuxer::default();
        // рвем кадр в произвольном месте — буфер должен склеить
        assert!(d.feed(&frame[..20]).is_empty());
        let lines = d.feed(&frame[20..]);
        assert_eq!(lines.len(), 1);
        assert!(!lines[0].stderr);
        assert_eq!(lines[0].text, "hello world");
        assert_eq!(lines[0].ts_unix, 1704067200);
        assert!(d.feed(&[]).is_empty());
    }

    #[test]
    fn demuxer_distinguishes_stderr() {
        let mut frame = vec![2, 0, 0, 0];
        let payload = b"panic: something broke\n";
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);

        let lines = LogDemuxer::default().feed(&frame);
        assert_eq!(lines.len(), 1);
        assert!(lines[0].stderr);
    }

    #[test]
    fn demuxer_handles_multiple_frames_in_one_chunk() {
        let mut chunk = Vec::new();
        for (ty, text) in [(1u8, &b"one\n"[..]), (2u8, &b"two\n"[..])] {
            chunk.extend_from_slice(&[ty, 0, 0, 0]);
            chunk.extend_from_slice(&(text.len() as u32).to_be_bytes());
            chunk.extend_from_slice(text);
        }
        let lines = LogDemuxer::default().feed(&chunk);
        assert_eq!(lines.len(), 2);
        assert!(!lines[0].stderr);
        assert!(lines[1].stderr);
    }

    #[test]
    fn log_line_without_timestamp_falls_back() {
        let (ts, text) = parse_log_line("just text");
        assert_eq!(ts, 0);
        assert_eq!(text, "just text");
    }

    #[test]
    fn event_die_parsed_with_exit_code() {
        let json = r#"{"status":"die","id":"abc","from":"nginx","Type":"container",
            "Action":"die","Actor":{"ID":"abc123","Attributes":{"name":"web","exitCode":"137"}},
            "scope":"local","time":1729999999,"timeNano":1729999999000000000}"#;
        let ev = parse_event_json(json).unwrap().unwrap();
        assert_eq!(ev.kind, EventKind::ContainerDied as i32);
        assert_eq!(ev.container_name, "web");
        assert_eq!(ev.message, "exit code 137");
        assert_eq!(ev.at_unix, 1729999999);
    }

    #[test]
    fn event_start_and_non_lifecycle() {
        let start = r#"{"Type":"container","Action":"start","Actor":{"ID":"x","Attributes":{"name":"api"}},"time":1}"#;
        assert_eq!(
            parse_event_json(start).unwrap().unwrap().kind,
            EventKind::ContainerStarted as i32
        );

        let volume = r#"{"Type":"volume","Action":"create","Actor":{"ID":"v"},"time":1}"#;
        assert!(parse_event_json(volume).unwrap().is_none());
    }
}
