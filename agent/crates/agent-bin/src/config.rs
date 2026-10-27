//! Конфиг агента: /etc/pulse-agent/config.toml (или путь из argv).

use std::path::PathBuf;

use anyhow::{Context, Result};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct Config {
    pub relay_url: String,
    pub token: String,
    #[serde(default = "default_socket")]
    pub docker_socket: PathBuf,
    #[serde(default = "default_metrics_interval")]
    pub metrics_interval_secs: u64,
    #[serde(default = "default_containers_interval")]
    pub containers_interval_secs: u64,
    /// PEM самоподписанного сертификата релея — для wss без публичного CA
    #[serde(default)]
    pub ca_cert: Option<String>,
}

fn default_socket() -> PathBuf {
    "/var/run/docker.sock".into()
}

fn default_metrics_interval() -> u64 {
    5
}

fn default_containers_interval() -> u64 {
    10
}

impl Config {
    /// Порядок приоритета: env (PULSE_RELAY_URL/PULSE_TOKEN) > файл конфига.
    /// Файл не обязателен, если заданы обе переменные окружения — так удобнее
    /// для контейнерного запуска (docker-compose).
    pub fn load(path: Option<PathBuf>) -> Result<Self> {
        let path = path.unwrap_or_else(|| PathBuf::from("/etc/pulse-agent/config.toml"));
        let cfg = match std::fs::read_to_string(&path) {
            Ok(raw) => toml::from_str::<Config>(&raw).context("config parse error")?,
            Err(_) if env_relay_set() => Self {
                relay_url: String::new(),
                token: String::new(),
                docker_socket: default_socket(),
                metrics_interval_secs: default_metrics_interval(),
                containers_interval_secs: default_containers_interval(),
                ca_cert: None,
            },
            Err(_) => {
                anyhow::bail!(
                    "config not found: {}\n\u{1b}[1mПодсказка:\u{1b}[0m создайте файл вида\n  relay_url = \"wss://relay.example.com/ws/agent\"\n  token = \"<токен агента>\"\nили задайте PULSE_RELAY_URL и PULSE_TOKEN",
                    path.display()
                );
            }
        };
        let cfg = Config {
            relay_url: std::env::var("PULSE_RELAY_URL").unwrap_or(cfg.relay_url),
            token: std::env::var("PULSE_TOKEN").unwrap_or(cfg.token),
            ..cfg
        };
        if !cfg.relay_url.starts_with("wss://") && !cfg.relay_url.starts_with("ws://") {
            anyhow::bail!("relay_url must start with wss:// (or ws:// for local dev)");
        }
        if cfg.token.is_empty() {
            anyhow::bail!("token must not be empty (config file or PULSE_TOKEN)");
        }
        Ok(cfg)
    }
}

fn env_relay_set() -> bool {
    std::env::var("PULSE_RELAY_URL").is_ok() && std::env::var("PULSE_TOKEN").is_ok()
}
