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
        .route("/ws/agent", get(agent_ws))
        .route("/ws/client", get(client_ws))
        .route("/api/agents", get(list_agents))
        .route("/api/tokens", post(create_token))
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
}

async fn list_agents(
    State(st): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<AgentRow>>, (StatusCode, &'static str)> {
    require_admin(&st, &headers)?;
    Ok(Json(
        st.connected_agents()
            .into_iter()
            .map(|(agent_id, hostname)| AgentRow { agent_id, hostname })
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
    fcm_token: String,
    agent_id: String,
}

async fn register_device(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<RegisterDeviceReq>,
) -> Result<StatusCode, (StatusCode, String)> {
    require_role(&st, &headers, "client").map_err(|(code, msg)| (code, msg.into()))?;
    st.db
        .add_device(&req.fcm_token, &req.agent_id)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::NO_CONTENT)
}
