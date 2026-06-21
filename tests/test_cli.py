from __future__ import annotations

import io
import unittest
from contextlib import redirect_stdout
from tempfile import TemporaryDirectory
from pathlib import Path

from mobile_agent.hosts.cli import (
    clean_console_input,
    expand_short_aliases,
    format_agent_message,
    format_self_test,
    format_tool_event,
    format_tool_output,
    print_self_test,
    summarize_tool_output,
    short_model,
    tui_footer,
    tui_status_line,
    tui_title,
)
from mobile_agent.core.agent import Agent, AgentConfig
from mobile_agent.core.store import ConversationStore
from mobile_agent.core.llm import MockLlmClient
from mobile_agent.core.tools import ToolRegistry


class CliTests(unittest.TestCase):
    def test_expand_short_aliases_accepts_dashless_commands(self) -> None:
        self.assertEqual(
            expand_short_aliases(["tui", "new", "noresume", "danger", "ask", "safe"]),
            [
                "--tui",
                "--new-session",
                "--no-auto-resume",
                "--permission-mode=danger",
                "--permission-mode=ask",
                "--permission-mode=safe",
            ],
        )
        self.assertEqual(expand_short_aliases(["--mock", "tui"]), ["--mock", "--tui"])

    def test_clean_console_input_strips_mojibake_bom_prefixes(self) -> None:
        self.assertEqual(clean_console_input("锘縲what time"), "what time")
        self.assertEqual(clean_console_input("ï»¿/status"), "/status")

    def test_format_tool_output_truncates_long_text(self) -> None:
        output = format_tool_output({"text": "x" * 50}, limit=20)
        self.assertIn("<truncated", output)
        self.assertLess(len(output), 80)

    def test_tui_footer_fits_narrow_widths(self) -> None:
        for width in (32, 40, 52):
            self.assertLessEqual(len(tui_footer(width)), width - 2)

    def test_tui_title_uses_short_form_on_narrow_width(self) -> None:
        self.assertEqual(tui_title("deepseek-v4-flash", "abcdef12", 40), " MA | abcdef12 ")

    def test_tui_status_line_fits_without_session(self) -> None:
        class Config:
            model = "deepseek-v4-flash"

        class Store:
            pass

        class Tools:
            def names(self):
                return ["a", "b"]

        class Agent:
            config = Config()
            store = Store()
            tools = Tools()

        for width in (32, 40, 52, 80):
            status = tui_status_line(Agent(), session_id=None, mock=True, width=width)
            self.assertLessEqual(len(status), width - 2)
            self.assertIn("v4flash" if width < 64 else "deepseek-v4-flash", status)

    def test_short_model_uses_readable_alias(self) -> None:
        self.assertEqual(short_model("deepseek-v4-flash"), "v4flash")

    def test_tui_status_marks_danger_mode_on_narrow_width(self) -> None:
        class Config:
            model = "deepseek-v4-flash"

        class Tools:
            def names(self):
                return []

        class Agent:
            config = Config()
            tools = Tools()
            permission_mode = "danger"

        status = tui_status_line(Agent(), session_id=None, mock=False, width=40)
        self.assertIn("v4flash!", status)
        self.assertLessEqual(len(status), 38)

    def test_format_agent_message_truncates_long_text(self) -> None:
        output = format_agent_message("x" * 200, limit=50)
        self.assertIn("<truncated", output)
        self.assertLess(len(output), 90)

    def test_summarize_http_tool_output_does_not_include_body(self) -> None:
        output = {
            "ok": True,
            "result": {
                "url": "https://example.test",
                "status": 200,
                "text": "x" * 1000,
                "truncated": True,
            },
        }

        summary = summarize_tool_output(output)

        self.assertIn("http 200", summary)
        self.assertIn("truncated=True", summary)
        self.assertNotIn("x" * 100, summary)

    def test_summarize_sensor_output_uses_count(self) -> None:
        output = {"ok": True, "result": {"sensors": ["a", "b", "c", "d"]}}

        summary = summarize_tool_output(output)

        self.assertIn("count=4", summary)
        self.assertIn("+1", summary)

    def test_format_tool_event_is_compact(self) -> None:
        event = {
            "tool": "http_get",
            "arguments": {"url": "https://example.test"},
            "output": {"ok": True, "result": {"url": "https://example.test", "status": 200, "text": "x" * 2000}},
        }

        formatted = format_tool_event(event, limit=120)

        self.assertIn("http_get", formatted)
        self.assertLess(len(formatted), 220)

    def test_print_self_test_outputs_summary_and_checks(self) -> None:
        with TemporaryDirectory() as tmp:
            agent = Agent(
                config=AgentConfig(model="mock", system_prompt="test"),
                llm=MockLlmClient(),
                tools=ToolRegistry(),
                store=ConversationStore(Path(tmp)),
            )
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                print_self_test(agent=agent)
        output = buffer.getvalue()
        self.assertIn("self-test", output)
        self.assertIn("session_store", output)
        self.assertIn("tool_registry", output)

    def test_format_self_test(self) -> None:
        lines = format_self_test(
            {
                "status": "ok",
                "summary": {"total": 1, "ok": 1, "warn": 0, "error": 0},
                "checks": [{"name": "python_runtime", "status": "ok", "message": "python ok"}],
                "recommendations": ["tip-1"],
            }
        )
        self.assertTrue(lines[0].startswith("self-test status="))
        self.assertIn("python_runtime", lines[1])
        self.assertIn("tip-1", lines[-1])


if __name__ == "__main__":
    unittest.main()
