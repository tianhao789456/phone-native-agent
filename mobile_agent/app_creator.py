from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from .core.llm import LlmClient


def safe_name(name: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_-]+", "_", name.strip()).strip("_").lower()
    return cleaned or "app"


def list_apps(root: Path = Path("my_apps")) -> list[dict[str, Any]]:
    root.mkdir(parents=True, exist_ok=True)
    apps = []
    for path in sorted(root.iterdir()):
        if path.is_dir():
            index = path / "index.html"
            apps.append({"name": path.name, "path": str(path), "has_index": index.exists()})
    return apps


def extract_html(text: str) -> str:
    if "```html" in text:
        return text.split("```html", 1)[1].split("```", 1)[0].strip()
    if "```" in text:
        return text.split("```", 1)[1].split("```", 1)[0].strip()
    return text.strip()


def create_app(*, llm: LlmClient, model: str, name: str, prompt: str, root: Path = Path("my_apps")) -> dict[str, Any]:
    app_name = safe_name(name)
    app_dir = root / app_name
    app_dir.mkdir(parents=True, exist_ok=True)
    system_prompt = (
        "You create small mobile-first single-file web apps for Android browsers. "
        "Return only complete HTML with inline CSS and JavaScript. "
        "No markdown, no explanation. Use touch-friendly controls."
    )
    result = llm.respond(
        model=model,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": prompt},
        ],
        tools=[],
    )
    html = extract_html(result.text)
    target = app_dir / "index.html"
    target.write_text(html, encoding="utf-8")
    return {"name": app_name, "path": str(target), "bytes": len(html.encode("utf-8")), "usage": result.usage}

