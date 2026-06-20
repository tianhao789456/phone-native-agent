from __future__ import annotations

import inspect
import json
from dataclasses import dataclass
from typing import Any, Callable, get_args, get_origin, get_type_hints


JsonDict = dict[str, Any]


def _schema_for_type(annotation: Any) -> JsonDict:
    if annotation in (str, inspect.Signature.empty):
        return {"type": "string"}
    if annotation is int:
        return {"type": "integer"}
    if annotation is float:
        return {"type": "number"}
    if annotation is bool:
        return {"type": "boolean"}
    origin = get_origin(annotation)
    if origin in (list, tuple):
        args = get_args(annotation)
        item_type = _schema_for_type(args[0]) if args else {"type": "string"}
        return {"type": "array", "items": item_type}
    if origin is dict:
        return {"type": "object"}
    return {"type": "string"}


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    func: Callable[..., Any]
    parameters: JsonDict

    def call(self, arguments: JsonDict) -> Any:
        bound = bind_arguments(self.func, arguments, self.parameters)
        return self.func(**bound)

    def as_openai_tool(self) -> JsonDict:
        return {
            "type": "function",
            "name": self.name,
            "description": self.description,
            "parameters": self.parameters,
        }

    def as_chat_completions_tool(self) -> JsonDict:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}
        self._openai_tools_cache: list[JsonDict] | None = None

    def register(self, func: Callable[..., Any] | None = None, *, name: str | None = None, description: str | None = None):
        def decorator(fn: Callable[..., Any]) -> Callable[..., Any]:
            tool_name = name or fn.__name__
            if tool_name in self._tools:
                raise ValueError(f"Tool already registered: {tool_name}")
            self._tools[tool_name] = Tool(
                name=tool_name,
                description=description or inspect.getdoc(fn) or tool_name,
                func=fn,
                parameters=function_parameters_schema(fn),
            )
            self._openai_tools_cache = None
            return fn

        if func is None:
            return decorator
        return decorator(func)

    def get(self, name: str) -> Tool:
        try:
            return self._tools[name]
        except KeyError as exc:
            raise KeyError(f"Unknown tool: {name}") from exc

    def names(self) -> list[str]:
        return sorted(self._tools)

    def openai_tools(self) -> list[JsonDict]:
        if self._openai_tools_cache is None:
            self._openai_tools_cache = [tool.as_openai_tool() for tool in self._tools.values()]
        return self._openai_tools_cache

    def list_metadata(self) -> list[JsonDict]:
        return [
            {
                "name": tool.name,
                "description": tool.description,
                "parameters": tool.parameters,
            }
            for tool in self._tools.values()
        ]

    def execute(self, name: str, arguments: JsonDict) -> JsonDict:
        try:
            tool = self.get(name)
            result = tool.call(arguments)
            return {"ok": True, "result": result}
        except Exception as exc:
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}


def function_parameters_schema(fn: Callable[..., Any]) -> JsonDict:
    signature = inspect.signature(fn)
    hints = get_type_hints(fn)
    properties: JsonDict = {}
    required: list[str] = []

    for name, parameter in signature.parameters.items():
        if parameter.kind in (parameter.VAR_POSITIONAL, parameter.VAR_KEYWORD):
            continue
        annotation = hints.get(name, parameter.annotation)
        schema = _schema_for_type(annotation)
        if parameter.default is not inspect.Signature.empty:
            schema["default"] = parameter.default
        else:
            required.append(name)
        properties[name] = schema

    return {
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": False,
    }


def bind_arguments(fn: Callable[..., Any], arguments: JsonDict, schema: JsonDict) -> JsonDict:
    allowed = set((schema.get("properties") or {}).keys())
    cleaned = {key: value for key, value in arguments.items() if key in allowed}
    signature = inspect.signature(fn)
    signature.bind(**cleaned)
    return cleaned


def dumps_tool_result(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)
