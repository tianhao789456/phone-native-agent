from __future__ import annotations

import ast
from pathlib import Path
from typing import Any


def list_plugins(plugin_dir: Path = Path("plugins")) -> list[dict[str, Any]]:
    plugin_dir.mkdir(parents=True, exist_ok=True)
    found = []
    for item in sorted(plugin_dir.iterdir()):
        entry = None
        if item.is_file() and item.suffix == ".py" and not item.name.startswith("_"):
            entry = item
        elif item.is_dir() and (item / "main.py").exists():
            entry = item / "main.py"
        if not entry:
            continue
        metadata = inspect_plugin(entry)
        metadata["path"] = str(entry)
        found.append(metadata)
    return found


def inspect_plugin(path: Path) -> dict[str, Any]:
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        config: dict[str, Any] = {}
        has_run = False
        for node in tree.body:
            if isinstance(node, ast.Assign):
                names = [target.id for target in node.targets if isinstance(target, ast.Name)]
                if "config" in names:
                    value = ast.literal_eval(node.value)
                    if isinstance(value, dict):
                        config = value
            elif isinstance(node, ast.FunctionDef) and node.name == "run":
                has_run = True
        return {
            "label": str(config.get("label", path.stem)),
            "icon": str(config.get("icon", "")),
            "valid": has_run,
        }
    except Exception as exc:
        return {"label": path.stem, "valid": False, "error": f"{type(exc).__name__}: {exc}"}
