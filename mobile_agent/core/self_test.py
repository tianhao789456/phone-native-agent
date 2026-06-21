from __future__ import annotations

import time
from pathlib import Path
from typing import Any


def run_agent_self_test(*, agent: Any, include_host_bridge_check: bool = False) -> dict[str, Any]:
    started_at = time.time()
    checks: list[dict[str, Any]] = []
    checks.append(_check_python_runtime())
    checks.append(_check_agent_config(agent))
    checks.append(_check_session_store(agent))
    checks.extend(_check_tools(agent))
    if include_host_bridge_check:
        checks.append(_check_host_bridge(agent))

    summary = {"ok": 0, "warn": 0, "error": 0}
    for check in checks:
        status = str(check.get("status", "warn"))
        if status not in summary:
            status = "warn"
        summary[status] += 1

    if summary["error"]:
        status = "error"
    elif summary["warn"]:
        status = "warn"
    else:
        status = "ok"

    recommendations: list[str] = []
    for check in checks:
        suggestion = check.get("suggestion")
        if suggestion:
            recommendations.append(f"[{check['name']}] {suggestion}")

    return {
        "generated_at": started_at,
        "finished_at": time.time(),
        "status": status,
        "checks": checks,
        "summary": {
            "total": len(checks),
            "ok": summary["ok"],
            "warn": summary["warn"],
            "error": summary["error"],
        },
        "recommendations": recommendations,
    }


def _check_python_runtime() -> dict[str, Any]:
    import sys

    return _build_check(
        "python_runtime",
        "ok",
        f"python={sys.version.split()[0]} platform={sys.platform}",
    )


def _check_agent_config(agent: Any) -> dict[str, Any]:
    model = getattr(agent.config, "model", None)
    try:
        llm = agent.llm
    except Exception as exc:  # pragma: no cover - defensive
        return _build_check(
            "agent_config",
            "error",
            f"failed to read llm config: {type(exc).__name__}: {exc}",
            {"model": model},
            suggestion="Check provider configuration, model name, and API key inputs.",
        )

    llm_name = type(llm).__name__
    if llm_name == "MockLlmClient":
        return _build_check("agent_config", "ok", "using MockLlmClient; no API key required.", {"model": model, "llm": llm_name})

    api_key = getattr(llm, "api_key", None)
    if api_key is None:
        return _build_check(
            "agent_config",
            "warn",
            "llm api_key attribute missing; cannot fully validate credentials.",
            {"model": model, "llm": llm_name},
            suggestion="Review environment and run /status to confirm provider configuration.",
        )

    if str(api_key).strip():
        return _build_check("agent_config", "ok", "llm api key resolved.", {"model": model, "llm": llm_name})

    return _build_check(
        "agent_config",
        "error",
        "llm api key is empty.",
        {"model": model, "llm": llm_name},
        suggestion="Set MOBILE_AGENT_API_KEY / DEEPSEEK_API_KEY / OPENAI_API_KEY.",
    )


def _check_session_store(agent: Any) -> dict[str, Any]:
    try:
        root = getattr(agent.store, "root")
        if not isinstance(root, Path):
            raise TypeError(f"unexpected store.root type: {type(root).__name__}")
        root.mkdir(parents=True, exist_ok=True)
        probe = root / ".self-test-probe"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
    except Exception as exc:
        return _build_check(
            "session_store",
            "error",
            f"session store check failed: {type(exc).__name__}: {exc}",
            suggestion="Fix file permissions for .mobile-agent-sessions or set a writable session root.",
        )
    return _build_check("session_store", "ok", f"session store writable: {root}")


def _check_tools(agent: Any) -> list[dict[str, Any]]:
    try:
        tool_names = set(agent.tools.names())
    except Exception as exc:
        return [
            _build_check(
                "tool_registry",
                "error",
                f"failed to read tool names: {type(exc).__name__}: {exc}",
                suggestion="Rebuild tool registry and confirm phone tool module loads normally.",
            )
        ]

    if not tool_names:
        return [_build_check("tool_registry", "error", "no tools are registered.", suggestion="Build and reload the tool registry.")]

    checks: list[dict[str, Any]] = [_build_check(
        "tool_registry", "ok", f"{len(tool_names)} tools available.", {"sample": sorted(tool_names)[:8]}
    )]

    if "get_time" not in tool_names:
        checks.append(
            _build_check("critical_tool:get_time", "error", "missing core tool get_time.", suggestion="Enable time-related tool registration.")
        )
        return checks

    result = agent.tools.execute("get_time", {})
    if not isinstance(result, dict) or not result.get("ok", False):
        checks.append(
            _build_check(
                "critical_tool:get_time",
                "warn",
                f"get_time execution failed: {result.get('error', 'unknown error') if isinstance(result, dict) else result}",
                {"result": result},
                suggestion="Check phone tool bridge and permissions for get_time.",
            )
        )
    else:
        checks.append(_build_check("critical_tool:get_time", "ok", "get_time execution ok."))

    return checks


def _check_host_bridge(agent: Any) -> dict[str, Any]:
    tool_names = set(agent.tools.names()) if hasattr(agent, "tools") else set()
    if "host_status" not in tool_names:
        return _build_check(
            "host_bridge",
            "warn",
            "host_status tool is not registered.",
            suggestion="Enable host bridge tools if running on the phone+native companion.",
        )
    result = agent.tools.execute("host_status", {})
    if not isinstance(result, dict) or not result.get("ok", False):
        return _build_check(
            "host_bridge",
            "warn",
            f"host_status call failed: {result.get('error', 'unknown error') if isinstance(result, dict) else result}",
            result if isinstance(result, dict) else None,
            suggestion="Check Android host bridge and phone permission state.",
        )
    return _build_check("host_bridge", "ok", "host_status call reachable.", result if isinstance(result, dict) else None)


def _build_check(
    name: str,
    status: str,
    message: str,
    details: dict[str, Any] | None = None,
    *,
    suggestion: str | None = None,
) -> dict[str, Any]:
    check: dict[str, Any] = {"name": name, "status": status, "message": message}
    if details is not None:
        check["details"] = details
    if suggestion:
        check["suggestion"] = suggestion
    return check
