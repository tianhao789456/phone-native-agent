from __future__ import annotations

from dataclasses import dataclass
import time
import uuid
from typing import Any, Callable

from mobile_agent.action_confirmation import action_approval, is_action_tool
from .llm import LlmClient
from .store import ConversationStore, Session
from .tools import ToolRegistry, dumps_tool_result
from .context import build_compaction_prompt, compact_messages, context_stats


@dataclass
class AgentConfig:
    model: str
    system_prompt: str
    max_tool_rounds: int = 5


class Agent:
    def __init__(self, *, config: AgentConfig, llm: LlmClient, tools: ToolRegistry, store: ConversationStore) -> None:
        self.config = config
        self.llm = llm
        self.tools = tools
        self.store = store

    def chat(
        self,
        message: str,
        session_id: str | None = None,
        confirm_action: Callable[[str, dict[str, Any]], bool] | None = None,
    ) -> dict[str, Any]:
        run_id = str(uuid.uuid4())
        started_at = time.time()
        session = self.store.load(session_id)
        if not session.messages:
            session.messages.append({"role": "system", "content": self.config.system_prompt})
        session.messages.append({"role": "user", "content": message})

        if self.llm.uses_previous_response_id and session.previous_response_id:
            prompt_messages = [{"role": "user", "content": message}]
        else:
            prompt_messages = self._history_messages(session.messages)

        model_started = time.time()
        result = self.llm.respond(
            model=self.config.model,
            messages=prompt_messages[-30:],
            tools=self.tools.openai_tools(),
            previous_response_id=session.previous_response_id,
        )
        spans: list[dict[str, Any]] = [
            {
                "kind": "llm",
                "name": "respond",
                "started_at": model_started,
                "ended_at": time.time(),
                "ok": True,
                "response_id": result.response_id,
                "tool_calls": len(result.tool_calls or []),
                "usage": result.usage,
            }
        ]

        trace: list[dict[str, Any]] = []
        rounds = 0
        while result.tool_calls and rounds < self.config.max_tool_rounds:
            rounds += 1
            assistant_tool_calls = [
                {
                    "id": call.call_id,
                    "type": "function",
                    "function": {
                        "name": call.name,
                        "arguments": dumps_tool_result(call.arguments),
                    },
                }
                for call in result.tool_calls
            ]
            if not self.llm.uses_previous_response_id:
                session.messages.append({"role": "assistant", "content": result.text or "", "tool_calls": assistant_tool_calls})
            tool_outputs = []
            for call in result.tool_calls:
                tool_started = time.time()
                approved = self._confirm_tool_action(call.name, call.arguments, confirm_action)
                if approved is None:
                    output = {
                        "ok": False,
                        "needs_confirmation": True,
                        "error": f"Action tool requires user confirmation: {call.name}",
                    }
                else:
                    with action_approval(approved):
                        output = self.tools.execute(call.name, call.arguments)
                tool_ended = time.time()
                event = {
                    "tool": call.name,
                    "arguments": call.arguments,
                    "output": output,
                    "created_at": tool_started,
                }
                trace.append(event)
                spans.append(
                    {
                        "kind": "tool",
                        "name": call.name,
                        "started_at": tool_started,
                        "ended_at": tool_ended,
                        "ok": bool(output.get("ok")),
                        "input": call.arguments,
                        "output": output,
                    }
                )
                session.messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.call_id,
                        "name": call.name,
                        "content": dumps_tool_result(output),
                    }
                )
                tool_outputs.append(
                    {
                        "type": "function_call_output",
                        "call_id": call.call_id,
                        "output": dumps_tool_result(output),
                    }
                )
            if not result.response_id:
                break
            if self.llm.uses_previous_response_id:
                model_started = time.time()
                result = self.llm.continue_with_tool_results(
                    model=self.config.model,
                    previous_response_id=result.response_id,
                    tool_results=tool_outputs,
                    tools=self.tools.openai_tools(),
                )
            else:
                model_started = time.time()
                result = self.llm.respond(
                    model=self.config.model,
                    messages=self._protocol_messages(session.messages, limit=30),
                    tools=self.tools.openai_tools(),
                )
            spans.append(
                {
                    "kind": "llm",
                    "name": "continue",
                    "started_at": model_started,
                    "ended_at": time.time(),
                    "ok": True,
                    "response_id": result.response_id,
                    "tool_calls": len(result.tool_calls or []),
                    "usage": result.usage,
                }
            )

        final_text = result.text
        if result.tool_calls and rounds >= self.config.max_tool_rounds and not final_text:
            final_text = f"Stopped after {self.config.max_tool_rounds} tool rounds without a final model answer."
        session.previous_response_id = result.response_id or session.previous_response_id
        session.messages.append({"role": "assistant", "content": final_text})
        run_trace = {
            "id": run_id,
            "started_at": started_at,
            "ended_at": time.time(),
            "response_id": result.response_id,
            "tool_rounds": rounds,
            "final_text": final_text,
            "spans": spans,
        }
        session.traces.append(run_trace)
        self.store.save(session)
        return {
            "session_id": session.id,
            "run_id": run_id,
            "message": final_text,
            "tool_trace": trace,
            "tool_rounds": rounds,
            "trace": run_trace,
            "context": context_stats(session.messages),
        }

    def compact(self, session_id: str, *, keep_last: int = 12) -> dict[str, Any]:
        session = self.store.load(session_id)
        if len(session.messages) <= keep_last + 2:
            return {
                "session_id": session.id,
                "compacted": False,
                "reason": "not_enough_history",
                "context": context_stats(session.messages),
            }

        before = context_stats(session.messages)
        prompt = build_compaction_prompt(session.messages, keep_last)
        started_at = time.time()
        result = self.llm.respond(
            model=self.config.model,
            messages=[
                {"role": "system", "content": self.config.system_prompt},
                {"role": "user", "content": prompt},
            ],
            tools=[],
        )
        session.messages = compact_messages(session.messages, result.text, keep_last=keep_last)
        after = context_stats(session.messages)
        session.traces.append(
            {
                "id": str(uuid.uuid4()),
                "kind": "compact",
                "started_at": started_at,
                "ended_at": time.time(),
                "response_id": result.response_id,
                "usage": result.usage,
                "before": before,
                "after": after,
            }
        )
        self.store.save(session)
        return {"session_id": session.id, "compacted": True, "before": before, "after": after}

    def _confirm_tool_action(
        self,
        name: str,
        arguments: dict[str, Any],
        confirm_action: Callable[[str, dict[str, Any]], bool] | None,
    ) -> bool | None:
        if not is_action_tool(name):
            return False
        if confirm_action is None:
            return None
        return bool(confirm_action(name, arguments))

    def _history_messages(self, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        cleaned = []
        for message in messages:
            role = message.get("role")
            content = str(message.get("content", ""))
            if role in {"system", "user"}:
                cleaned.append({"role": role, "content": content})
            elif role == "assistant":
                cleaned.append({"role": "assistant", "content": content})
            elif role == "tool":
                name = message.get("name") or "tool"
                cleaned.append({"role": "assistant", "content": f"Previous {name} result: {content}"})
        return cleaned[-30:]

    def _protocol_messages(self, messages: list[dict[str, Any]], *, limit: int) -> list[dict[str, Any]]:
        cleaned = []
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
            elif role == "tool":
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
            elif role in {"system", "user", "assistant"}:
                cleaned.append({"role": role, "content": str(message.get("content", ""))})
                pending_tool_calls = set()

        trimmed = cleaned[-limit:]
        while trimmed and trimmed[0].get("role") == "tool":
            trimmed.pop(0)
        return trimmed
