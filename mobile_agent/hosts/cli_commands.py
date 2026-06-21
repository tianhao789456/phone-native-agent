from __future__ import annotations

import os
import platform
import shlex
import shutil
import subprocess
from pathlib import Path

from mobile_agent.app_creator import create_app, list_apps
from mobile_agent.core.agent import Agent
from mobile_agent.core.context import cache_summary, context_stats, latest_cache_usage
from mobile_agent.core.plugins import list_plugins
from mobile_agent.hosts.cli_io import line, print_error
from mobile_agent.hosts.cli_io import color_text


def handle_local_command(agent: Agent, message: str, *, session_id: str | None, mock: bool) -> tuple[str | None, bool]:
    if message in ("/exit", "/quit"):
        return session_id, True
    if message in ("/help", "/?"):
        print_help()
        return session_id, False
    if message in ("/new", "/new-session"):
        session = agent.store.create()
        line("session", f"new {session.id}")
        return session.id, False
    if message == "/sessions":
        print_sessions(agent, current_session_id=session_id)
        return session_id, False
    if message == "/plugins":
        print_plugins()
        return session_id, False
    if message == "/apps":
        print_apps()
        return session_id, False
    if message.startswith("/create-app"):
        create_app_from_command(agent, message)
        return session_id, False
    if message.startswith("/resume"):
        return resume_session_from_command(agent, message, current_session_id=session_id), False
    if message == "/status":
        print_status(agent, session_id=session_id, mock=mock)
        return session_id, False
    if message == "/doctor":
        print_doctor(agent=agent, mock=mock)
        return session_id, False
    if message == "/self-test":
        print_self_test(agent=agent)
        return session_id, False
    if message == "/context":
        print_context(agent, session_id=session_id)
        return session_id, False
    if message.startswith("/compact"):
        compact_session_from_command(agent, message, session_id=session_id)
        return session_id, False
    if message == "/session":
        return print_or_create_session(agent, session_id=session_id), False
    if message == "/history":
        print_history(agent, session_id=session_id)
        return session_id, False
    if message == "/traces":
        print_traces(agent, session_id=session_id)
        return session_id, False
    if message == "/tools":
        print_tools(agent)
        return session_id, False
    return session_id, False


def print_sessions(agent: Agent, *, current_session_id: str | None) -> None:
    sessions = agent.store.list_sessions()
    if not sessions:
        line("session", "none")
        return
    for item in sessions[:20]:
        current = "*" if item["id"] == current_session_id else " "
        line("session", f"{current} {item['id']} messages={item['messages']} traces={item['traces']}")


def print_plugins() -> None:
    plugins = list_plugins()
    if not plugins:
        line("plugins", "none")
    for plugin in plugins:
        status = "ok" if plugin.get("valid") else "disabled"
        line("plugin", f"{plugin.get('icon', '')} {plugin.get('label')} [{status}] {plugin.get('path')}")


def print_apps() -> None:
    apps = list_apps()
    if not apps:
        line("apps", "none")
    for app in apps:
        line("app", f"{app['name']} index={app['has_index']} path={app['path']}")


def create_app_from_command(agent: Agent, message: str) -> None:
    parts = shlex.split(message)
    if len(parts) < 3:
        line("usage", "/create-app <name> <prompt>")
        return
    name = parts[1]
    app_prompt = " ".join(parts[2:])
    try:
        result = create_app(llm=agent.llm, model=agent.config.model, name=name, prompt=app_prompt)
    except Exception as exc:
        print_error("create-app", exc)
        return
    line("app", f"created {result['name']} {result['path']} bytes={result['bytes']}")


def resume_session_from_command(agent: Agent, message: str, *, current_session_id: str | None) -> str | None:
    parts = shlex.split(message)
    if len(parts) < 2:
        line("usage", "/resume <session_id>")
        return current_session_id
    session = agent.store.load(parts[1])
    line("session", f"resumed {session.id} messages={len(session.messages)} traces={len(session.traces)}")
    return session.id


def compact_session_from_command(agent: Agent, message: str, *, session_id: str | None) -> None:
    if not session_id:
        line("session", "no active session")
        return
    parts = shlex.split(message)
    keep_last = int(parts[1]) if len(parts) > 1 else 12
    try:
        result = agent.compact(session_id, keep_last=keep_last)
    except Exception as exc:
        print_error("compact", exc)
        return
    if result["compacted"]:
        before = result["before"]["estimated_tokens"]
        after = result["after"]["estimated_tokens"]
        line("compact", f"ok estimated_tokens {before} -> {after}")
    else:
        line("compact", f"skipped reason={result['reason']} estimated_tokens={result['context']['estimated_tokens']}")


def print_or_create_session(agent: Agent, *, session_id: str | None) -> str:
    session = agent.store.load(session_id) if session_id else agent.store.create()
    line("session", f"{session.id} messages={len(session.messages)} traces={len(session.traces)}")
    return session.id


def print_history(agent: Agent, *, session_id: str | None) -> None:
    if not session_id:
        line("session", "no active session")
        return
    session = agent.store.load(session_id)
    for item in session.messages[-20:]:
        role = item.get("role", "")
        content = str(item.get("content", ""))
        line(role, content[:500])


def print_traces(agent: Agent, *, session_id: str | None) -> None:
    if not session_id:
        line("session", "no active session")
        return
    session = agent.store.load(session_id)
    for trace in session.traces[-10:]:
        line("trace", f"{trace.get('id')} rounds={trace.get('tool_rounds')} spans={len(trace.get('spans', []))}")


def print_tools(agent: Agent) -> None:
    for item in agent.tools.list_metadata():
        line("tool", f"{item['name']}: {item['description']}")


def print_self_test(*, agent: Agent) -> None:
    result = agent.run_self_test()
    line("self-test", f"status={result['status']} total={result['summary']['total']} ok={result['summary']['ok']} warn={result['summary']['warn']} error={result['summary']['error']}")
    for item in result["checks"]:
        status = item.get("status", "unknown")
        message = item.get("message", "")
        line(status, f"{item.get('name')}: {message}")
    for suggestion in result.get("recommendations", []):
        line("hint", suggestion, color="yellow")


def print_banner(agent: Agent, *, session_id: str | None, mock: bool) -> None:
    mode = "mock" if mock else "live"
    print(color_text("Mobile Agent", "bold"))
    line("model", f"{agent.config.model} mode={mode}")
    line("session", session_id or "new on first message")
    line("hint", "type /help for commands", color="dim")


def print_help() -> None:
    print(
        """commands>
  /status              show runtime status
  /doctor              check phone runtime and native tool availability
  /self-test           run a local runtime self-test and get follow-up suggestions
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
