from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


JsonDict = dict[str, Any]


class HostBridgeError(RuntimeError):
    pass


@dataclass(frozen=True)
class HostBridgeClient:
    base_url: str = "http://127.0.0.1:8790"
    timeout: int = 10

    @classmethod
    def from_env(cls) -> "HostBridgeClient":
        return cls(
            base_url=os.environ.get("MOBILE_AGENT_HOST_URL", "http://127.0.0.1:8790").rstrip("/"),
            timeout=int(os.environ.get("MOBILE_AGENT_HOST_TIMEOUT", "10")),
        )

    def health(self) -> JsonDict:
        return self._request("GET", "/health")

    def status(self) -> JsonDict:
        return self._request("GET", "/status")

    def tools(self) -> JsonDict:
        return self._request("GET", "/tools")

    def call(self, tool: str, arguments: JsonDict | None = None, *, actions_approved: bool = False) -> JsonDict:
        if not tool.strip():
            raise ValueError("tool is required")
        payload: JsonDict = {"tool": tool, "arguments": arguments or {}}
        if actions_approved:
            payload["actions_approved"] = True
        return self._request("POST", "/tools/call", payload)

    def _request(self, method: str, path: str, body: JsonDict | None = None) -> JsonDict:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self.base_url}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = response.read()
                if not payload:
                    return {"ok": True, "status": response.status}
                return json.loads(payload.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise HostBridgeError(f"Host bridge HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise HostBridgeError(f"Host bridge unavailable: {exc}") from exc
