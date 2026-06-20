#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
if [ -f "$HOME/.mobile-agent.env" ]; then
  set -a
  . "$HOME/.mobile-agent.env"
  set +a
fi

python - <<'PY'
from mobile_agent.hosts.cli import build_agent
from mobile_agent.settings import DEFAULT_CONFIG_PATH

agent = build_agent(mock=False, config_path=DEFAULT_CONFIG_PATH)
result = agent.chat("Reply with exactly: mobile-agent-ok")
print(result["message"])
PY

