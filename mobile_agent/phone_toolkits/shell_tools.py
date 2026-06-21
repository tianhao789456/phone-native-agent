from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from mobile_agent.core.tools import ToolRegistry


def register_shell_tools(
    registry: ToolRegistry,
    *,
    allowed: set[str],
    danger: bool,
    tool_path: Callable[[str, bool], Path],
    subprocess_module: Any,
    shutil_module: Any,
    completed_result: Callable[[Any], dict[str, Any]],
) -> None:
    @registry.register(description="Run a pre-approved local shell command. Only exact configured commands are allowed.")
    def shell_limited(command: str) -> dict[str, Any]:
        if command not in allowed:
            raise PermissionError(f"Command is not allowed: {command}")
        completed = subprocess_module.run(command, shell=True, text=True, capture_output=True, timeout=20)
        return {
            "returncode": completed.returncode,
            "stdout": completed.stdout[-4000:],
            "stderr": completed.stderr[-4000:],
        }

    @registry.register(description="Run a .sh or .py script under the current workspace with timeout and output capture.")
    def run_script(path: str, args: list[str] | None = None, timeout: int = 60) -> dict[str, Any]:
        target = tool_path(path, danger)
        if not target.exists():
            raise FileNotFoundError(path)
        if target.suffix not in {".sh", ".py"}:
            raise ValueError("Only .sh and .py scripts are supported.")
        if timeout < 1 or timeout > 600:
            raise ValueError("timeout must be between 1 and 600 seconds.")
        argv = [str(item) for item in (args or [])]
        if target.suffix == ".py":
            command = [shutil_module.which("python") or "python", str(target), *argv]
        else:
            command = [shutil_module.which("sh") or "sh", str(target), *argv]
        completed = subprocess_module.run(command, text=True, capture_output=True, timeout=timeout, cwd=Path.cwd())
        return completed_result(completed)

    @registry.register(description="Run an unrestricted shell command. Only available when permission_mode is danger.")
    def run_shell(command: str, timeout: int = 60) -> dict[str, Any]:
        if not danger:
            raise PermissionError("run_shell requires permission_mode=danger. Start with: ma danger")
        if timeout < 1 or timeout > 600:
            raise ValueError("timeout must be between 1 and 600 seconds.")
        completed = subprocess_module.run(command, shell=True, text=True, capture_output=True, timeout=timeout, cwd=Path.cwd())
        return completed_result(completed)
