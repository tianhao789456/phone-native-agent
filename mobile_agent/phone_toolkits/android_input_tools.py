from __future__ import annotations

from pathlib import Path
from typing import Any, Callable
import xml.etree.ElementTree as ET

from mobile_agent.core.tools import ToolRegistry


def register_android_input_tools(
    registry: ToolRegistry,
    *,
    android_command: Callable[[str], str],
    adb_input_text: Callable[[str], str],
    completed_result: Callable[[Any], dict[str, Any]],
    subprocess_module: Any,
) -> None:
    def run_android_input(args: list[str]) -> dict[str, Any]:
        input_cmd = android_command("input")
        completed = subprocess_module.run([input_cmd, *args], text=True, capture_output=True, timeout=15)
        return completed_result(completed)

    @registry.register(description="Return a text summary of the current Android screen using uiautomator XML, no vision model required.")
    def screen_dump(max_nodes: int = 60) -> dict[str, Any]:
        if max_nodes < 1 or max_nodes > 200:
            raise ValueError("max_nodes must be between 1 and 200")
        uiautomator = android_command("uiautomator")
        dump_path = Path.cwd() / ".mobile-agent-window.xml"
        completed = subprocess_module.run([uiautomator, "dump", str(dump_path)], text=True, capture_output=True, timeout=15)
        if completed.returncode != 0:
            result = completed_result(completed)
            result["hint"] = "Termux cannot access uiautomator on some Android builds. Use ADB/Shizuku/Accessibility bridge for screen XML."
            return result
        xml_text = dump_path.read_text(encoding="utf-8", errors="replace")
        return {"xml_path": str(dump_path), "nodes": summarize_ui_xml(xml_text, max_nodes=max_nodes)}

    @registry.register(description="Tap Android screen coordinates.")
    def tap(x: int, y: int) -> dict[str, Any]:
        return run_android_input(["tap", str(x), str(y)])

    @registry.register(description="Swipe Android screen coordinates.")
    def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> dict[str, Any]:
        return run_android_input(["swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)])

    @registry.register(description="Type text into the focused Android input field.")
    def type_text(text: str) -> dict[str, Any]:
        if not text:
            raise ValueError("text is required")
        return run_android_input(["text", adb_input_text(text)])

    @registry.register(description="Send an Android keyevent such as BACK, HOME, ENTER, or numeric keycode.")
    def keyevent(key: str) -> dict[str, Any]:
        key_name = str(key).upper()
        aliases = {"BACK": "4", "HOME": "3", "ENTER": "66", "DEL": "67", "TAB": "61"}
        return run_android_input(["keyevent", aliases.get(key_name, key)])

    @registry.register(description="Open an Android app by package name using monkey.")
    def open_app(package: str) -> dict[str, Any]:
        if not package or "/" in package or " " in package:
            raise ValueError("package must be an Android package name")
        monkey = android_command("monkey")
        completed = subprocess_module.run(
            [monkey, "-p", package, "-c", "android.intent.category.LAUNCHER", "1"],
            text=True,
            capture_output=True,
            timeout=15,
        )
        return completed_result(completed)


def summarize_ui_xml(xml_text: str, *, max_nodes: int) -> list[dict[str, Any]]:
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
