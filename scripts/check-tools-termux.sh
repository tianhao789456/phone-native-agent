#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
python - <<'PY'
from mobile_agent.phone_tools import build_registry

registry = build_registry(["pwd", "whoami", "python --version"])
for name, args in [
    ("get_time", {}),
    ("device_info", {}),
    ("battery_status", {}),
    ("shell_limited", {"command": "pwd"}),
]:
    print(f"== {name}")
    print(registry.execute(name, args))
PY

