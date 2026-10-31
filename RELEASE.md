# RELEASE.md — чек-лист выкладки Pulse в продакшн

Этот документ описывает полный цикл релиза: релей, агент, Android-приложение, пуши.

## 0. Модель безопасности (что гарантировано, что нет)

- Транспорт: TLS (Terminates на reverse-proxy), авторизация bearer-токенами.
- Токены в БД релея — только SHA-256 хеши; утечка БД не дает доступа.
- Токен клиента на телефоне — AndroidKeyStore (AES/GCM, StrongBox при наличии),
  `allowBackup=false` — секert не уходит в облако/бэкапы.
- Агент работает без root (группа docker), systemd-песочница, только исходящие
  соединения.
- **Ограничение текущей версии:** payload внутри TLS не зашифрован end-to-end —
  релей технически видит метрики и алерты. Это roadmap-пункт (E2E-шифрование).
  Не публикуйте адрес релея, ограничивайте выпуск токенов.

## 1. Релей (сервер)

```bash
# 1. бинарник: собрать локально или взять из GitHub Release (release.yml)
cargo build --release          # в relay/
scp target/release/pulse-relay vps:/usr/local/bin/

# 2. пользователь и каталоги
useradd --system --no-create-home --shell /usr/sbin/nologin pulse-relay
mkdir -p /etc/pulse-relay

# 3. конфиг (см. deploy/pulse-relay.env.example)
openssl rand -hex 24   # это PULSE_ADMIN_TOKEN
cp deploy/pulse-relay.env.example /etc/pulse-relay/pulse-relay.env
chmod 600 /etc/pulse-relay/pulse-relay.env && nano /etc/pulse-relay/pulse-relay.env

# 4. systemd
cp deploy/pulse-relay.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now pulse-relay

# 5. TLS через Caddy (рекомендуется) или nginx — deploy/Caddyfile / deploy/nginx.conf
#    порт 8080 наружу НЕ открывать: только 80/443
```

Проверка: `curl https://relay.example.com/health` → `ok`.

Выпуск токенов:

```bash
curl -XPOST https://relay.example.com/api/tokens -H 'X-Admin-Token: <ADMIN>' \
     -H 'Content-Type: application/json' -d '{"label":"my-vps","role":"agent"}'
curl -XPOST https://relay.example.com/api/tokens -H 'X-Admin-Token: <ADMIN>' \
     -H 'Content-Type: application/json' -d '{"label":"phone","role":"client"}'
```

`label` агентского токена становится `agent_id` сервера в приложении.

## 2. Агент (на каждом VPS)

```bash
# из GitHub Release (release.yml собирает x86_64-musl / aarch64-gnu + sha256)
PULSE_RELEASE_URL=https://github.com/<org>/<repo>/releases/download/v1.0.0 \
  ./deploy/install.sh wss://relay.example.com/ws/agent <agent_token>
```

install.sh: скачивает бинарник, создает пользователя `pulse-agent`,
ставит конфиг в `/etc/pulse-agent/config.toml` (600) и systemd-юнит с песочницей.
Проверка: `systemctl status pulse-agent`, `journalctl -u pulse-agent -f`.
Нагрузка: RSS < 20 МБ, CPU ≈ 0% (event-driven, без polling).

Обновление из приложения: меню сервера → «Обновить агента». Кнопка обновляет
локальный релей, затем агент; проверяет SHA-256 и сохраняет резервные копии
в `/var/lib/watchdeck-updates/`. На узле без локального релея обновляется только агент.

## 3. FCM push

1. [Firebase Console] → создать проект → добавить Android-приложение `app.pulse`.2. Скачать `google-services.json` → `android/app/google-services.json`
   (в .gitignore, в CI не попадает).
3. В `android/app/build.gradle.kts` добавить плагин
   `id("com.google.gms.google-services")` (версия 4.4.2 в root build.gradle.kts).
4. На сервере релея: `PULSE_FCM_PROJECT_ID=<id проекта>` +
   `GOOGLE_APPLICATION_CREDENTIALS=/etc/pulse-relay/firebase-service-account.json`
   (сервисный аккаунт с ролью Firebase Cloud Messaging API Admin).
5. Приложение при первом запуске регистрирует FCM-токен (см. TODO в
   PulseMessagingService.onNewToken: POST /api/devices с client-токеном).

Без Firebase-конфига приложение работает: пуши не приходят, остальное функционирует.

## 4. Android релизная сборка

```bash
# 1. ключ подписи (один раз, хранить в менеджере секретов)
keytool -genkeypair -v -keystore pulse-release.keystore \
        -alias pulse -keyalg RSA -keysize 4096 -validity 10000

# 2. сборка
export PULSE_STORE_FILE=/path/pulse-release.keystore
export PULSE_STORE_PASSWORD=...
export PULSE_KEY_ALIAS=pulse
export PULSE_KEY_PASSWORD=...
cd android && ./gradlew assembleRelease bundleRelease
# → app/build/outputs/apk/release/app-release.apk (или .aab для Play Console)
```

Включено: R8 minify + resource shrinking (proguard-rules.pro), targetSdk 36,
runtime-запрос POST_NOTIFICATIONS (Android 13+), адаптивная иконка (+monochrome),
локализация: EN по умолчанию, RU для локали `ru` (другие языки → EN).

Пуши: релей отправляет data-only FCM — текст уведомления собирается на
устройстве по локали пользователя (в `PulseMessagingService`), сервер не знает
язык клиента.

Внутренний тест: Play Console → Internal testing, либо прямой install APK.
Для стор-скриншотов/ревью без сервера — «Demo mode» на экране онбординга.

Unit-тесты: `./gradlew testDebugUnitTest` (форматирование, маппинг протокола).

## 5. GitHub Release (автоматизация)

`release.yml` срабатывает на тег `v*`:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

Workflow создаёт черновик релиза. После успешных проверок CI и сверки SHA-256
опубликуйте его: `gh release edit v1.1.0 --draft=false --latest`.

Собирает и прикладывает к релизу:
- `pulse-agent-x86_64` (+ sha256) — статический musl-бинарник
- `pulse-agent-aarch64` — aarch64 GNU
- `pulse-android-release` — unsigned release APK (подписанный требует секретов:
  добавьте `PULSE_STORE_FILE` и др. как secrets репозитория)

`install.sh` по умолчанию качает latest release; для self-host форка задайте
`PULSE_RELEASE_URL` на свой репозиторий.

## 6. Эксплуатация

- Бэкап релея: файл `relay.db` (SQLite, WAL). Алерты не критичны, токены — да
  (хеши: восстановление невозможно, просто перевыпустите).
- Мониторинг самого релея: `GET /health` в uptime-монитор; алерт если agents=[]
  при ожидаемых N.
- Ротация админ-токена: поменять `PULSE_ADMIN_TOKEN` + `systemctl restart pulse-relay`.
- Отзыв токена: `DELETE` не реализован в v1 — отзыв = пересоздание БД токенов
  (roadmap: таблица revoked).

## 7. Известные ограничения v1.0

- E2E-шифрование payload (релей видит данные в TLS-терминации).
- Отзыв отдельных токенов.
- Автообновление агента.
- iOS-клиент.
- Выбор контейнера на экране логов (сейчас стримится первый RUNNING).
