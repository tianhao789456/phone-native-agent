from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_cli_formatting_lives_outside_cli_host_loop() -> None:
    cli_source = (ROOT / "mobile_agent" / "hosts" / "cli.py").read_text(encoding="utf-8")
    format_source = (ROOT / "mobile_agent" / "hosts" / "cli_format.py").read_text(encoding="utf-8")
    tui_source = (ROOT / "mobile_agent" / "hosts" / "cli_tui.py").read_text(encoding="utf-8")
    commands_source = (ROOT / "mobile_agent" / "hosts" / "cli_commands.py").read_text(encoding="utf-8")

    assert "from mobile_agent.hosts.cli_format import" in cli_source
    assert "from mobile_agent.hosts.cli_tui import" in cli_source
    assert "from mobile_agent.hosts.cli_commands import" in cli_source

    assert "def format_tool_event" not in cli_source
    assert "def summarize_tool_output" not in cli_source
    assert "def format_self_test" not in cli_source
    assert "def run_tui" not in cli_source
    assert "def draw_tui" not in cli_source
    assert "def handle_local_command" not in cli_source

    assert "def format_tool_event" in format_source
    assert "def summarize_tool_output" in format_source
    assert "def format_self_test" in format_source
    assert "def run_tui" in tui_source
    assert "def draw_tui" in tui_source
    assert "def handle_local_command" in commands_source
