from __future__ import annotations


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


def format_self_test(result: dict) -> list[str]:
    lines: list[str] = [
        f"self-test status={result['status']} total={result['summary']['total']} ok={result['summary']['ok']} warn={result['summary']['warn']} error={result['summary']['error']}"
    ]
    for item in result["checks"]:
        status = item.get("status", "unknown")
        lines.append(f"{status}: {item.get('name')} {item.get('message', '')}")
    for suggestion in result.get("recommendations", []):
        lines.append(f"hint: {suggestion}")
    return lines
