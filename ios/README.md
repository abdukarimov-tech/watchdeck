# WatchDeck для iOS (Swift)

iOS-клиент WatchDeck: SwiftUI-приложение, функциональный порт Android-версии
(`android/`) на тот же бэкенд — релей + агенты (`relay/`, `agent/`).

## Структура

```
ios/
  project.yml            — спецификация Xcode-проекта для xcodegen
  WatchDeck.xcodeproj    — сгенерированный проект (коммитится)
  WatchDeck/             — исходники приложения
    App/                 — @main, RootView, экран блокировки
    Data/                — AppModel (сессия), ServersStore, KeychainStore
    Net/                 — обертки над PulseCore
    UI/                  — SwiftUI-экраны и компоненты
    Resources/           — локализация (en/ru), legal-документы, ассеты
  PulseCore/             — локальный Swift-пакет: вся логика без UI
    Sources/PulseCore/
      Proto/             — мини-кодек protobuf + сообщения pulse.v1
      Net/               — WS-репозиторий (ping/backoff/пиннинг), RelayAdmin REST
      SSH/               — инсталлятор агента, PTY-терминал (Citadel)
      Demo/              — демо-репозиторий
    Tests/               — unit-тесты кодека/форматтеров (запускаются на macOS)
```

Логика вынесена в `PulseCore` специально: пакет тестируется `swift test`
на macOS без симулятора, а приложение собирается поверх него.

## Сборка

Требования: Xcode 27+, [xcodegen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`).

```bash
# пересобрать проект после изменения project.yml или списка файлов
cd ios && xcodegen generate

# сборка из CLI (симулятор не обязателен — generic-сборка)
xcodebuild -project WatchDeck.xcodeproj -scheme WatchDeck \
  -destination 'generic/platform=iOS Simulator' build

# unit-тесты ядра (без симулятора)
cd PulseCore && swift test
```

Для запуска на устройстве: открыть `WatchDeck.xcodeproj` в Xcode, выбрать
свой Team в Signing & Capabilities (бандл `app.pulse`). Для App Store
понадобится иконка/скриншоты — ассеты уже подключены.

Минимальная версия iOS: **17.0**.

## Что умеет (паритет с Android)

- Мульти-сервер: список серверов с мини-виджетами CPU/RAM/Disk, активный сервер,
  живой WebSocket к каждому релею одновременно
- Дашборд: кольцевые индикаторы CPU/память, сеть (rx/tx), аптайм, диски
- Контейнеры: список, статус, stats (CPU/RAM для запущенных), start/restart
- Логи: стриминг в реальном времени (tail 200 + follow), фильтр All/Issues,
  подсветка ERROR/WARN/stderr, буфер 2000 строк
- Терминал: интерактивный SSH-shell (PTY) напрямую с телефона, host key
  пиннинг по отпечатку SHA256, быстрые команды
- SSH-онбординг: установка релея + агента на сервер по SSH за один проход
  (тот же сценарий, что в Android: relay → токены → агент → TLS-сертификат),
  с диалогом подтверждения host key
- Обновление агента по SSH (download + systemd restart) с проверкой отпечатка
- Управление релеем: список/отзыв токенов (X-Admin-Token)
- Безопасность: секреты в Keychain (iOS-аналог Keystore), TLS-пиннинг
  самоподписанного сертификата релея (полный PEM), блокировка приложения
  Face ID / Touch ID / кодом
- Демо-режим без сервера (скриншоты, store review)
- Локализация: английский по умолчанию, русский для локали `ru`
- Watchdog: локальное уведомление, когда релей активного сервера стал недоступен

## Отличия от Android-версии (сознательные)

- **Push через FCM на iOS не приходит.** Релей пока отправляет пуш только через
  Firebase Cloud Messaging. Алерты на iOS доставляются живым WebSocket-соединением
  (реплей последних 50 алертов при подключении + локальные уведомления).
  Для фоновых пушей нужен APNs-транспорт на релее (`relay/src/fcm.rs`) —
  отдельная задача, `/api/devices` iOS не вызывает.
- Отпечаток SSH host key считается по SSH wire-формату ключа (как делает
  `ssh-keygen -lf`), Android считает по X.509-кодировке Java. Поэтому
  отпечатки одного сервера в iOS и Android различаются — каждый сверяется
  со своим каноническим видом.
- Приватные SSH-ключи: поддержаны форматы OpenSSH **ed25519** и **RSA**
  (с парольной фразой). ECDSA-ключи — использовать пароль или ed25519/RSA.

## Зависимости

Внешних зависимостей у приложения две (и обе — в пакете `PulseCore`):

| Пакет | Зачем |
|---|---|
| [Citadel](https://github.com/orlandos-nl/Citadel) 0.12.x | SSH-клиент (инсталлятор, терминал) |
| SwiftNIO SSH (форк Citadel) | транзитивно, протокол SSH |

Protobuf-кодек написан вручную под контракт `proto/pulse.proto` (~15 сообщений,
wire format) — SwiftProtobuf не нужен, формат закреплен тестами на байтовые
векторы и roundtrip.
