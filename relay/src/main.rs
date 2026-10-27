//! pulse-relay: маршрутизатор между агентами и клиентами + FCM-пуши.
//!
//! Релей видит только зашифрованный (TLS) трафик и метаданные маршрутизации;
//! в roadmap — E2E-шифрование payload.

mod api;
mod db;
mod fcm;
mod state;
mod ws;

use std::net::SocketAddr;

use anyhow::Result;
use tracing_subscriber::EnvFilter;

pub mod pb {
    include!(concat!(env!("OUT_DIR"), "/pulse.v1.rs"));
}

pub const RELAY_VERSION: &str = env!("CARGO_PKG_VERSION");

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let bind: SocketAddr = std::env::var("PULSE_BIND")
        .unwrap_or_else(|_| "0.0.0.0:8080".into())
        .parse()?;
    let admin_token = std::env::var("PULSE_ADMIN_TOKEN")
        .expect("PULSE_ADMIN_TOKEN must be set (token for /api/* endpoints)");

    let db_path = std::env::var("PULSE_DB").unwrap_or_else(|_| "relay.db".into());
    let database = db::Db::open(&db_path)?;
    database.migrate()?;

    let fcm = fcm::FcmSender::from_env();

    let state = state::AppState::new(admin_token, database, fcm);
    let app = api::router(state);

    tracing::info!(%bind, version = RELAY_VERSION, "pulse-relay listening");
    let listener = tokio::net::TcpListener::bind(bind).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
