from __future__ import annotations

import argparse
import json
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from mobile_agent.core.context import context_stats
from mobile_agent.hosts.cli import build_agent
from mobile_agent.hosts.terminal_tasks import (
    OUTPUT_PREVIEW_CHARS,
    TASK_ROOT,
    TerminalTaskManager,
    coerce_preview_chars,
    coerce_timeout,
    fold_text,
    resolve_cwd,
)
from mobile_agent.settings import DEFAULT_CONFIG_PATH


def make_handler(agent):
    terminal_tasks = TerminalTaskManager(TASK_ROOT)

    class Handler(BaseHTTPRequestHandler):
        server_version = "MobileAgentHTTP/0.1"

        def do_GET(self) -> None:
            if self.path == "/health":
                self._json({"ok": True, "tools": agent.tools.names()})
                return
            if self.path == "/self-test":
                self._json(agent.run_self_test())
                return
            if self.path.startswith("/status"):
                session_id = self._query_param("session_id")
                latest = agent.store.latest() if not session_id else agent.store.load(session_id)
                ctx = context_stats(latest.messages) if latest else {"messages": 0, "estimated_tokens": 0}
                latest_trace = latest.traces[-1] if latest and latest.traces else {}
                usage = _latest_usage(latest_trace)
                self._json(
                    {
                        "ok": True,
                        "model": agent.config.model,
                        "permission_mode": str(getattr(agent, "permission_mode", "safe")),
                        "tools": agent.tools.names(),
                        "session": {
                            "id": latest.id if latest else None,
                            "messages": len(latest.messages) if latest else 0,
                            "traces": len(latest.traces) if latest else 0,
                            "updated_at": latest.updated_at if latest else None,
                        },
                        "context": ctx,
                        "usage": usage,
                    }
                )
                return
            if self.path == "/tools":
                self._json({"tools": agent.tools.list_metadata()})
                return
            if self.path == "/terminal/status":
                self._json(
                    {
                        "ok": True,
                        "available": True,
                        "permission_mode": str(getattr(agent, "permission_mode", "safe")),
                        "cwd": str(Path.cwd()),
                        "tasks_root": str(terminal_tasks.root),
                    }
                )
                return
            if self.path.startswith("/terminal/tasks/"):
                try:
                    task_id = self.path.removeprefix("/terminal/tasks/").split("?", 1)[0]
                    self._json(terminal_tasks.read_task(task_id))
                except Exception as exc:
                    self._json({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, status=404)
                return
            if self.path == "/sessions":
                self._json({"sessions": agent.store.list_sessions()})
                return
            if self.path.startswith("/sessions/"):
                session_id = self.path.removeprefix("/sessions/")
                session = agent.store.load(session_id)
                self._json({"session": {"id": session.id, "messages": session.messages, "traces": session.traces}})
                return
            self._json({"error": "not found"}, status=404)

        def do_POST(self) -> None:
            if self.path == "/sessions":
                session = agent.store.create()
                self._json({"session_id": session.id}, status=201)
                return
            if self.path == "/terminal/run":
                try:
                    payload = self._read_json()
                    self._json(run_terminal_command(agent, payload))
                except Exception as exc:
                    self._json({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, status=400)
                return
            if self.path == "/terminal/script":
                try:
                    payload = self._read_json()
                    denied = _require_danger(agent)
                    if denied:
                        self._json(denied)
                    else:
                        self._json(terminal_tasks.create_script_task(payload))
                except Exception as exc:
                    self._json({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, status=400)
                return
            if self.path.startswith("/terminal/tasks/") and self.path.endswith("/cancel"):
                try:
                    task_id = self.path.removeprefix("/terminal/tasks/").removesuffix("/cancel")
                    denied = _require_danger(agent)
                    if denied:
                        self._json(denied)
                    else:
                        self._json(terminal_tasks.cancel_task(task_id))
                except Exception as exc:
                    self._json({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, status=400)
                return
            if self.path != "/chat":
                self._json({"error": "not found"}, status=404)
                return
            try:
                payload = self._read_json()
                message = payload.get("message")
                if not isinstance(message, str) or not message.strip():
                    raise ValueError("message must be a non-empty string")
                result = agent.chat(message, session_id=payload.get("session_id"))
                self._json(result)
            except Exception as exc:
                self._json({"error": f"{type(exc).__name__}: {exc}"}, status=400)

        def do_DELETE(self) -> None:
            if self.path.startswith("/sessions/"):
                session_id = self.path.removeprefix("/sessions/")
                deleted = agent.store.delete(session_id)
                self._json({"deleted": deleted}, status=200 if deleted else 404)
                return
            self._json({"error": "not found"}, status=404)

        def log_message(self, format: str, *args: Any) -> None:
            return

        def _read_json(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8")
            return json.loads(raw or "{}")

        def _query_param(self, name: str) -> str | None:
            if "?" not in self.path:
                return None
            query = self.path.split("?", 1)[1]
            for part in query.split("&"):
                key, _, value = part.partition("=")
                if key == name:
                    return value or None
            return None

        def _json(self, payload: dict[str, Any], status: int = 200) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return Handler


def run_terminal_command(agent, payload: dict[str, Any]) -> dict[str, Any]:
    denied = _require_danger(agent)
    if denied:
        return denied
    command = payload.get("command")
    if not isinstance(command, str) or not command.strip():
        raise ValueError("command must be a non-empty string")
    timeout = coerce_timeout(payload.get("timeout", 60))
    max_output_chars = coerce_preview_chars(payload.get("max_output_chars", OUTPUT_PREVIEW_CHARS))
    cwd = resolve_cwd(payload.get("cwd"))
    try:
        completed = subprocess.run(
            command,
            shell=True,
            text=True,
            capture_output=True,
            timeout=timeout,
            cwd=cwd,
        )
        stdout = completed.stdout
        stderr = completed.stderr
        return {
            "ok": completed.returncode == 0,
            "command": command,
            "cwd": str(cwd),
            "returncode": completed.returncode,
            "stdout": fold_text(stdout, max_output_chars)["text"],
            "stderr": fold_text(stderr, max_output_chars)["text"],
            "output": {
                "stdout": fold_text(stdout, max_output_chars),
                "stderr": fold_text(stderr, max_output_chars),
            },
            "timed_out": False,
        }
    except subprocess.TimeoutExpired as exc:
        stdout = (exc.stdout or "") if isinstance(exc.stdout, str) else ""
        stderr = (exc.stderr or "") if isinstance(exc.stderr, str) else ""
        return {
            "ok": False,
            "command": command,
            "cwd": str(cwd),
            "returncode": None,
            "stdout": fold_text(stdout, max_output_chars)["text"],
            "stderr": fold_text(stderr, max_output_chars)["text"],
            "output": {
                "stdout": fold_text(stdout, max_output_chars),
                "stderr": fold_text(stderr, max_output_chars),
            },
            "timed_out": True,
            "error": f"command timed out after {timeout}s",
        }


def _require_danger(agent) -> dict[str, Any] | None:
    mode = str(getattr(agent, "permission_mode", "safe"))
    if mode == "danger":
        return None
    return {
        "ok": False,
        "needs_permission": True,
        "permission_mode": mode,
        "error": "terminal execution requires permission_mode=danger",
    }


def _latest_usage(trace: dict[str, Any]) -> dict[str, Any]:
    for span in reversed(trace.get("spans", []) if isinstance(trace, dict) else []):
        usage = span.get("usage") if isinstance(span, dict) else None
        if isinstance(usage, dict) and usage:
            prompt_hit = usage.get("prompt_cache_hit_tokens") or usage.get("cached_tokens") or 0
            prompt_miss = usage.get("prompt_cache_miss_tokens") or 0
            total = prompt_hit + prompt_miss
            cache_hit_rate = round(prompt_hit / total, 4) if total else None
            return {**usage, "cache_hit_rate": cache_hit_rate}
    return {}


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the mobile agent HTTP host.")
    parser.add_argument("--mock", action="store_true", help="Use a deterministic local mock LLM.")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    agent = build_agent(mock=args.mock, config_path=args.config)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(agent))
    print(f"Mobile Agent HTTP listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
