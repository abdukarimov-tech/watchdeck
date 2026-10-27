#!/bin/sh
# Bootstrap-установка pulse-agent на сервер.
# Использование: ./install.sh <relay_url> <agent_token>
# Пример:        ./install.sh wss://relay.example.com/ws/agent pulse_abc123...
set -eu

RELAY_URL="${1:?usage: install.sh <relay_url> <agent_token>}"
TOKEN="${2:?usage: install.sh <relay_url> <agent_token>}"
REPO_URL="${PULSE_RELEASE_URL:-https://github.com/example/pulse/releases/latest/download}"

ARCH=$(uname -m)
case "$ARCH" in
  x86_64) ARTIFACT="pulse-agent-x86_64" ;;
  aarch64 | arm64) ARTIFACT="pulse-agent-aarch64" ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

echo "==> downloading agent binary"
curl -fsSL "$REPO_URL/$ARTIFACT" -o /tmp/pulse-agent
chmod +x /tmp/pulse-agent

echo "==> creating user (no login, no root)"
id pulse-agent >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin pulse-agent

echo "==> installing"
install -m 0755 /tmp/pulse-agent /usr/local/bin/pulse-agent
rm -f /tmp/pulse-agent

mkdir -p /etc/pulse-agent
cat > /etc/pulse-agent/config.toml <<EOF
relay_url = "$RELAY_URL"
token = "$TOKEN"
EOF
chmod 600 /etc/pulse-agent/config.toml
chown -R pulse-agent:pulse-agent /etc/pulse-agent

install -m 0644 "$(dirname "$0")/pulse-agent.service" /etc/systemd/system/pulse-agent.service
systemctl daemon-reload

# доступ к docker.sock без root: membership в группе docker
usermod -aG docker pulse-agent

echo "==> enabling service"
systemctl enable --now pulse-agent

echo "done. check status: systemctl status pulse-agent"
echo "logs: journalctl -u pulse-agent -f"
