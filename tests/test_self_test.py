from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mobile_agent.core.agent import Agent, AgentConfig
from mobile_agent.core.llm import MockLlmClient
from mobile_agent.core.store import ConversationStore
from mobile_agent.core.tools import ToolRegistry
from mobile_agent.core.self_test import run_agent_self_test
from mobile_agent.phone_tools import build_registry


class MinimalToolRegistry(ToolRegistry):
    def __init__(self, tool_results: dict[str, dict]):
        super().__init__()
        self._names = {"get_time"}
        self.tool_results = tool_results

    def names(self) -> list[str]:
        return sorted(self._names)

    def execute(self, name: str, arguments: dict) -> dict:
        if name in self.tool_results:
            return self.tool_results[name]
        return {"ok": True, "result": {"name": name}}


class MissingKeyLlm:
    uses_previous_response_id = False
    api_key = ""


class SelfTestTests(unittest.TestCase):
    def test_self_test_for_mock_agent_is_ok(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=build_registry(),
                store=ConversationStore(Path(tmp)),
            )
            result = run_agent_self_test(agent=agent)

        self.assertEqual(result["status"], "ok")
        self.assertGreaterEqual(result["summary"]["total"], 3)
        self.assertTrue(any(item["name"] == "tool_registry" for item in result["checks"]))

    def test_self_test_warns_when_critical_tool_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            class Registry(MinimalToolRegistry):
                def __init__(self) -> None:
                    super().__init__({"get_time": {"ok": False, "error": "blocked"}})

            registry = Registry()
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=registry,
                store=ConversationStore(Path(tmp)),
            )
            result = run_agent_self_test(agent=agent)

        self.assertEqual(result["status"], "warn")
        self.assertIn("critical_tool:get_time", [item["name"] for item in result["checks"]])
        self.assertEqual(next(item["status"] for item in result["checks"] if item["name"] == "critical_tool:get_time"), "warn")
        self.assertEqual(result["summary"]["warn"], 1)
        self.assertTrue(any("get_time" in item for item in result["recommendations"]))

    def test_self_test_reports_api_key_error_for_custom_llm(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="deepseek", system_prompt="test"),
                llm=MissingKeyLlm(),
                tools=ToolRegistry(),
                store=ConversationStore(Path(tmp)),
            )
            result = run_agent_self_test(agent=agent)

        self.assertEqual(result["status"], "error")
        self.assertGreaterEqual(result["summary"]["error"], 1)
        self.assertEqual(next(item["status"] for item in result["checks"] if item["name"] == "agent_config"), "error")

    def test_host_bridge_check_is_optional(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=ToolRegistry(),
                store=ConversationStore(Path(tmp)),
            )
            result = run_agent_self_test(agent=agent, include_host_bridge_check=True)

        self.assertIn("host_bridge", {item["name"] for item in result["checks"]})
        self.assertLessEqual(result["summary"]["warn"], result["summary"]["total"])


if __name__ == "__main__":
    unittest.main()
