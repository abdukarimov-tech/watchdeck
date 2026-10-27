//! agent-core: метрики хоста и Docker-клиент для агента Pulse.
//!
//! Библиотека намеренно не знает про транспорт — только сбор данных
//! и парсинг, чтобы всё можно было покрыть юнит-тестами.

pub mod docker;
pub mod metrics;

/// Сгенерированный prost-код из `proto/pulse.proto`.
pub mod pb {
    include!(concat!(env!("OUT_DIR"), "/pulse.v1.rs"));
}

pub use docker::DockerClient;
pub use metrics::MetricsSampler;
pub use pb::*;

pub const AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");
