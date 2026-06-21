from __future__ import annotations

import os
import shutil
import sys
from typing import Callable

from mobile_agent.core.agent import Agent
from mobile_agent.core.context import cache_summary, context_stats, latest_cache_usage
from mobile_agent.hosts.cli_format import format_agent_message, format_self_test, format_tool_event
from mobile_agent.hosts.cli_io import color_text


def run_tui(
    agent: Agent,
    *,
    session_id: str | None,
    mock: bool,
    clean_console_input: Callable[[str], str],
    confirm_action,
) -> None:
    transcript = [
        f"model {agent.config.model} mode={'mock' if mock else 'live'} perm={permission_mode(agent)}",
        f"session {short_session(session_id)}",
        "cmd /help /status /tools /new /exit",
    ]
    while True:
        draw_tui(agent, session_id=session_id, transcript=transcript, mock=mock)
        try:
            message = clean_console_input(input(color_text("you> ", "bold")))
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not message:
            continue
        transcript.append(f"you  {message}")
        if len(transcript) > 80:
            transcript = transcript[-80:]
        if message in ("/exit", "/quit"):
            break
        if message in ("/help", "/?"):
            transcript.extend(
                [
                    "help /status /doctor /tools /session /sessions /resume <id>",
                    "help /new /history /traces /context /compact [keep_last] /exit",
                    "help /self-test run local self-test and show suggestions",
                    "help default startup resumes latest session; use --new-session for a fresh one",
                ]
            )
            continue
        if message == "/new":
            session = agent.store.create()
            session_id = session.id
            transcript.append(f"session new {session_id}")
            continue
        if message == "/status":
            session = agent.store.load(session_id) if session_id else None
            transcript.append(f"status model={agent.config.model} tools={len(agent.tools.names())}")
            transcript.append(f"status session={session.id if session else 'none'} messages={len(session.messages) if session else 0}")
            continue
        if message == "/context":
            if not session_id:
                transcript.append("context no active session")
            else:
                session = agent.store.load(session_id)
                stats = context_stats(session.messages)
                transcript.append(
                    f"context ~{stats['estimated_tokens']} tok messages={stats['messages']} window={stats['window_used_percent']}%"
                )
            continue
        if message == "/tools":
            for item in agent.tools.list_metadata():
                transcript.append(f"tool {item['name']}: {item['description']}")
            continue
        if message == "/doctor":
            transcript.append("doctor use normal CLI for full doctor output: ma /doctor")
            transcript.append("doctor quick termux-api-app check is shown by /doctor outside --tui")
            continue
        if message == "/self-test":
            result = agent.run_self_test()
            for line_text in format_self_test(result):
                transcript.append(line_text)
            continue
        try:
            result = agent.chat(message, session_id=session_id, confirm_action=confirm_action)
        except Exception as exc:
            transcript.append(f"error chat {type(exc).__name__}: {exc}")
            continue
        session_id = result["session_id"]
        for trace in result["tool_trace"]:
            transcript.append("tool " + format_tool_event(trace, limit=220))
        stats = result["context"]
        transcript.append(
            f"context ~{stats['estimated_tokens']} tok messages={stats['messages']} window={stats['window_used_percent']}%"
        )
        for line_text in wrap_text(format_agent_message(result["message"], limit=1000), width=max(40, terminal_width() - 12)):
            transcript.append("agent " + line_text)


def terminal_width() -> int:
    env_width = os.environ.get("MA_TUI_WIDTH")
    if env_width and env_width.isdigit():
        return max(32, min(120, int(env_width)))
    return max(32, min(120, shutil.get_terminal_size((80, 24)).columns))


def terminal_height() -> int:
    return max(16, min(60, shutil.get_terminal_size((80, 24)).lines))


def draw_tui(agent: Agent, *, session_id: str | None, transcript: list[str], mock: bool) -> None:
    width = terminal_width()
    height = terminal_height()
    body_height = height - 7
    chars = box_chars()
    if sys.stdout.isatty():
        print("\033[2J\033[H", end="")
    title = tui_title(agent.config.model, short_session(session_id), width)
    print(chars["tl"] + fit_text(title, width - 2, fill=chars["h"]) + chars["tr"])
    body_lines = []
    for item in transcript:
        body_lines.extend(wrap_text(item, width=width - 4))
    visible_lines = body_lines[-body_height:]
    for wrapped in visible_lines:
        print(chars["v"] + " " + fit_text(wrapped, width - 4) + " " + chars["v"])
    for _ in range(max(0, body_height - len(visible_lines))):
        print(chars["v"] + " " + (" " * (width - 4)) + " " + chars["v"])
    print(chars["ml"] + fit_text(tui_status_line(agent, session_id=session_id, mock=mock, width=width), width - 2, fill=chars["h"]) + chars["mr"])
    print(chars["ml"] + fit_text(tui_footer(width), width - 2, fill=chars["h"]) + chars["mr"])
    print(chars["bl"] + (chars["h"] * (width - 2)) + chars["br"])


def tui_status_line(agent: Agent, *, session_id: str | None, mock: bool, width: int) -> str:
    mode = "mock" if mock else "live"
    model = short_model(agent.config.model)
    perm = permission_mode(agent)
    tools = len(agent.tools.names())
    if not session_id:
        if width < 46:
            suffix = "!" if perm == "danger" else ""
            return f" {model}{suffix} ctx 0t cache n/a "
        if width < 64:
            return f" model={model} ctx=0t cache=n/a perm={perm} "
        return f" model={agent.config.model} ctx=0tok msg=0 cache=n/a tools={tools} mode={mode} perm={perm} "
    try:
        session = agent.store.load(session_id)
        stats = context_stats(session.messages)
        cache = cache_summary(latest_cache_usage(session.traces))
    except Exception as exc:
        return f" status error {type(exc).__name__} "
    cache_text = "n/a"
    if cache.get("available"):
        cache_text = f"{cache['cache_hit_percent']}%"
    if width < 46:
        suffix = "!" if perm == "danger" else ""
        return f" {model}{suffix} ctx {stats['estimated_tokens']}t cache {cache_text} "
    if width < 64:
        return f" model={model} ctx={stats['estimated_tokens']}t cache={cache_text} perm={perm} "
    return (
        f" model={agent.config.model} ctx={stats['estimated_tokens']}tok msg={stats['messages']} "
        f"win={stats['window_used_percent']}% cache={cache_text} tools={tools} mode={mode} perm={perm} "
    )


def tui_footer(width: int) -> str:
    if width < 46:
        return " /help /exit | new "
    if width < 64:
        return " /help /exit | new | resume "
    return " /help /exit | new=fresh | default=resume "


def tui_title(model: str, session: str, width: int) -> str:
    if width < 46:
        return f" MA | {session} "
    if width < 68:
        return f" Mobile Agent | {session} "
    return f" Mobile Agent | {model} | {session} "


def short_session(session_id: str | None) -> str:
    if not session_id:
        return "new"
    return session_id[:8]


def short_model(model: str) -> str:
    aliases = {
        "deepseek-v4-flash": "v4flash",
        "deepseek-chat": "ds-chat",
        "deepseek-reasoner": "ds-r1",
    }
    return aliases.get(model, model[:10])


def permission_mode(agent: Agent) -> str:
    return str(getattr(agent, "permission_mode", "safe"))


def box_chars() -> dict[str, str]:
    encoding = (sys.stdout.encoding or "").lower()
    if not sys.stdout.isatty() or "utf" not in encoding:
        return {"tl": "+", "tr": "+", "bl": "+", "br": "+", "ml": "+", "mr": "+", "h": "-", "v": "|"}
    return {
        "tl": "\u250c",
        "tr": "\u2510",
        "bl": "\u2514",
        "br": "\u2518",
        "ml": "\u251c",
        "mr": "\u2524",
        "h": "\u2500",
        "v": "\u2502",
    }


def fit_text(text: str, width: int, *, fill: str = " ") -> str:
    if len(text) > width:
        marker = "\u2026" if box_chars()["h"] == "\u2500" else "~"
        return text[: max(0, width - 1)] + marker
    return text + (fill * (width - len(text)))


def wrap_text(text: str, *, width: int) -> list[str]:
    if width < 10:
        return [text]
    chunks = []
    remaining = text
    while len(remaining) > width:
        chunks.append(remaining[:width])
        remaining = remaining[width:]
    chunks.append(remaining)
    return chunks
