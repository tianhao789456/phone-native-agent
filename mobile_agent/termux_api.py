from __future__ import annotations

import json
import shutil
import subprocess
from collections.abc import Callable
from typing import Any


CommandRunner = Callable[..., subprocess.CompletedProcess[str]]
CommandResolver = Callable[[str], str | None]


class TermuxApi:
    def __init__(
        self,
        *,
        command_resolver: CommandResolver = shutil.which,
        runner: CommandRunner = subprocess.run,
    ) -> None:
        self.command_resolver = command_resolver
        self.runner = runner

    def run_text(self, args: list[str], *, timeout: int) -> dict[str, Any]:
        command = self.command_resolver(args[0])
        if not command:
            return {"available": False, "ok": False, "error": f"{args[0]} not found"}
        completed = self.runner([command, *args[1:]], text=True, capture_output=True, timeout=timeout)
        return {
            "available": True,
            "ok": completed.returncode == 0,
            "returncode": completed.returncode,
            "stdout": completed.stdout.strip(),
            "stderr": completed.stderr.strip(),
        }

    def run_json(self, args: list[str], *, timeout: int) -> dict[str, Any]:
        result = self.run_text(args, timeout=timeout)
        if not result.get("ok"):
            return result
        raw = str(result.get("stdout", ""))
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        if isinstance(parsed, dict):
            return parsed
        return {"items": parsed}
