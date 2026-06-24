# Phone Self-Test Feedback Plan (v1)

## Goal

Ensure the phone side can also read and display self-test results, rather than just dumping execution logs.

## Two Types of Verification

### PC-Side Verification

PC-side verification does not require an Android App installation.

It only validates that the Python Agent core, CLI commands, HTTP routes, protocol assembly, and self-test result structure are stable. Currently covered:

- `agent.run_self_test()`
- CLI `/self-test`
- HTTP `GET /self-test`
- `status / summary / checks / recommendations` response structure

Corresponding tests:

- `tests/test_self_test.py`
- `tests/test_agent_protocol.py`
- `tests/test_http.py`
- `tests/test_cli.py`

### Phone Real-Device Verification

Phone-side verification requires installing the latest APK and enabling necessary permissions.

These must be verified on a real device:

- Whether the App UI can display `/self-test` results
- Whether the Android Host bridge is reachable
- Whether Accessibility tools return the screen structure
- Whether native tools can be called
- Whether failure recommendations are displayed to the user on the phone

Prerequisites:

- Install the latest APK
- Enable Accessibility Service
- Grant necessary permissions (notifications, storage, etc.)
- Start the Android Host / Python Host connection
- To verify the host bridge, enable `include_host_bridge_check`

## Phone-Side Verification Steps

1. Install the latest APK and start the phone-side Agent.
2. Send `/self-test` on the phone, or request `GET /self-test`.
3. Verify the response includes at least:
   - `status` (`ok` / `warn` / `error`)
   - `summary.total / ok / warn / error`
   - `checks`
   - `recommendations`
4. Check for these key check names:
   - `python_runtime`
   - `agent_config`
   - `session_store`
   - `tool_registry`
   - `critical_tool:get_time`
5. If host bridge check is enabled, it should also include:
   - `host_bridge`

## Expected Behavior

- When `status=ok`, `recommendations` can be empty.
- When `status=warn/error`, corresponding recommendations should be provided and displayed in the phone UI.
- Host bridge is not forced by default, to avoid false alarms during PC-only testing or when no phone is connected.

## Regression Cases

- If `tool_registry` is missing `get_time`, a `critical_tool:get_time` warning should appear.
- If the API key is empty, `agent_config` should be `error`, and the overall `status` should be `error`.
- `host_bridge` is not checked by default; after enabling `include_host_bridge_check`, the `host_bridge` check should be returned.
