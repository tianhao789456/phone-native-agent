from __future__ import annotations

import datetime as dt
import os
import platform
import shutil
import subprocess
import urllib.request
import webbrowser
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET

from .action_confirmation import current_action_approved
from .core.tools import ToolRegistry
from .host_bridge import HostBridgeClient
from .termux_api import TermuxApi


def build_registry(allowed_shell_commands: list[str] | None = None, *, permission_mode: str = "safe") -> ToolRegistry:
    registry = ToolRegistry()
    allowed = set(allowed_shell_commands or [])
    termux_api = TermuxApi()
    host_bridge = HostBridgeClient.from_env()
    danger = permission_mode == "danger"

    @registry.register(description="Return the current local time and timezone.")
    def get_time() -> dict[str, str]:
        now = dt.datetime.now().astimezone()
        return {"iso": now.isoformat(), "timezone": now.tzname() or ""}

    @registry.register(description="Return basic device and runtime information.")
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

    @registry.register(description="Read a UTF-8 text file under the current agent workspace.")
    def read_file(path: str, max_bytes: int = 20000) -> dict[str, Any]:
        target = _tool_path(path, allow_outside=danger)
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
        target = _tool_path(path, allow_outside=danger)
        if target.exists() and not overwrite:
            raise FileExistsError(f"File exists and overwrite is false: {path}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return {"path": str(target), "bytes": len(content.encode("utf-8"))}

    @registry.register(description="Fetch an HTTP or HTTPS URL with a short timeout and byte limit.")
    def http_get(url: str, max_bytes: int = 50000) -> dict[str, Any]:
        if not (url.startswith("http://") or url.startswith("https://")):
            raise ValueError("Only http:// and https:// URLs are allowed.")
        if max_bytes < 1 or max_bytes > 200000:
            raise ValueError("max_bytes must be between 1 and 200000")
        request = urllib.request.Request(url, headers={"User-Agent": "mobile-agent/0.1"})
        with urllib.request.urlopen(request, timeout=15) as response:
            data = response.read(max_bytes + 1)
            content_type = response.headers.get("content-type", "")
            status = response.status
        return {
            "url": url,
            "status": status,
            "content_type": content_type,
            "text": data[:max_bytes].decode("utf-8", errors="replace"),
            "truncated": len(data) > max_bytes,
        }

    @registry.register(description="Echo text back. Useful for testing tool calls.")
    def echo(text: str) -> str:
        return text

    @registry.register(description="Return Android Host App bridge status when the native host is running.")
    def host_status() -> dict[str, Any]:
        return host_bridge.status()

    @registry.register(description="List tools exposed by the Android Host App bridge.")
    def host_tools() -> dict[str, Any]:
        return host_bridge.tools()

    @registry.register(description="Call a tool exposed by the Android Host App bridge.")
    def host_call(tool: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        return host_bridge.call(tool, arguments or {}, actions_approved=current_action_approved())

    @registry.register(description="Return the Android Host App private workspace status.")
    def host_workspace_info() -> dict[str, Any]:
        return host_bridge.call("workspace.info", {})

    @registry.register(description="List files under the Android Host App private workspace.")
    def host_list_files(path: str = ".", max_entries: int = 100) -> dict[str, Any]:
        return host_bridge.call("workspace.list", {"path": path, "max_entries": max_entries})

    @registry.register(description="Read a UTF-8 text file under the Android Host App private workspace.")
    def host_read_file(path: str, max_bytes: int = 20000) -> dict[str, Any]:
        return host_bridge.call("workspace.read", {"path": path, "max_bytes": max_bytes})

    @registry.register(description="Write a UTF-8 text file under the Android Host App private workspace.")
    def host_write_file(path: str, content: str, overwrite: bool = False) -> dict[str, Any]:
        return host_bridge.call("workspace.write", {"path": path, "content": content, "overwrite": overwrite})

    @registry.register(description="Search UTF-8 text files under the Android Host App private workspace.")
    def host_search_files(
        query: str,
        path: str = ".",
        max_matches: int = 50,
        max_bytes_per_file: int = 200000,
    ) -> dict[str, Any]:
        return host_bridge.call(
            "workspace.search",
            {
                "query": query,
                "path": path,
                "max_matches": max_matches,
                "max_bytes_per_file": max_bytes_per_file,
            },
        )

    @registry.register(description="Observe the current Android foreground app and compact screen node list together.")
    def host_observe(max_nodes: int = 40) -> dict[str, Any]:
        return host_bridge.call("accessibility.observe", {"max_nodes": max_nodes})

    @registry.register(description="Return a compact screen node list from the Android Host App Accessibility backend.")
    def host_screen_dump(max_nodes: int = 80) -> dict[str, Any]:
        return host_bridge.call("accessibility.dump", {"max_nodes": max_nodes})

    @registry.register(description="Find screen nodes by text, content description, view id, or class name through the Android Host App.")
    def host_screen_find(query: str, contains: bool = True, max_nodes: int = 20) -> dict[str, Any]:
        return host_bridge.call("accessibility.find", {"query": query, "contains": contains, "max_nodes": max_nodes})

    @registry.register(description="Return the current foreground app package and root node summary from the Android Host App.")
    def host_current_app() -> dict[str, Any]:
        return host_bridge.call("accessibility.current_app", {})

    @registry.register(description="Open an installed Android app by package name through the Android Host App.")
    def host_open_app(package: str) -> dict[str, Any]:
        return host_bridge.call("android.open_app", {"package": package}, actions_approved=current_action_approved())

    @registry.register(description="Click visible text or content description through the Android Host App Accessibility backend.")
    def host_click_text(text: str, contains: bool = True) -> dict[str, Any]:
        return host_bridge.call("accessibility.click_text", {"text": text, "contains": contains}, actions_approved=current_action_approved())

    @registry.register(description="Click an Android view resource id through the Android Host App Accessibility backend.")
    def host_click_view_id(view_id: str) -> dict[str, Any]:
        return host_bridge.call("accessibility.click_view_id", {"view_id": view_id}, actions_approved=current_action_approved())

    @registry.register(description="Click a node by index from host_screen_dump through the Android Host App Accessibility backend.")
    def host_click_index(index: int) -> dict[str, Any]:
        return host_bridge.call("accessibility.click_index", {"index": index}, actions_approved=current_action_approved())

    @registry.register(description="Set text in the focused or first editable field through the Android Host App Accessibility backend.")
    def host_input_text(text: str) -> dict[str, Any]:
        return host_bridge.call("accessibility.input_text", {"text": text}, actions_approved=current_action_approved())

    @registry.register(description="Perform Android Back through the Android Host App Accessibility backend.")
    def host_back() -> dict[str, Any]:
        return host_bridge.call("accessibility.back", {}, actions_approved=current_action_approved())

    @registry.register(description="Perform Android Home through the Android Host App Accessibility backend.")
    def host_home() -> dict[str, Any]:
        return host_bridge.call("accessibility.home", {}, actions_approved=current_action_approved())

    @registry.register(description="Scroll the current page through the Android Host App Accessibility backend.")
    def host_scroll(direction: str = "forward", text: str = "", view_id: str = "") -> dict[str, Any]:
        return host_bridge.call(
            "accessibility.scroll",
            {"direction": direction, "text": text, "view_id": view_id},
            actions_approved=current_action_approved(),
        )

    @registry.register(description="Open a URL using Termux when available, otherwise the default browser.")
    def open_url(url: str) -> dict[str, Any]:
        if not (url.startswith("http://") or url.startswith("https://")):
            raise ValueError("Only http:// and https:// URLs are allowed.")
        termux_open = shutil.which("termux-open-url")
        if termux_open:
            completed = subprocess.run([termux_open, url], text=True, capture_output=True, timeout=10)
            return {"method": "termux-open-url", "returncode": completed.returncode, "stderr": completed.stderr.strip()}
        ok = webbrowser.open(url)
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
        target = _tool_path(path, allow_outside=danger)
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

    @registry.register(description="Run a pre-approved local shell command. Only exact configured commands are allowed.")
    def shell_limited(command: str) -> dict[str, Any]:
        if command not in allowed:
            raise PermissionError(f"Command is not allowed: {command}")
        completed = subprocess.run(command, shell=True, text=True, capture_output=True, timeout=20)
        return {
            "returncode": completed.returncode,
            "stdout": completed.stdout[-4000:],
            "stderr": completed.stderr[-4000:],
        }

    @registry.register(description="Run a .sh or .py script under the current workspace with timeout and output capture.")
    def run_script(path: str, args: list[str] | None = None, timeout: int = 60) -> dict[str, Any]:
        target = _tool_path(path, allow_outside=danger)
        if not target.exists():
            raise FileNotFoundError(path)
        if target.suffix not in {".sh", ".py"}:
            raise ValueError("Only .sh and .py scripts are supported.")
        if timeout < 1 or timeout > 600:
            raise ValueError("timeout must be between 1 and 600 seconds.")
        argv = [str(item) for item in (args or [])]
        if target.suffix == ".py":
            command = [shutil.which("python") or "python", str(target), *argv]
        else:
            command = [shutil.which("sh") or "sh", str(target), *argv]
        completed = subprocess.run(command, text=True, capture_output=True, timeout=timeout, cwd=Path.cwd())
        return _completed_result(completed)

    @registry.register(description="Run an unrestricted shell command. Only available when permission_mode is danger.")
    def run_shell(command: str, timeout: int = 60) -> dict[str, Any]:
        if not danger:
            raise PermissionError("run_shell requires permission_mode=danger. Start with: ma danger")
        if timeout < 1 or timeout > 600:
            raise ValueError("timeout must be between 1 and 600 seconds.")
        completed = subprocess.run(command, shell=True, text=True, capture_output=True, timeout=timeout, cwd=Path.cwd())
        return _completed_result(completed)

    @registry.register(description="Return a text summary of the current Android screen using uiautomator XML, no vision model required.")
    def screen_dump(max_nodes: int = 60) -> dict[str, Any]:
        if max_nodes < 1 or max_nodes > 200:
            raise ValueError("max_nodes must be between 1 and 200")
        uiautomator = _android_command("uiautomator")
        dump_path = Path.cwd() / ".mobile-agent-window.xml"
        completed = subprocess.run([uiautomator, "dump", str(dump_path)], text=True, capture_output=True, timeout=15)
        if completed.returncode != 0:
            result = _completed_result(completed)
            result["hint"] = "Termux cannot access uiautomator on some Android builds. Use ADB/Shizuku/Accessibility bridge for screen XML."
            return result
        xml_text = dump_path.read_text(encoding="utf-8", errors="replace")
        return {"xml_path": str(dump_path), "nodes": _summarize_ui_xml(xml_text, max_nodes=max_nodes)}

    @registry.register(description="Tap Android screen coordinates.")
    def tap(x: int, y: int) -> dict[str, Any]:
        return _run_android_input(["tap", str(x), str(y)])

    @registry.register(description="Swipe Android screen coordinates.")
    def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> dict[str, Any]:
        return _run_android_input(["swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)])

    @registry.register(description="Type text into the focused Android input field.")
    def type_text(text: str) -> dict[str, Any]:
        if not text:
            raise ValueError("text is required")
        return _run_android_input(["text", _adb_input_text(text)])

    @registry.register(description="Send an Android keyevent such as BACK, HOME, ENTER, or numeric keycode.")
    def keyevent(key: str) -> dict[str, Any]:
        key_name = str(key).upper()
        aliases = {"BACK": "4", "HOME": "3", "ENTER": "66", "DEL": "67", "TAB": "61"}
        return _run_android_input(["keyevent", aliases.get(key_name, key)])

    @registry.register(description="Open an Android app by package name using monkey.")
    def open_app(package: str) -> dict[str, Any]:
        if not package or "/" in package or " " in package:
            raise ValueError("package must be an Android package name")
        monkey = _android_command("monkey")
        completed = subprocess.run([monkey, "-p", package, "-c", "android.intent.category.LAUNCHER", "1"], text=True, capture_output=True, timeout=15)
        return _completed_result(completed)

    return registry


def _workspace_path(path: str) -> Path:
    root = Path.cwd().resolve()
    target = (root / path).resolve()
    if root != target and root not in target.parents:
        raise PermissionError("Path escapes the agent workspace.")
    return target


def _tool_path(path: str, *, allow_outside: bool) -> Path:
    if allow_outside:
        return Path(path).expanduser().resolve()
    return _workspace_path(path)


def _completed_result(completed: subprocess.CompletedProcess[str]) -> dict[str, Any]:
    return {
        "ok": completed.returncode == 0,
        "returncode": completed.returncode,
        "stdout": completed.stdout[-4000:],
        "stderr": completed.stderr[-4000:],
    }


def _android_command(name: str) -> str:
    for candidate in (shutil.which(name), f"/system/bin/{name}"):
        if candidate and Path(candidate).exists():
            return candidate
    raise FileNotFoundError(f"Android command not found: {name}")


def _run_android_input(args: list[str]) -> dict[str, Any]:
    input_cmd = _android_command("input")
    completed = subprocess.run([input_cmd, *args], text=True, capture_output=True, timeout=15)
    return _completed_result(completed)


def _adb_input_text(text: str) -> str:
    return text.replace("%", "%25").replace(" ", "%s")


def _summarize_ui_xml(xml_text: str, *, max_nodes: int) -> list[dict[str, Any]]:
    root = ET.fromstring(xml_text)
    nodes = []
    for item in root.iter("node"):
        text = item.attrib.get("text") or item.attrib.get("content-desc") or ""
        resource_id = item.attrib.get("resource-id", "")
        clickable = item.attrib.get("clickable") == "true"
        editable = item.attrib.get("class", "").endswith("EditText")
        if not text and not resource_id and not clickable and not editable:
            continue
        nodes.append(
            {
                "text": text,
                "resource_id": resource_id,
                "class": item.attrib.get("class", ""),
                "clickable": clickable,
                "editable": editable,
                "bounds": item.attrib.get("bounds", ""),
            }
        )
        if len(nodes) >= max_nodes:
            break
    return nodes
