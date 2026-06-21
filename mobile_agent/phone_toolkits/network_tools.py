from __future__ import annotations

from typing import Any

from mobile_agent.core.tools import ToolRegistry


def register_network_tools(registry: ToolRegistry, *, urllib_request: Any) -> None:
    @registry.register(description="Fetch an HTTP or HTTPS URL with a short timeout and byte limit.")
    def http_get(url: str, max_bytes: int = 50000) -> dict[str, Any]:
        if not (url.startswith("http://") or url.startswith("https://")):
            raise ValueError("Only http:// and https:// URLs are allowed.")
        if max_bytes < 1 or max_bytes > 200000:
            raise ValueError("max_bytes must be between 1 and 200000")
        request = urllib_request.Request(url, headers={"User-Agent": "mobile-agent/0.1"})
        with urllib_request.urlopen(request, timeout=15) as response:
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
