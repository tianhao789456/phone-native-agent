from __future__ import annotations

import argparse
import json
import os
import platform
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

from mobile_agent.core.agent import Agent

from mobile_agent.app_creator import create_app, list_apps
from mobile_agent.core.context import cache_summary, context_stats, latest_cache_usage
from mobile_agent.core.plugins import list_plugins
from mobile_agent.core.llm import MockLlmClient, OpenAICompatibleChatClient, OpenAIResponsesClient
from mobile_agent.core.store import ConversationStore
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
    expanded = []
    for item in argv:
        expanded.append(aliases.get(item, item))
    return expanded


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
    session_id = choose_initial_session(agent, requested=args.session, new_session=args.new_session, auto_resume=not args.no_auto_resume)
    if args.tui:
        run_tui(agent, session_id=session_id, mock=args.mock)
        return
    print_banner(agent, session_id=session_id, mock=args.mock)
    while True:
        try:
            message = clean_console_input(input(prompt()))
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not message:
            continue
        if message in ("/exit", "/quit"):
            break
        if message in ("/help", "/?"):
            print_help()
            continue
        if message in ("/new", "/new-session"):
            session = agent.store.create()
            session_id = session.id
            line("session", f"new {session_id}")
            continue
        if message == "/sessions":
            sessions = agent.store.list_sessions()
            if not sessions:
                line("session", "none")
                continue
            for item in sessions[:20]:
                current = "*" if item["id"] == session_id else " "
                line("session", f"{current} {item['id']} messages={item['messages']} traces={item['traces']}")
            continue
        if message == "/plugins":
            plugins = list_plugins()
            if not plugins:
                line("plugins", "none")
            for plugin in plugins:
                status = "ok" if plugin.get("valid") else "disabled"
                line("plugin", f"{plugin.get('icon', '')} {plugin.get('label')} [{status}] {plugin.get('path')}")
            continue
        if message == "/apps":
            apps = list_apps()
            if not apps:
                line("apps", "none")
            for app in apps:
                line("app", f"{app['name']} index={app['has_index']} path={app['path']}")
            continue
        if message.startswith("/create-app"):
            parts = shlex.split(message)
            if len(parts) < 3:
                line("usage", "/create-app <name> <prompt>")
                continue
            name = parts[1]
            app_prompt = " ".join(parts[2:])
            try:
                result = create_app(llm=agent.llm, model=agent.config.model, name=name, prompt=app_prompt)
            except Exception as exc:
                print_error("create-app", exc)
                continue
            line("app", f"created {result['name']} {result['path']} bytes={result['bytes']}")
            continue
        if message.startswith("/resume"):
            parts = shlex.split(message)
            if len(parts) < 2:
                line("usage", "/resume <session_id>")
                continue
            session_id = parts[1]
            session = agent.store.load(session_id)
            line("session", f"resumed {session.id} messages={len(session.messages)} traces={len(session.traces)}")
            continue
        if message == "/status":
            print_status(agent, session_id=session_id, mock=args.mock)
            continue
        if message == "/doctor":
            print_doctor(agent=agent, mock=args.mock)
            continue
        if message == "/context":
            print_context(agent, session_id=session_id)
            continue
        if message.startswith("/compact"):
            if not session_id:
                line("session", "no active session")
                continue
            parts = shlex.split(message)
            keep_last = int(parts[1]) if len(parts) > 1 else 12
            try:
                result = agent.compact(session_id, keep_last=keep_last)
            except Exception as exc:
                print_error("compact", exc)
                continue
            if result["compacted"]:
                before = result["before"]["estimated_tokens"]
                after = result["after"]["estimated_tokens"]
                line("compact", f"ok estimated_tokens {before} -> {after}")
            else:
                line("compact", f"skipped reason={result['reason']} estimated_tokens={result['context']['estimated_tokens']}")
            continue
        if message == "/session":
            if session_id:
                session = agent.store.load(session_id)
            else:
                session = agent.store.create()
                session_id = session.id
            line("session", f"{session.id} messages={len(session.messages)} traces={len(session.traces)}")
            continue
        if message == "/history":
            if not session_id:
                line("session", "no active session")
                continue
            session = agent.store.load(session_id)
            for item in session.messages[-20:]:
                role = item.get("role", "")
                content = str(item.get("content", ""))
                line(role, content[:500])
            continue
        if message == "/traces":
            if not session_id:
                line("session", "no active session")
                continue
            session = agent.store.load(session_id)
            for trace in session.traces[-10:]:
                line("trace", f"{trace.get('id')} rounds={trace.get('tool_rounds')} spans={len(trace.get('spans', []))}")
            continue
        if message == "/tools":
            for item in agent.tools.list_metadata():
                line("tool", f"{item['name']}: {item['description']}")
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
    for prefix in ("锘?", "ï»¿", "锘縲", "锘縖"):
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


def format_tool_output(output: object, *, limit: int = 1200) -> str:
    text = str(output)
    if len(text) <= limit:
        return text
    return text[:limit] + f"... <truncated {len(text) - limit} chars>"


def format_tool_event(trace: dict, *, limit: int = 360) -> str:
    name = str(trace.get("tool", "tool"))
    arguments = trace.get("arguments") or {}
    output = trace.get("output")
    return f"{name} {compact_arguments(arguments)} -> {summarize_tool_output(output, limit=limit)}"


def format_agent_message(message: object, *, limit: int = 1600) -> str:
    text = str(message)
    if len(text) <= limit:
        return text
    return text[:limit] + f"... <truncated {len(text) - limit} chars>"


def compact_arguments(arguments: object, *, limit: int = 120) -> str:
    if not arguments:
        return "{}"
    text = str(arguments)
    if len(text) <= limit:
        return text
    return text[:limit] + f"... <+{len(text) - limit}>"


def summarize_tool_output(output: object, *, limit: int = 360) -> str:
    if not isinstance(output, dict):
        return format_tool_output(output, limit=limit)
    if output.get("ok") is False:
        return f"error: {output.get('error', 'unknown error')}"
    result = output.get("result", output)
    if not isinstance(result, dict):
        return format_tool_output(result, limit=limit)

    if {"url", "status", "text"}.issubset(result):
        text = str(result.get("text", ""))
        return (
            f"ok http {result.get('status')} url={result.get('url')} "
            f"bytes={len(text.encode('utf-8'))} truncated={result.get('truncated')}"
        )
    if "percentage" in result and "status" in result:
        return (
            f"ok battery {result.get('percentage')}% {result.get('status')} "
            f"temp={result.get('temperature')}C plugged={result.get('plugged')}"
        )
    if "sensors" in result and isinstance(result.get("sensors"), list):
        sensors = result["sensors"]
        sample = ", ".join(str(item) for item in sensors[:3])
        suffix = f", +{len(sensors) - 3}" if len(sensors) > 3 else ""
        return f"ok sensors count={len(sensors)} [{sample}{suffix}]"
    if "path" in result and "bytes" in result:
        return f"ok file path={result.get('path')} bytes={result.get('bytes')} exists={result.get('exists')}"
    if "returncode" in result:
        return f"ok={result.get('ok')} returncode={result.get('returncode')} stderr={str(result.get('stderr', ''))[:120]}"
    if "iso" in result:
        return f"ok time {result.get('iso')}"

    text = str(result)
    if len(text) <= limit:
        return f"ok {text}"
    keys = ", ".join(list(result.keys())[:8])
    return f"ok object keys=[{keys}] chars={len(text)}"


def print_error(scope: str, exc: Exception) -> None:
    line("error", f"{scope}: {type(exc).__name__}: {exc}", color="red")
    line("hint", "command loop is still alive; use /status, /context, /new, or /exit", color="dim")


def run_tui(agent: Agent, *, session_id: str | None, mock: bool) -> None:
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
        try:
            result = agent.chat(message, session_id=session_id, confirm_action=confirm_action_prompt)
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
    return {"tl": "┌", "tr": "┐", "bl": "└", "br": "┘", "ml": "├", "mr": "┤", "h": "─", "v": "│"}


def fit_text(text: str, width: int, *, fill: str = " ") -> str:
    if len(text) > width:
        marker = "…" if box_chars()["h"] == "─" else "~"
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


def use_color() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if os.environ.get("MA_COLOR") == "1":
        return True
    return sys.stdout.isatty()


def color_text(text: str, color: str | None = None) -> str:
    if not color or not use_color():
        return text
    colors = {
        "cyan": "36",
        "green": "32",
        "yellow": "33",
        "red": "31",
        "dim": "2",
        "bold": "1",
    }
    code = colors.get(color)
    if not code:
        return text
    return f"\033[{code}m{text}\033[0m"


def line(label: str, message: str, *, color: str | None = None) -> None:
    label_color = color or {
        "agent": "green",
        "tool": "cyan",
        "error": "red",
        "context": "dim",
        "doctor": "yellow",
    }.get(label)
    print(f"{color_text(label + '>', label_color)} {message}")


def prompt() -> str:
    return color_text("you> ", "bold")


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


def print_banner(agent: Agent, *, session_id: str | None, mock: bool) -> None:
    mode = "mock" if mock else "live"
    title = "Mobile Agent"
    print(color_text(title, "bold"))
    line("model", f"{agent.config.model} mode={mode}")
    line("session", session_id or "new on first message")
    line("hint", "type /help for commands", color="dim")


def print_help() -> None:
    print(
        """commands>
  /status              show runtime status
  /doctor              check phone runtime and native tool availability
  /tools               list available tools
  /session             show current session
  /sessions            list recent sessions
  /resume <id>         resume a session
  /new                 start a new session
  /plugins             list acornix-style plugins
  /apps                list generated mobile apps
  /create-app n prompt create a mobile web app in my_apps/n
  /history             show recent messages
  /traces              show recent run traces
  /context             show context estimate and latest cache usage
  /compact [keep_last] compact older context into a summary
  /exit                quit
"""
    )


def print_status(agent: Agent, *, session_id: str | None, mock: bool) -> None:
    session = agent.store.load(session_id) if session_id else None
    line("model", agent.config.model)
    line("mode", "mock" if mock else "live")
    line("tools", str(len(agent.tools.names())))
    if session:
        line("session", f"{session.id} messages={len(session.messages)} traces={len(session.traces)}")
        print_context(agent, session_id=session.id)
    else:
        line("session", "none")


def print_doctor(*, agent: Agent, mock: bool) -> None:
    line("doctor", f"platform={platform.platform()} python={platform.python_version()}")
    line("doctor", f"cwd={Path.cwd()}")
    line("doctor", f"mode={'mock' if mock else 'live'} model={agent.config.model}")
    line("doctor", f"provider={os.environ.get('MOBILE_AGENT_PROVIDER') or 'config'}")
    for env_name in ("MOBILE_AGENT_API_KEY", "DEEPSEEK_API_KEY", "OPENAI_API_KEY"):
        line("doctor", f"env {env_name}={'set' if os.environ.get(env_name) else 'missing'}")
    for command in (
        "ma",
        "python",
        "termux-battery-status",
        "termux-open-url",
        "termux-torch",
        "termux-camera-photo",
        "termux-sensor",
        "termux-notification",
    ):
        path = shutil.which(command)
        line("doctor", f"cmd {command}={path or 'missing'}")
    battery_command = shutil.which("termux-battery-status")
    if battery_command:
        try:
            completed = subprocess.run([battery_command], text=True, capture_output=True, timeout=5)
            if completed.returncode == 0 and completed.stdout.strip():
                line("doctor", "termux-api-app=responding")
            else:
                detail = (completed.stderr or completed.stdout).strip()[:160]
                line("doctor", f"termux-api-app=not-responding {detail}")
        except subprocess.TimeoutExpired:
            line("doctor", "termux-api-app=not-responding timeout; install/open Termux:API Android add-on and grant permissions")
    sessions = Path(".mobile-agent-sessions")
    try:
        sessions.mkdir(exist_ok=True)
        probe = sessions / ".doctor-write-test"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink(missing_ok=True)
        line("doctor", "sessions=writable")
    except Exception as exc:
        line("doctor", f"sessions=not-writable {type(exc).__name__}: {exc}")
    storage = Path.home() / "storage"
    if storage.exists():
        line("doctor", f"termux-storage=present {storage}")
    else:
        line("doctor", "termux-storage=missing; run termux-setup-storage if shared files are needed")


def print_context(agent: Agent, *, session_id: str | None) -> None:
    if not session_id:
        line("context", "no active session")
        return
    session = agent.store.load(session_id)
    stats = context_stats(session.messages)
    cache = cache_summary(latest_cache_usage(session.traces))
    print_context_line(stats, {"spans": [{"usage": cache}]}, cache_already_summarized=True)
    line("context_roles", str(stats["by_role"]))


def print_context_line(stats: dict, trace: dict, *, cache_already_summarized: bool = False) -> None:
    if cache_already_summarized:
        cache = (trace.get("spans") or [{}])[-1].get("usage") or {"available": False}
    else:
        usage = None
        for span in reversed(trace.get("spans", [])):
            if span.get("usage"):
                usage = span["usage"]
                break
        cache = cache_summary(usage)
    base = f"context> ~{stats['estimated_tokens']} tok, {stats['messages']} messages, {stats['window_used_percent']}% window"
    if cache.get("available"):
        base += f", cache hit {cache['cache_hit_tokens']}/{cache['prompt_tokens']} tok ({cache['cache_hit_percent']}%)"
    else:
        base += ", cache n/a"
    line("context", base.removeprefix("context> "), color="dim")


if __name__ == "__main__":
    main()
