# Кочегарка (Stokehold) — мониторинг VPS/Docker с телефона

Мобильный клиент (Android) для мониторинга серверов и управления Docker-контейнерами:
дашборд CPU/RAM/Disk, список контейнеров с перезапуском, стриминг логов с подсветкой
ошибок и push-уведомления о падении сервисов.

> Название: сервера — топки, DevOps — кочегар. Агент — термопара в топке,
> релей — машинный телеграф, приложение — пульт кочегарки: пуш «топка погасла»,
> restart — «подкинуть угля», логи — вахтенный журнал.
> В коде сохранено рабочее имя `pulse` (package `app.pulse`, relay/agent бинари)
> — это не видно пользователям и не мешает релизу.

```
┌─────────┐   wss    ┌──────────┐   wss    ┌─────────┐
│  Phone  │ ◄──────► │  Relay   │ ◄──────► │  Agent  │
│  (Kotlin)│          │ (Rust)   │          │ (Rust)  │──► /var/run/docker.sock
└─────────┘          └────┬─────┘          └─────────┘
                          │ FCM push (контейнер упал)
                          ▼
                     Google → устройство
```

- **Агент** (`agent/`) — легковесный демон на Rust (цель: RSS < 20 МБ, CPU ≈ 0% в покое):
  читает метрики через sysinfo, разговаривает с Docker по unix-сокету, шлет события
  по WebSocket к релею. Слушающих портов нет, работает без root (группа docker).
- **Релей** (`relay/`) — маршрутизатор на Rust (axum): авторизация по токенам,
  роутинг сообщений агент↔клиент, история алертов в SQLite, отправка FCM-пушей.
- **Приложение** (`android/`) — Kotlin + Jetpack Compose: живой WebSocket к релею
  (OkHttp + protobuf-lite), выбор сервера, статус-баннер соединения, токен клиента
  в Android Keystore (StrongBox при наличии). Есть демо-режим без сервера (для
  скриншотов и store review).

## Структура

```
proto/pulse.proto      — контракт протокола (protobuf), единый для всех частей
agent/                 — Rust workspace: agent-core (метрики, docker), agent-bin
relay/                 — релей
android/               — приложение
deploy/                — systemd unit + install.sh
docker-compose.dev.yml — локальный e2e-стенд
```

## Локальный стенд (agent + relay)

```bash
export PULSE_ADMIN_TOKEN=dev-admin
docker compose -f docker-compose.dev.yml up --build -d

# выпуск токенов (label агента = его agent_id)
curl -s -XPOST localhost:8080/api/tokens -H 'X-Admin-Token: dev-admin' \
     -H 'Content-Type: application/json' -d '{"label":"dev-vps","role":"agent"}'
curl -s -XPOST localhost:8080/api/tokens -H 'X-Admin-Token: dev-admin' \
     -H 'Content-Type: application/json' -d '{"label":"phone","role":"client"}'

export PULSE_AGENT_TOKEN=<agent-токен из ответа>
docker compose -f docker-compose.dev.yml up agent -d

# проверка: агент должен появиться в списке
curl -s localhost:8080/api/agents -H 'X-Admin-Token: dev-admin'
```

REST релея: `GET /health`, `GET /api/agents`, `POST /api/tokens`,
`GET /api/alerts?agent_id=...` (Bearer client-токен), `POST /api/devices` (регистрация FCM-токена).

WS: `GET /ws/agent` (Bearer agent-токен), `GET /ws/client?agent_id=...` (Bearer client-токен).

## Установка агента на сервер

```bash
./deploy/install.sh wss://relay.example.com/ws/agent <agent_token>
```

Скрипт ставит бинарник в `/usr/local/bin`, создает системного пользователя
`pulse-agent` (без root, группа docker), конфиг в `/etc/pulse-agent/config.toml`
и включает systemd-юнит. Конфиг можно задать и через env: `PULSE_RELAY_URL`, `PULSE_TOKEN`.

## Android

```bash
cd android && ./gradlew assembleDebug          # debug
PULSE_STORE_FILE=... PULSE_STORE_PASSWORD=... PULSE_KEY_ALIAS=... \
PULSE_KEY_PASSWORD=... ./gradlew assembleRelease   # release (R8 + подпись)
```

Приложение стартует с экрана подключения (адрес релея + client-токен, либо
«Демо-режим»), затем — три вкладки: Сервер (метрики), Контейнеры (restart с
подтверждением), Логи (стрим с подсветкой ERROR/WARN). При нескольких агентах
появляется чип-переключатель серверов. Протокол — сгенерированные
protobuf-kotlin-lite классы из `proto/pulse.proto`.

Релизный цикл (подписание, FCM, деплой релея, GitHub Release) — [RELEASE.md](RELEASE.md).

### Push-уведомления

1. Создайте Firebase-проект, добавьте Android-приложение с package `app.pulse`.2. Положите `google-services.json` в `android/app/` и подключите плагин
   `com.google.gms.google-services`.
3. На сервере релея: `PULSE_FCM_PROJECT_ID=<project>` +
   `GOOGLE_APPLICATION_CREDENTIALS=<service-account.json>`.
4. Приложение регистрирует FCM-токен: `POST /api/devices {fcm_token, agent_id}`.

Без этих шагов релей работает с FCM в режиме заглушки (пуш логируется).

## Roadmap

- [ ] E2E-шифрование payload поверх TLS (сейчас — токены + TLS)
- [ ] Отзыв отдельных токенов
- [ ] SSH-bootstrap установки агента из приложения
- [ ] iOS-клиент (Swift)
- [ ] Автообновление агента, графики истории, быстрые скрипты
- [ ] Выбор контейнера на экране логов

## Разработка

Тесты: `cargo test` в `agent/` и `relay/`. Линтеры: `cargo fmt && cargo clippy`.
CI (GitHub Actions) проверяет оба Rust-крейта и Android-сборку.
