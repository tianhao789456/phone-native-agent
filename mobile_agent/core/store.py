from __future__ import annotations

import json
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class Session:
    id: str
    messages: list[dict[str, Any]] = field(default_factory=list)
    traces: list[dict[str, Any]] = field(default_factory=list)
    previous_response_id: str | None = None
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


class ConversationStore:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def create(self) -> Session:
        session = Session(id=str(uuid.uuid4()))
        self.save(session)
        return session

    def load(self, session_id: str | None) -> Session:
        if not session_id:
            return self.create()
        path = self._path(session_id)
        if not path.exists():
            return Session(id=session_id)
        data = json.loads(path.read_text(encoding="utf-8"))
        return Session(
            id=data["id"],
            messages=data.get("messages", []),
            traces=data.get("traces", []),
            previous_response_id=data.get("previous_response_id"),
            created_at=float(data.get("created_at", time.time())),
            updated_at=float(data.get("updated_at", time.time())),
        )

    def save(self, session: Session) -> None:
        session.updated_at = time.time()
        text = json.dumps(asdict(session), ensure_ascii=False, indent=2)
        self._path(session.id).write_bytes(text.encode("utf-8", errors="replace"))

    def list_sessions(self) -> list[dict[str, object]]:
        sessions: list[dict[str, object]] = []
        for path in sorted(self.root.glob("*.json")):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            sessions.append(
                {
                    "id": data.get("id", path.stem),
                    "messages": len(data.get("messages", [])),
                    "traces": len(data.get("traces", [])),
                    "previous_response_id": data.get("previous_response_id"),
                    "created_at": data.get("created_at"),
                    "updated_at": data.get("updated_at"),
                }
            )
        return sorted(sessions, key=lambda item: float(item.get("updated_at") or 0), reverse=True)

    def latest(self) -> Session | None:
        sessions = self.list_sessions()
        if not sessions:
            return None
        return self.load(str(sessions[0]["id"]))

    def delete(self, session_id: str) -> bool:
        path = self._path(session_id)
        if not path.exists():
            return False
        path.unlink()
        return True

    def _path(self, session_id: str) -> Path:
        safe = "".join(ch for ch in session_id if ch.isalnum() or ch in ("-", "_"))
        return self.root / f"{safe}.json"
