from __future__ import annotations

import tempfile
import time
import unittest
from pathlib import Path

from mobile_agent.hosts.terminal_tasks import (
    TerminalTaskManager,
    coerce_preview_chars,
    coerce_timeout,
    fold_text,
    normalize_interpreter,
    script_filename,
)


class TerminalTaskTests(unittest.TestCase):
    def test_script_task_executes_and_persists_folded_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "tasks"
            manager = TerminalTaskManager(root)
            result = manager.create_script_task(
                {
                    "script": "print('start')\nfor i in range(200): print(f'line-{i}')\n",
                    "interpreter": "python",
                    "cwd": tmp,
                    "timeout": 10,
                    "max_output_chars": 1000,
                }
            )

        self.assertEqual(result["status"], "finished")
        self.assertEqual(result["returncode"], 0)
        self.assertTrue(result["ok"])
        self.assertTrue(result["output"]["stdout"]["truncated"])
        self.assertTrue(Path(result["script_path"]).name.endswith(".py"))

    def test_script_task_can_run_without_wait_and_be_read_later(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manager = TerminalTaskManager(Path(tmp) / "tasks")
            result = manager.create_script_task(
                {
                    "script": "print('async-ok')\n",
                    "interpreter": "python",
                    "cwd": tmp,
                    "wait": False,
                    "timeout": 10,
                }
            )
            task_id = result["task_id"]
            for _ in range(20):
                loaded = manager.read_task(task_id)
                if loaded["status"] == "finished":
                    break
                time.sleep(0.05)
            else:
                self.fail("task did not finish")

        self.assertEqual(loaded["returncode"], 0)
        self.assertIn("async-ok", loaded["output"]["stdout"]["text"])

    def test_helpers_validate_and_normalize_inputs(self) -> None:
        self.assertEqual(coerce_timeout("5"), 5)
        self.assertEqual(coerce_preview_chars(1), 1000)
        self.assertEqual(coerce_preview_chars(1000000), 50000)
        self.assertEqual(normalize_interpreter("PYTHON3"), "python3")
        self.assertEqual(script_filename("node"), "script.js")
        self.assertEqual(script_filename("python"), "script.py")
        self.assertTrue(fold_text("x" * 2000, 1000)["truncated"])

        with self.assertRaises(ValueError):
            coerce_timeout(0)
        with self.assertRaises(ValueError):
            normalize_interpreter("cmd")


if __name__ == "__main__":
    unittest.main()
