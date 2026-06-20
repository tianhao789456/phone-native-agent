from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from .core.agent import AgentConfig


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[1] / "config" / "agent.json"


def load_config(path: Path = DEFAULT_CONFIG_PATH) -> dict[str, Any]:
    load_dotenv(Path.cwd() / ".env")
    load_dotenv(Path.cwd().parent / ".env")
    load_dotenv(Path(__file__).resolve().parents[2] / ".env")
    load_dotenv(Path.home() / ".mobile-agent.env")
    if not path.exists():
        raise FileNotFoundError(f"Config file not found: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def load_agent_config(path: Path = DEFAULT_CONFIG_PATH) -> AgentConfig:
    data = load_config(path)
    return AgentConfig(
        model=os.environ.get("MOBILE_AGENT_MODEL") or data.get("model", "gpt-5.4-mini"),
        system_prompt=data.get("system_prompt", "You are a helpful phone agent."),
        max_tool_rounds=int(data.get("max_tool_rounds", 5)),
    )


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip().lstrip("\ufeff")
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value
