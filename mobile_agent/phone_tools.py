from __future__ import annotations

import shutil
import subprocess
import urllib.request
import webbrowser
from pathlib import Path
from typing import Any

from .action_confirmation import current_action_approved
from .core.tools import ToolRegistry
from .host_bridge import HostBridgeClient
from .phone_toolkits.android_input_tools import register_android_input_tools, summarize_ui_xml
from .phone_toolkits.core_tools import register_core_tools
from .phone_toolkits.host_bridge_tools import register_host_bridge_tools
from .phone_toolkits.network_tools import register_network_tools
from .phone_toolkits.pathing import completed_result, tool_path, workspace_path
from .phone_toolkits.shell_tools import register_shell_tools
from .phone_toolkits.termux_tools import register_termux_tools
from .phone_toolkits.workspace_tools import register_workspace_tools
from .termux_api import TermuxApi


def build_registry(allowed_shell_commands: list[str] | None = None, *, permission_mode: str = "safe") -> ToolRegistry:
    registry = ToolRegistry()
    allowed = set(allowed_shell_commands or [])
    termux_api = TermuxApi()
    host_bridge = HostBridgeClient.from_env()
    danger = permission_mode == "danger"

    register_core_tools(registry, permission_mode=permission_mode)
    register_workspace_tools(registry, danger=danger, tool_path=_tool_path_for_registration)
    register_network_tools(registry, urllib_request=urllib.request)
    register_host_bridge_tools(
        registry,
        host_bridge=host_bridge,
        current_action_approved=current_action_approved,
    )
    register_termux_tools(
        registry,
        termux_api=termux_api,
        danger=danger,
        tool_path=_tool_path_for_registration,
        subprocess_module=subprocess,
        shutil_module=shutil,
        webbrowser_module=webbrowser,
    )
    register_shell_tools(
        registry,
        allowed=allowed,
        danger=danger,
        tool_path=_tool_path_for_registration,
        subprocess_module=subprocess,
        shutil_module=shutil,
        completed_result=_completed_result,
    )
    register_android_input_tools(
        registry,
        android_command=_android_command,
        adb_input_text=_adb_input_text,
        completed_result=_completed_result,
        subprocess_module=subprocess,
    )
    return registry


def _workspace_path(path: str) -> Path:
    return workspace_path(path)


def _tool_path(path: str, *, allow_outside: bool) -> Path:
    return tool_path(path, allow_outside=allow_outside)


def _tool_path_for_registration(path: str, allow_outside: bool) -> Path:
    return _tool_path(path, allow_outside=allow_outside)


def _completed_result(completed: subprocess.CompletedProcess[str]) -> dict[str, Any]:
    return completed_result(completed)


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
    return summarize_ui_xml(xml_text, max_nodes=max_nodes)
