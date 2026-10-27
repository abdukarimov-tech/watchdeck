//! Отправка FCM-пушей (HTTP v1 API). Без настроенного Firebase работает
//! как заглушка с логированием — скелет собирается и запускается без него.
//!
//! Включение: задать PULSE_FCM_PROJECT_ID и credentials для gcp_auth
//! (GOOGLE_APPLICATION_CREDENTIALS=path/to/service-account.json).

#[derive(Clone)]
pub enum FcmSender {
    Disabled,
    Enabled {
        project_id: String,
        http: reqwest::Client,
    },
}

impl FcmSender {
    pub fn from_env() -> Self {
        match std::env::var("PULSE_FCM_PROJECT_ID") {
            Ok(project_id) if !project_id.is_empty() => {
                tracing::info!(%project_id, "FCM push enabled");
                Self::Enabled {
                    project_id,
                    http: reqwest::Client::new(),
                }
            }
            _ => {
                tracing::info!("FCM push disabled (set PULSE_FCM_PROJECT_ID to enable)");
                Self::Disabled
            }
        }
    }

    pub async fn push_container_alert(
        &self,
        device_tokens: &[String],
        agent_id: &str,
        container: &str,
        message: &str,
        kind: &str,
    ) {
        match self {
            Self::Disabled => {
                tracing::info!(agent = %agent_id, container = %container, kind, "push suppressed (fcm disabled): {message}");
            }
            Self::Enabled { project_id, http } => {
                if device_tokens.is_empty() {
                    return;
                }
                let token = match gcp_auth::provider().await {
                    Ok(p) => match p
                        .token(&["https://www.googleapis.com/auth/firebase.messaging"])
                        .await
                    {
                        Ok(t) => t.as_str().to_owned(),
                        Err(e) => {
                            tracing::warn!(error = %e, "gcp auth failed");
                            return;
                        }
                    },
                    Err(e) => {
                        tracing::warn!(error = %e, "gcp provider unavailable");
                        return;
                    }
                };
                let title = match kind {
                    "CONTAINER_DIED" => format!("Контейнер {container} упал"),
                    "OOM" => format!("Контейнер {container}: нехватка памяти"),
                    "CONTAINER_STARTED" => format!("Контейнер {container} запущен"),
                    _ => format!("Событие на {agent_id}"),
                };
                let body = if message.is_empty() {
                    format!("сервер {agent_id}")
                } else {
                    format!("{message} · сервер {agent_id}")
                };
                for device in device_tokens {
                    let payload = serde_json::json!({
                        "message": {
                            "token": device,
                            "notification": { "title": title, "body": body },
                            "data": {
                                "agent_id": agent_id,
                                "container": container,
                                "kind": kind,
                            }
                        }
                    });
                    let url = format!(
                        "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
                    );
                    if let Err(e) = http
                        .post(&url)
                        .bearer_auth(&token)
                        .json(&payload)
                        .send()
                        .await
                    {
                        tracing::warn!(error = %e, "fcm send failed");
                    }
                }
            }
        }
    }
}
