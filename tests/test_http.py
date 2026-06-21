from __future__ import annotations

import json
import tempfile
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from mobile_agent.core.agent import Agent, AgentConfig
from mobile_agent.core.llm import MockLlmClient
from mobile_agent.core.store import ConversationStore
from mobile_agent.hosts.http_server import make_handler
from mobile_agent.phone_tools import build_registry


class HttpTests(unittest.TestCase):
    def test_health_tools_and_chat(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=build_registry(),
                store=ConversationStore(Path(tmp)),
            )
            server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(agent))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                health = self._json_get(f"{base}/health")
                self.assertTrue(health["ok"])
                self.assertIn("get_time", health["tools"])

                status = self._json_get(f"{base}/status")
                self.assertEqual(status["model"], "mock")
                self.assertEqual(status["permission_mode"], "safe")
                self.assertIn("context", status)
                self.assertIn("session", status)

                tools = self._json_get(f"{base}/tools")
                self.assertTrue(any(tool["name"] == "battery_status" for tool in tools["tools"]))

                terminal_status = self._json_get(f"{base}/terminal/status")
                self.assertTrue(terminal_status["ok"])
                self.assertEqual(terminal_status["permission_mode"], "safe")

                blocked_terminal = self._json_post(f"{base}/terminal/run", {"command": "echo hi"})
                self.assertFalse(blocked_terminal["ok"])
                self.assertTrue(blocked_terminal["needs_permission"])

                blocked_script = self._json_post(f"{base}/terminal/script", {"script": "echo hi"})
                self.assertFalse(blocked_script["ok"])
                self.assertTrue(blocked_script["needs_permission"])

                chat = self._json_post(f"{base}/chat", {"message": "what time is it?"})
                self.assertEqual(chat["tool_trace"][0]["tool"], "get_time")

                self_test = self._json_get(f"{base}/self-test")
                self.assertIn("status", self_test)
                self.assertIn("checks", self_test)
                self.assertIn("summary", self_test)
                self.assertIn("session_store", [check["name"] for check in self_test["checks"]])
            finally:
                server.shutdown()
                server.server_close()

    def test_terminal_run_executes_in_danger_mode(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=build_registry(permission_mode="danger"),
                store=ConversationStore(Path(tmp)),
            )
            agent.permission_mode = "danger"
            server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(agent))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                result = self._json_post(f"{base}/terminal/run", {"command": "echo hi", "cwd": tmp})
                self.assertTrue(result["ok"])
                self.assertEqual(result["returncode"], 0)
                self.assertIn("hi", result["stdout"])
                self.assertIn("output", result)
                self.assertFalse(result["output"]["stdout"]["truncated"])
            finally:
                server.shutdown()
                server.server_close()

    def test_terminal_script_task_executes_and_persists_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=build_registry(permission_mode="danger"),
                store=ConversationStore(Path(tmp)),
            )
            agent.permission_mode = "danger"
            server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(agent))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                result = self._json_post(
                    f"{base}/terminal/script",
                    {
                        "script": "echo start\nfor i in $(seq 1 300); do echo line-$i; done",
                        "cwd": tmp,
                        "timeout": 10,
                        "max_output_chars": 20,
                    },
                )
                self.assertEqual(result["status"], "finished")
                self.assertEqual(result["returncode"], 0)
                self.assertTrue(result["ok"])
                self.assertTrue(result["output"]["stdout"]["truncated"])
                self.assertTrue(Path(result["script_path"]).exists())
                self.assertTrue(Path(result["stdout_path"]).exists())

                loaded = self._json_get(f"{base}/terminal/tasks/{result['task_id']}")
                self.assertEqual(loaded["task_id"], result["task_id"])
                self.assertIn("line-300", Path(loaded["stdout_path"]).read_text(encoding="utf-8"))
            finally:
                server.shutdown()
                server.server_close()

    def _json_get(self, url: str):
        with urllib.request.urlopen(url, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))

    def _json_post(self, url: str, payload: dict):
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))


if __name__ == "__main__":
    unittest.main()
