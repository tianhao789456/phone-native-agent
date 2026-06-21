from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from mobile_agent.core.tools import ToolRegistry


def register_termux_tools(
    registry: ToolRegistry,
    *,
    termux_api: Any,
    danger: bool,
    tool_path: Callable[[str, bool], Path],
    subprocess_module: Any,
    shutil_module: Any,
    webbrowser_module: Any,
) -> None:
    @registry.register(description="Open a URL using Termux when available, otherwise the default browser.")
    def open_url(url: str) -> dict[str, Any]:
        if not (url.startswith("http://") or url.startswith("https://")):
            raise ValueError("Only http:// and https:// URLs are allowed.")
        termux_open = shutil_module.which("termux-open-url")
        if termux_open:
            completed = subprocess_module.run([termux_open, url], text=True, capture_output=True, timeout=10)
            return {"method": "termux-open-url", "returncode": completed.returncode, "stderr": completed.stderr.strip()}
        ok = webbrowser_module.open(url)
        return {"method": "webbrowser", "opened": ok}

    @registry.register(description="Return Termux battery status when termux-api is installed.")
    def battery_status() -> dict[str, Any]:
        data = termux_api.run_json(["termux-battery-status"], timeout=10)
        if data.get("available") is False:
            return data
        data["available"] = True
        data["ok"] = True
        return data

    @registry.register(description="Turn the phone flashlight on or off through Termux:API.")
    def flashlight(enabled: bool) -> dict[str, Any]:
        state = "on" if enabled else "off"
        return termux_api.run_text(["termux-torch", state], timeout=10)

    @registry.register(description="Take a photo with Termux:API and save it under the current workspace.")
    def camera_photo(path: str = "captures/photo.jpg", camera_id: int = 0) -> dict[str, Any]:
        target = tool_path(path, danger)
        target.parent.mkdir(parents=True, exist_ok=True)
        result = termux_api.run_text(["termux-camera-photo", "-c", str(camera_id), str(target)], timeout=30)
        result["path"] = str(target)
        result["exists"] = target.exists()
        result["bytes"] = target.stat().st_size if target.exists() else 0
        return result

    @registry.register(description="List available Android sensors through Termux:API.")
    def sensors() -> dict[str, Any]:
        return termux_api.run_json(["termux-sensor", "-l"], timeout=10)

    @registry.register(description="Send a local Android notification through Termux:API.")
    def notify(title: str, content: str) -> dict[str, Any]:
        if not title.strip():
            raise ValueError("title is required")
        if not content.strip():
            raise ValueError("content is required")
        return termux_api.run_text(["termux-notification", "--title", title, "--content", content], timeout=10)
