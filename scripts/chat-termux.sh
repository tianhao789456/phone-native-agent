#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
if [ -f "$HOME/.mobile-agent.env" ]; then
  set -a
  . "$HOME/.mobile-agent.env"
  set +a
fi

python -m mobile_agent.hosts.cli "$@"
