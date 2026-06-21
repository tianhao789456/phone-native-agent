from __future__ import annotations

import unittest

from mobile_agent.core import agent_protocol


class AgentProtocolTests(unittest.TestCase):
    def test_initial_messages_uses_new_user_message_when_prev_id_exists(self) -> None:
        messages = [
            {"role": "system", "content": "system"},
            {"role": "user", "content": "hello"},
            {"role": "assistant", "content": "reply"},
        ]
        result = agent_protocol.build_initial_messages(
            messages,
            use_previous_response_context=True,
            previous_response_id="resp-1",
            current_message="next",
        )
        self.assertEqual(result, [{"role": "user", "content": "next"}])

    def test_initial_messages_uses_full_history_when_no_previous_id(self) -> None:
        messages = [
            {"role": "system", "content": "system"},
            {"role": "user", "content": "hello"},
            {"role": "assistant", "content": "reply"},
            {"role": "tool", "name": "read_file", "content": "ok"},
            {"role": "assistant", "tool_calls": [{"id": "c1"}]},
        ]
        result = agent_protocol.build_initial_messages(
            messages,
            use_previous_response_context=True,
            previous_response_id=None,
            current_message="next",
        )
        self.assertEqual(
            result,
            [
                {"role": "system", "content": "system"},
                {"role": "user", "content": "hello"},
                {"role": "assistant", "content": "reply"},
                {"role": "assistant", "content": "Previous read_file result: ok"},
                {"role": "assistant", "content": ""},
            ],
        )

    def test_history_messages_preserves_window(self) -> None:
        messages = []
        for index in range(4):
            messages.append({"role": "user", "content": f"m{index}"})
        messages.append({"role": "tool", "name": "a", "content": "tool output"})
        result = agent_protocol.build_history_messages(messages, limit=3)
        self.assertEqual(len(result), 3)
        self.assertEqual(result[0]["content"], "m2")

    def test_protocol_messages_keeps_tool_result_pairing(self) -> None:
        messages = [
            {"role": "system", "content": "s"},
            {"role": "user", "content": "u"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"id": "call-1", "type": "function", "function": {"name": "host_back", "arguments": "{}"}}],
            },
            {"role": "tool", "tool_call_id": "call-1", "name": "host_back", "content": "{\"ok\": true}"},
        ]
        result = agent_protocol.build_protocol_messages(messages, limit=10)
        self.assertEqual(len(result), 4)
        self.assertEqual(result[-1]["role"], "tool")
        self.assertEqual(result[-1]["tool_call_id"], "call-1")


    def test_tool_serializers_match_protocol_shape(self) -> None:
        class Call:
            call_id = "call-1"
            name = "get_time"
            arguments = {"tz": "CN"}

        calls = [Call()]
        self.assertEqual(
            agent_protocol.tool_call_messages(calls),
            [
                {
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "get_time", "arguments": "{\"tz\": \"CN\"}"},
                }
            ],
        )
        self.assertEqual(
            agent_protocol.tool_output_messages([{"call_id": "call-1", "output": {"ok": True}}]),
            [{"type": "function_call_output", "call_id": "call-1", "output": "{\"ok\": true}"}],
        )


if __name__ == "__main__":
    unittest.main()
