from __future__ import annotations

from typing import Any, Iterable

from .tools import dumps_tool_result


def normalize_message_content(message: dict[str, Any]) -> str:
    return str(message.get("content", ""))


def build_initial_messages(
    messages: list[dict[str, Any]],
    *,
    use_previous_response_context: bool,
    previous_response_id: str | None,
    current_message: str,
) -> list[dict[str, Any]]:
    if use_previous_response_context and previous_response_id:
        return [{"role": "user", "content": current_message}]
    return build_history_messages(messages)


def build_history_messages(messages: list[dict[str, Any]], *, limit: int = 30) -> list[dict[str, Any]]:
    cleaned: list[dict[str, Any]] = []
    for message in messages:
        role = message.get("role")
        content = normalize_message_content(message)
        if role in {"system", "user"}:
            cleaned.append({"role": role, "content": content})
        elif role == "assistant":
            cleaned.append({"role": "assistant", "content": content})
        elif role == "tool":
            name = message.get("name") or "tool"
            cleaned.append({"role": "assistant", "content": f"Previous {name} result: {content}"})
    return cleaned[-limit:]


def build_protocol_messages(messages: list[dict[str, Any]], *, limit: int) -> list[dict[str, Any]]:
    cleaned: list[dict[str, Any]] = []
    pending_tool_calls: set[str] = set()
    for message in messages:
        role = message.get("role")
        if role == "assistant" and message.get("tool_calls"):
            item = {key: value for key, value in message.items() if key in {"role", "content", "tool_calls"}}
            cleaned.append(item)
            pending_tool_calls = {
                str(call.get("id"))
                for call in message.get("tool_calls", [])
                if isinstance(call, dict) and call.get("id")
            }
            continue

        if role == "tool":
            tool_call_id = str(message.get("tool_call_id") or "")
            if tool_call_id and tool_call_id in pending_tool_calls:
                cleaned.append(
                    {
                        key: value
                        for key, value in message.items()
                        if key in {"role", "content", "tool_call_id", "name"}
                    }
                )
                pending_tool_calls.discard(tool_call_id)
            else:
                name = message.get("name") or "tool"
                cleaned.append({"role": "assistant", "content": f"Previous {name} result: {message.get('content', '')}"})
            continue

        if role in {"system", "user", "assistant"}:
            cleaned.append({"role": role, "content": normalize_message_content(message)})
            pending_tool_calls = set()

    trimmed = cleaned[-limit:]
    while trimmed and trimmed[0].get("role") == "tool":
        trimmed.pop(0)
    return trimmed


def tool_call_messages(tool_calls: Iterable[Any]) -> list[dict[str, Any]]:
    return [
        {
            "id": call.call_id,
            "type": "function",
            "function": {
                "name": call.name,
                "arguments": dumps_tool_result(call.arguments),
            },
        }
        for call in tool_calls
    ]


def tool_output_messages(tool_outputs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": "function_call_output",
            "call_id": item["call_id"],
            "output": dumps_tool_result(item["output"]),
        }
        for item in tool_outputs
    ]
