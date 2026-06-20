#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
python - <<'PY'
import json
import subprocess
import time
import urllib.request

server = subprocess.Popen(
    ["sh", "scripts/run-termux.sh"],
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
)
try:
    deadline = time.time() + 10
    while True:
        try:
            urllib.request.urlopen("http://127.0.0.1:8787/health", timeout=1).read()
            break
        except Exception:
            if time.time() > deadline:
                raise
            time.sleep(0.25)

    payload = json.dumps({"message": "Reply with exactly: phone-http-ok"}).encode("utf-8")
    request = urllib.request.Request(
        "http://127.0.0.1:8787/chat",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.loads(response.read().decode("utf-8"))
    print(data["message"])
    print(data["session_id"])
finally:
    server.terminate()
    try:
        server.wait(timeout=5)
    except subprocess.TimeoutExpired:
        server.kill()
PY

