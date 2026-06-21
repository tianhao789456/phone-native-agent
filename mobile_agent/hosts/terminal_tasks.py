from __future__ import annotations

import json
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Any


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
        timeout = coerce_timeout(payload.get("timeout", 60))
        wait = bool(payload.get("wait", True))
        max_output_chars = coerce_preview_chars(payload.get("max_output_chars", OUTPUT_PREVIEW_CHARS))
        cwd = resolve_cwd(payload.get("cwd"))
        interpreter = normalize_interpreter(payload.get("interpreter", "sh"))
        task_id = uuid.uuid4().hex
        task_dir = self.root / task_id
        task_dir.mkdir(parents=True, exist_ok=False)
        script_path = task_dir / script_filename(interpreter)
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
            "command": script_command(interpreter, script_path),
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
        write_json(meta_path, meta)
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
                "stdout": folded_file(Path(meta["stdout_path"]), max_output_chars),
                "stderr": folded_file(Path(meta["stderr_path"]), max_output_chars),
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
        write_json(meta_path, meta)
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
                write_json(meta_path, meta)

    def _task_dir(self, task_id: str) -> Path:
        if not task_id.replace("-", "").isalnum():
            raise ValueError("invalid task id")
        task_dir = (self.root / task_id).resolve()
        if self.root not in task_dir.parents and task_dir != self.root:
            raise ValueError("invalid task path")
        return task_dir


def resolve_cwd(value: Any) -> Path:
    cwd = Path(value).expanduser().resolve() if isinstance(value, str) and value.strip() else Path.cwd()
    if not cwd.exists() or not cwd.is_dir():
        raise ValueError(f"cwd is not a directory: {cwd}")
    return cwd


def coerce_timeout(value: Any) -> int:
    timeout = int(value)
    if timeout < 1 or timeout > 600:
        raise ValueError("timeout must be between 1 and 600 seconds")
    return timeout


def coerce_preview_chars(value: Any) -> int:
    size = int(value)
    if size < 1000:
        return 1000
    if size > 50000:
        return 50000
    return size


def normalize_interpreter(value: Any) -> str:
    interpreter = str(value or "sh").strip().lower()
    allowed = {"sh", "bash", "python", "python3", "node"}
    if interpreter not in allowed:
        raise ValueError(f"unsupported interpreter: {interpreter}")
    return interpreter


def script_filename(interpreter: str) -> str:
    if interpreter in {"python", "python3"}:
        return "script.py"
    if interpreter == "node":
        return "script.js"
    return "script.sh"


def script_command(interpreter: str, script_path: Path) -> list[str]:
    if interpreter == "bash":
        return ["bash", str(script_path)]
    if interpreter == "python":
        return ["python", str(script_path)]
    if interpreter == "python3":
        return ["python3", str(script_path)]
    if interpreter == "node":
        return ["node", str(script_path)]
    return ["sh", str(script_path)]


def folded_file(path: Path, max_chars: int) -> dict[str, Any]:
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


def fold_text(raw: str, max_chars: int) -> dict[str, Any]:
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


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
