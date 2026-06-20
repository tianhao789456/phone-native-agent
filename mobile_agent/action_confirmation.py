from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator


ACTION_TOOLS = {
    "host_call",
    "host_click_text",
    "host_click_view_id",
    "host_click_index",
    "host_input_text",
    "host_back",
    "host_home",
    "host_scroll",
    "host_open_app",
    "tap",
    "swipe",
    "type_text",
    "keyevent",
    "open_app",
}

_action_approved: ContextVar[bool] = ContextVar("mobile_agent_action_approved", default=False)


def is_action_tool(name: str) -> bool:
    return name in ACTION_TOOLS


def current_action_approved() -> bool:
    return _action_approved.get()


@contextmanager
def action_approval(approved: bool) -> Iterator[None]:
    token = _action_approved.set(approved)
    try:
        yield
    finally:
        _action_approved.reset(token)
