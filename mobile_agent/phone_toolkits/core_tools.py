from __future__ import annotations

import datetime as dt
import os
import platform
from pathlib import Path
from typing import Any

from mobile_agent.core.tools import ToolRegistry


def register_core_tools(registry: ToolRegistry, *, permission_mode: str) -> None:
    @registry.register(description="Return the current local time and timezone.", enabled_by_default=True)
    def get_time() -> dict[str, str]:
        now = dt.datetime.now().astimezone()
        return {"iso": now.isoformat(), "timezone": now.tzname() or ""}

    @registry.register(description="Return basic device and runtime information.", enabled_by_default=True)
    def device_info() -> dict[str, Any]:
        return {
            "platform": platform.platform(),
            "system": platform.system(),
            "machine": platform.machine(),
            "python": platform.python_version(),
            "cwd": str(Path.cwd()),
            "termux": "com.termux" in os.environ.get("PREFIX", "") or "TERMUX_VERSION" in os.environ,
            "permission_mode": permission_mode,
        }

    @registry.register(description="Echo text back. Useful for testing tool calls.")
    def echo(text: str) -> str:
        return text
