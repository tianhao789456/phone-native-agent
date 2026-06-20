from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mobile_agent.core.plugins import list_plugins


class PluginTests(unittest.TestCase):
    def test_listing_plugin_does_not_execute_module(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            marker = root / "executed.txt"
            plugin_dir = root / "plugins"
            plugin_dir.mkdir()
            (plugin_dir / "sample.py").write_text(
                "\n".join(
                    [
                        "from pathlib import Path",
                        f"Path({str(marker)!r}).write_text('bad')",
                        "config = {'label': 'Sample', 'icon': '*'}",
                        "def run():",
                        "    return 'ok'",
                    ]
                ),
                encoding="utf-8",
            )

            plugins = list_plugins(plugin_dir)

            self.assertEqual(plugins[0]["label"], "Sample")
            self.assertTrue(plugins[0]["valid"])
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
