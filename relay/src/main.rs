//! pulse-relay: маршрутизатор между агентами и клиентами + FCM-пуши.
//!
//! Релей видит только зашифрованный (TLS) трафик и метаданные маршрутизации;
//! в roadmap — E2E-шифрование payload.

mod api;
mod apns;
mod db;
mod fcm;
mod monitoring;
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
    // см. Cargo.toml: два бэкенда rustls в дереве — выбираем ring явно
    rustls::crypto::ring::default_provider()
        .install_default()
        .expect("failed to install rustls CryptoProvider");

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

    // TLS включается переменными PULSE_TLS_CERT/PULSE_TLS_KEY (самоподписанный
    // сертификат генерирует установщик); без них — обычный HTTP.
    let tls_cert = std::env::var("PULSE_TLS_CERT")
        .ok()
        .filter(|s| !s.is_empty());
    let tls_key = std::env::var("PULSE_TLS_KEY")
        .ok()
        .filter(|s| !s.is_empty());
    match (tls_cert, tls_key) {
        (Some(cert), Some(key)) => {
            let config = axum_server::tls_rustls::RustlsConfig::from_pem_file(cert, key).await?;
            tracing::info!(%bind, version = RELAY_VERSION, "pulse-relay listening (TLS)");
            axum_server::bind_rustls(bind, config)
                .serve(app.into_make_service())
                .await?;
        }
        _ => {
            tracing::info!(%bind, version = RELAY_VERSION, "pulse-relay listening (no TLS)");
            let listener = tokio::net::TcpListener::bind(bind).await?;
            axum::serve(listener, app).await?;
        }
    }
    Ok(())
}
