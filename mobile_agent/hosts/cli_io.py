from __future__ import annotations

import os
import sys


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


def print_error(scope: str, exc: Exception) -> None:
    line("error", f"{scope}: {type(exc).__name__}: {exc}", color="red")
    line("hint", "command loop is still alive; use /status, /context, /new, or /exit", color="dim")
