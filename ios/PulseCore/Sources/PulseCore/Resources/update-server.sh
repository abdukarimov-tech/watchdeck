#!/bin/sh
# Shared by Android and iOS. Requires root; never changes tokens or certificates.
set -eu
umask 077
base=${1:?release download URL required}
root=${PULSE_UPDATE_ROOT:-}
bin="$root/usr/local/bin"
state="$root/var/lib/watchdeck-updates"
mkdir -p "$root/run/lock" "$state"
exec 9>"$root/run/lock/watchdeck-update.lock"
flock -n 9 || { echo 'Another server update is running'; exit 1; }
case "$(uname -m)" in
    x86_64) arch=x86_64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *) echo 'Unsupported architecture'; exit 1 ;;
esac
[ -x "$bin/pulse-agent" ] || { echo 'Agent binary missing'; exit 1; }
[ "$(systemctl show pulse-agent.service -p LoadState --value)" = loaded ] || { echo 'Agent service missing'; exit 1; }
components=agent
relay_state=$(systemctl show pulse-relay.service -p LoadState --value 2>/dev/null || true)
case "$relay_state" in
    loaded) [ -x "$bin/pulse-relay" ]; command -v python3 >/dev/null; components='relay agent' ;;
    not-found|'') echo 'No local relay: updating agent only' ;;
    *) echo "Cannot update relay with LoadState=$relay_state"; exit 1 ;;
esac
work=$(mktemp -d)
backup=''
changed=''
committed=no
cleanup() {
    rc=$?
    trap - EXIT HUP INT TERM
    if [ "$committed" != yes ] && [ -n "$changed" ]; then
        echo 'Update failed; restoring previous binaries'
        for name in $changed; do
            install -m 755 "$backup/pulse-$name" "$bin/.pulse-$name.rollback" && mv -f "$bin/.pulse-$name.rollback" "$bin/pulse-$name" || rc=1
        done
        for name in $components; do
            case " $changed " in *" $name "*) systemctl restart "pulse-$name" || rc=1 ;; esac
        done
        rc=1
    fi
    rm -rf "$work"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
download() {
    if command -v curl >/dev/null; then
        curl -fLsS --connect-timeout 15 --max-time 120 "$base/$1" -o "$work/$1"
    else
        wget -q -T 120 -t 1 -O "$work/$1" "$base/$1"
    fi
}
echo "Downloading server components ($arch)"
download "sha256-$arch.txt"
: > "$work/checksums"
for name in $components; do
    artifact="pulse-$name-$arch"
    download "$artifact"
    awk -v name="$artifact" '$2 == name { print; found++ } END { if (found != 1) exit 1 }' "$work/sha256-$arch.txt" >> "$work/checksums"
done
(cd "$work" && sha256sum -c checksums)
backup=$(mktemp -d "$state/backup-XXXXXXXX")
for name in $components; do cp -p "$bin/pulse-$name" "$backup/pulse-$name"; done
if [ "$components" = 'relay agent' ]; then
    cp -a "$root/etc/pulse-relay" "$backup/relay-config"
    python3 - "$root" "$backup/relay.db" <<'PY'
import pathlib, shlex, sqlite3, sys
root, destination = sys.argv[1:]
config = pathlib.Path(root + '/etc/pulse-relay/pulse-relay.env')
db = '/var/lib/pulse-relay/relay.db'
for line in config.read_text().splitlines():
    if line.strip().startswith('PULSE_DB='):
        values = shlex.split(line.strip().split('=', 1)[1], comments=True)
        if len(values) != 1: raise ValueError('Invalid PULSE_DB')
        db = values[0]
if not db.startswith('/'): raise ValueError('PULSE_DB must be an absolute path')
path = pathlib.Path(root + db)
if path.exists():
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True) as source:
        with sqlite3.connect(destination) as target: source.backup(target)
PY
fi
echo "Backup saved: $backup"
for name in $components; do
    install -m 755 "$work/pulse-$name-$arch" "$bin/.pulse-$name.new"
    mv -f "$bin/.pulse-$name.new" "$bin/pulse-$name"
    changed="$changed $name"
    echo "Restarting pulse-$name"
    systemctl restart "pulse-$name"
    sleep 2
    systemctl is-active --quiet "pulse-$name"
done
committed=yes
echo 'Server update completed'
