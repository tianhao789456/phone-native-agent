from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mobile_agent.host_bridge import HostBridgeClient, HostBridgeError


class FakeHostHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/status":
            self._json({"ok": True, "host": "android-host", "backends": ["accessibility"]})
            return
        if self.path == "/tools":
            self._json({"ok": True, "tools": [{"name": "accessibility.status"}]})
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("content-length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        if self.path == "/tools/call":
            self._json({"ok": True, "called": body})
            return
        self.send_error(404)

    def log_message(self, format, *args):
        return

    def _json(self, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class HostBridgeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeHostHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.client = HostBridgeClient(f"http://{host}:{port}")

    def tearDown(self) -> None:
        self.server.shutdown()
        self.thread.join(timeout=2)
        self.server.server_close()

    def test_status_and_tools(self) -> None:
        self.assertEqual(self.client.status()["host"], "android-host")
        self.assertEqual(self.client.tools()["tools"][0]["name"], "accessibility.status")

    def test_call_posts_tool_arguments(self) -> None:
        result = self.client.call("accessibility.status", {"verbose": True})
        self.assertEqual(result["called"]["tool"], "accessibility.status")
        self.assertEqual(result["called"]["arguments"], {"verbose": True})

    def test_call_can_send_action_approval_flag(self) -> None:
        result = self.client.call("accessibility.back", {}, actions_approved=True)
        self.assertTrue(result["called"]["actions_approved"])

    def test_unavailable_bridge_raises_clear_error(self) -> None:
        client = HostBridgeClient("http://127.0.0.1:1", timeout=1)
        with self.assertRaises(HostBridgeError):
            client.status()


if __name__ == "__main__":
    unittest.main()
