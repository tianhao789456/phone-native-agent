from __future__ import annotations

from typing import Any, Callable

from mobile_agent.core.tools import ToolRegistry


def register_host_bridge_tools(
    registry: ToolRegistry,
    *,
    host_bridge: Any,
    current_action_approved: Callable[[], bool],
) -> None:
    @registry.register(description="Return Android Host App bridge status when the native host is running.")
    def host_status() -> dict[str, Any]:
        return host_bridge.status()

    @registry.register(description="List tools exposed by the Android Host App bridge.")
    def host_tools() -> dict[str, Any]:
        return host_bridge.tools()

    @registry.register(description="Call a tool exposed by the Android Host App bridge.")
    def host_call(tool: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        return host_bridge.call(tool, arguments or {}, actions_approved=current_action_approved())

    @registry.register(description="Return the Android Host App private workspace status.")
    def host_workspace_info() -> dict[str, Any]:
        return host_bridge.call("workspace.info", {})

    @registry.register(description="List files under the Android Host App private workspace.")
    def host_list_files(path: str = ".", max_entries: int = 100) -> dict[str, Any]:
        return host_bridge.call("workspace.list", {"path": path, "max_entries": max_entries})

    @registry.register(description="Read a UTF-8 text file under the Android Host App private workspace.")
    def host_read_file(path: str, max_bytes: int = 20000) -> dict[str, Any]:
        return host_bridge.call("workspace.read", {"path": path, "max_bytes": max_bytes})

    @registry.register(description="Write a UTF-8 text file under the Android Host App private workspace.")
    def host_write_file(path: str, content: str, overwrite: bool = False) -> dict[str, Any]:
        return host_bridge.call("workspace.write", {"path": path, "content": content, "overwrite": overwrite})

    @registry.register(description="Search UTF-8 text files under the Android Host App private workspace.")
    def host_search_files(
        query: str,
        path: str = ".",
        max_matches: int = 50,
        max_bytes_per_file: int = 200000,
    ) -> dict[str, Any]:
        return host_bridge.call(
            "workspace.search",
            {
                "query": query,
                "path": path,
                "max_matches": max_matches,
                "max_bytes_per_file": max_bytes_per_file,
            },
        )

    @registry.register(description="Take a structured Accessibility snapshot. Prefer this over deprecated host_observe/host_screen_dump.")
    def accessibility_snapshot_v2(
        max_nodes: int = 120,
        max_depth: int = 12,
        include_uninteresting: bool = False,
        visible_only: bool = True,
        max_text_chars: int = 300,
    ) -> dict[str, Any]:
        return host_bridge.call(
            "accessibility_snapshot_v2",
            {
                "max_nodes": max_nodes,
                "max_depth": max_depth,
                "include_uninteresting": include_uninteresting,
                "visible_only": visible_only,
                "max_text_chars": max_text_chars,
            },
        )

    @registry.register(description="Observe the current Android foreground app and compact screen node list together. Legacy alias of accessibility_snapshot_v2.")
    def host_observe(max_nodes: int = 40) -> dict[str, Any]:
        return host_bridge.call("accessibility_snapshot_v2", {"max_nodes": max_nodes})

    @registry.register(description="Return a compact screen node list from the Android Host App Accessibility backend. Legacy alias of accessibility_snapshot_v2.")
    def host_screen_dump(max_nodes: int = 80) -> dict[str, Any]:
        return host_bridge.call("accessibility_snapshot_v2", {"max_nodes": max_nodes})

    @registry.register(description="Perform a supported global key action through the host Accessibility backend.")
    def host_press_key(key: str) -> dict[str, Any]:
        return host_bridge.call("host_press_key", {"key": key}, actions_approved=current_action_approved())

    @registry.register(description="Find screen nodes by text, content description, view id, or class name through the Android Host App.")
    def host_screen_find(query: str, contains: bool = True, max_nodes: int = 20) -> dict[str, Any]:
        return host_bridge.call("accessibility.find", {"query": query, "contains": contains, "max_nodes": max_nodes})

    @registry.register(description="Return the current foreground app package and root node summary from the Android Host App.")
    def host_current_app() -> dict[str, Any]:
        return host_bridge.call("accessibility.current_app", {})

    @registry.register(description="Open an installed Android app by package name through the Android Host App.")
    def host_open_app(package: str) -> dict[str, Any]:
        return host_bridge.call("android.open_app", {"package": package}, actions_approved=current_action_approved())

    @registry.register(description="Click visible text or content description through the Android Host App Accessibility backend.")
    def host_click_text(text: str, contains: bool = True) -> dict[str, Any]:
        return host_bridge.call("accessibility.click_text", {"text": text, "contains": contains}, actions_approved=current_action_approved())

    @registry.register(description="Click an Android view resource id through the Android Host App Accessibility backend.")
    def host_click_view_id(view_id: str) -> dict[str, Any]:
        return host_bridge.call("accessibility.click_view_id", {"view_id": view_id}, actions_approved=current_action_approved())

    @registry.register(description="Click a node by index from accessibility_snapshot_v2 through the Android Host App Accessibility backend.")
    def host_click_index(index: int) -> dict[str, Any]:
        return host_bridge.call("accessibility.click_index", {"index": index}, actions_approved=current_action_approved())

    @registry.register(description="Set text in the focused or first editable field through the Android Host App Accessibility backend.")
    def host_input_text(text: str) -> dict[str, Any]:
        return host_bridge.call("accessibility.input_text", {"text": text}, actions_approved=current_action_approved())

    @registry.register(description="Perform Android Back through the Android Host App Accessibility backend. Legacy alias of host_press_key('back').")
    def host_back() -> dict[str, Any]:
        return host_press_key("back")

    @registry.register(description="Perform Android Home through the Android Host App Accessibility backend. Legacy alias of host_press_key('home').")
    def host_home() -> dict[str, Any]:
        return host_press_key("home")

    @registry.register(description="Scroll the current page through the Android Host App Accessibility backend.")
    def host_scroll(direction: str = "forward", text: str = "", view_id: str = "") -> dict[str, Any]:
        return host_bridge.call(
            "accessibility.scroll",
            {"direction": direction, "text": text, "view_id": view_id},
            actions_approved=current_action_approved(),
        )
