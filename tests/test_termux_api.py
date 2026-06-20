from __future__ import annotations

import subprocess
import unittest

from mobile_agent.termux_api import TermuxApi


class TermuxApiTests(unittest.TestCase):
    def test_run_text_reports_missing_command(self) -> None:
        api = TermuxApi(command_resolver=lambda name: None)

        result = api.run_text(["termux-missing"], timeout=1)

        self.assertFalse(result["available"])
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

    def test_run_text_uses_resolved_command(self) -> None:
        calls = []

        def runner(args, **kwargs):
            calls.append((args, kwargs))
            return subprocess.CompletedProcess(args=args, returncode=0, stdout=" done\n", stderr="")

        api = TermuxApi(command_resolver=lambda name: f"/bin/{name}", runner=runner)

        result = api.run_text(["termux-torch", "on"], timeout=7)

        self.assertTrue(result["available"])
        self.assertTrue(result["ok"])
        self.assertEqual(result["stdout"], "done")
        self.assertEqual(calls[0][0], ["/bin/termux-torch", "on"])
        self.assertEqual(calls[0][1]["timeout"], 7)

    def test_run_text_reports_nonzero_returncode(self) -> None:
        def runner(args, **kwargs):
            return subprocess.CompletedProcess(args=args, returncode=2, stdout="", stderr="denied\n")

        api = TermuxApi(command_resolver=lambda name: f"/bin/{name}", runner=runner)

        result = api.run_text(["termux-camera-photo"], timeout=1)

        self.assertTrue(result["available"])
        self.assertFalse(result["ok"])
        self.assertEqual(result["stderr"], "denied")

    def test_run_json_parses_dict_and_list(self) -> None:
        outputs = iter(['{"level": 99}', '["a", "b"]'])

        def runner(args, **kwargs):
            return subprocess.CompletedProcess(args=args, returncode=0, stdout=next(outputs), stderr="")

        api = TermuxApi(command_resolver=lambda name: f"/bin/{name}", runner=runner)

        self.assertEqual(api.run_json(["termux-battery-status"], timeout=1), {"level": 99})
        self.assertEqual(api.run_json(["termux-sensor", "-l"], timeout=1), {"items": ["a", "b"]})

    def test_run_json_keeps_invalid_json_as_raw_text(self) -> None:
        def runner(args, **kwargs):
            return subprocess.CompletedProcess(args=args, returncode=0, stdout="not-json", stderr="")

        api = TermuxApi(command_resolver=lambda name: f"/bin/{name}", runner=runner)

        self.assertEqual(api.run_json(["termux-sensor"], timeout=1), {"raw": "not-json"})


if __name__ == "__main__":
    unittest.main()
