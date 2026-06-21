from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_phone_tools_is_a_thin_registry_facade() -> None:
    source = (ROOT / "mobile_agent" / "phone_tools.py").read_text(encoding="utf-8")

    assert source.count("@registry.register") == 0
    assert "register_core_tools(" in source
    assert "register_workspace_tools(" in source
    assert "register_host_bridge_tools(" in source
    assert "register_termux_tools(" in source
    assert "register_shell_tools(" in source
    assert "register_android_input_tools(" in source


def test_phone_toolkits_are_grouped_by_runtime_domain() -> None:
    toolkit_dir = ROOT / "mobile_agent" / "phone_toolkits"
    expected = {
        "android_input_tools.py",
        "core_tools.py",
        "host_bridge_tools.py",
        "network_tools.py",
        "pathing.py",
        "shell_tools.py",
        "termux_tools.py",
        "workspace_tools.py",
    }

    assert expected.issubset({path.name for path in toolkit_dir.glob("*.py")})
