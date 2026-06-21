from __future__ import annotations

from pathlib import Path
from typing import Any


def workspace_path(path: str) -> Path:
    root = Path.cwd().resolve()
    target = (root / path).resolve()
    if root != target and root not in target.parents:
        raise PermissionError("Path escapes the agent workspace.")
    return target


def tool_path(path: str, *, allow_outside: bool) -> Path:
    if allow_outside:
        return Path(path).expanduser().resolve()
    return workspace_path(path)


def completed_result(completed: Any) -> dict[str, Any]:
    return {
        "ok": completed.returncode == 0,
        "returncode": completed.returncode,
        "stdout": completed.stdout[-4000:],
        "stderr": completed.stderr[-4000:],
    }
