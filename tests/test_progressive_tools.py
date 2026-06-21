from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mobile_agent.core.agent import Agent, AgentConfig
from mobile_agent.core.llm import ModelResult, ToolCall
from mobile_agent.core.store import ConversationStore
from mobile_agent.core.tools import ToolRegistry
from mobile_agent.phone_tools import build_registry


class ProgressiveToolLlm:
    uses_previous_response_id = False

    def __init__(self) -> None:
        self.seen_tools: list[list[str]] = []
        self.step = 0

    def respond(self, *, model: str, messages: list[dict], tools: list[dict], previous_response_id: str | None = None) -> ModelResult:
        self.step += 1
        self.seen_tools.append([str(tool["name"]) for tool in tools])
        if self.step == 1:
            return ModelResult(
                text="",
                response_id="progressive-1",
                tool_calls=[ToolCall(call_id="load-secret", name="tool_info", arguments={"names": ["secret_tool"]})],
            )
        if self.step == 2:
            return ModelResult(
                text="",
                response_id="progressive-2",
                tool_calls=[ToolCall(call_id="call-secret", name="secret_tool", arguments={"text": "hello"})],
            )
        return ModelResult(text="done", response_id="progressive-3")

    def continue_with_tool_results(self, *, model: str, tool_results: list[dict], previous_response_id: str, tools: list[dict]) -> ModelResult:
        raise NotImplementedError


class ProgressiveToolTests(unittest.TestCase):
    def test_model_tools_are_thin_by_default(self) -> None:
        registry = build_registry()
        model_tool_names = {tool["name"] for tool in registry.model_tools()}
        full_tool_names = {tool["name"] for tool in registry.openai_tools()}

        self.assertIn("tool_catalog", model_tool_names)
        self.assertIn("tool_info", model_tool_names)
        self.assertIn("get_time", model_tool_names)
        self.assertNotIn("battery_status", model_tool_names)
        self.assertIn("battery_status", full_tool_names)
        self.assertLess(len(model_tool_names), len(full_tool_names))

    def test_tool_catalog_and_info_return_stable_metadata(self) -> None:
        registry = build_registry()

        catalog = registry.execute("tool_catalog", {"query": "battery"})
        self.assertTrue(catalog["ok"])
        self.assertEqual(catalog["result"][0]["name"], "battery_status")
        self.assertEqual(catalog["result"][0]["category"], "termux")

        info = registry.execute("tool_info", {"names": ["battery_status"]})
        self.assertTrue(info["ok"])
        self.assertEqual(info["result"][0]["name"], "battery_status")
        self.assertIn("parameters", info["result"][0])

    def test_agent_loads_requested_tool_after_tool_info(self) -> None:
        registry = ToolRegistry()

        @registry.register(description="A non-default test tool.")
        def secret_tool(text: str) -> dict[str, str]:
            return {"echo": text}

        llm = ProgressiveToolLlm()
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=llm,
                tools=registry,
                store=ConversationStore(Path(tmp)),
            )
            result = agent.chat("use the secret tool")

        self.assertEqual(result["message"], "done")
        self.assertIn("tool_info", llm.seen_tools[0])
        self.assertNotIn("secret_tool", llm.seen_tools[0])
        self.assertIn("secret_tool", llm.seen_tools[1])
        self.assertEqual(result["tool_trace"][1]["output"]["result"], {"echo": "hello"})

    def test_tool_loading_policy_lives_outside_agent_loop(self) -> None:
        root = Path(__file__).resolve().parents[1]
        agent_source = (root / "mobile_agent" / "core" / "agent.py").read_text(encoding="utf-8")
        tool_loading_source = (root / "mobile_agent" / "core" / "tool_loading.py").read_text(encoding="utf-8")

        self.assertIn("from .tool_loading import ToolLoadState", agent_source)
        self.assertIn("class ToolLoadState", tool_loading_source)
        self.assertNotIn('name != "tool_info"', agent_source)


if __name__ == "__main__":
    unittest.main()
