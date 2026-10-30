//! REST API релея: health, выпуск токенов, список агентов, история алертов,
//! регистрация FCM-устройств.

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};

use crate::state::AppState;
use crate::ws::{agent_ws, client_ws};

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/api/incidents", get(incidents))
        .route("/api/agent-ids", get(agent_ids))
        .route("/api/metrics", get(metrics_history))
        .route("/api/alert-policy", get(get_policy).put(put_policy))
        .route("/ws/agent", get(agent_ws))
        .route("/ws/client", get(client_ws))
        .route("/api/agents", get(list_agents))
        .route("/api/tokens", post(create_token).get(list_tokens))
        .route("/api/tokens/revoke", post(revoke_token))
        .route("/api/alerts", get(recent_alerts))
        .route("/api/devices", post(register_device))
        .with_state(state)
}

async fn health() -> &'static str {
    "ok"
}

fn require_admin(st: &AppState, headers: &HeaderMap) -> Result<(), (StatusCode, &'static str)> {
    let token = headers
        .get("X-Admin-Token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    if token == st.admin_token {
        Ok(())
    } else {
        Err((StatusCode::UNAUTHORIZED, "admin token required"))
    }
}

fn require_role(
    st: &AppState,
    headers: &HeaderMap,
    role: &str,
) -> Result<(), (StatusCode, &'static str)> {
    let token = headers
        .get("Authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .unwrap_or_default();
    match st.db.verify_token(token) {
        Some((_, r)) if r == role => Ok(()),
        _ => Err((StatusCode::UNAUTHORIZED, "invalid token")),
    }
}

#[derive(Serialize)]
struct AgentRow {
    agent_id: String,
    hostname: String,
    agent_version: String,
}

async fn list_agents(
    State(st): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<AgentRow>>, (StatusCode, String)> {
    // список серверов нужен клиентскому приложению; админ-токен тоже принимаем
    if require_role(&st, &headers, "client").is_err() {
        require_admin(&st, &headers).map_err(|(code, msg)| (code, msg.into()))?;
    }
    Ok(Json(
        st.connected_agents()
            .into_iter()
            .map(|(agent_id, hostname, agent_version)| AgentRow {
                agent_id,
                hostname,
                agent_version,
            })
            .collect(),
    ))
}

#[derive(Deserialize)]
struct CreateTokenReq {
    label: String,
    /// "agent" | "client"
    role: String,
}

#[derive(Serialize)]
struct CreateTokenRes {
    token: String,
    label: String,
    role: String,
}

async fn create_token(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<CreateTokenReq>,
) -> Result<Json<CreateTokenRes>, (StatusCode, String)> {
    require_admin(&st, &headers).map_err(|(code, msg)| (code, msg.into()))?;
    if req.label.trim().is_empty() || !matches!(req.role.as_str(), "agent" | "client") {
        return Err((
            StatusCode::BAD_REQUEST,
            "label required, role must be agent|client".into(),
        ));
    }
    let t = st
        .db
        .create_token(&req.role, req.label.trim())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(CreateTokenRes {
        token: t.token,
        label: t.label,
        role: t.role,
    }))
}

#[derive(Serialize)]
struct AlertRow {
    agent_id: String,
    kind: String,
    container_name: String,
    message: String,
    at_unix: i64,
}

async fn recent_alerts(
    State(st): State<AppState>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> Result<Json<Vec<AlertRow>>, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(code, msg)| (code, msg.into()))?;
    let agent_id = q.get("agent_id").cloned().unwrap_or_default();
    let limit = q
        .get("limit")
        .and_then(|l| l.parse::<i64>().ok())
        .unwrap_or(50)
        .min(500);
    let rows = st
        .db
        .recent_alerts(&agent_id, limit)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(
        rows.into_iter()
            .map(|a| AlertRow {
                agent_id: a.agent_id,
                kind: a.kind,
                container_name: a.container_name,
                message: a.message,
                at_unix: a.at_unix,
            })
            .collect(),
    ))
}

#[derive(Deserialize)]
struct RegisterDeviceReq {
    #[serde(default)]
    fcm_token: String,
    #[serde(default)]
    apns_token: String,
    #[serde(default)]
    sandbox: bool,
    agent_id: String,
}

async fn register_device(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<RegisterDeviceReq>,
) -> Result<StatusCode, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(code, msg)| (code, msg.into()))?;
    if req.agent_id.trim().is_empty() || req.fcm_token.is_empty() == req.apns_token.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "agent_id and exactly one push token required".into(),
        ));
    }
    if !req.apns_token.is_empty() {
        if !(32..=512).contains(&req.apns_token.len())
            || !req.apns_token.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err((StatusCode::BAD_REQUEST, "invalid APNs token".into()));
        }
        st.db
            .add_apns_device(&req.apns_token, &req.agent_id, req.sandbox)
            .map_err(internal_error)?;
    } else {
        if req.fcm_token.len() > 4096 {
            return Err((StatusCode::BAD_REQUEST, "invalid FCM token".into()));
        }
        st.db
            .add_device(&req.fcm_token, &req.agent_id)
            .map_err(internal_error)?;
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize)]
struct TokenRowRes {
    id: i64,
    label: String,
    role: String,
    revoked: bool,
}

async fn list_tokens(
    State(st): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<TokenRowRes>>, (StatusCode, String)> {
    require_admin(&st, &headers).map_err(|(code, msg)| (code, msg.into()))?;
    let rows = st
        .db
        .list_tokens()
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(
        rows.into_iter()
            .map(|t| TokenRowRes {
                id: t.id,
                label: t.label,
                role: t.role,
                revoked: t.revoked,
            })
            .collect(),
    ))
}

#[derive(Deserialize)]
struct RevokeReq {
    id: i64,
}

async fn revoke_token(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<RevokeReq>,
) -> Result<StatusCode, (StatusCode, String)> {
    require_admin(&st, &headers).map_err(|(code, msg)| (code, msg.into()))?;
    st.db
        .revoke_token(req.id)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct MonitorQuery {
    #[serde(default)]
    agent_id: String,
    #[serde(default = "default_hours")]
    hours: i64,
}
fn default_hours() -> i64 {
    24
}
async fn incidents(
    State(st): State<AppState>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<MonitorQuery>,
) -> Result<Json<Vec<crate::monitoring::Incident>>, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(c, m)| (c, m.into()))?;
    st.db
        .incidents(&q.agent_id)
        .map(Json)
        .map_err(internal_error)
}
async fn metrics_history(
    State(st): State<AppState>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<MonitorQuery>,
) -> Result<Json<Vec<crate::monitoring::MetricPoint>>, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(c, m)| (c, m.into()))?;
    if q.agent_id.is_empty() || !matches!(q.hours, 1 | 24 | 168) {
        return Err((
            StatusCode::BAD_REQUEST,
            "agent_id required; hours must be 1,24,168".into(),
        ));
    }
    st.db
        .metric_history(&q.agent_id, q.hours, crate::db::now_unix() as i64)
        .map(Json)
        .map_err(internal_error)
}
async fn get_policy(
    State(st): State<AppState>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<MonitorQuery>,
) -> Result<Json<crate::monitoring::AlertPolicy>, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(c, m)| (c, m.into()))?;
    if q.agent_id.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "agent_id required".into()));
    }
    st.db
        .alert_policy(&q.agent_id)
        .map(Json)
        .map_err(internal_error)
}
async fn put_policy(
    State(st): State<AppState>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<MonitorQuery>,
    Json(p): Json<crate::monitoring::AlertPolicy>,
) -> Result<StatusCode, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(c, m)| (c, m.into()))?;
    if q.agent_id.is_empty() || !p.valid() {
        return Err((
            StatusCode::BAD_REQUEST,
            "invalid agent_id or alert policy".into(),
        ));
    }
    st.db
        .save_alert_policy(&q.agent_id, &p)
        .map_err(internal_error)?;
    Ok(StatusCode::NO_CONTENT)
}
fn internal_error(e: anyhow::Error) -> (StatusCode, String) {
    tracing::error!(error=%e,"monitoring database error");
    (StatusCode::INTERNAL_SERVER_ERROR, "database error".into())
}

async fn agent_ids(
    State(st): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<String>>, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(c, m)| (c, m.into()))?;
    let conn = st.db.conn.lock().unwrap();
    let mut stmt = conn
        .prepare(
            "SELECT DISTINCT label FROM tokens WHERE role='agent' AND revoked=0 ORDER BY label",
        )
        .map_err(|e| internal_error(e.into()))?;
    let rows = stmt
        .query_map([], |r| r.get::<_, String>(0))
        .map_err(|e| internal_error(e.into()))?;
    let ids = rows
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|e| internal_error(e.into()))?;
    Ok(Json(ids))
}
