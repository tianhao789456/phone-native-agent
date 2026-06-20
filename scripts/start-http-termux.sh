#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd /sdcard/Download/mobile-agent
if [ -f "$HOME/.mobile-agent.env" ]; then
  set -a
  . "$HOME/.mobile-agent.env"
  set +a
fi

export MOBILE_AGENT_PERMISSION_MODE="${MOBILE_AGENT_PERMISSION_MODE:-danger}"

pkill -f "python.*mobile_agent.hosts.http_server" 2>/dev/null || true
nohup python -m mobile_agent.hosts.http_server --host 127.0.0.1 --port "${PORT:-8787}" > mobile-agent-http.log 2>&1 &
echo "mobile-agent http started on 127.0.0.1:${PORT:-8787}"
