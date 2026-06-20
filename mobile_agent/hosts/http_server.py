from __future__ import annotations

import argparse
import json
import os
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from mobile_agent.core.context import context_stats
from mobile_agent.hosts.cli import build_agent
from mobile_agent.settings import DEFAULT_CONFIG_PATH


OUTPUT_PREVIEW_CHARS = 12000
TASK_ROOT = Path(".mobile-agent/tasks")


class TerminalTaskManager:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._processes: dict[str, subprocess.Popen[str]] = {}

    def create_script_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        script = payload.get("script")
        if not isinstance(script, str) or not script.strip():
            raise ValueError("script must be a non-empty string")
        timeout = _coerce_timeout(payload.get("timeout", 60))
        wait = bool(payload.get("wait", True))
        max_output_chars = _coerce_preview_chars(payload.get("max_output_chars", OUTPUT_PREVIEW_CHARS))
        cwd = _resolve_cwd(payload.get("cwd"))
        interpreter = _normalize_interpreter(payload.get("interpreter", "sh"))
        task_id = uuid.uuid4().hex
        task_dir = self.root / task_id
        task_dir.mkdir(parents=True, exist_ok=False)
        script_path = task_dir / _script_filename(interpreter)
        stdout_path = task_dir / "stdout.txt"
        stderr_path = task_dir / "stderr.txt"
        meta_path = task_dir / "task.json"
        script_path.write_text(script, encoding="utf-8")
        if interpreter in {"sh", "bash"}:
            script_path.chmod(script_path.stat().st_mode | 0o700)
        meta = {
            "ok": None,
            "task_id": task_id,
            "kind": "script",
            "name": str(payload.get("name") or "script"),
            "status": "queued",
            "interpreter": interpreter,
            "command": _script_command(interpreter, script_path),
            "cwd": str(cwd),
            "timeout": timeout,
            "script_path": str(script_path),
            "stdout_path": str(stdout_path),
            "stderr_path": str(stderr_path),
            "created_at": time.time(),
            "started_at": None,
            "finished_at": None,
            "returncode": None,
            "timed_out": False,
            "cancelled": False,
        }
        _write_json(meta_path, meta)
        if wait:
            self._run_task(task_id, meta, meta_path, stdout_path, stderr_path)
            return self.read_task(task_id, max_output_chars=max_output_chars)
        thread = threading.Thread(
            target=self._run_task,
            args=(task_id, meta, meta_path, stdout_path, stderr_path),
            name=f"mobile-agent-task-{task_id[:8]}",
            daemon=True,
        )
        thread.start()
        return self.read_task(task_id, max_output_chars=max_output_chars)

    def read_task(self, task_id: str, max_output_chars: int = OUTPUT_PREVIEW_CHARS) -> dict[str, Any]:
        task_dir = self._task_dir(task_id)
        meta_path = task_dir / "task.json"
        if not meta_path.exists():
            raise FileNotFoundError(f"task not found: {task_id}")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        return {
            **meta,
            "ok": meta.get("returncode") == 0 if meta.get("status") == "finished" else False,
            "output": {
                "stdout": _folded_file(Path(meta["stdout_path"]), max_output_chars),
                "stderr": _folded_file(Path(meta["stderr_path"]), max_output_chars),
            },
        }

    def cancel_task(self, task_id: str) -> dict[str, Any]:
        with self._lock:
            process = self._processes.get(task_id)
        if process is None or process.poll() is not None:
            return {"ok": False, "task_id": task_id, "cancelled": False, "error": "task is not running"}
        process.terminate()
        return {"ok": True, "task_id": task_id, "cancelled": True}

    def _run_task(
        self,
        task_id: str,
        meta: dict[str, Any],
        meta_path: Path,
        stdout_path: Path,
        stderr_path: Path,
    ) -> None:
        command = list(meta["command"])
        meta.update({"status": "running", "started_at": time.time()})
        _write_json(meta_path, meta)
        with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout_file, stderr_path.open(
            "w", encoding="utf-8", errors="replace"
        ) as stderr_file:
            process = subprocess.Popen(
                command,
                cwd=meta["cwd"],
                text=True,
                stdout=stdout_file,
                stderr=stderr_file,
            )
            with self._lock:
                self._processes[task_id] = process
            try:
                returncode = process.wait(timeout=int(meta["timeout"]))
                meta.update({"status": "finished", "returncode": returncode})
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                meta.update(
                    {
                        "status": "timeout",
                        "returncode": process.returncode,
                        "timed_out": True,
                        "error": f"script timed out after {meta['timeout']}s",
                    }
                )
            finally:
                with self._lock:
                    self._processes.pop(task_id, None)
                if process.returncode is not None and process.returncode < 0 and not meta.get("timed_out"):
                    meta.update({"status": "cancelled", "cancelled": True, "returncode": process.returncode})
                meta["finished_at"] = time.time()
                _write_json(meta_path, meta)

    def _task_dir(self, task_id: str) -> Path:
        if not task_id.replace("-", "").isalnum():
            raise ValueError("invalid task id")
        task_dir = (self.root / task_id).resolve()
        if self.root not in task_dir.parents and task_dir != self.root:
            raise ValueError("invalid task path")
        return task_dir


def _resolve_cwd(value: Any) -> Path:
    cwd = Path(value).expanduser().resolve() if isinstance(value, str) and value.strip() else Path.cwd()
    if not cwd.exists() or not cwd.is_dir():
        raise ValueError(f"cwd is not a directory: {cwd}")
    return cwd


def _coerce_timeout(value: Any) -> int:
    timeout = int(value)
    if timeout < 1 or timeout > 600:
        raise ValueError("timeout must be between 1 and 600 seconds")
    return timeout


def _coerce_preview_chars(value: Any) -> int:
    size = int(value)
    if size < 1000:
        return 1000
    if size > 50000:
        return 50000
    return size


def _normalize_interpreter(value: Any) -> str:
    interpreter = str(value or "sh").strip().lower()
    allowed = {"sh", "bash", "python", "python3", "node"}
    if interpreter not in allowed:
        raise ValueError(f"unsupported interpreter: {interpreter}")
    return interpreter


def _script_filename(interpreter: str) -> str:
    if interpreter in {"python", "python3"}:
        return "script.py"
    if interpreter == "node":
        return "script.js"
    return "script.sh"


def _script_command(interpreter: str, script_path: Path) -> list[str]:
    if interpreter == "bash":
        return ["bash", str(script_path)]
    if interpreter == "python":
        return ["python", str(script_path)]
    if interpreter == "python3":
        return ["python3", str(script_path)]
    if interpreter == "node":
        return ["node", str(script_path)]
    return ["sh", str(script_path)]


def _folded_file(path: Path, max_chars: int) -> dict[str, Any]:
    if not path.exists():
        return {
            "text": "",
            "truncated": False,
            "bytes": 0,
            "path": str(path),
        }
    raw = path.read_text(encoding="utf-8", errors="replace")
    truncated = len(raw) > max_chars
    if truncated:
        half = max_chars // 2
        text = raw[:half] + "\n\n... output folded ...\n\n" + raw[-half:]
    else:
        text = raw
    return {
        "text": text,
        "truncated": truncated,
        "chars": len(raw),
        "bytes": path.stat().st_size,
        "path": str(path),
    }


def _fold_text(raw: str, max_chars: int) -> dict[str, Any]:
    truncated = len(raw) > max_chars
    if truncated:
        half = max_chars // 2
        text = raw[:half] + "\n\n... output folded ...\n\n" + raw[-half:]
    else:
        text = raw
    return {
        "text": text,
        "truncated": truncated,
        "chars": len(raw),
    }


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def make_handler(agent):
    terminal_tasks = TerminalTaskManager(TASK_ROOT)

    class Handler(BaseHTTPRequestHandler):
        server_version = "MobileAgentHTTP/0.1"

        def do_GET(self) -> None:
            if self.path == "/health":
                self._json({"ok": True, "tools": agent.tools.names()})
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
    timeout = _coerce_timeout(payload.get("timeout", 60))
    max_output_chars = _coerce_preview_chars(payload.get("max_output_chars", OUTPUT_PREVIEW_CHARS))
    cwd = _resolve_cwd(payload.get("cwd"))
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
            "stdout": _fold_text(stdout, max_output_chars)["text"],
            "stderr": _fold_text(stderr, max_output_chars)["text"],
            "output": {
                "stdout": _fold_text(stdout, max_output_chars),
                "stderr": _fold_text(stderr, max_output_chars),
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
            "stdout": _fold_text(stdout, max_output_chars)["text"],
            "stderr": _fold_text(stderr, max_output_chars)["text"],
            "output": {
                "stdout": _fold_text(stdout, max_output_chars),
                "stderr": _fold_text(stderr, max_output_chars),
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
