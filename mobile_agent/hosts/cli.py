from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from mobile_agent.core.agent import Agent
from mobile_agent.core.llm import MockLlmClient, OpenAICompatibleChatClient, OpenAIResponsesClient
from mobile_agent.core.store import ConversationStore
from mobile_agent.hosts.cli_commands import (
    handle_local_command,
    print_banner,
    print_context_line,
    print_self_test,
)
from mobile_agent.hosts.cli_format import (
    compact_arguments,
    format_agent_message,
    format_self_test,
    format_tool_event,
    format_tool_output,
    summarize_tool_output,
)
from mobile_agent.hosts.cli_io import color_text, line, print_error, prompt
from mobile_agent.hosts.cli_tui import (
    box_chars,
    draw_tui,
    fit_text,
    run_tui,
    short_model,
    short_session,
    terminal_height,
    terminal_width,
    tui_footer,
    tui_status_line,
    tui_title,
    wrap_text,
)
from mobile_agent.phone_tools import build_registry
from mobile_agent.settings import DEFAULT_CONFIG_PATH, load_agent_config, load_config


def expand_short_aliases(argv: list[str]) -> list[str]:
    aliases = {
        "tui": "--tui",
        "new": "--new-session",
        "noresume": "--no-auto-resume",
        "safe": "--permission-mode=safe",
        "ask": "--permission-mode=ask",
        "danger": "--permission-mode=danger",
    }
    return [aliases.get(item, item) for item in argv]


def build_agent(*, mock: bool, config_path: Path, permission_mode: str | None = None) -> Agent:
    config = load_agent_config(config_path)
    raw_config = load_config(config_path)
    mode = permission_mode or os.environ.get("MOBILE_AGENT_PERMISSION_MODE") or raw_config.get("permission_mode", "safe")
    llm = build_llm(mock=mock, raw_config=raw_config)
    tools = build_registry(raw_config.get("allowed_shell_commands", []), permission_mode=mode)
    store = ConversationStore(Path(".mobile-agent-sessions"))
    agent = Agent(config=config, llm=llm, tools=tools, store=store)
    agent.permission_mode = mode
    return agent


def build_llm(*, mock: bool, raw_config: dict):
    if mock:
        return MockLlmClient()
    provider = os.environ.get("MOBILE_AGENT_PROVIDER") or raw_config.get("provider", "openai_responses")
    if provider == "openai_responses":
        base_url = os.environ.get("MOBILE_AGENT_BASE_URL")
        if not base_url:
            base_url = raw_config.get("base_url") if raw_config.get("provider") == "openai_responses" else None
        return OpenAIResponsesClient(api_key=os.environ.get("OPENAI_API_KEY"), base_url=base_url or "https://api.openai.com/v1")
    if provider == "openai_compat":
        api_key = os.environ.get("MOBILE_AGENT_API_KEY") or os.environ.get("DEEPSEEK_API_KEY") or os.environ.get("OPENAI_API_KEY")
        if not api_key:
            raise RuntimeError("Set MOBILE_AGENT_API_KEY, DEEPSEEK_API_KEY, or OPENAI_API_KEY.")
        base_url = os.environ.get("MOBILE_AGENT_BASE_URL")
        if not base_url:
            base_url = raw_config.get("base_url") if raw_config.get("provider") == "openai_compat" else None
        return OpenAICompatibleChatClient(api_key=api_key, base_url=base_url or "https://api.deepseek.com")
    raise ValueError(f"Unknown provider: {provider}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the mobile agent CLI host.")
    parser.add_argument("--mock", action="store_true", help="Use a deterministic local mock LLM.")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    parser.add_argument("--session", help="Resume an existing session id.")
    parser.add_argument("--new-session", action="store_true", help="Start a new session instead of resuming the latest one.")
    parser.add_argument("--no-auto-resume", action="store_true", help="Do not automatically resume the latest session.")
    parser.add_argument("--tui", action="store_true", help="Use an experimental framed terminal UI.")
    parser.add_argument(
        "--permission-mode",
        choices=("safe", "ask", "danger"),
        help="Tool permission mode. 'ask' requires action confirmation; 'danger' exposes unrestricted shell.",
    )
    args = parser.parse_args(expand_short_aliases(sys.argv[1:]))

    agent = build_agent(mock=args.mock, config_path=args.config, permission_mode=args.permission_mode)
    session_id = choose_initial_session(
        agent,
        requested=args.session,
        new_session=args.new_session,
        auto_resume=not args.no_auto_resume,
    )
    if args.tui:
        run_tui(
            agent,
            session_id=session_id,
            mock=args.mock,
            clean_console_input=clean_console_input,
            confirm_action=confirm_action_prompt,
        )
        return
    run_plain_cli(agent, session_id=session_id, mock=args.mock)


def run_plain_cli(agent: Agent, *, session_id: str | None, mock: bool) -> None:
    print_banner(agent, session_id=session_id, mock=mock)
    while True:
        try:
            message = clean_console_input(input(prompt()))
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not message:
            continue
        session_id, should_exit = handle_local_command(agent, message, session_id=session_id, mock=mock)
        if should_exit:
            break
        if message.startswith("/"):
            continue
        try:
            result = agent.chat(message, session_id=session_id, confirm_action=confirm_action_prompt)
        except Exception as exc:
            print_error("chat", exc)
            continue
        session_id = result["session_id"]
        for trace in result["tool_trace"]:
            line("tool", format_tool_event(trace))
        print_context_line(result["context"], result["trace"])
        line("agent", format_agent_message(result["message"]))


def clean_console_input(value: str) -> str:
    text = value.strip().lstrip("\ufeff")
    for prefix in ("锘?", "ï»¿", "锘縲", "锘縖", "閿?", "茂禄驴", "閿樼覆", "閿樼笘"):
        if text.startswith(prefix):
            text = text[len(prefix):]
    slash = text.find("/")
    if slash > 0 and slash <= 4:
        text = text[slash:]
    return text


def confirm_action_prompt(tool: str, arguments: dict) -> bool:
    print()
    line("confirm", f"{tool} {json.dumps(arguments, ensure_ascii=False)}", color="yellow")
    while True:
        answer = input(color_text("allow this phone action? [y/N] ", "yellow")).strip().lower()
        if answer in ("y", "yes", "是", "确认", "允许"):
            return True
        if answer in ("", "n", "no", "否", "取消"):
            return False


def choose_initial_session(agent: Agent, *, requested: str | None, new_session: bool, auto_resume: bool) -> str | None:
    if requested:
        return requested
    if new_session:
        return agent.store.create().id
    if auto_resume:
        latest = agent.store.latest()
        if latest:
            return latest.id
    return None


if __name__ == "__main__":
    main()
