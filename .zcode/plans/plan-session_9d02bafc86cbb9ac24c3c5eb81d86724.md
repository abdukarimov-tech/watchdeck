# Скелет проекта: мониторинг VPS/Docker (агент Rust + релей + Android)

Рабочее имя проекта — **Pulse** (кодовое, легко заменить). Monorepo в `/Users/murat/Documents/Projects/devops`, инициализируем git.

## Структура репозитория

```
devops/
├── proto/                  # protobuf-контракт (единственный источник правды)
│   └── pulse.proto
├── agent/                  # Rust workspace
│   ├── Cargo.toml          # workspace: agent-core, agent-bin
│   ├── crates/agent-core/  # библиотека: метрики, docker, состояние (тестируемая)
│   └── crates/agent-bin/   # бинарник, config, transport, main
├── relay/                  # Rust: axum + WebSocket
├── android/                # Kotlin + Jetpack Compose, single module
├── deploy/
│   ├── pulse-agent.service # systemd unit (не root, группа docker)
│   └── install.sh          # bootstrap-скрипт установки агента
├── docker-compose.dev.yml  # локальный стенд: relay + агент против test docker socket
└── README.md, .gitignore, .github/workflows/ci.yml
```

## 1. Протокол (`proto/pulse.proto`)

Envelope-модель: `Msg { id, oneof payload }` — `Hello` (auth token, версия, host info), `Metrics` (cpu, ram, disk, net, uptime), `ContainerList`, `ContainerAction` (start/stop/restart) + `ContainerActionResult`, `LogsRequest`/`LogsChunk` (стриминг), `EventAlert` (контейнер умер/ожил → источник пуша). Codegen: `prost` для Rust (build.rs), `protobuf-kotlin-lite` для Android через gradle-плагин.

## 2. Агент (Rust, tokio)

- **Метрики** (`agent-core`): крейт `sysinfo` (CPU/RAM/disk/net) — без ручного парсинга /proc, дельта CPU между тиками раз в 5 сек.
- **Docker**: HTTP по unix-сокету `/var/run/docker.sock` (hyper + hyperlocal): `/containers/json`, POST start/stop/restart, `GET /events` (event-driven, не polling) и `/logs?follow=1` для стрима.
- **Transport**: исходящий WebSocket (tokio-tungstenite + rustls) к релею, автопереподключение с backoff. Агент никогда не слушает входящие порты.
- **Config**: `/etc/pulse-agent/config.toml` (relay_url, token), при отсутствии — гасим systemd-юнит с понятным сообщением.
- Цель по ресурсам: RSS < 20 МБ, CPU ≈ 0% в покое. Unit-тесты на формирование метрик/маппинг docker API (мок-сервер).

## 3. Релей (Rust, axum)

- WS-эндпоинты `/ws/agent` и `/ws/client`, авторизация по токенам из заголовка.
- Роутинг: таблица `agent_id → живое соединение` в памяти, события в SQLite (sled/sqlx) — история алертов для клиента, пришедшего после оффлайна.
- FCM-модуль: HTTP v1 API с service-account (google-auth), включается конфигом; без настроенного Firebase-проекта модуль работает в режиме логирования (stub), чтобы скелет собирался и без него.
- REST: `/health`, `/api/agents` (список подключенных), выпуск токенов агента (`POST /api/tokens`) — для MVP с одного админ-токена.

## 4. Android (Kotlin + Jetpack Compose)

- Один gradle-модуль, MVVM: `ui/` (Compose-экраны), `data/` (репозитории), `net/` (OkHttp WebSocket + REST), `crypto/` (Android Keystore, StrongBox при наличии — хранение токена сервера).
- Экраны MVP: **Onboarding** (ввод адреса релея + токен агента / pairing-код), **Dashboard** (CPU/RAM/Disk), **Containers** (статусы, кнопка restart с подтверждением), **Logs** (стрим, подсветка ERROR/WARN).
- `FirebaseMessagingService` для пушей о падении; фонового мониторинга нет — пуши только через релей (как обсуждали).
- Сначала репозитории на мок-данных (UI можно вертеть сразу), затем переключение на живой WS.

## 5. Локальный стенд и проверка

`docker-compose.dev.yml`: relay + агент (с смонтированным docker.sock) — e2e-проверка «агент подключился, метрики текут, restart контейнера проходит» через REST релея до того, как Android будет готов.

## Порядок работ

1. Каркас репо: git init, workspace-структура, README, CI (cargo + gradle).
2. `pulse.proto` + codegen Rust/Kotlin.
3. Агент: config → метрики → docker-клиент → WS-транспорт (+ тесты).
4. Релей: WS-роутинг → SQLite-история → FCM-модуль (stub/real).
5. E2E-проверка через docker-compose.
6. Android: проект, Compose-экраны на моках → живой WS + Keystore.
7. Итоговый README: как поднять стенд локально.

## Что останется за кадром MVP (roadmap, не делаем сейчас)

SSH-bootstrap установки агента из приложения (MVP ставится install.sh вручную), iOS, E2E-шифрование поверх TLS (сейчас токен+TLS), автообновление агента, графики истории, быстрые скрипты. Для реальных пушей понадобится Firebase-проект — до этого FCM-модуль в режиме stub.

Замечание: для сборки Android нужны Android SDK/NDK — проверю наличие локально; если нет, закоммичу проект с инструкцией и соберу только Rust-часть.