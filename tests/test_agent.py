from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mobile_agent.core.agent import Agent, AgentConfig
from mobile_agent.core.llm import MockLlmClient, ModelResult, ToolCall
from mobile_agent.core.store import ConversationStore
from mobile_agent.core.tools import ToolRegistry
from mobile_agent.action_confirmation import current_action_approved
from mobile_agent.action_confirmation import is_action_tool
from mobile_agent.phone_tools import build_registry


class RecordingToolLlm:
    uses_previous_response_id = False

    def __init__(self) -> None:
        self.calls: list[list[dict]] = []

    def respond(self, *, model: str, messages: list[dict], tools: list[dict], previous_response_id: str | None = None) -> ModelResult:
        self.calls.append(messages)
        user_messages = [str(item.get("content", "")).lower() for item in messages if item.get("role") == "user"]
        user_text = user_messages[-1] if user_messages else ""
        if "time" in user_text and not any(item.get("role") == "tool" for item in messages):
            return ModelResult(text="", response_id="rec-1", tool_calls=[ToolCall(call_id="call-1", name="get_time", arguments={})])
        return ModelResult(text="ok", response_id="rec-2")

    def continue_with_tool_results(self, *, model: str, tool_results: list[dict], previous_response_id: str, tools: list[dict]) -> ModelResult:
        return ModelResult(text="ok", response_id="rec-continue")


class ActionToolLlm:
    uses_previous_response_id = False

    def respond(self, *, model: str, messages: list[dict], tools: list[dict], previous_response_id: str | None = None) -> ModelResult:
        if not any(item.get("role") == "tool" for item in messages):
            return ModelResult(text="", response_id="act-1", tool_calls=[ToolCall(call_id="call-action", name="host_back", arguments={})])
        return ModelResult(text="done", response_id="act-2")

    def continue_with_tool_results(self, *, model: str, tool_results: list[dict], previous_response_id: str, tools: list[dict]) -> ModelResult:
        return ModelResult(text="done", response_id="act-continue")


class AgentTests(unittest.TestCase):
    def test_mock_chat_without_tools(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=build_registry(),
                store=ConversationStore(Path(tmp)),
            )
            result = agent.chat("hello")
            self.assertIn("session_id", result)
            self.assertIn("Mock agent ready", result["message"])

    def test_mock_chat_calls_time_tool(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=build_registry(),
                store=ConversationStore(Path(tmp)),
            )
            result = agent.chat("what time is it?")
            self.assertEqual(result["tool_rounds"], 1)
            self.assertEqual(result["tool_trace"][0]["tool"], "get_time")
            self.assertIn("Tool result", result["message"])
            self.assertIn("run_id", result)
            self.assertTrue(result["trace"]["spans"])
            session = agent.store.load(result["session_id"])
            roles = [message["role"] for message in session.messages]
            self.assertIn("tool", roles)
            self.assertEqual(len(session.traces), 1)

    def test_shell_limited_blocks_unknown_commands(self) -> None:
        registry = build_registry(["pwd"])
        result = registry.execute("shell_limited", {"command": "rm -rf /"})
        self.assertFalse(result["ok"])
        self.assertIn("PermissionError", result["error"])

    def test_unknown_tool_returns_model_readable_error(self) -> None:
        registry = build_registry()
        result = registry.execute("missing_tool", {})
        self.assertFalse(result["ok"])
        self.assertIn("Unknown tool", result["error"])

    def test_native_phone_tools_are_registered(self) -> None:
        registry = build_registry()
        for name in ("flashlight", "camera_photo", "sensors", "notify"):
            self.assertIn(name, registry.names())

    def test_file_tools_stay_inside_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            import os

            old = Path.cwd()
            try:
                os.chdir(tmp)
                registry = build_registry()
                written = registry.execute("write_file", {"path": "notes/test.txt", "content": "hello"})
                self.assertTrue(written["ok"])
                read = registry.execute("read_file", {"path": "notes/test.txt"})
                self.assertEqual(read["result"]["content"], "hello")
                escaped = registry.execute("read_file", {"path": "../outside.txt"})
                self.assertFalse(escaped["ok"])
                escaped_photo = registry.execute("camera_photo", {"path": "../outside.jpg"})
                self.assertFalse(escaped_photo["ok"])
            finally:
                os.chdir(old)

    def test_next_turn_does_not_send_orphan_tool_messages(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            llm = RecordingToolLlm()
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=llm,
                tools=build_registry(),
                store=ConversationStore(Path(tmp)),
            )
            first = agent.chat("what time is it?")
            agent.chat("continue", session_id=first["session_id"])

            second_turn_messages = llm.calls[-1]
            self.assertFalse(any(item.get("role") == "tool" for item in second_turn_messages))
            self.assertFalse(any("tool_calls" in item for item in second_turn_messages))

    def test_action_tool_without_confirmation_is_not_executed(self) -> None:
        registry = ToolRegistry()

        @registry.register(description="test action")
        def host_back() -> dict:
            return {"approved": current_action_approved()}

        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=ActionToolLlm(),
                tools=registry,
                store=ConversationStore(Path(tmp)),
            )
            result = agent.chat("go back")

        output = result["tool_trace"][0]["output"]
        self.assertFalse(output["ok"])
        self.assertTrue(output["needs_confirmation"])

    def test_action_tool_confirmation_sets_approval_context(self) -> None:
        registry = ToolRegistry()

        @registry.register(description="test action")
        def host_back() -> dict:
            return {"approved": current_action_approved()}

        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=ActionToolLlm(),
                tools=registry,
                store=ConversationStore(Path(tmp)),
            )
            result = agent.chat("go back", confirm_action=lambda name, arguments: True)

        output = result["tool_trace"][0]["output"]
        self.assertTrue(output["ok"])
        self.assertTrue(output["result"]["approved"])

    def test_host_open_app_is_an_action_tool(self) -> None:
        self.assertTrue(is_action_tool("host_open_app"))
        self.assertFalse(is_action_tool("host_observe"))
        self.assertFalse(is_action_tool("host_screen_find"))


if __name__ == "__main__":
    unittest.main()
