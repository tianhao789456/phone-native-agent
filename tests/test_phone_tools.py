from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import subprocess

from mobile_agent.phone_tools import build_registry


class FakeTermuxApi:
    instances: list["FakeTermuxApi"] = []

    def __init__(self) -> None:
        self.calls: list[tuple[str, list[str], int]] = []
        FakeTermuxApi.instances.append(self)

    def run_text(self, args: list[str], *, timeout: int):
        self.calls.append(("text", args, timeout))
        return {"available": True, "ok": True, "returncode": 0, "stdout": "", "stderr": ""}

    def run_json(self, args: list[str], *, timeout: int):
        self.calls.append(("json", args, timeout))
        return {"available": True, "ok": True, "items": []}


class FakeHostBridge:
    instances: list["FakeHostBridge"] = []

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        FakeHostBridge.instances.append(self)

    @classmethod
    def from_env(cls):
        return cls()

    def status(self):
        return {"ok": True, "host": "fake-host"}

    def tools(self):
        return {"ok": True, "tools": [{"name": "accessibility.dump"}]}

    def call(self, tool: str, arguments: dict | None = None, **kwargs):
        args = arguments or {}
        self.calls.append((tool, args))
        return {"ok": True, "tool": tool, "arguments": args}


class PhoneToolTests(unittest.TestCase):
    def setUp(self) -> None:
        FakeTermuxApi.instances.clear()
        FakeHostBridge.instances.clear()

    def test_flashlight_maps_boolean_to_termux_torch_state(self) -> None:
        with patch("mobile_agent.phone_tools.TermuxApi", FakeTermuxApi):
            registry = build_registry()
            registry.execute("flashlight", {"enabled": True})
            registry.execute("flashlight", {"enabled": False})

        calls = FakeTermuxApi.instances[0].calls
        self.assertEqual(calls[0], ("text", ["termux-torch", "on"], 10))
        self.assertEqual(calls[1], ("text", ["termux-torch", "off"], 10))

    def test_notify_rejects_blank_title_or_content(self) -> None:
        with patch("mobile_agent.phone_tools.TermuxApi", FakeTermuxApi):
            registry = build_registry()

        self.assertFalse(registry.execute("notify", {"title": "", "content": "hello"})["ok"])
        self.assertFalse(registry.execute("notify", {"title": "hello", "content": ""})["ok"])

    def test_camera_photo_writes_under_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            old = Path.cwd()
            try:
                import os

                os.chdir(tmp)
                with patch("mobile_agent.phone_tools.TermuxApi", FakeTermuxApi):
                    registry = build_registry()
                    result = registry.execute("camera_photo", {"path": "captures/test.jpg", "camera_id": 1})
            finally:
                os.chdir(old)

        self.assertTrue(result["ok"])
        call = FakeTermuxApi.instances[0].calls[0]
        self.assertEqual(call[0], "text")
        self.assertEqual(call[1][0:3], ["termux-camera-photo", "-c", "1"])
        self.assertTrue(call[1][3].endswith("captures\\test.jpg") or call[1][3].endswith("captures/test.jpg"))

    def test_sensors_uses_json_command(self) -> None:
        with patch("mobile_agent.phone_tools.TermuxApi", FakeTermuxApi):
            registry = build_registry()
            registry.execute("sensors", {})

        self.assertEqual(FakeTermuxApi.instances[0].calls[0], ("json", ["termux-sensor", "-l"], 10))

    def test_run_shell_requires_danger_mode(self) -> None:
        safe_registry = build_registry(permission_mode="safe")
        danger_registry = build_registry(permission_mode="danger")

        self.assertFalse(safe_registry.execute("run_shell", {"command": "echo hi"})["ok"])
        with patch("mobile_agent.phone_tools.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(args=[], returncode=0, stdout="hi\n", stderr="")
            result = danger_registry.execute("run_shell", {"command": "echo hi"})

        self.assertTrue(result["ok"])
        self.assertEqual(result["result"]["stdout"], "hi\n")

    def test_file_tools_restrict_workspace_unless_danger_mode(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            old = Path.cwd()
            outside = Path(tmp).parent / f"mobile-agent-outside-{Path(tmp).name}.txt"
            try:
                import os

                os.chdir(tmp)
                safe_registry = build_registry(permission_mode="safe")
                danger_registry = build_registry(permission_mode="danger")
                self.assertFalse(safe_registry.execute("write_file", {"path": str(outside), "content": "x"})["ok"])
                result = danger_registry.execute("write_file", {"path": str(outside), "content": "x", "overwrite": True})
                self.assertTrue(result["ok"])
                self.assertEqual(outside.read_text(encoding="utf-8"), "x")
            finally:
                os.chdir(old)
                if outside.exists():
                    outside.unlink()

    def test_run_script_executes_workspace_python_script(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            old = Path.cwd()
            try:
                import os

                os.chdir(tmp)
                script = Path("hello.py")
                script.write_text("print('ok')\n", encoding="utf-8")
                registry = build_registry()
                result = registry.execute("run_script", {"path": "hello.py"})
            finally:
                os.chdir(old)

        self.assertTrue(result["ok"])
        self.assertEqual(result["result"]["returncode"], 0)
        self.assertIn("ok", result["result"]["stdout"])

    def test_android_input_tools_use_system_input_command(self) -> None:
        calls = []

        def fake_run(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args=args, returncode=0, stdout="", stderr="")

        with patch("mobile_agent.phone_tools._android_command", lambda name: f"/system/bin/{name}"):
            with patch("mobile_agent.phone_tools.subprocess.run", fake_run):
                registry = build_registry()
                self.assertTrue(registry.execute("tap", {"x": 1, "y": 2})["ok"])
                self.assertTrue(registry.execute("type_text", {"text": "hello world"})["ok"])

        self.assertEqual(calls[0], ["/system/bin/input", "tap", "1", "2"])
        self.assertEqual(calls[1], ["/system/bin/input", "text", "hello%sworld"])

    def test_screen_dump_reports_uiautomator_failure_with_hint(self) -> None:
        def fake_run(args, **kwargs):
            return subprocess.CompletedProcess(args=args, returncode=127, stdout="", stderr="app_process inaccessible")

        with patch("mobile_agent.phone_tools._android_command", lambda name: f"/system/bin/{name}"):
            with patch("mobile_agent.phone_tools.subprocess.run", fake_run):
                registry = build_registry()
                result = registry.execute("screen_dump", {})

        self.assertTrue(result["ok"])
        self.assertFalse(result["result"]["ok"])
        self.assertIn("Accessibility", result["result"]["hint"])

    def test_host_accessibility_wrappers_call_bridge_tools(self) -> None:
        with patch("mobile_agent.phone_tools.HostBridgeClient", FakeHostBridge):
            registry = build_registry()
            self.assertTrue(registry.execute("host_workspace_info", {})["ok"])
            self.assertTrue(registry.execute("host_list_files", {"path": "notes", "max_entries": 10})["ok"])
            self.assertTrue(registry.execute("host_read_file", {"path": "notes/a.txt", "max_bytes": 12})["ok"])
            self.assertTrue(registry.execute("host_write_file", {"path": "notes/a.txt", "content": "hello", "overwrite": True})["ok"])
            self.assertTrue(registry.execute("host_search_files", {"query": "hello", "path": "notes", "max_matches": 3})["ok"])
            self.assertTrue(registry.execute("host_observe", {"max_nodes": 7})["ok"])
            self.assertTrue(registry.execute("host_screen_dump", {"max_nodes": 5})["ok"])
            self.assertTrue(registry.execute("host_screen_find", {"query": "OK", "contains": False, "max_nodes": 2})["ok"])
            self.assertTrue(registry.execute("host_current_app", {})["ok"])
            self.assertTrue(registry.execute("host_click_text", {"text": "OK", "contains": False})["ok"])
            self.assertTrue(registry.execute("host_click_index", {"index": 3})["ok"])
            self.assertTrue(registry.execute("host_input_text", {"text": "hello"})["ok"])
            self.assertTrue(registry.execute("host_back", {})["ok"])

        calls = FakeHostBridge.instances[0].calls
        self.assertEqual(calls[0], ("workspace.info", {}))
        self.assertEqual(calls[1], ("workspace.list", {"path": "notes", "max_entries": 10}))
        self.assertEqual(calls[2], ("workspace.read", {"path": "notes/a.txt", "max_bytes": 12}))
        self.assertEqual(calls[3], ("workspace.write", {"path": "notes/a.txt", "content": "hello", "overwrite": True}))
        self.assertEqual(
            calls[4],
            ("workspace.search", {"query": "hello", "path": "notes", "max_matches": 3, "max_bytes_per_file": 200000}),
        )
        self.assertEqual(calls[5], ("accessibility_snapshot_v2", {"max_nodes": 7}))
        self.assertEqual(calls[6], ("accessibility_snapshot_v2", {"max_nodes": 5}))
        self.assertEqual(calls[7], ("accessibility.find", {"query": "OK", "contains": False, "max_nodes": 2}))
        self.assertEqual(calls[8], ("accessibility.current_app", {}))
        self.assertEqual(calls[9], ("accessibility.click_text", {"text": "OK", "contains": False}))
        self.assertEqual(calls[10], ("accessibility.click_index", {"index": 3}))
        self.assertEqual(calls[11], ("accessibility.input_text", {"text": "hello"}))
        self.assertEqual(calls[12], ("host_press_key", {"key": "back"}))


if __name__ == "__main__":
    unittest.main()
