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
    category: str = "general"
    enabled_by_default: bool = False
    expose_in_catalog: bool = True

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
        self._model_tools_cache: dict[tuple[str, ...], list[JsonDict]] = {}
        self._register_catalog_tools()

    def register(
        self,
        func: Callable[..., Any] | None = None,
        *,
        name: str | None = None,
        description: str | None = None,
        category: str | None = None,
        enabled_by_default: bool = False,
        expose_in_catalog: bool = True,
    ):
        def decorator(fn: Callable[..., Any]) -> Callable[..., Any]:
            tool_name = name or fn.__name__
            if tool_name in self._tools:
                raise ValueError(f"Tool already registered: {tool_name}")
            self._tools[tool_name] = Tool(
                name=tool_name,
                description=description or inspect.getdoc(fn) or tool_name,
                func=fn,
                parameters=function_parameters_schema(fn),
                category=category or infer_tool_category(fn),
                enabled_by_default=enabled_by_default,
                expose_in_catalog=expose_in_catalog,
            )
            self._clear_caches()
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

    def model_tools(self, loaded_names: set[str] | None = None) -> list[JsonDict]:
        names = set(loaded_names or set())
        names.update(name for name, tool in self._tools.items() if tool.enabled_by_default)
        cache_key = tuple(sorted(names))
        if cache_key not in self._model_tools_cache:
            self._model_tools_cache[cache_key] = [self._tools[name].as_openai_tool() for name in self._tools if name in names]
        return self._model_tools_cache[cache_key]

    def list_metadata(self) -> list[JsonDict]:
        return [
            {
                "name": tool.name,
                "description": tool.description,
                "parameters": tool.parameters,
                "category": tool.category,
                "enabled_by_default": tool.enabled_by_default,
            }
            for tool in self._tools.values()
        ]

    def catalog(self, *, category: str = "", query: str = "", include_parameters: bool = False) -> list[JsonDict]:
        query_normalized = query.strip().lower()
        category_normalized = category.strip().lower()
        items: list[JsonDict] = []
        for tool in self._tools.values():
            if not tool.expose_in_catalog:
                continue
            if category_normalized and tool.category.lower() != category_normalized:
                continue
            haystack = f"{tool.name} {tool.category} {tool.description}".lower()
            if query_normalized and query_normalized not in haystack:
                continue
            item: JsonDict = {
                "name": tool.name,
                "category": tool.category,
                "description": tool.description,
                "loaded": tool.enabled_by_default,
            }
            if include_parameters:
                item["parameters"] = tool.parameters
            items.append(item)
        return sorted(items, key=lambda item: (str(item["category"]), str(item["name"])))

    def tool_info(self, names: list[str]) -> list[JsonDict]:
        details = []
        for name in names:
            tool = self.get(name)
            details.append(
                {
                    "name": tool.name,
                    "category": tool.category,
                    "description": tool.description,
                    "parameters": tool.parameters,
                    "enabled_by_default": tool.enabled_by_default,
                }
            )
        return details

    def execute(self, name: str, arguments: JsonDict) -> JsonDict:
        try:
            tool = self.get(name)
            result = tool.call(arguments)
            return {"ok": True, "result": result}
        except Exception as exc:
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}

    def _clear_caches(self) -> None:
        self._openai_tools_cache = None
        self._model_tools_cache.clear()

    def _register_catalog_tools(self) -> None:
        @self.register(
            description=(
                "List available tools by stable category and short description. "
                "Use this first when the needed tool is not loaded."
            ),
            category="system",
            enabled_by_default=True,
            expose_in_catalog=False,
        )
        def tool_catalog(category: str = "", query: str = "", include_parameters: bool = False) -> list[JsonDict]:
            return self.catalog(category=category, query=query, include_parameters=include_parameters)

        @self.register(
            description=(
                "Load detailed schemas for one or more tool names from tool_catalog. "
                "After calling this, the requested tools become available in the next step."
            ),
            category="system",
            enabled_by_default=True,
            expose_in_catalog=False,
        )
        def tool_info(names: list[str]) -> list[JsonDict]:
            return self.tool_info(names)


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


def infer_tool_category(fn: Callable[..., Any]) -> str:
    module = getattr(fn, "__module__", "")
    if ".phone_toolkits." in module:
        suffix = module.rsplit(".phone_toolkits.", 1)[1].split(".", 1)[0]
        return suffix.removesuffix("_tools")
    if module.endswith(".core.tools"):
        return "system"
    return "general"


def bind_arguments(fn: Callable[..., Any], arguments: JsonDict, schema: JsonDict) -> JsonDict:
    allowed = set((schema.get("properties") or {}).keys())
    cleaned = {key: value for key, value in arguments.items() if key in allowed}
    signature = inspect.signature(fn)
    signature.bind(**cleaned)
    return cleaned


def dumps_tool_result(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)
