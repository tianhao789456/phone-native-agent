#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
python - <<'PY'
from mobile_agent.phone_tools import build_registry

safe = build_registry(permission_mode="safe")
danger = build_registry(permission_mode="danger")
checks = [
    ("screen", safe, "screen_dump", {"max_nodes": 5}),
    ("safe_shell", safe, "run_shell", {"command": "echo safe"}),
    ("danger_shell", danger, "run_shell", {"command": "echo danger"}),
]

for label, registry, tool, args in checks:
    print(label, registry.execute(tool, args))
PY
