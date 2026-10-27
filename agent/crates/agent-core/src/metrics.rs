//! Сбор метрик хоста через sysinfo.
//!
//! Сэмплер создается один раз и живет все время работы агента: sysinfo
//! считает CPU-usage и сетевые скорости как дельту между двумя refresh,
//! поэтому разовые вызовы не имеют смысла.

use std::collections::HashMap;
use std::time::Instant;

use sysinfo::{Networks, System};

use crate::pb::{DiskStat, MetricsReport, NetStat};

pub struct MetricsSampler {
    sys: System,
    networks: Networks,
    last_net_refresh: Option<Instant>,
    last_net_counters: HashMap<String, (u64, u64)>,
}

impl MetricsSampler {
    pub fn new() -> Self {
        let mut sys = System::new();
        sys.refresh_memory();
        // Первый refresh_cpu нужен sysinfo как база для следующей дельты.
        sys.refresh_cpu();
        let networks = Networks::new_with_refreshed_list();
        let last_net_counters = counters(&networks);
        Self {
            sys,
            networks,
            last_net_refresh: None,
            last_net_counters,
        }
    }

    /// Статическая информация о хосте для `Hello` (не меняется между вызовами).
    pub fn host_info(&self) -> crate::pb::Hello {
        crate::pb::Hello {
            agent_version: crate::AGENT_VERSION.into(),
            hostname: System::host_name().unwrap_or_default(),
            os: System::long_os_version().unwrap_or_default(),
            kernel: System::kernel_version().unwrap_or_default(),
            cpu_cores: self.sys.cpus().len() as u32,
            total_memory: self.sys.total_memory(),
        }
    }

    /// Собирает срез метрик. Блокируется на ~250 мс: sysinfo требует два
    /// refresh_cpu с интервалом, чтобы посчитать загрузку.
    pub fn collect(&mut self) -> MetricsReport {
        self.sys.refresh_memory();
        self.sys.refresh_cpu();
        std::thread::sleep(sysinfo::MINIMUM_CPU_UPDATE_INTERVAL);
        self.sys.refresh_cpu();

        self.networks.refresh_list();
        self.networks.refresh();
        let elapsed = self
            .last_net_refresh
            .map(|t| t.elapsed().as_secs_f64())
            .filter(|s| *s > 0.0);
        let prev = std::mem::replace(&mut self.last_net_counters, counters(&self.networks));
        self.last_net_refresh = Some(Instant::now());

        let (rx, tx) = net_rates(&self.networks, &prev, elapsed);
        let load = System::load_average();

        let disks = sysinfo::Disks::new_with_refreshed_list()
            .list()
            .iter()
            // фильтруем виртуальные ФС (snap/loop/docker overlay) — шум для пользователя
            .filter(|d| {
                !d.mount_point()
                    .as_os_str()
                    .to_string_lossy()
                    .starts_with("/snap")
            })
            .map(|d| DiskStat {
                mount: d.mount_point().to_string_lossy().into_owned(),
                total: d.total_space(),
                used: d.total_space().saturating_sub(d.available_space()),
            })
            .collect();

        MetricsReport {
            cpu_usage_percent: self.sys.global_cpu_info().cpu_usage() as f64,
            cpu_cores: self.sys.cpus().len() as u32,
            load_avg: vec![load.one, load.five, load.fifteen],
            mem_total: self.sys.total_memory(),
            mem_used: self.sys.used_memory(),
            swap_total: self.sys.total_swap(),
            swap_used: self.sys.used_swap(),
            uptime_seconds: System::uptime(),
            disks,
            net: Some(NetStat {
                rx_bytes_per_sec: rx,
                tx_bytes_per_sec: tx,
            }),
            collected_at_unix: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or_default(),
        }
    }
}

impl Default for MetricsSampler {
    fn default() -> Self {
        Self::new()
    }
}

fn counters(networks: &Networks) -> HashMap<String, (u64, u64)> {
    networks
        .iter()
        .map(|(name, data)| {
            (
                name.clone(),
                (data.total_received(), data.total_transmitted()),
            )
        })
        .collect()
}

fn net_rates(
    networks: &Networks,
    prev: &HashMap<String, (u64, u64)>,
    elapsed: Option<f64>,
) -> (u64, u64) {
    let Some(elapsed) = elapsed else {
        return (0, 0); // первый вызов — базы для дельты еще нет
    };
    let mut rx = 0u64;
    let mut tx = 0u64;
    for (name, data) in networks.iter() {
        // lo-интерфейс не интересен; остальное суммируем
        if name == "lo" {
            continue;
        }
        if let Some((prev_rx, prev_tx)) = prev.get(name) {
            rx += data.total_received().saturating_sub(*prev_rx);
            tx += data.total_transmitted().saturating_sub(*prev_tx);
        }
    }
    ((rx as f64 / elapsed) as u64, (tx as f64 / elapsed) as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collect_returns_sane_metrics() {
        let mut s = MetricsSampler::new();
        let m = s.collect();
        assert!(m.cpu_usage_percent >= 0.0 && m.cpu_usage_percent <= 100.0);
        assert!(m.cpu_cores >= 1);
        assert!(m.mem_total > 0);
        assert!(m.mem_used <= m.mem_total);
        assert_eq!(m.load_avg.len(), 3);
        // диски и память присутствуют на любой машине
        assert!(!m.disks.is_empty());
    }

    #[test]
    fn first_collect_has_zero_net_rates() {
        let mut s = MetricsSampler::new();
        let m = s.collect();
        let net = m.net.unwrap();
        assert_eq!(net.rx_bytes_per_sec, 0);
        assert_eq!(net.tx_bytes_per_sec, 0);
    }

    #[test]
    fn host_info_is_filled() {
        let s = MetricsSampler::new();
        let h = s.host_info();
        assert!(!h.hostname.is_empty());
        assert!(h.cpu_cores >= 1);
        assert!(h.total_memory > 0);
        assert_eq!(h.agent_version, crate::AGENT_VERSION);
    }
}
