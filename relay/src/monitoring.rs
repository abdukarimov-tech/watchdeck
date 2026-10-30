//! Persistent incidents, bounded metrics history and per-agent alert policies.
use crate::{
    db::Db,
    pb::{ContainerList, EventAlert, EventKind, MetricsReport},
};
use anyhow::Result;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct AlertPolicy {
    pub enabled: bool,
    pub cpu_percent: f64,
    pub memory_percent: f64,
    pub disk_percent: f64,
    pub sustain_seconds: i64,
    pub restart_count: i64,
    pub maintenance_until: i64,
}
impl Default for AlertPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            cpu_percent: 90.,
            memory_percent: 85.,
            disk_percent: 90.,
            sustain_seconds: 300,
            restart_count: 3,
            maintenance_until: 0,
        }
    }
}
impl AlertPolicy {
    pub fn valid(&self) -> bool {
        [self.cpu_percent, self.memory_percent, self.disk_percent]
            .iter()
            .all(|x| x.is_finite() && (1.0..=100.0).contains(x))
            && (0..=3600).contains(&self.sustain_seconds)
            && (2..=100).contains(&self.restart_count)
            && self.maintenance_until >= 0
    }
}
#[derive(Debug, Serialize, Deserialize)]
pub struct MetricPoint {
    pub at_unix: i64,
    pub cpu_percent: f64,
    pub memory_percent: f64,
    pub disk_percent: f64,
    pub rx_bytes_per_sec: f64,
    pub tx_bytes_per_sec: f64,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Incident {
    pub id: i64,
    pub agent_id: String,
    pub kind: String,
    pub container_id: String,
    pub container_name: String,
    pub message: String,
    pub opened_at: i64,
    pub last_at: i64,
    pub resolved_at: Option<i64>,
    pub occurrences: i64,
}
pub fn migrate(conn: &Connection) -> Result<()> {
    conn.execute_batch("CREATE TABLE IF NOT EXISTS metric_history (
      agent_id TEXT NOT NULL, bucket INTEGER NOT NULL, at_unix INTEGER NOT NULL,
      cpu REAL NOT NULL, memory REAL NOT NULL, disk REAL NOT NULL, rx REAL NOT NULL, tx REAL NOT NULL,
      PRIMARY KEY(agent_id,bucket));
      CREATE INDEX IF NOT EXISTS metric_history_time ON metric_history(at_unix);
      CREATE TABLE IF NOT EXISTS device_subscriptions (fcm_token TEXT NOT NULL,agent_id TEXT NOT NULL,PRIMARY KEY(fcm_token,agent_id));
      INSERT OR IGNORE INTO device_subscriptions SELECT fcm_token,agent_id FROM devices;
      CREATE TABLE IF NOT EXISTS apns_devices (token TEXT NOT NULL, agent_id TEXT NOT NULL, sandbox INTEGER NOT NULL, PRIMARY KEY(token,agent_id,sandbox));
      CREATE TABLE IF NOT EXISTS alert_policies (agent_id TEXT PRIMARY KEY, policy TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS condition_state (agent_id TEXT NOT NULL, key TEXT NOT NULL,
        since INTEGER NOT NULL, last_seen INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(agent_id,key));
      CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL,
        key TEXT NOT NULL, kind TEXT NOT NULL, container_id TEXT NOT NULL, container_name TEXT NOT NULL,
        message TEXT NOT NULL, opened_at INTEGER NOT NULL, last_at INTEGER NOT NULL,
        resolved_at INTEGER, occurrences INTEGER NOT NULL DEFAULT 1);
      CREATE UNIQUE INDEX IF NOT EXISTS incident_active ON incidents(agent_id,key) WHERE resolved_at IS NULL;
      CREATE INDEX IF NOT EXISTS incident_history ON incidents(agent_id,last_at DESC);
      CREATE TABLE IF NOT EXISTS notification_dedupe (agent_id TEXT NOT NULL,key TEXT NOT NULL, at_unix INTEGER NOT NULL,PRIMARY KEY(agent_id,key));
      CREATE TABLE IF NOT EXISTS restart_events (agent_id TEXT NOT NULL, container_id TEXT NOT NULL, at_unix INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS restart_events_time ON restart_events(agent_id,container_id,at_unix);")?;
    Ok(())
}
fn policy(conn: &Connection, agent: &str) -> Result<AlertPolicy> {
    let raw: Option<String> = conn
        .query_row(
            "SELECT policy FROM alert_policies WHERE agent_id=?1",
            [agent],
            |r| r.get(0),
        )
        .optional()?;
    Ok(raw
        .map(|s| serde_json::from_str(&s))
        .transpose()?
        .unwrap_or_default())
}
fn open(
    conn: &Connection,
    agent: &str,
    key: &str,
    kind: &str,
    container: (&str, &str),
    message: &str,
    now: i64,
) -> Result<()> {
    let changed = conn.execute("UPDATE incidents SET kind=?3,message=?4,last_at=?5,occurrences=occurrences+1,container_name=?6 WHERE agent_id=?1 AND key=?2 AND resolved_at IS NULL",
        params![agent,key,kind,message,now,container.1])?;
    if changed == 0 {
        conn.execute("INSERT INTO incidents(agent_id,key,kind,container_id,container_name,message,opened_at,last_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?7)", params![agent,key,kind,container.0,container.1,message,now])?;
    }
    Ok(())
}
fn resolve(conn: &Connection, agent: &str, key: &str, now: i64) -> Result<()> {
    conn.execute("UPDATE incidents SET resolved_at=?3,last_at=?3 WHERE agent_id=?1 AND key=?2 AND resolved_at IS NULL",params![agent,key,now])?;
    Ok(())
}
impl Db {
    pub fn alert_policy(&self, agent: &str) -> Result<AlertPolicy> {
        policy(&self.conn.lock().unwrap(), agent)
    }
    pub fn save_alert_policy(&self, agent: &str, p: &AlertPolicy) -> Result<()> {
        anyhow::ensure!(p.valid(), "invalid alert policy");
        let conn = self.conn.lock().unwrap();
        conn.execute("INSERT INTO alert_policies VALUES(?1,?2) ON CONFLICT(agent_id) DO UPDATE SET policy=excluded.policy",params![agent,serde_json::to_string(p)?])?;
        // Restart pending durations when a rule changes; existing incidents remain visible until recovery.
        conn.execute(
            "DELETE FROM condition_state WHERE agent_id=?1 AND active=0",
            [agent],
        )?;
        Ok(())
    }
    pub fn incidents(&self, agent: &str) -> Result<Vec<Incident>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT id,agent_id,kind,container_id,container_name,message,opened_at,last_at,resolved_at,occurrences FROM incidents WHERE (?1='' OR agent_id=?1) ORDER BY (resolved_at IS NULL) DESC,last_at DESC,id DESC LIMIT 300")?;
        let rows = stmt.query_map([agent], |r| {
            Ok(Incident {
                id: r.get(0)?,
                agent_id: r.get(1)?,
                kind: r.get(2)?,
                container_id: r.get(3)?,
                container_name: r.get(4)?,
                message: r.get(5)?,
                opened_at: r.get(6)?,
                last_at: r.get(7)?,
                resolved_at: r.get(8)?,
                occurrences: r.get(9)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }
    pub fn metric_history(&self, agent: &str, hours: i64, now: i64) -> Result<Vec<MetricPoint>> {
        anyhow::ensure!(matches!(hours, 1 | 24 | 168), "hours must be 1,24,168");
        let step = match hours {
            1 => 60,
            24 => 300,
            _ => 1800,
        };
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT (at_unix/?3)*?3,AVG(cpu),AVG(memory),AVG(disk),AVG(rx),AVG(tx) FROM metric_history WHERE agent_id=?1 AND at_unix>=?2 GROUP BY at_unix/?3 ORDER BY at_unix/?3 LIMIT 1000")?;
        let rows = stmt.query_map(params![agent, now - hours * 3600, step], |r| {
            Ok(MetricPoint {
                at_unix: r.get(0)?,
                cpu_percent: r.get(1)?,
                memory_percent: r.get(2)?,
                disk_percent: r.get(3)?,
                rx_bytes_per_sec: r.get(4)?,
                tx_bytes_per_sec: r.get(5)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }
    pub fn observe_metrics(
        &self,
        agent: &str,
        m: &MetricsReport,
        now: i64,
    ) -> Result<Vec<EventAlert>> {
        let conn = self.conn.lock().unwrap();
        let ratio = |used: u64, total: u64| {
            if total > 0 {
                used as f64 / total as f64 * 100.
            } else {
                0.
            }
        };
        let cpu = if m.cpu_usage_percent.is_finite() {
            m.cpu_usage_percent.clamp(0., 100.)
        } else {
            0.
        };
        let memory = ratio(m.mem_used, m.mem_total).clamp(0., 100.);
        let disk = m
            .disks
            .iter()
            .map(|d| ratio(d.used, d.total))
            .fold(0., f64::max)
            .clamp(0., 100.);
        let (rx, tx) = m
            .net
            .as_ref()
            .map(|n| (n.rx_bytes_per_sec as f64, n.tx_bytes_per_sec as f64))
            .unwrap_or_default();
        conn.execute("INSERT INTO metric_history VALUES(?1,?2,?3,?4,?5,?6,?7,?8) ON CONFLICT(agent_id,bucket) DO UPDATE SET at_unix=excluded.at_unix,cpu=excluded.cpu,memory=excluded.memory,disk=excluded.disk,rx=excluded.rx,tx=excluded.tx",params![agent,now/30,now,cpu,memory,disk,rx,tx])?;
        conn.execute(
            "DELETE FROM metric_history WHERE at_unix<?1",
            [now - 8 * 86400],
        )?;
        let p = policy(&conn, agent)?;
        let mut events = Vec::new();
        for (key, name, value, threshold) in [
            ("cpu", "CPU", cpu, p.cpu_percent),
            ("memory", "RAM", memory, p.memory_percent),
            ("disk", "Disk", disk, p.disk_percent),
        ] {
            let previous: Option<(i64,i64,bool)> = conn.query_row("SELECT since,last_seen,active FROM condition_state WHERE agent_id=?1 AND key=?2",params![agent,key],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
            let (mut since, last, active) = previous.unwrap_or((now, now, false));
            if now - last > 90 && !active {
                since = now;
            }
            let high = p.enabled && value >= threshold;
            if high && since == 0 {
                since = now;
            }
            let next_active = if high {
                active || now - since >= p.sustain_seconds
            } else {
                false
            };
            if next_active != active {
                let message = if next_active {
                    format!("{name}: {value:.1}% (threshold {threshold:.0}%)")
                } else {
                    format!("{name}: recovered ({value:.1}%)")
                };
                if next_active {
                    open(
                        &conn,
                        agent,
                        key,
                        &format!("{}_HIGH", key.to_uppercase()),
                        ("", ""),
                        &message,
                        now,
                    )?;
                } else {
                    resolve(&conn, agent, key, now)?;
                }
                events.push(EventAlert {
                    kind: if next_active {
                        EventKind::ResourceHigh
                    } else {
                        EventKind::ResourceRecovered
                    } as i32,
                    container_id: String::new(),
                    container_name: name.into(),
                    at_unix: now,
                    message,
                });
            }
            if !high {
                since = 0;
            }
            conn.execute("INSERT INTO condition_state VALUES(?1,?2,?3,?4,?5) ON CONFLICT(agent_id,key) DO UPDATE SET since=excluded.since,last_seen=excluded.last_seen,active=excluded.active",params![agent,key,since,now,next_active])?;
        }
        Ok(events)
    }
    pub fn observe_event(&self, agent: &str, ev: &EventAlert, now: i64) -> Result<Vec<EventAlert>> {
        let conn = self.conn.lock().unwrap();
        let mut events = Vec::new();
        let kind = EventKind::try_from(ev.kind).unwrap_or(EventKind::EventUnknown);
        let id = if ev.container_id.is_empty() {
            &ev.container_name
        } else {
            &ev.container_id
        };
        let key = format!("container:{id}");
        match kind {
            EventKind::ServerOffline => open(
                &conn,
                agent,
                "server",
                "SERVER_OFFLINE",
                ("", ""),
                &ev.message,
                now,
            )?,
            EventKind::ServerOnline => resolve(&conn, agent, "server", now)?,
            EventKind::ContainerDied | EventKind::Oom => {
                if kind == EventKind::ContainerDied {
                    conn.execute(
                        "INSERT INTO restart_events VALUES(?1,?2,?3)",
                        params![agent, id, now],
                    )?;
                }
                conn.execute("DELETE FROM restart_events WHERE at_unix<?1", [now - 300])?;
                let count:i64 = conn.query_row("SELECT COUNT(*) FROM restart_events WHERE agent_id=?1 AND container_id=?2 AND at_unix>=?3",params![agent,id,now-300],|r|r.get(0))?;
                let p = policy(&conn, agent)?;
                let label = kind.as_str_name();
                open(
                    &conn,
                    agent,
                    &key,
                    label,
                    (&ev.container_id, &ev.container_name),
                    &ev.message,
                    now,
                )?;
                if count >= p.restart_count {
                    let active: bool = conn.query_row("SELECT EXISTS(SELECT 1 FROM incidents WHERE agent_id=?1 AND key=?2 AND resolved_at IS NULL)", params![agent,format!("restart:{id}")], |r|r.get(0))?;
                    if !active {
                        events.push(EventAlert {
                            kind: EventKind::RestartLoop as i32,
                            container_id: ev.container_id.clone(),
                            container_name: ev.container_name.clone(),
                            message: format!("{count} exits in 5 minutes"),
                            at_unix: now,
                        });
                    }
                    open(
                        &conn,
                        agent,
                        &format!("restart:{id}"),
                        "RESTART_LOOP",
                        (&ev.container_id, &ev.container_name),
                        "Repeated container exits within 5 minutes",
                        now,
                    )?;
                }
            }
            EventKind::ContainerStarted => resolve(&conn, agent, &key, now)?,
            _ => {}
        }
        Ok(events)
    }
    pub fn observe_containers(
        &self,
        agent: &str,
        list: &ContainerList,
        now: i64,
    ) -> Result<Vec<EventAlert>> {
        let conn = self.conn.lock().unwrap();
        let mut events = Vec::new();
        for c in &list.containers {
            let key = format!("health:{}", c.id);
            let active:bool=conn.query_row("SELECT EXISTS(SELECT 1 FROM incidents WHERE agent_id=?1 AND key=?2 AND resolved_at IS NULL)",params![agent,key],|r|r.get(0))?;
            let unhealthy = c.health == "unhealthy";
            if unhealthy && !active {
                open(
                    &conn,
                    agent,
                    &key,
                    "CONTAINER_UNHEALTHY",
                    (&c.id, &c.name),
                    "Docker healthcheck failed",
                    now,
                )?;
            }
            if c.health == "healthy" && active {
                resolve(&conn, agent, &key, now)?;
            }
            if (unhealthy && !active) || (c.health == "healthy" && active) {
                events.push(EventAlert {
                    kind: if unhealthy {
                        EventKind::ContainerUnhealthy
                    } else {
                        EventKind::ResourceRecovered
                    } as i32,
                    container_id: c.id.clone(),
                    container_name: c.name.clone(),
                    message: if unhealthy {
                        "Docker healthcheck failed"
                    } else {
                        "Docker healthcheck recovered"
                    }
                    .into(),
                    at_unix: now,
                });
            }
            // A restart-loop incident recovers after five stable minutes.
            if c.state == crate::pb::ContainerState::Running as i32 {
                resolve(&conn, agent, &format!("container:{}", c.id), now)?;
                let last: Option<i64> = conn.query_row(
                    "SELECT MAX(at_unix) FROM restart_events WHERE agent_id=?1 AND container_id=?2",
                    params![agent, c.id],
                    |r| r.get(0),
                )?;
                if last.is_none_or(|t| now - t >= 300) {
                    resolve(&conn, agent, &format!("restart:{}", c.id), now)?;
                }
            }
        }
        Ok(events)
    }
    pub fn should_notify(&self, agent: &str, key: &str, now: i64) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let p = policy(&conn, agent)?;
        if !p.enabled || p.maintenance_until > now {
            return Ok(false);
        }
        let last: Option<i64> = conn
            .query_row(
                "SELECT at_unix FROM notification_dedupe WHERE agent_id=?1 AND key=?2",
                params![agent, key],
                |r| r.get(0),
            )
            .optional()?;
        if last.is_some_and(|t| now - t < 300) {
            return Ok(false);
        }
        conn.execute("INSERT INTO notification_dedupe VALUES(?1,?2,?3) ON CONFLICT(agent_id,key) DO UPDATE SET at_unix=excluded.at_unix",params![agent,key,now])?;
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn db() -> Db {
        let d = Db::open(":memory:").unwrap();
        d.migrate().unwrap();
        d
    }
    fn metrics(cpu: f64) -> MetricsReport {
        MetricsReport {
            cpu_usage_percent: cpu,
            mem_total: 100,
            mem_used: 20,
            ..Default::default()
        }
    }
    #[test]
    fn migration_preserves_legacy_device_subscriptions() {
        let d = Db::open(":memory:").unwrap();
        d.conn.lock().unwrap().execute_batch("CREATE TABLE devices(fcm_token TEXT PRIMARY KEY,agent_id TEXT NOT NULL,created_unix INTEGER NOT NULL); INSERT INTO devices VALUES('legacy-phone','old-agent',1);").unwrap();
        d.migrate().unwrap();
        d.add_device("legacy-phone", "new-agent").unwrap();
        d.migrate().unwrap();
        assert_eq!(d.device_tokens("old-agent").unwrap(), vec!["legacy-phone"]);
        assert_eq!(d.device_tokens("new-agent").unwrap(), vec!["legacy-phone"]);
    }
    #[test]
    fn history_policies_and_incidents_survive_reopening_database() {
        let path =
            std::env::temp_dir().join(format!("watchdeck-{}.db", crate::db::generate_token()));
        {
            let d = Db::open(path.to_str().unwrap()).unwrap();
            d.migrate().unwrap();
            d.save_alert_policy(
                "a",
                &AlertPolicy {
                    sustain_seconds: 0,
                    ..Default::default()
                },
            )
            .unwrap();
            d.observe_metrics("a", &metrics(99.), 100).unwrap();
        }
        {
            let d = Db::open(path.to_str().unwrap()).unwrap();
            d.migrate().unwrap();
            assert_eq!(d.alert_policy("a").unwrap().sustain_seconds, 0);
            assert_eq!(d.incidents("a").unwrap().len(), 1);
            assert_eq!(d.metric_history("a", 1, 200).unwrap().len(), 1);
        }
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn sustained_threshold_recovers_and_reopens_without_duplicates() {
        let d = db();
        let p = AlertPolicy {
            sustain_seconds: 60,
            ..Default::default()
        };
        d.save_alert_policy("a", &p).unwrap();
        assert!(d
            .observe_metrics("a", &metrics(95.), 100)
            .unwrap()
            .is_empty());
        assert!(d
            .observe_metrics("a", &metrics(95.), 130)
            .unwrap()
            .is_empty());
        assert_eq!(d.observe_metrics("a", &metrics(95.), 160).unwrap().len(), 1);
        assert!(d
            .observe_metrics("a", &metrics(95.), 180)
            .unwrap()
            .is_empty());
        assert_eq!(d.incidents("a").unwrap().len(), 1);
        assert_eq!(d.observe_metrics("a", &metrics(20.), 200).unwrap().len(), 1);
        assert!(d.incidents("a").unwrap()[0].resolved_at.is_some());
        assert!(d
            .observe_metrics("a", &metrics(95.), 210)
            .unwrap()
            .is_empty());
        d.observe_metrics("a", &metrics(95.), 270).unwrap();
        assert_eq!(d.incidents("a").unwrap().len(), 2);
    }
    #[test]
    fn observation_gaps_do_not_count_as_sustained_load() {
        let d = db();
        d.observe_metrics("a", &metrics(99.), 100).unwrap();
        assert!(d
            .observe_metrics("a", &metrics(99.), 1000)
            .unwrap()
            .is_empty());
        assert!(d.incidents("a").unwrap().is_empty());
    }
    #[test]
    fn maintenance_and_notification_deduplication_are_persistent() {
        let d = db();
        let p = AlertPolicy {
            maintenance_until: 500,
            ..Default::default()
        };
        d.save_alert_policy("a", &p).unwrap();
        assert!(!d.should_notify("a", "die:c", 100).unwrap());
        assert!(d.should_notify("a", "die:c", 600).unwrap());
        assert!(!d.should_notify("a", "die:c", 601).unwrap());
        assert!(d.should_notify("a", "die:c", 900).unwrap());
    }
    #[test]
    fn history_is_bounded_downsampled_and_isolated() {
        let d = db();
        for t in (100..3600).step_by(5) {
            d.observe_metrics("a", &metrics(20.), t).unwrap();
        }
        d.observe_metrics("b", &metrics(80.), 3600).unwrap();
        let points = d.metric_history("a", 1, 3600).unwrap();
        assert!(points.len() <= 60);
        assert!(points.iter().all(|p| p.cpu_percent == 20.));
        d.observe_metrics("a", &metrics(30.), 10 * 86400).unwrap();
        assert!(d.metric_history("a", 168, 10 * 86400).unwrap().len() == 1);
    }
    #[test]
    fn server_and_container_incidents_recover_and_restart_loops_survive_short_restarts() {
        let d = db();
        for t in [100, 130, 160] {
            d.observe_event(
                "a",
                &EventAlert {
                    kind: EventKind::ContainerDied as i32,
                    container_id: "c".into(),
                    container_name: "web".into(),
                    ..Default::default()
                },
                t,
            )
            .unwrap();
            d.observe_event(
                "a",
                &EventAlert {
                    kind: EventKind::ContainerStarted as i32,
                    container_id: "c".into(),
                    ..Default::default()
                },
                t + 1,
            )
            .unwrap();
        }
        assert!(d
            .incidents("a")
            .unwrap()
            .iter()
            .any(|i| i.kind == "RESTART_LOOP" && i.resolved_at.is_none()));
        d.observe_event(
            "a",
            &EventAlert {
                kind: EventKind::ServerOffline as i32,
                ..Default::default()
            },
            200,
        )
        .unwrap();
        d.observe_event(
            "a",
            &EventAlert {
                kind: EventKind::ServerOnline as i32,
                ..Default::default()
            },
            220,
        )
        .unwrap();
        assert!(d
            .incidents("a")
            .unwrap()
            .iter()
            .find(|i| i.kind == "SERVER_OFFLINE")
            .unwrap()
            .resolved_at
            .is_some());
    }
    #[test]
    fn device_can_subscribe_to_multiple_servers() {
        let d = db();
        d.add_device("phone", "a").unwrap();
        d.add_device("phone", "b").unwrap();
        assert_eq!(d.device_tokens("a").unwrap().len(), 1);
        assert_eq!(d.device_tokens("b").unwrap().len(), 1);
    }
    #[test]
    fn invalid_policy_rejected() {
        let d = db();
        let p = AlertPolicy {
            cpu_percent: f64::NAN,
            ..Default::default()
        };
        assert!(d.save_alert_policy("a", &p).is_err());
    }
}
