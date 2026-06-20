from __future__ import annotations

import json
import time
from typing import Any


def estimate_tokens_text(text: str) -> int:
    if not text:
        return 0
    ascii_chars = sum(1 for ch in text if ord(ch) < 128)
    non_ascii_chars = len(text) - ascii_chars
    return max(1, ascii_chars // 4 + non_ascii_chars)


def estimate_message_tokens(message: dict[str, Any]) -> int:
    total = 4
    for key in ("role", "name", "content", "tool_call_id"):
        value = message.get(key)
        if value is not None:
            total += estimate_tokens_text(str(value))
    if message.get("tool_calls"):
        total += estimate_tokens_text(json.dumps(message["tool_calls"], ensure_ascii=False, default=str))
    return total


def context_stats(messages: list[dict[str, Any]], *, model_window: int = 64000) -> dict[str, Any]:
    tokens_by_role: dict[str, int] = {}
    total = 0
    for message in messages:
        role = str(message.get("role", "unknown"))
        tokens = estimate_message_tokens(message)
        tokens_by_role[role] = tokens_by_role.get(role, 0) + tokens
        total += tokens
    return {
        "messages": len(messages),
        "estimated_tokens": total,
        "model_window": model_window,
        "window_used_percent": round((total / model_window) * 100, 2) if model_window else None,
        "by_role": tokens_by_role,
    }


def latest_cache_usage(traces: list[dict[str, Any]]) -> dict[str, Any] | None:
    for trace in reversed(traces):
        for span in reversed(trace.get("spans", [])):
            usage = span.get("usage")
            if usage:
                return usage
        usage = trace.get("usage")
        if usage:
            return usage
    return None


def cache_summary(usage: dict[str, Any] | None) -> dict[str, Any]:
    if not usage:
        return {"available": False}
    hit = int(usage.get("prompt_cache_hit_tokens") or 0)
    miss = int(usage.get("prompt_cache_miss_tokens") or 0)
    prompt = int(usage.get("prompt_tokens") or hit + miss or 0)
    denominator = hit + miss or prompt
    return {
        "available": True,
        "prompt_tokens": prompt,
        "cache_hit_tokens": hit,
        "cache_miss_tokens": miss,
        "cache_hit_percent": round((hit / denominator) * 100, 2) if denominator else 0.0,
    }


def build_compaction_prompt(messages: list[dict[str, Any]], keep_last: int) -> str:
    older = messages[1:-keep_last] if keep_last > 0 else messages[1:]
    transcript = []
    for message in older:
        role = message.get("role", "unknown")
        content = str(message.get("content", ""))
        if message.get("tool_calls"):
            content += "\nTOOL_CALLS: " + json.dumps(message["tool_calls"], ensure_ascii=False, default=str)
        transcript.append(f"{role}: {content}")
    return (
        "Compress the following older conversation into a durable memory summary for a phone-resident agent. "
        "Keep user goals, decisions, constraints, tool results, file paths, session facts, and unresolved tasks. "
        "Do not invent facts. Use concise bullets.\n\n"
        + "\n\n".join(transcript)
    )


def compact_messages(original: list[dict[str, Any]], summary: str, *, keep_last: int) -> list[dict[str, Any]]:
    if not original:
        return original
    system = original[0]
    tail = original[-keep_last:] if keep_last > 0 else []
    summary_message = {
        "role": "system",
        "content": "Compacted prior conversation summary:\n" + summary.strip(),
        "created_at": time.time(),
        "compacted": True,
    }
    return [system, summary_message, *tail]
