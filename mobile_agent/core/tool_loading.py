from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .tools import ToolRegistry


@dataclass
class ToolLoadState:
    loaded_names: set[str] = field(default_factory=set)

    def model_tools(self, registry: ToolRegistry) -> list[dict[str, Any]]:
        return registry.model_tools(self.loaded_names)

    def record_tool_result(self, *, name: str, arguments: dict[str, Any], output: dict[str, Any], registry: ToolRegistry) -> None:
        if name != "tool_info" or not output.get("ok"):
            return
        for tool_name in _requested_tool_names(arguments.get("names")):
            if tool_name in registry.names():
                self.loaded_names.add(tool_name)


def _requested_tool_names(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [str(item) for item in value]
    return []
