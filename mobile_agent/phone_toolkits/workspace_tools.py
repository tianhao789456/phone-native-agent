from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from mobile_agent.core.tools import ToolRegistry


def register_workspace_tools(
    registry: ToolRegistry,
    *,
    danger: bool,
    tool_path: Callable[[str, bool], Path],
) -> None:
    @registry.register(description="Read a UTF-8 text file under the current agent workspace.")
    def read_file(path: str, max_bytes: int = 20000) -> dict[str, Any]:
        target = tool_path(path, danger)
        if max_bytes < 1 or max_bytes > 100000:
            raise ValueError("max_bytes must be between 1 and 100000")
        data = target.read_bytes()[:max_bytes]
        return {
            "path": str(target),
            "content": data.decode("utf-8", errors="replace"),
            "truncated": target.stat().st_size > max_bytes,
        }

    @registry.register(description="Write a UTF-8 text file under the current agent workspace. Does not overwrite by default.")
    def write_file(path: str, content: str, overwrite: bool = False) -> dict[str, Any]:
        target = tool_path(path, danger)
        if target.exists() and not overwrite:
            raise FileExistsError(f"File exists and overwrite is false: {path}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return {"path": str(target), "bytes": len(content.encode("utf-8"))}
