from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Protocol


JsonDict = dict[str, Any]


@dataclass
class ToolCall:
    call_id: str
    name: str
    arguments: JsonDict


@dataclass
class ModelResult:
    text: str
    response_id: str | None = None
    tool_calls: list[ToolCall] | None = None
    usage: JsonDict | None = None
    raw: JsonDict | None = None


class LlmClient(Protocol):
    uses_previous_response_id: bool

    def respond(
        self,
        *,
        model: str,
        messages: list[JsonDict],
        tools: list[JsonDict],
        previous_response_id: str | None = None,
    ) -> ModelResult:
        ...

    def continue_with_tool_results(
        self,
        *,
        model: str,
        tool_results: list[JsonDict],
        previous_response_id: str,
        tools: list[JsonDict],
    ) -> ModelResult:
        ...


class OpenAIResponsesClient:
    uses_previous_response_id = True

    def __init__(self, api_key: str | None = None, base_url: str = "https://api.openai.com/v1") -> None:
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY")
        self.base_url = base_url.rstrip("/")
        if not self.api_key:
            raise RuntimeError("OPENAI_API_KEY is not set. Use --mock for local testing.")

    def respond(
        self,
        *,
        model: str,
        messages: list[JsonDict],
        tools: list[JsonDict],
        previous_response_id: str | None = None,
    ) -> ModelResult:
        payload: JsonDict = {"model": model, "input": messages}
        if tools:
            payload["tools"] = tools
        if previous_response_id:
            payload["previous_response_id"] = previous_response_id
        return self._create_response(payload)

    def continue_with_tool_results(
        self,
        *,
        model: str,
        tool_results: list[JsonDict],
        previous_response_id: str,
        tools: list[JsonDict],
    ) -> ModelResult:
        payload: JsonDict = {
            "model": model,
            "previous_response_id": previous_response_id,
            "input": tool_results,
        }
        if tools:
            payload["tools"] = tools
        return self._create_response(payload)

    def _create_response(self, payload: JsonDict) -> ModelResult:
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/responses",
            data=body,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                data = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            error_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"OpenAI API HTTP {exc.code}: {error_body}") from exc
        return parse_responses_result(data)


class MockLlmClient:
    uses_previous_response_id = False

    def __init__(self) -> None:
        self._counter = 0

    def respond(
        self,
        *,
        model: str,
        messages: list[JsonDict],
        tools: list[JsonDict],
        previous_response_id: str | None = None,
    ) -> ModelResult:
        self._counter += 1
        if messages and messages[-1].get("role") == "tool":
            return ModelResult(
                text=f"Tool result: {messages[-1].get('content', '')}",
                response_id=f"mock-{self._counter}",
                usage={"prompt_tokens": 20, "prompt_cache_hit_tokens": 10, "prompt_cache_miss_tokens": 10},
            )
        user_messages = [str(item.get("content", "")) for item in messages if item.get("role") == "user"]
        content = (user_messages[-1] if user_messages else "").lower()
        if "battery" in content and any(tool.get("name") == "battery_status" for tool in tools):
            return ModelResult(
                text="",
                response_id=f"mock-{self._counter}",
                tool_calls=[ToolCall(call_id=f"call-{self._counter}", name="battery_status", arguments={})],
            )
        if "flashlight" in content and any(tool.get("name") == "flashlight" for tool in tools):
            return ModelResult(
                text="",
                response_id=f"mock-{self._counter}",
                tool_calls=[ToolCall(call_id=f"call-{self._counter}", name="flashlight", arguments={"enabled": True})],
            )
        if "sensor" in content and any(tool.get("name") == "sensors" for tool in tools):
            return ModelResult(
                text="",
                response_id=f"mock-{self._counter}",
                tool_calls=[ToolCall(call_id=f"call-{self._counter}", name="sensors", arguments={})],
            )
        if "notify" in content and any(tool.get("name") == "notify" for tool in tools):
            return ModelResult(
                text="",
                response_id=f"mock-{self._counter}",
                tool_calls=[
                    ToolCall(
                        call_id=f"call-{self._counter}",
                        name="notify",
                        arguments={"title": "Mobile Agent", "content": "Mock notification"},
                    )
                ],
            )
        if "photo" in content and any(tool.get("name") == "camera_photo" for tool in tools):
            return ModelResult(
                text="",
                response_id=f"mock-{self._counter}",
                tool_calls=[
                    ToolCall(
                        call_id=f"call-{self._counter}",
                        name="camera_photo",
                        arguments={"path": "captures/mock-photo.jpg", "camera_id": 0},
                    )
                ],
            )
        if "time" in content and any(tool.get("name") == "get_time" for tool in tools):
            return ModelResult(
                text="",
                response_id=f"mock-{self._counter}",
                tool_calls=[ToolCall(call_id=f"call-{self._counter}", name="get_time", arguments={})],
            )
        return ModelResult(
            text="Mock agent ready. Ask about time or battery to exercise tool calls.",
            response_id=f"mock-{self._counter}",
            usage={"prompt_tokens": 10, "prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 10},
        )

    def continue_with_tool_results(
        self,
        *,
        model: str,
        tool_results: list[JsonDict],
        previous_response_id: str,
        tools: list[JsonDict],
    ) -> ModelResult:
        self._counter += 1
        return ModelResult(
            text=f"Tool result: {json.dumps(tool_results, ensure_ascii=False)}",
            response_id=f"mock-{self._counter}",
            usage={"prompt_tokens": 20, "prompt_cache_hit_tokens": 10, "prompt_cache_miss_tokens": 10},
        )


class OpenAICompatibleChatClient:
    uses_previous_response_id = False

    def __init__(self, *, api_key: str, base_url: str) -> None:
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")

    def respond(
        self,
        *,
        model: str,
        messages: list[JsonDict],
        tools: list[JsonDict],
        previous_response_id: str | None = None,
    ) -> ModelResult:
        payload: JsonDict = {"model": model, "messages": messages}
        if tools:
            payload["tools"] = [_responses_tool_to_chat_tool(tool) for tool in tools]
            payload["tool_choice"] = "auto"
        return self._create_chat_completion(payload)

    def continue_with_tool_results(
        self,
        *,
        model: str,
        tool_results: list[JsonDict],
        previous_response_id: str,
        tools: list[JsonDict],
    ) -> ModelResult:
        raise NotImplementedError("Chat completions clients receive tool results through stored messages.")

    def _create_chat_completion(self, payload: JsonDict) -> ModelResult:
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=body,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                data = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            error_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Chat API HTTP {exc.code}: {error_body}") from exc
        return parse_chat_completion_result(data)


def parse_responses_result(data: JsonDict) -> ModelResult:
    output = data.get("output", [])
    texts: list[str] = []
    tool_calls: list[ToolCall] = []
    for item in output:
        item_type = item.get("type")
        if item_type == "function_call":
            args = item.get("arguments") or {}
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except json.JSONDecodeError:
                    args = {}
            tool_calls.append(
                ToolCall(
                    call_id=str(item.get("call_id") or item.get("id") or ""),
                    name=str(item.get("name") or ""),
                    arguments=args,
                )
            )
        if item_type == "message":
            for content in item.get("content", []):
                if content.get("type") in ("output_text", "text"):
                    texts.append(str(content.get("text", "")))
    if not texts and isinstance(data.get("output_text"), str):
        texts.append(data["output_text"])
    return ModelResult(
        text="\n".join(text for text in texts if text),
        response_id=data.get("id"),
        tool_calls=tool_calls,
        usage=data.get("usage"),
        raw=data,
    )


def parse_chat_completion_result(data: JsonDict) -> ModelResult:
    choices = data.get("choices") or []
    if not choices:
        return ModelResult(text="", response_id=data.get("id"), raw=data)
    message = choices[0].get("message") or {}
    tool_calls = []
    for item in message.get("tool_calls") or []:
        function = item.get("function") or {}
        args = function.get("arguments") or {}
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {}
        tool_calls.append(
            ToolCall(
                call_id=str(item.get("id") or ""),
                name=str(function.get("name") or ""),
                arguments=args,
            )
        )
    return ModelResult(
        text=str(message.get("content") or ""),
        response_id=data.get("id"),
        tool_calls=tool_calls,
        usage=data.get("usage"),
        raw=data,
    )


def _responses_tool_to_chat_tool(tool: JsonDict) -> JsonDict:
    return {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool.get("description", ""),
            "parameters": tool.get("parameters", {"type": "object", "properties": {}}),
        },
    }
