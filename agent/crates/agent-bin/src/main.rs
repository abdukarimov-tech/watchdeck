//! pulse-agent: легковесный агент мониторинга VPS/Docker.

mod config;
mod transport;

use anyhow::Result;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config_path = std::env::args().nth(1).map(std::path::PathBuf::from);
    let cfg = config::Config::load(config_path)?;
    tracing::info!(relay = %cfg.relay_url, "pulse-agent {} starting", agent_core::AGENT_VERSION);

    transport::run(cfg).await
}
