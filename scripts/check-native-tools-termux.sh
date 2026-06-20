#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
python - <<'PY'
from mobile_agent.phone_tools import build_registry

registry = build_registry()
checks = [
    ("battery_status", {}),
    ("sensors", {}),
    ("notify", {"title": "Mobile Agent", "content": "Native notification test"}),
    ("flashlight", {"enabled": True}),
    ("flashlight", {"enabled": False}),
    ("camera_photo", {"path": "captures/test-photo.jpg", "camera_id": 0}),
]

for name, args in checks:
    output = registry.execute(name, args)
    print(name, output)
PY
