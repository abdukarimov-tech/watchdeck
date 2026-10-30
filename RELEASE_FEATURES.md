# Monitoring features

The agent and relay must be updated together before using the new monitoring screens.
New protobuf fields are additive; older clients ignore health/Compose metadata and new event kinds.
Older relays return HTTP 404 for history, incidents and alert rules; clients show an update error.

## History and incidents

The relay stores metrics in the existing SQLite database: one latest sample per 30-second bucket,
retained for eight days. Requests return averages in 60-second, five-minute or 30-minute buckets
for one hour, 24 hours or seven days. Data starts accumulating after the relay upgrade;
there is no backfill. Restarting the relay preserves history and alert policies.

Client-token authenticated endpoints:

- `GET /api/metrics?agent_id=ID&hours=1|24|168`
- `GET /api/agent-ids` (registered agents, including currently offline agents)
- `GET /api/incidents?agent_id=ID` (omit agent ID for the relay-wide incident center)
- `GET /api/alert-policy?agent_id=ID`
- `PUT /api/alert-policy?agent_id=ID`

Policy JSON:

```json
{"enabled":true,"cpu_percent":90,"memory_percent":85,"disk_percent":90,
 "sustain_seconds":300,"restart_count":3,"maintenance_until":0}
```

CPU, memory and the fullest disk are checked on each incoming metrics sample.
A gap over 90 seconds restarts a pending duration, so an offline interval cannot count
as sustained high load. Notifications fire on condition transitions; repeat notifications
of the same type/resource are limited to one per five minutes. Maintenance suppresses push
notifications while retaining incident history. `enabled=false` disables notifications
and resource threshold evaluation, but lifecycle and Docker incidents are still recorded.
Restart-loop detection counts container exit events over five minutes. A loop incident
recovers after five stable running minutes; a Docker health incident recovers on `healthy`,
not merely `starting` or `running`.

The incident center refreshes every 15 seconds while visible. History refreshes every
30 seconds. Each incident records first occurrence, last event, count and recovery time.
Old alert rows remain accessible but are not retroactively converted to incidents.

## Logs and diagnostics

Both clients let users select a container, search, filter ERROR/WARN, pause the displayed
buffer, copy and export the filtered lines. The most recent 2,000 lines are retained.
Changing containers or leaving the log screen cancels the upstream stream. Closing a
client connection also cancels its streams on the relay. Streaming resumes on reconnect;
Docker tail replay can include lines already displayed before the connection was lost.
An incident links to its specific agent/container, including stopped containers.

Compose project/service labels and Docker health status are shown separately from the
container's running state. Containers without a healthcheck are explicitly marked.

SSH diagnostics include resource/process/service commands, local saved commands and a
confirmation showing the exact command, SSH user, host and port before execution.
Commands execute directly against the selected server, using its saved host-key pin.
Saved commands are device-local and are never sent to the relay.

## Android push

Configure Firebase as described in RELEASE.md and set the relay's FCM environment values.
The app subscribes the device to actual agent IDs. Subscriptions support multiple agents
per device; Firebase token rotation schedules a network-constrained registration job.
The user must grant notification permission. Provider HTTP rejections are logged.

## iOS push and WidgetKit provisioning

Enable Push Notifications for bundle ID `app.pulse` in Apple Developer and enable App Groups
for `app.pulse` and `app.pulse.widgets`. Provision both targets with
`group.app.pulse.watchdeck`. The XcodeGen project includes both entitlements and embeds
WatchDeckWidgets. Debug uses APNs development and Release uses production.

Set on the relay (see deploy/pulse-relay.env.example):

```text
PULSE_APNS_KEY_FILE=/etc/pulse-relay/AuthKey.p8
PULSE_APNS_KEY_ID=YOUR_KEY_ID
PULSE_APNS_TEAM_ID=YOUR_TEAM_ID
PULSE_APNS_TOPIC=app.pulse
```

Use an APNs-enabled P-256 PKCS#8 `.p8` signing key. Keep it readable only by the relay user.
APNs tokens are signed with ES256, reused for at most 50 minutes, and sent over HTTP/2.
The iOS app registers its device token for each registered agent, including offline agents. Alert delivery requires
notification permission and a signed build on a supported device. Simulator compilation
and unit tests do not prove delivery through Apple or Firebase.

## Home-screen widgets

Add the Android widget through the launcher and select a server during configuration.
On iOS, add WatchDeck and choose a server in the widget configuration. Multiple widget
instances can select different servers. Tapping opens the selected server in the app.

Widgets read a non-secret local snapshot updated while the app receives data. They never
receive client/admin tokens, passwords or SSH keys. A snapshot older than two minutes
is explicitly marked as stale and shows its last update time. Widgets do not keep a
background monitoring connection or claim to be live while the app is suspended; open
WatchDeck to refresh. Widget refresh scheduling is controlled by Android/WidgetKit.

## Verification

```sh
cargo test --locked --manifest-path agent/Cargo.toml
cargo test --locked --manifest-path relay/Cargo.toml
cargo build --locked --manifest-path relay/Cargo.toml
python3 relay/tests/monitoring_e2e.py
./android/gradlew -p android assembleDebug testDebugUnitTest lintDebug
swift test --package-path ios/PulseCore
xcodegen generate --spec ios/project.yml
xcodebuild -project ios/WatchDeck.xcodeproj -scheme WatchDeck \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The black-box relay tests use only Python's standard library plus protoc. They exercise
real HTTP/WebSocket authorization, heartbeat interoperability, thresholds/recovery,
healthcheck transitions, restart incidents, history, stream cancellation and device registration.

Android emulator checks covered incidents → container logs, log controls, history, saving
alert rules, launcher widget configuration and opening the selected server from the widget.
The iOS app and WidgetKit extension compiled for the simulator; no simulator runtime or
provisioned device was available for visual verification or real push delivery.

## Platform references

- [WidgetKit timeline and shared containers](https://developer.apple.com/documentation/widgetkit/timelineprovider)
- [APNs notification requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
- [APNs provider token authentication](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)
- [Android widget updates](https://developer.android.com/develop/ui/views/appwidgets/advanced)
