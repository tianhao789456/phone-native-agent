from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_SRC = ROOT / "android-host" / "app" / "src" / "main"
KOTLIN_SRC = ANDROID_SRC / "java" / "com" / "mobileagent" / "host"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def execute_tool_source(core: str) -> str:
    if "private fun executeTool(" in core:
        execute_start = core.index("private fun executeTool(")
        execute_end = core.index("private fun docsIndex", execute_start)
        return core[execute_start:execute_end]
    return read(KOTLIN_SRC / "NativeToolExecutor.kt")


def core_chat_wrapper_source(core: str) -> str:
    chat_start = core.index("fun chat(")
    chat_end = core.index("private fun systemLogs(", chat_start)
    return core[chat_start:chat_end]


def test_accessibility_snapshot_v2_contract_is_registered_and_dispatched() -> None:
    accessibility = read(KOTLIN_SRC / "AccessibilityState.kt")
    formatter = read(KOTLIN_SRC / "AccessibilityNodeFormatter.kt")
    snapshot_builder = read(KOTLIN_SRC / "AccessibilitySnapshotBuilder.kt")
    phone_catalog = read(KOTLIN_SRC / "NativePhoneToolCatalog.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    phone_dispatcher = read(KOTLIN_SRC / "NativePhoneToolDispatcher.kt")

    assert "fun snapshotV2(" in accessibility
    assert '"accessibility_snapshot_v2"' in snapshot_builder
    assert "visibleOnly: Boolean = true" in accessibility
    assert "maxTextChars: Int = 300" in accessibility
    assert "AccessibilitySnapshotBuilder.snapshot(" in accessibility
    assert "AccessibilityNodeFormatter.summaryV2(" in snapshot_builder
    assert '.put("version", "accessibility_snapshot_v2")' in snapshot_builder
    assert '.put("actionable", actions)' in snapshot_builder
    assert '.put("agent_actions", agentActions(node))' in formatter
    assert "ACTION_SELECT" not in formatter[formatter.index("private fun agentActions") :]

    assert 'name = "accessibility_snapshot_v2"' in phone_catalog
    assert '"visible_only" to NativeToolSchema.boolProp(true)' in phone_catalog
    assert '"max_text_chars" to NativeToolSchema.intProp(300)' in phone_catalog

    assert '"accessibility_snapshot_v2" -> AccessibilityState.snapshotV2(' in phone_dispatcher
    assert 'arguments.optBoolean("visible_only", true)' in phone_dispatcher
    assert '"accessibility_snapshot_v2",' in phone_dispatcher
    assert "val TOOL_NAMES = setOf(" in phone_dispatcher


def test_accessibility_node_formatter_owns_node_serialization() -> None:
    accessibility = read(KOTLIN_SRC / "AccessibilityState.kt")
    formatter = read(KOTLIN_SRC / "AccessibilityNodeFormatter.kt")
    tree_search = read(KOTLIN_SRC / "AccessibilityTreeSearch.kt")

    assert "object AccessibilityNodeFormatter" in formatter
    assert "fun isInteresting(node: AccessibilityNodeInfo): Boolean" in formatter
    assert "fun selectorFor(node: AccessibilityNodeInfo, path: String): String" in formatter
    assert "fun summary(node: AccessibilityNodeInfo): JSONObject" in formatter
    assert "fun summaryV2(" in formatter
    assert "private fun nodeActions(node: AccessibilityNodeInfo): JSONArray" in formatter
    assert "private fun agentActions(node: AccessibilityNodeInfo): JSONArray" in formatter
    assert "private fun compactText(value: String, maxChars: Int)" in formatter

    assert "AccessibilityNodeFormatter.summary(root)" in accessibility
    assert "AccessibilityNodeFormatter.summary(target)" in accessibility
    assert "AccessibilityNodeFormatter.isInteresting(" in tree_search
    assert "AccessibilityNodeFormatter.selectorFor(" in tree_search
    assert "private fun isInteresting(" not in accessibility
    assert "private fun selectorFor(" not in accessibility
    assert "private fun nodeSummary(" not in accessibility
    assert "private fun nodeSummaryV2(" not in accessibility
    assert "private fun nodeActions(" not in accessibility
    assert "private fun agentActions(" not in accessibility


def test_accessibility_tree_search_owns_traversal_and_matching() -> None:
    accessibility = read(KOTLIN_SRC / "AccessibilityState.kt")
    tree_search = read(KOTLIN_SRC / "AccessibilityTreeSearch.kt")

    assert "object AccessibilityTreeSearch" in tree_search
    assert "fun collect(node: AccessibilityNodeInfo, out: JSONArray, maxNodes: Int, path: String, depth: Int)" in tree_search
    assert "fun collectMatches(" in tree_search
    assert "fun findNode(" in tree_search
    assert "fun interestingNodeByIndex(root: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo?" in tree_search
    assert "fun matches(value: String, expected: String, contains: Boolean): Boolean" in tree_search

    assert "AccessibilityTreeSearch.collect(root, nodes, maxNodes, path = \"0\", depth = 0)" in accessibility
    assert "AccessibilityTreeSearch.collectMatches(root, matches, query, contains, maxNodes, path = \"0\", depth = 0)" in accessibility
    assert "AccessibilityTreeSearch.findNode(root)" in accessibility
    assert "AccessibilityTreeSearch.interestingNodeByIndex(root, index)" in accessibility
    assert "AccessibilityTreeSearch.matches(" in accessibility
    assert "private fun collect(" not in accessibility
    assert "private fun collectMatches(" not in accessibility
    assert "private fun findNode(" not in accessibility
    assert "private fun nodeByInterestingIndex(" not in accessibility
    assert "private fun matches(" not in accessibility


def test_intent_file_tools_are_split_registered_and_fileprovider_backed() -> None:
    intent_tools = read(KOTLIN_SRC / "AndroidIntentTools.kt")
    android_actions = read(KOTLIN_SRC / "NativeAndroidActionController.kt")
    phone_catalog = read(KOTLIN_SRC / "NativePhoneToolCatalog.kt")
    tool_sets = read(KOTLIN_SRC / "NativeCoreToolSets.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    phone_dispatcher = read(KOTLIN_SRC / "NativePhoneToolDispatcher.kt")
    manifest = read(ANDROID_SRC / "AndroidManifest.xml")
    file_paths = read(ANDROID_SRC / "res" / "xml" / "file_paths.xml")
    gradle = read(ROOT / "android-host" / "app" / "build.gradle.kts")

    assert "class AndroidIntentTools(" in intent_tools
    assert "fun intentOpen(arguments: JSONObject)" in intent_tools
    assert "fun shareFile(arguments: JSONObject)" in intent_tools
    assert "fun openFileWith(arguments: JSONObject)" in intent_tools
    assert "FileProvider.getUriForFile" in intent_tools
    assert "Intent.FLAG_GRANT_READ_URI_PERMISSION" in intent_tools

    for tool_name in ("intent_open", "share_file", "open_file_with"):
        assert f'name = "{tool_name}"' in phone_catalog
        assert f'"{tool_name}" -> intentTools.' in phone_dispatcher
        assert f'"{tool_name}"' in tool_sets

    assert "private fun intentOpen" not in core
    assert "private fun shareFile" not in core
    assert "private fun openFileWith" not in core

    assert "class NativeAndroidActionController(" in android_actions
    assert "fun openApp(packageName: String): JSONObject" in android_actions
    assert "fun openUrl(url: String): JSONObject" in android_actions
    assert "AccessibilityState.observeAfterAction(delayMs = 500)" in android_actions
    assert "AccessibilityState.observeAfterAction(delayMs = 800)" in android_actions
    assert "private val androidActions = NativeAndroidActionController(context)" in core
    assert "openApp = { packageName -> androidActions.openApp(packageName) }" in core
    assert "openUrl = { url -> androidActions.openUrl(url) }" in core
    assert "private fun openApp(" not in core
    assert "private fun openUrl(" not in core
    assert "androidx.core.content.FileProvider" in manifest
    assert "${applicationId}.fileprovider" in manifest
    assert "<files-path" in file_paths
    assert "<external-path" in file_paths
    assert 'androidx.core:core-ktx' in gradle


def test_native_tool_descriptor_is_split_from_registry_catalog() -> None:
    descriptor = read(KOTLIN_SRC / "NativeToolDescriptor.kt")
    registry = read(KOTLIN_SRC / "NativeToolRegistry.kt")
    catalog = read(KOTLIN_SRC / "NativeToolCatalog.kt")
    schema = read(KOTLIN_SRC / "NativeToolSchema.kt")
    phone_catalog = read(KOTLIN_SRC / "NativePhoneToolCatalog.kt")
    terminal_catalog = read(KOTLIN_SRC / "NativeTerminalToolCatalog.kt")
    ssh_catalog = read(KOTLIN_SRC / "NativeSshToolCatalog.kt")

    assert "data class NativeToolDescriptor(" in descriptor
    assert "enum class NativeToolAccess" in descriptor
    assert "enum class NativeToolRisk" in descriptor
    assert "fun schema(): JSONObject" in descriptor

    assert "data class NativeToolDescriptor(" not in registry
    assert "enum class NativeToolAccess" not in registry
    assert "val descriptors: List<NativeToolDescriptor> = NativeToolCatalog.descriptors" in registry
    assert "NativeToolDescriptor(" not in registry
    assert "object NativeToolCatalog" in catalog
    assert "NativePhoneToolCatalog.descriptors" in catalog
    assert "NativeTerminalToolCatalog.descriptors" in catalog
    assert "NativeSshToolCatalog.descriptors" in catalog
    assert "object NativeToolSchema" in schema
    assert "fun props(vararg pairs: Pair<String, JSONObject>)" in schema
    assert 'name = "accessibility_snapshot_v2"' in phone_catalog
    assert '"terminal_run"' in terminal_catalog
    assert 'name = "ssh_run"' in ssh_catalog


def test_native_tool_group_policy_is_split_from_registry_catalog() -> None:
    groups = read(KOTLIN_SRC / "NativeToolGroups.kt")
    registry = read(KOTLIN_SRC / "NativeToolRegistry.kt")

    assert "class NativeToolGroups(" in groups
    assert "fun baselineTools()" in groups
    assert "fun toolsForGroups(" in groups
    assert "fun normalizeTools(" in groups

    assert "private val toolGroups = NativeToolGroups(descriptors)" in registry
    assert "private val toolsByGroup = mapOf(" not in registry
    assert "fun toolsForGroups(groups: Set<String>): Set<String> = toolGroups.toolsForGroups(groups)" in registry


def test_native_chat_stop_and_model_request_are_split_from_core_loop() -> None:
    stop = read(KOTLIN_SRC / "NativeStopController.kt")
    requester = read(KOTLIN_SRC / "NativeModelRequester.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "class NativeStopController(" in stop
    assert "fun request(sessionId: String)" in stop
    assert "fun block(sessionId: String, phase: String" in stop
    assert "private val stopRequests = ConcurrentHashMap" in stop

    assert "class NativeModelRequester(" in requester
    assert "fun requestWithEvents(" in requester
    assert "private fun messagesWithMemoryContext(" in requester
    assert "[MOBILE_AGENT_RELEVANT_MEMORY_V2]" in requester

    assert "private val stopController = NativeStopController" in core
    assert "private val modelRequester = NativeModelRequester(modelClient)" in core
    assert "stopController.request(sessionId)" in core
    assert "modelRequester.requestWithEvents(" in loop_engine
    assert "modelRequester.requestWithEvents(" not in core
    assert "private val stopRequests = ConcurrentHashMap" not in core
    assert "private fun requestModelWithEvents(" not in core
    assert "private fun messagesWithMemoryContext(" not in core


def test_native_chat_controller_and_session_store_own_chat_orchestration() -> None:
    chat_controller = read(KOTLIN_SRC / "NativeChatController.kt")
    session_store = read(KOTLIN_SRC / "NativeSessionStore.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeChatController(" in chat_controller
    assert "fun chat(message: String, requestedSessionId: String?, actionsApproved: Boolean = false)" in chat_controller
    assert "taskLoopEngine.run(" in chat_controller
    assert "taskMemoryCoordinator.persistTaskLoopRun(" in chat_controller
    assert "memory.buildInjectionContext(message)" in chat_controller
    assert "contextManager.compactMessagesIfNeeded(sessionId, messages)" in chat_controller
    assert "sessionStore.saveMessages(sessionId, messages)" in chat_controller

    assert "class NativeSessionStore(" in session_store
    assert "fun newSession(): String" in session_store
    assert "fun loadMessages(sessionId: String): JSONArray" in session_store
    assert "fun saveMessages(sessionId: String, messages: JSONArray)" in session_store
    assert "private fun sessionKey(sessionId: String)" in session_store

    assert "private val sessionStore = NativeSessionStore(prefs)" in core
    assert "private val chatController = NativeChatController(" in core
    assert "return chatController.chat(message, requestedSessionId, actionsApproved)" in core
    assert "taskLoopEngine.run(" not in core
    assert "taskMemoryCoordinator.persistTaskLoopRun(" not in core
    assert "memory.buildInjectionContext(message)" not in core
    assert "private fun loadMessages(" not in core
    assert "private fun saveMessages(" not in core
    assert "private fun sessionKey(" not in core


def test_native_diagnostic_tool_runner_owns_diagnostic_native_tool_calls() -> None:
    runner = read(KOTLIN_SRC / "NativeDiagnosticToolRunner.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeDiagnosticToolRunner(" in runner
    assert "fun execute(name: String, arguments: JSONObject, actionsApproved: Boolean = false)" in runner
    assert '"diagnostic native tool call"' in runner
    assert "executeToolDirect(name, arguments, actionsApproved, taskPlan, null)" in runner
    assert "executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, null)" in runner
    assert "DIRECT_DIAGNOSTIC_TOOLS = NativeMcpToolDispatcher.TOOL_NAMES" in runner
    assert "stepEvaluator.summary(output)" in runner

    assert "private val diagnosticToolRunner = NativeDiagnosticToolRunner(" in core
    assert "executeToolDirect = { name, arguments, actionsApproved, taskPlan, sessionId ->" in core
    assert "return diagnosticToolRunner.execute(name, arguments, actionsApproved)" in core
    diagnostic_start = core.index("fun executeNativeToolForDiagnostics(")
    diagnostic_end = core.index("fun reconnectForUi()", diagnostic_start)
    diagnostic_source = core[diagnostic_start:diagnostic_end]
    assert "AgentEventStore.record(" not in diagnostic_source
    assert '"diagnostic native tool call"' not in diagnostic_source
    assert "stepEvaluator.summary(output)" not in diagnostic_source


def test_native_docs_and_status_controllers_own_docs_sync_and_status_payload() -> None:
    docs = read(KOTLIN_SRC / "NativeDocsController.kt")
    status = read(KOTLIN_SRC / "NativeStatusController.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeDocsController(" in docs
    assert "fun index(): JSONObject = MobileAgentDocs.index(context)" in docs
    assert "fun read(arguments: JSONObject): JSONObject" in docs
    assert "fun search(arguments: JSONObject): JSONObject" in docs
    assert "fun sync(): JSONObject = MobileAgentDocs.syncToWorkspace(context, workspace)" in docs
    assert "fun syncOnce()" in docs
    assert "private var officialDocsSynced = false" in docs

    assert "class NativeStatusController(" in status
    assert "fun status(sessionId: String?): JSONObject" in status
    assert '.put("runtime", "android-native")' in status
    assert "toolsets.resolve(activeSessionId)" in status
    assert "docsController.index()" in status
    assert "contextManager.latestUsage(activeSessionId)" in status

    assert "private val docsController = NativeDocsController(" in core
    assert "private val statusController = NativeStatusController(" in core
    assert "return statusController.status(sessionId)" in core
    assert "docsIndex = { docsController.index() }" in core
    assert "docsRead = { arguments -> docsController.read(arguments) }" in core
    assert "docsSearch = { arguments -> docsController.search(arguments) }" in core
    assert "docsSync = { docsController.sync() }" in core
    assert "MobileAgentDocs.syncToWorkspace(context, workspace)" not in core
    assert "private fun docsIndex(" not in core
    assert "private fun docsRead(" not in core
    assert "private fun docsSearch(" not in core
    assert "private fun docsSync(" not in core
    assert "private var officialDocsSynced" not in core


def test_native_tool_call_parsing_is_split_from_chat_loop() -> None:
    tool_call = read(KOTLIN_SRC / "NativeToolCall.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "data class NativeToolCall(" in tool_call
    assert "fun fromJson(call: JSONObject): NativeToolCall" in tool_call
    assert "private fun parseArguments(raw: String)" in tool_call

    assert "NativeToolCall.fromJson(modelResponse.toolCalls.getJSONObject(index))" in loop_engine
    assert "NativeToolCall.fromJson(modelResponse.toolCalls.getJSONObject(index))" not in core
    chat_source = core_chat_wrapper_source(core)
    assert 'optJSONObject("function")' not in chat_source
    assert 'function.optString("arguments")' not in chat_source


def test_native_loop_step_evaluation_is_split_from_core_loop() -> None:
    evaluator = read(KOTLIN_SRC / "NativeLoopStepEvaluator.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    tool_sets = read(KOTLIN_SRC / "NativeCoreToolSets.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "class NativeLoopStepEvaluator(" in evaluator
    for symbol in (
        "fun state(output: JSONObject)",
        "fun summary(output: JSONObject)",
        "fun closedLoopStep(",
        "fun evidenceFromStep(",
        "fun updateVerificationState(",
        "fun completionReview(",
        "fun isStateChangingAction(",
        "fun buildLoopGuardStop(",
    ):
        assert symbol in evaluator

    assert "object NativeCoreToolSets" in tool_sets
    assert "val screenActionTools: Set<String>" in tool_sets
    assert "val terminalDelegationTools: Set<String>" in tool_sets
    assert "val verificationTools: Set<String>" in tool_sets
    assert "private val stepEvaluator = NativeLoopStepEvaluator(" in core
    assert "NativeCoreToolSets.screenActionTools" in core
    assert "NativeCoreToolSets.verificationTools" in core
    assert "private val screenActionTools = setOf(" not in core
    assert "private val terminalDelegationTools = setOf(" not in core
    assert "private val verificationTools = setOf(" not in core
    assert "stepEvaluator.closedLoopStep(" in loop_engine
    assert "stepEvaluator.updateVerificationState(" in loop_engine
    assert "stepEvaluator.completionReview(" in loop_engine
    assert "taskPlan = taskPlan" in loop_engine
    assert "stateChangingActionExecuted" in loop_engine
    assert "single_action_round" in loop_engine
    assert "stepEvaluator.isStateChangingAction(name)" in loop_engine
    assert "stepEvaluator.closedLoopStep(" not in core
    assert "stepEvaluator.updateVerificationState(" not in core
    assert "stepEvaluator.completionReview(" not in core
    assert "private fun toolStepState(" not in core
    assert "private fun toolStepSummary(" not in core
    assert "private fun closedLoopStep(" not in core
    assert "private fun updateVerificationState(" not in core


def test_native_task_loop_has_done_when_completion_gate_and_failure_memory() -> None:
    plan = read(KOTLIN_SRC / "NativeTaskPlanController.kt")
    planning_catalog = read(KOTLIN_SRC / "NativePlanningToolCatalog.kt")
    chat_controller = read(KOTLIN_SRC / "NativeChatController.kt")
    evaluator = read(KOTLIN_SRC / "NativeLoopStepEvaluator.kt")
    memory = read(KOTLIN_SRC / "NativeTaskMemoryCoordinator.kt")
    profile = read(KOTLIN_SRC / "NativeAgentProfile.kt")

    assert 'taskPlan.put("done_when", sanitizeDoneWhen(incomingDoneWhen))' in plan
    assert "private fun sanitizeDoneWhen(values: JSONArray)" in plan
    assert '"done_when" to NativeToolSchema.arrayProp()' in planning_catalog
    assert '.put("done_when", JSONArray())' in chat_controller

    assert "taskPlan: JSONObject" in evaluator
    assert 'val doneWhen = taskPlan.optJSONArray("done_when") ?: JSONArray()' in evaluator
    assert 'status == "completed" && failedSteps == 0 && !pendingOpen && doneWhenSatisfied' in evaluator
    assert '"missing_done_when_evidence"' in evaluator
    assert "done_when_satisfied" in evaluator

    assert "fun recordLoopFailureExperience(" in memory
    assert '"lesson_type", "failed_approach"' in memory
    assert "scopeForTool(tool)" in memory
    assert "recordLoopFailureExperience(" in chat_controller
    assert '"loop_failure_experience"' in chat_controller

    assert "done_when criteria" in profile
    assert "Use exactly one state-changing action per loop round" in profile


def test_native_loop_guard_state_is_split_from_chat_loop() -> None:
    guard = read(KOTLIN_SRC / "NativeLoopGuardState.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "class NativeLoopGuardState(" in guard
    assert "fun retriesLeft(" in guard
    assert "fun failureCount(" in guard
    assert "fun isRepeatedFailure(" in guard

    assert "val loopGuardState = NativeLoopGuardState()" in loop_engine
    assert "val loopGuardState = NativeLoopGuardState()" not in core
    chat_source = core_chat_wrapper_source(core)
    assert "mutableMapOf<String, Int>()" not in chat_source
    assert "loopGuardState.retriesLeft(" in loop_engine
    assert "loopGuardState.failureCount(" in loop_engine


def test_native_tool_step_events_are_split_from_chat_loop() -> None:
    events = read(KOTLIN_SRC / "NativeToolStepEvents.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "class NativeToolStepEvents(" in events
    assert "fun started(" in events
    assert "fun finished(" in events
    assert "fun warnFailed(" in events
    assert "fun loopGuardStopped(" in events

    chat_source = core_chat_wrapper_source(core)
    assert "private val stepEvents = NativeToolStepEvents" in core
    assert "stepEvents.started(" in loop_engine
    assert "stepEvents.finished(" in loop_engine
    assert "stepEvents.warnFailed(" in loop_engine
    assert "stepEvents.started(" not in chat_source
    assert "stepEvents.finished(" not in chat_source
    assert "stepEvents.warnFailed(" not in chat_source
    assert "AgentEventStore.record(\n                    \"tool_started\"" not in chat_source
    assert "\"tool step did not succeed\"" not in chat_source


def test_native_tool_step_artifacts_are_split_from_chat_loop() -> None:
    artifacts = read(KOTLIN_SRC / "NativeToolStepArtifacts.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "data class NativeToolStepArtifacts(" in artifacts
    assert "val loopStep: JSONObject" in artifacts
    assert "val toolTraceItem: JSONObject" in artifacts
    assert "val toolMessage: JSONObject" in artifacts
    assert "fun build(" in artifacts

    chat_source = core_chat_wrapper_source(core)
    assert "NativeToolStepArtifacts.build(" in loop_engine
    assert "toolTrace.put(artifacts.toolTraceItem)" in loop_engine
    assert "messages.put(artifacts.toolMessage)" in loop_engine
    assert "NativeToolStepArtifacts.build(" not in chat_source
    assert "toolTrace.put(artifacts.toolTraceItem)" not in chat_source
    assert "messages.put(artifacts.toolMessage)" not in chat_source
    assert '.put("tool_call_id", call.id)' not in chat_source


def test_native_task_loop_report_is_split_from_core_loop() -> None:
    report = read(KOTLIN_SRC / "NativeTaskLoopReport.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    loop_engine = read(KOTLIN_SRC / "NativeTaskLoopEngine.kt")

    assert "object NativeTaskLoopReport" in report
    assert "fun build(" in report
    assert '.put("completion_review", completionReview)' in report

    chat_source = core_chat_wrapper_source(core)
    assert "NativeTaskLoopReport.build(" in loop_engine
    assert "NativeTaskLoopReport.build(" not in chat_source
    assert '.put("completion_review",' not in chat_source


def test_native_tool_dispatchers_split_plugin_and_phone_domains_from_execute_tool() -> None:
    plugin_dispatcher = read(KOTLIN_SRC / "NativePluginToolDispatcher.kt")
    phone_dispatcher = read(KOTLIN_SRC / "NativePhoneToolDispatcher.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativePluginToolDispatcher(" in plugin_dispatcher
    assert "val TOOL_NAMES = setOf(" in plugin_dispatcher
    assert '"plugin_run" -> runWorkflow(' in plugin_dispatcher

    assert "class NativePhoneToolDispatcher(" in phone_dispatcher
    assert "val TOOL_NAMES = setOf(" in phone_dispatcher
    assert '"host_click_text" -> AccessibilityState.clickText(' in phone_dispatcher
    assert '"intent_open" -> intentTools.intentOpen(arguments)' in phone_dispatcher

    assert "val pluginToolDispatcher = NativePluginToolDispatcher" in factory
    assert "val phoneToolDispatcher = NativePhoneToolDispatcher" in factory
    assert "executor = NativeToolExecutor(" in factory
    assert "private val pluginToolDispatcher = NativePluginToolDispatcher" not in core
    assert "private val phoneToolDispatcher = NativePhoneToolDispatcher" not in core

    execute_source = execute_tool_source(core)
    assert "in NativePluginToolDispatcher.TOOL_NAMES -> pluginExecute(" in execute_source
    assert "in NativePhoneToolDispatcher.TOOL_NAMES -> phoneExecute(" in execute_source
    assert '"plugin_info" -> plugins.info()' not in execute_source
    assert '"host_click_text" -> AccessibilityState.clickText(' not in execute_source
    assert '"intent_open" -> androidIntentTools.intentOpen(arguments)' not in execute_source


def test_native_ssh_dispatcher_splits_remote_pc_tools_from_execute_tool() -> None:
    ssh_dispatcher = read(KOTLIN_SRC / "NativeSshToolDispatcher.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeSshToolDispatcher(" in ssh_dispatcher
    assert "val TOOL_NAMES = setOf(" in ssh_dispatcher
    assert '"ssh_run" -> run(arguments)' in ssh_dispatcher
    assert '"file_push" -> push(arguments)' in ssh_dispatcher
    assert '"pc_file_workflow" -> pcFileWorkflow(arguments)' in ssh_dispatcher

    execute_source = execute_tool_source(core)
    assert "val sshToolDispatcher = NativeSshToolDispatcher" in factory
    assert "private val sshToolDispatcher = NativeSshToolDispatcher" not in core
    assert "in NativeSshToolDispatcher.TOOL_NAMES -> sshExecute(" in execute_source
    assert '"ssh_run" -> sshRun(arguments)' not in execute_source
    assert '"file_push" -> sshFilePush(arguments)' not in execute_source
    assert '"storage_permission_status" -> storagePermissionStatus()' not in execute_source


def test_ssh_bridge_command_and_diagnostics_helpers_are_split() -> None:
    bridge = read(KOTLIN_SRC / "SshBridge.kt")
    command_builder = read(KOTLIN_SRC / "SshBridgeCommandBuilder.kt")
    diagnostics = read(KOTLIN_SRC / "SshBridgeDiagnostics.kt")
    file_transfer = read(KOTLIN_SRC / "SshBridgeFileTransfer.kt")

    assert "object SshBridgeCommandBuilder" in command_builder
    assert "fun build(command: String, cwd: String, shell: String): String" in command_builder
    assert "private fun encodePowerShell(script: String): String" in command_builder
    assert "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand" in command_builder

    assert "object SshBridgeDiagnostics" in diagnostics
    assert "fun hint(status: String): String" in diagnostics
    assert "fun parseCandidateHosts(arguments: JSONObject, fallbackHost: String): List<String>" in diagnostics
    assert "fun formatException(exc: Throwable): String" in diagnostics
    assert "rootCause" in diagnostics

    assert "SshBridgeCommandBuilder.build(command, cwd, shell)" in bridge
    assert "SshBridgeDiagnostics.parseCandidateHosts(arguments, runtimeConfig.sshHost())" in bridge
    assert "SshBridgeDiagnostics.hint(status)" in bridge
    assert "SshBridgeDiagnostics.formatException(" in bridge
    assert "private fun buildRemoteCommand(" not in bridge
    assert "private fun encodePowerShell(" not in bridge
    assert "private fun diagnoseHint(" not in bridge
    assert "private fun parseCandidateHosts(" not in bridge
    assert "private fun formatException(" not in bridge

    assert "class SshBridgeFileTransfer(" in file_transfer
    assert "fun push(current: Session, localPath: String, remotePath: String, overwrite: Boolean = true)" in file_transfer
    assert "fun pull(current: Session, remotePath: String, localPath: String = \"\", overwrite: Boolean = true)" in file_transfer
    assert "private fun resolveLocalPath(path: String): java.io.File?" in file_transfer
    assert "private fun normalizeRemoteSftpPath(path: String): String" in file_transfer
    assert 'Regex("^[A-Za-z]:/.*")' in file_transfer
    assert '"/$normalized"' in file_transfer
    assert '.put("requested_remote_path", remotePath)' in file_transfer
    assert "local path cannot be resolved" in file_transfer
    assert "shared_storage:/Download/..." in file_transfer
    assert "private fun openSftp(session: Session): ChannelSftp?" in file_transfer
    assert "private fun ensureRemoteDirectory(sftp: ChannelSftp, remotePath: String)" in file_transfer
    assert "FileOutputStream(localFile).use" in file_transfer
    assert "sftp.put(localFile.absolutePath, normalizedRemotePath" in file_transfer

    assert "private val fileTransfer = SshBridgeFileTransfer(" in bridge
    assert "return fileTransfer.push(current, localPath, remotePath, overwrite)" in bridge
    assert "return fileTransfer.pull(current, remotePath, localPath, overwrite)" in bridge
    assert "private fun openSftp(" not in bridge
    assert "private fun remoteExists(" not in bridge
    assert "private fun ensureRemoteDirectory(" not in bridge


def test_mobile_workspace_accepts_known_android_absolute_paths_for_transfer() -> None:
    workspace = read(KOTLIN_SRC / "MobileWorkspace.kt")
    ssh_catalog = read(KOTLIN_SRC / "NativeSshToolCatalog.kt")

    assert "private fun resolveKnownAbsolutePath(raw: String): File?" in workspace
    assert "Environment.getExternalStorageDirectory().canonicalFile" in workspace
    assert "context.filesDir.canonicalFile" in workspace
    assert "val knownAbsolute = resolveKnownAbsolutePath(raw)" in workspace
    assert "if (knownAbsolute != null) return knownAbsolute" in workspace
    assert "Android shared storage paths like /storage/emulated/0/Download/..." in workspace
    assert "Android absolute paths under /storage/emulated/0/... are accepted" in ssh_catalog
    assert "should be normalized to shared_storage:/..." in ssh_catalog
    assert "C:/Users/... is accepted and normalized to /C:/Users/..." in ssh_catalog


def test_native_tool_executor_owns_unified_tool_routing() -> None:
    executor = read(KOTLIN_SRC / "NativeToolExecutor.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeToolExecutor(" in executor
    assert "fun execute(" in executor
    assert "private fun permissionGate(" in executor
    assert "when (name) {" in executor

    for symbol in (
        "in NativePlanningToolDispatcher.TOOL_NAMES ->",
        "in NativeMemoryToolDispatcher.TOOL_NAMES ->",
        "in NativeDiagnosticsToolDispatcher.TOOL_NAMES ->",
        "in NativeTerminalToolDispatcher.TOOL_NAMES ->",
        "in NativeWebToolDispatcher.TOOL_NAMES ->",
        "in NativeWorkspaceToolDispatcher.TOOL_NAMES ->",
        "in NativePluginToolDispatcher.TOOL_NAMES ->",
        "in NativePhoneToolDispatcher.TOOL_NAMES ->",
        "in NativeMcpToolDispatcher.TOOL_NAMES ->",
        "in NativePcBridgeToolDispatcher.TOOL_NAMES ->",
        "in NativeSshToolDispatcher.TOOL_NAMES ->",
    ):
        assert symbol in executor

    assert "executor = NativeToolExecutor(" in factory
    assert "private val toolDispatchers = NativeToolDispatchersFactory.create(" in core
    assert "private val toolExecutor = toolDispatchers.executor" in core
    assert "private val toolExecutor = NativeToolExecutor(" not in core
    assert "private fun executeTool(" not in core
    assert "private fun permissionGate(" not in core


def test_native_mcp_dispatcher_splits_mcp_tools_from_execute_tool() -> None:
    mcp_dispatcher = read(KOTLIN_SRC / "NativeMcpToolDispatcher.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeMcpToolDispatcher(" in mcp_dispatcher
    assert "val TOOL_NAMES = setOf(" in mcp_dispatcher
    assert '"mcp_tools" -> tools(' in mcp_dispatcher
    assert '"mcp_call" -> call(' in mcp_dispatcher

    execute_source = execute_tool_source(core)
    assert "val mcpToolDispatcher = NativeMcpToolDispatcher" in factory
    assert "private val mcpToolDispatcher = NativeMcpToolDispatcher" not in core
    assert "in NativeMcpToolDispatcher.TOOL_NAMES -> mcpExecute(" in execute_source
    assert '"mcp_call" -> mcpCall(' not in execute_source
    assert '"mcp_tools" -> mcpTools(' not in execute_source


def test_native_terminal_dispatcher_splits_termux_backend_tools_from_execute_tool() -> None:
    terminal_dispatcher = read(KOTLIN_SRC / "NativeTerminalToolDispatcher.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeTerminalToolDispatcher(" in terminal_dispatcher
    assert "val TOOL_NAMES = setOf(" in terminal_dispatcher
    assert '"terminal_run" -> run(' in terminal_dispatcher
    assert '"recover_terminal_backend" -> recover(arguments)' in terminal_dispatcher
    assert '"diagnose_terminal" -> diagnose()' in terminal_dispatcher

    execute_source = execute_tool_source(core)
    assert "val terminalToolDispatcher = NativeTerminalToolDispatcher" in factory
    assert "private val terminalToolDispatcher = NativeTerminalToolDispatcher" not in core
    assert "in NativeTerminalToolDispatcher.TOOL_NAMES -> terminalExecute(" in execute_source
    assert '"terminal_run" -> terminalRun(' not in execute_source
    assert '"recover_terminal_backend" -> recoverTerminalBackend(arguments)' not in execute_source


def test_native_terminal_client_owns_termux_http_backend() -> None:
    terminal_client = read(KOTLIN_SRC / "NativeTerminalClient.kt")
    remote_runtime = read(KOTLIN_SRC / "NativeRemoteRuntimeFacade.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")
    manifest = read(ANDROID_SRC / "AndroidManifest.xml")

    assert "class NativeTerminalClient(" in terminal_client
    assert "fun status(): JSONObject" in terminal_client
    assert "fun diagnose(): JSONObject" in terminal_client
    assert "fun recover(arguments: JSONObject): JSONObject" in terminal_client
    assert "fun tools(): JSONObject" in terminal_client
    assert "fun chat(message: String): JSONObject" in terminal_client
    assert "fun run(command: String, cwd: String, timeout: Int): JSONObject" in terminal_client
    assert "fun script(arguments: JSONObject): JSONObject" in terminal_client
    assert "fun taskStatus(taskId: String, maxOutputChars: Int): JSONObject" in terminal_client
    assert "fun taskCancel(taskId: String): JSONObject" in terminal_client
    assert "private fun startTermuxHttpWithRunCommand(forceRestart: Boolean)" in terminal_client
    assert "sh scripts/start-http-termux.sh" in terminal_client
    assert "pkill -f 'mobile_agent.hosts.http_server'" not in terminal_client
    assert '"com.termux.permission.RUN_COMMAND"' in terminal_client
    assert "run_command_permission" in terminal_client
    assert '<package android:name="com.termux" />' in manifest
    assert "ensureTermuxRunCommandPermission()" in main
    assert "requestPermissions(arrayOf(permission), TERMUX_RUN_COMMAND_PERMISSION_REQUEST)" in main
    recovery_catalog = read(KOTLIN_SRC / "NativeRecoveryToolCatalog.kt")
    assert '"force_restart"' in recovery_catalog
    recover_start = recovery_catalog.index('name = "recover_terminal_backend"')
    recover_end = recovery_catalog.index('name = "pc_bridge_recover"', recover_start)
    assert "autoRecover = false" in recovery_catalog[recover_start:recover_end]
    assert "private fun terminalUrl(path: String): String" in terminal_client
    assert "private fun getJson(url: String, timeoutMs: Int): JSONObject" in terminal_client
    assert "private fun postJsonNoAuth(url: String, payload: JSONObject, timeoutMs: Int): JSONObject" in terminal_client

    assert "private val terminalClient = NativeTerminalClient" in core
    assert "private val remoteRuntime = NativeRemoteRuntimeFacade(" in core
    assert "fun terminalStatus(): JSONObject = terminalClient.status()" in remote_runtime
    assert "fun diagnoseTerminal(): JSONObject = terminalClient.diagnose()" in remote_runtime
    assert "fun recoverTerminalBackend(arguments: JSONObject): JSONObject = terminalClient.recover(arguments)" in remote_runtime
    assert "private fun terminalStatus(" not in core
    assert "private fun diagnoseTerminal(" not in core
    assert "private fun recoverTerminalBackend(" not in core
    assert "fun terminalTools(): JSONObject = terminalClient.tools()" in remote_runtime
    assert "fun terminalRun(command: String, cwd: String, timeout: Int): JSONObject" in remote_runtime
    assert "private fun terminalTools(" not in core
    assert "private fun terminalRun(" not in core
    assert "private fun startTermuxHttpWithRunCommand()" not in core
    assert "private fun terminalUrl(path: String): String" not in core
    assert "private fun getJson(url: String, timeoutMs: Int): JSONObject" not in core
    assert "private fun postJsonNoAuth(url: String, payload: JSONObject, timeoutMs: Int): JSONObject" not in core
    assert "RunCommandService" not in core


def test_android_kotlin_sources_do_not_contain_mojibake_markers() -> None:
    markers = ("\ufffd", "锟斤拷")
    offenders: list[str] = []
    for path in KOTLIN_SRC.glob("*.kt"):
        text = read(path)
        for marker in markers:
            if marker in text:
                offenders.append(f"{path.name}:{marker}")
    assert offenders == []


def test_android_host_e2e_script_exercises_bridge_self_health_and_snapshot() -> None:
    script = read(ROOT / "scripts" / "android-host-e2e.ps1")

    assert "adb forward \"tcp:$BridgePort\" \"tcp:$BridgePort\"" in script
    assert "$base/status" in script
    assert 'tool = "self_health_check"' in script
    assert 'tool = "accessibility_snapshot_v2"' in script
    assert 'snapshot.result.version -ne "accessibility_snapshot_v2"' in script
    assert "snapshot.result.node_count -le 0" in script


def test_main_status_formatter_is_split_from_activity() -> None:
    formatter = read(KOTLIN_SRC / "MainStatusFormatter.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")
    config_controller = read(KOTLIN_SRC / "MainConfigDialogController.kt")

    assert "object MainStatusFormatter" in formatter
    assert "fun summarizeStatus(status: JSONObject?)" in formatter
    assert "fun terminalHeaderLabel(status: JSONObject?)" in formatter
    assert "fun mcpHeaderLabel(status: JSONObject?)" in formatter
    assert "fun configSummary(status: JSONObject)" in formatter
    assert "fun formatTokenK(value: Long)" in formatter
    assert "fun permissionLabel(mode: String)" in formatter

    assert "MainStatusFormatter.summarizeStatus(" not in main
    assert "MainStatusFormatter.configSummary(" in config_controller
    assert "MainStatusFormatter.configSummary(" not in main
    assert "MainStatusFormatter.summarizeStatus(" in read(KOTLIN_SRC / "MainStatusController.kt")
    assert "private fun summarizeStatus(" not in main
    assert "private fun terminalRuntimeLabel(" not in main
    assert "private fun cacheSummary(" not in main
    assert "private fun permissionLabel(" not in main


def test_main_status_controller_is_split_from_activity() -> None:
    status_controller = read(KOTLIN_SRC / "MainStatusController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainStatusController(" in status_controller
    assert "private val statusRefreshRunnable" in status_controller
    assert "fun start()" in status_controller
    assert "fun stop()" in status_controller
    assert "fun refresh()" in status_controller

    assert "private lateinit var statusController: MainStatusController" in main
    assert "statusController = MainStatusController(" in main
    assert "private var statusRefreshActive" not in main
    assert "private val statusRefreshRunnable" not in main
    assert "fun startStatusRefresh()" in main


def test_main_layout_builder_is_split_from_activity() -> None:
    layout = read(KOTLIN_SRC / "MainLayoutBuilder.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "data class MainLayoutViews(" in layout
    assert "object MainLayoutBuilder" in layout
    assert "fun build(" in layout
    assert "fun keepComposerAboveKeyboard(root: LinearLayout, composer: LinearLayout)" in layout
    assert "private fun statusBarHeight(activity: Activity): Int" in layout
    assert 'title.text = "手机 Agent"' in layout
    assert 'input.imeOptions = EditorInfo.IME_ACTION_SEND' in layout

    assert "MainLayoutBuilder.build(" in main
    assert "messageRenderer = MainMessageRenderer(" in main
    assert "MainLayoutBuilder.keepComposerAboveKeyboard(" in main
    assert 'title.text = "手机 Agent"' not in main
    assert "EditorInfo.IME_ACTION_SEND" not in main
    assert "private fun statusBarHeight(" not in main
    assert "private fun keepComposerAboveKeyboard(" not in main


def test_main_message_renderer_is_split_from_activity() -> None:
    renderer = read(KOTLIN_SRC / "MainMessageRenderer.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainMessageRenderer(" in renderer
    assert "fun render(role: String, text: String, detail: String? = null)" in renderer
    assert 'val visibleText = "$role\\n$text"' in renderer
    assert "bubble.text = text" in renderer
    assert "showMessageActions(role, visibleText, detail)" in renderer
    assert 'detailsDialogController.showScrollable("$role 详情", detail)' in renderer
    assert "bubble.setOnLongClickListener" in renderer
    assert 'val labels = mutableListOf("复制消息", "分享消息")' in renderer
    assert 'labels.add("复制详情")' in renderer
    assert 'labels.add("查看详情")' in renderer
    assert "ClipData.newPlainText(label, text)" in renderer
    assert "Intent(Intent.ACTION_SEND)" in renderer
    assert 'Intent.createChooser(intent, "分享消息")' in renderer
    assert 'Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()' in renderer
    assert '"我" -> Color.rgb(219, 234, 254)' in renderer
    assert '"助手" -> Color.WHITE' in renderer
    assert '"工具" -> Color.rgb(240, 253, 244)' in renderer
    assert '"错误" -> Color.rgb(254, 242, 242)' in renderer
    assert 'scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }' in renderer

    assert "private lateinit var messageRenderer: MainMessageRenderer" in main
    assert "messageRenderer.render(role, text, detail)" in main
    assert 'bubble.text = "$role\\n$text"' not in main
    assert "params.setMargins(0, 0, 0, 14)" not in main


def test_memory_panel_formatter_is_split_from_activity() -> None:
    formatter = read(KOTLIN_SRC / "MemoryPanelFormatter.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")
    memory_controller = read(KOTLIN_SRC / "MainMemoryDialogController.kt")

    assert "object MemoryPanelFormatter" in formatter
    assert "fun formatMemorySummary(result: JSONObject)" in formatter
    assert "fun formatExperienceMatches(result: JSONObject)" in formatter
    assert "fun formatProcedureList(result: JSONObject)" in formatter

    assert "MemoryPanelFormatter.formatMemorySummary(" in memory_controller
    assert "MemoryPanelFormatter.formatExperienceMatches(" in memory_controller
    assert "MemoryPanelFormatter.formatProcedureList(" in memory_controller
    assert "MemoryPanelFormatter.formatMemorySummary(" not in main
    assert "MemoryPanelFormatter.formatExperienceMatches(" not in main
    assert "MemoryPanelFormatter.formatProcedureList(" not in main
    assert "private fun formatMemorySummary(" not in main
    assert "private fun formatExperienceMatches(" not in main
    assert "private fun formatProcedureList(" not in main


def test_mobile_memory_text_helpers_are_split_from_store() -> None:
    text = read(KOTLIN_SRC / "MobileMemoryText.kt")
    store = read(KOTLIN_SRC / "MobileMemoryStore.kt")

    assert "object MobileMemoryText" in text
    assert "fun readableMemoryItem(item: JSONObject): String" in text
    assert "fun readableExperience(item: JSONObject): String" in text
    assert "fun formatMemory(items: JSONArray): String" in text
    assert "fun formatExperience(items: JSONArray): String" in text
    assert "fun formatProcedures(items: JSONArray): String" in text
    assert "fun buildProcedureMarkdown(app: String, scope: String, lessons: List<JSONObject>, updatedAt: String): String" in text
    assert "fun procedurePreview(content: String): String" in text
    assert "fun tokenSet(text: String): Set<String>" in text
    assert "fun scoreText(rawQuery: String, queryTokens: Set<String>, text: String): Int" in text
    assert "fun sanitizeSecret(value: String): String" in text
    assert "fun slug(value: String): String" in text

    assert "MobileMemoryText.readableExperience(" in store
    assert "MobileMemoryText.scoreText(" in store
    assert "MobileMemoryText.buildProcedureMarkdown(" not in store
    assert "MobileMemoryText.slug(" not in store
    assert "private fun readableMemoryItem(" not in store
    assert "private fun readableExperience(" not in store
    assert "private fun formatMemory(" not in store
    assert "private fun formatExperience(" not in store
    assert "private fun formatProcedures(" not in store
    assert "private fun buildProcedureMarkdown(" not in store
    assert "private fun procedurePreview(" not in store
    assert "private fun tokenSet(" not in store
    assert "private fun scoreText(" not in store
    assert "private fun sanitizeSecret(" not in store
    assert "private fun slug(" not in store


def test_mobile_memory_profile_store_owns_profile_and_task_history() -> None:
    profile_store = read(KOTLIN_SRC / "MobileMemoryProfileStore.kt")
    store = read(KOTLIN_SRC / "MobileMemoryStore.kt")

    assert "class MobileMemoryProfileStore(" in profile_store
    assert "fun readProfile(): JSONObject" in profile_store
    assert "fun query(question: String, limit: Int = 5): JSONObject" in profile_store
    assert "fun searchMemory(query: String, limit: Int = 8): JSONObject" in profile_store
    assert "fun writeMemory(arguments: JSONObject): JSONObject" in profile_store
    assert "fun recordTask(task: String, status: String, finalAnswer: String, toolsUsed: JSONArray, appsUsed: JSONArray, runId: String): JSONObject" in profile_store
    assert "private fun findDuplicate(array: JSONArray, text: String, key: String): Int" in profile_store
    assert "private fun trimArray(container: JSONObject, key: String, max: Int)" in profile_store

    assert "private val profileStore = MobileMemoryProfileStore(" in store
    assert "return profileStore.query(question, limit)" in store
    assert "return profileStore.searchMemory(query, limit)" in store
    assert "return profileStore.writeMemory(arguments)" in store
    assert "return profileStore.recordTask(task, status, finalAnswer, toolsUsed, appsUsed, runId)" in store
    assert "private fun readProfile(): JSONObject = profileStore.readProfile()" in store
    assert "private fun findDuplicate(array: JSONArray, text: String, key: String): Int" not in store
    assert "private fun trimArray(container: JSONObject, key: String, max: Int)" not in store


def test_mobile_memory_experience_store_owns_lesson_storage() -> None:
    experience_store = read(KOTLIN_SRC / "MobileMemoryExperienceStore.kt")
    store = read(KOTLIN_SRC / "MobileMemoryStore.kt")

    assert "class MobileMemoryExperienceStore(" in experience_store
    assert "fun readExperience(): JSONObject" in experience_store
    assert "fun search(arguments: JSONObject): JSONObject" in experience_store
    assert "fun record(arguments: JSONObject): JSONObject" in experience_store
    assert "fun update(arguments: JSONObject): JSONObject" in experience_store
    assert "fun delete(arguments: JSONObject): JSONObject" in experience_store
    assert "fun compact(arguments: JSONObject): JSONObject" in experience_store
    assert "fun heuristicExperienceFromTool(tool: String, state: String, summary: String, sourceTask: String): JSONObject?" in experience_store
    assert "private fun findDuplicateExperience(" in experience_store
    assert "private fun countScopeLessons(" in experience_store
    assert "generateProcedure(JSONObject().put(\"app\", app).put(\"tool_scope\", scope))" in experience_store

    assert "private val experienceStore = MobileMemoryExperienceStore(" in store
    assert "return experienceStore.search(arguments)" in store
    assert "return experienceStore.record(arguments)" in store
    assert "return experienceStore.update(arguments)" in store
    assert "return experienceStore.delete(arguments)" in store
    assert "return experienceStore.compact(arguments)" in store
    assert "return experienceStore.heuristicExperienceFromTool(tool, state, summary, sourceTask)" in store
    assert "private fun readExperience(): JSONObject = experienceStore.readExperience()" in store
    assert "private fun findDuplicateExperience(" not in store
    assert "private fun countScopeLessons(" not in store


def test_mobile_memory_procedure_store_owns_procedure_files() -> None:
    procedure_store = read(KOTLIN_SRC / "MobileMemoryProcedureStore.kt")
    store = read(KOTLIN_SRC / "MobileMemoryStore.kt")

    assert "class MobileMemoryProcedureStore(" in procedure_store
    assert "fun list(arguments: JSONObject): JSONObject" in procedure_store
    assert "fun read(arguments: JSONObject): JSONObject" in procedure_store
    assert "fun search(arguments: JSONObject): JSONObject" in procedure_store
    assert "fun generate(arguments: JSONObject, lessons: JSONArray, updatedAt: String): JSONObject" in procedure_store
    assert "private fun resolveFile(arguments: JSONObject): File" in procedure_store
    assert "private fun meta(file: File): JSONObject" in procedure_store
    assert "MobileMemoryText.buildProcedureMarkdown(app, scope, selected, updatedAt)" in procedure_store

    assert "private val procedures = MobileMemoryProcedureStore(" in store
    assert "return procedures.list(arguments)" in store
    assert "return procedures.read(arguments)" in store
    assert "return procedures.search(arguments)" in store
    assert "return procedures.generate(arguments, lessons, nowIso())" in store
    assert "private fun resolveProcedureFile(" not in store
    assert "private fun procedureMeta(" not in store


def test_mobile_memory_learning_store_owns_learning_session_files() -> None:
    learning_store = read(KOTLIN_SRC / "MobileMemoryLearningStore.kt")
    store = read(KOTLIN_SRC / "MobileMemoryStore.kt")

    assert "class MobileMemoryLearningStore(" in learning_store
    assert "fun start(arguments: JSONObject): JSONObject" in learning_store
    assert "fun record(arguments: JSONObject): JSONObject" in learning_store
    assert "fun status(): JSONObject" in learning_store
    assert "fun activeSession(): JSONObject?" in learning_store
    assert "fun sessionDir(session: JSONObject): File" in learning_store
    assert "fun traceFile(session: JSONObject): File" in learning_store
    assert "fun clearActive()" in learning_store
    assert "fun appendEvent(session: JSONObject, eventType: String, details: JSONObject): JSONObject" in learning_store

    assert "private val learning = MobileMemoryLearningStore(" in store
    assert "return learning.start(arguments)" in store
    assert "return learning.record(arguments)" in store
    assert "return learning.status()" in store
    assert "learning.appendEvent(session, \"stop\"" in store
    assert "learning.clearActive()" in store
    assert "private fun appendLearningEvent(" not in store


def test_diagnostic_trace_formatter_is_split_from_activity() -> None:
    formatter = read(KOTLIN_SRC / "DiagnosticTraceFormatter.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "object DiagnosticTraceFormatter" in formatter
    assert "fun toolTraceItem(tool: String, args: JSONObject, output: JSONObject, step: Int)" in formatter
    assert "fun diagnosticLoop(trace: JSONArray)" in formatter
    assert "fun uiToolState(output: JSONObject)" in formatter
    assert "fun toolStepBrief(output: JSONObject)" in formatter
    assert "fun reconnectSummary(result: JSONObject)" in formatter

    assert "DiagnosticTraceFormatter.toolTraceItem(" not in main
    assert "DiagnosticTraceFormatter.diagnosticLoop(" not in main
    assert "DiagnosticTraceFormatter.uiToolState(" not in main
    assert "DiagnosticTraceFormatter.toolStepBrief(" not in main
    assert "private fun toolTraceItem(" not in main
    assert "private fun diagnosticLoop(" not in main
    assert "private fun uiToolState(" not in main
    assert "private fun reconnectSummary(" not in main


def test_main_local_command_parser_is_split_from_activity() -> None:
    parser = read(KOTLIN_SRC / "MainLocalCommandParser.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")
    settings_controller = read(KOTLIN_SRC / "MainSettingsCommandController.kt")

    assert "object MainLocalCommandParser" in parser
    assert "fun normalize(text: String)" in parser
    assert "fun normalizePermissionMode(mode: String)" in parser
    assert "fun isHighPowerMode(mode: String)" in parser
    assert "fun looksLikeApiKey(text: String)" in parser
    assert '"termux "' in parser
    assert '"失败分析"' in parser

    assert "MainLocalCommandParser.normalize(text)" in main
    assert "MainLocalCommandParser.looksLikeApiKey(" in read(KOTLIN_SRC / "MainConversationController.kt")
    assert "MainLocalCommandParser.normalizePermissionMode(mode)" in settings_controller
    assert "MainLocalCommandParser.isHighPowerMode(" in settings_controller
    assert "private fun normalizeLocalCommand(" not in main
    assert "private fun isHighPowerMode(" not in main
    assert "private fun looksLikeApiKey(" not in main


def test_main_local_command_runner_is_split_from_activity() -> None:
    runner = read(KOTLIN_SRC / "MainLocalCommandRunner.kt")
    catalog = read(KOTLIN_SRC / "MainCommandCatalog.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainLocalCommandRunner" in runner
    assert "interface Actions" in runner
    assert "fun run(command: String)" in runner
    assert "private fun runParameterized(command: String)" in runner
    assert "val HELP_TEXT: String = MainCommandCatalog.helpText()" in runner
    assert "object MainCommandCatalog" in catalog
    assert "fun helpText()" in catalog
    assert "fun docsText()" in catalog
    assert 'MainCommandInfo("-commands", "命令大全"' in catalog
    assert 'MainCommandInfo("-mcp tools", "MCP 工具"' in catalog
    assert 'MainCommandInfo("-ssh connect", "连接 SSH"' in catalog
    assert '"terminal:"' in runner
    assert '"mcp:"' in runner
    assert '"ssh:"' in runner

    assert "private lateinit var localCommandRunner: MainLocalCommandRunner" in main
    assert "private fun createLocalCommandRunner()" in main
    assert "localCommandRunner.run(command)" in main
    run_start = main.index("private fun runLocalCommand(command: String)")
    run_end = main.index("private fun setMaxToolRoundsFromCommand", run_start)
    run_source = main[run_start:run_end]
    assert "when (command)" not in run_source
    assert "MainLocalCommandRunner.HELP_TEXT" not in main


def test_main_panel_summary_formatter_is_split_from_activity() -> None:
    formatter = read(KOTLIN_SRC / "MainPanelSummaryFormatter.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "object MainPanelSummaryFormatter" in formatter
    assert "fun localToolsSummary(status: JSONObject)" in formatter
    assert "fun officialDocsSummary(docsIndex: JSONObject)" in formatter
    assert "MainStatusFormatter.configSummary(status)" in formatter

    assert "MainPanelSummaryFormatter.localToolsSummary(" in main
    assert "MainPanelSummaryFormatter.officialDocsSummary(" in main
    assert "private fun localToolsSummary(" not in main
    assert "private fun officialDocsSummary(" not in main


def test_main_config_dialog_builder_is_split_from_activity() -> None:
    builder = read(KOTLIN_SRC / "MainConfigDialogBuilder.kt")
    controller = read(KOTLIN_SRC / "MainConfigDialogController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "data class MainConfigDialogForm(" in builder
    assert "object MainConfigDialogBuilder" in builder
    assert "fun build(" in builder
    assert "val modeById: Map<Int, String>" in builder
    assert "val terminalEnabled: CheckBox" in builder
    assert "val mcpToken: EditText" in builder
    assert "val sshPassphrase: EditText" in builder
    assert "openStorageSettings: () -> Unit" in builder
    assert 'addSectionTitle(context, layout, "权限模式")' in builder
    assert 'addSectionTitle(context, layout, "终端接口", topPadding = 18)' in builder
    assert 'addSectionTitle(context, layout, "SSH 连接", topPadding = 18)' in builder
    assert 'storageButton.text = "打开文件访问授权"' in builder
    assert "锟斤拷" not in builder
    assert "\ufffd" not in builder

    assert "class MainConfigDialogController(" in controller
    assert "fun show()" in controller
    assert "fun confirmHighPowerMode(mode: String, onConfirmed: () -> Unit)" in controller
    assert "MainConfigDialogBuilder.build(" in controller
    assert ".setView(form.layout)" in controller
    assert "form.modeById[form.modeGroup.checkedRadioButtonId]" in controller
    assert "core.setTerminalConfig(" in controller
    assert "core.setMcpConfig(" in controller
    assert "core.setSshConfig(" in controller
    assert "private fun configSummary()" in controller

    assert "private lateinit var configDialogController: MainConfigDialogController" in main
    assert "private fun createConfigDialogController()" in main
    assert "configDialogController.show()" in main
    assert "configDialogController.confirmHighPowerMode(mode, onConfirmed)" in main
    assert "private fun showConfigDialog()" in main
    assert "MainConfigDialogBuilder.build(" not in main
    assert "core.setTerminalConfig(form.terminalEnabled.isChecked" not in main
    assert "private fun confirmHighPowerMode(" not in main
    assert "private fun configSummary()" not in main
    assert "val modeGroup = RadioGroup(this)" not in main
    assert "val terminalEnabled = CheckBox(this)" not in main
    assert "val storageButton = Button(this)" not in main


def test_main_memory_dialog_controller_is_split_from_activity() -> None:
    controller = read(KOTLIN_SRC / "MainMemoryDialogController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainMemoryDialogController(" in controller
    assert "fun showMemoryExperiencePanel()" in controller
    assert "fun showLearningStartDialog()" in controller
    assert "fun stopLearningMode()" in controller
    assert "private fun showExperienceConfidenceDialog()" in controller
    assert "private fun showExperienceCompactDialog()" in controller
    assert "private fun showProcedureGenerateDialog()" in controller
    assert "MemoryPanelFormatter.formatMemorySummary(" in controller
    assert 'JSONObject().put("path", "docs/official/skills.md")' in controller

    assert "private lateinit var memoryDialogController: MainMemoryDialogController" in main
    assert "private fun createMemoryDialogController()" in main
    assert "memoryDialogController.showMemoryExperiencePanel()" in main
    assert "memoryDialogController.showLearningStartDialog()" in main
    assert "memoryDialogController.stopLearningMode()" in main
    assert "private fun showExperienceConfidenceDialog()" not in main
    assert "private fun showExperienceCompactDialog()" not in main
    assert "private fun showProcedureGenerateDialog()" not in main
    assert "private fun showSingleInputDialog(" not in main


def test_main_tool_dialog_runner_is_split_from_activity() -> None:
    runner = read(KOTLIN_SRC / "MainToolDialogRunner.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainToolDialogRunner(" in runner
    assert "fun run(" in runner
    assert "core.executeNativeToolForDiagnostics(tool, args, true)" in runner
    assert 'addMessage("工具", "$title\\n${text.take(1200)}", it.toString(2))' in runner
    assert "showScrollable(title, text)" in runner

    assert "private lateinit var toolDialogRunner: MainToolDialogRunner" in main
    assert "private fun createToolDialogRunner()" in main
    assert 'toolDialogRunner.run(tool, args, title, formatter, "mobile-agent-memory-ui")' in main
    assert 'toolDialogRunner.run(tool, args, title, formatter, "mobile-agent-docs-ui")' in main
    assert "private fun runMemoryToolForDialog(" not in main
    assert "private fun runDocsToolForDialog(" not in main
    assert "nativeCore.executeNativeToolForDiagnostics(tool, args, true)" not in main


def test_main_runtime_dialog_controller_is_split_from_activity() -> None:
    controller = read(KOTLIN_SRC / "MainRuntimeDialogController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainRuntimeDialogController(" in controller
    assert "fun showMcpStatus()" in controller
    assert "fun showMcpTools(search: String = \"\")" in controller
    assert "fun showSshStatus()" in controller
    assert "fun showSshConnect()" in controller
    assert "fun showSshDiagnose()" in controller
    assert "fun showSshSelectHost(candidates: String = \"\")" in controller
    assert "fun showTerminalHealth(autoRecover: Boolean)" in controller
    assert "private fun runUiTask(" in controller
    assert "core.mcpStatusForUi()" in controller
    assert "core.sshSelectHostForUi(args)" in controller
    assert "core.terminalHealthForUi(autoRecover)" in controller

    assert "private lateinit var runtimeDialogController: MainRuntimeDialogController" in main
    assert "private fun createRuntimeDialogController()" in main
    assert "runtimeDialogController.showMcpStatus()" in main
    assert "runtimeDialogController.showSshSelectHost(candidates)" in main
    assert "runtimeDialogController.showTerminalHealth(autoRecover)" in main
    assert "nativeCore.mcpStatusForUi()" not in main
    assert "nativeCore.sshDiagnoseForUi()" not in main
    assert "nativeCore.terminalHealthForUi(autoRecover)" not in main


def test_runtime_panels_use_human_readable_summaries() -> None:
    controller = read(KOTLIN_SRC / "MainRuntimeDialogController.kt")
    formatter = read(KOTLIN_SRC / "MainRuntimeSummaryFormatter.kt")
    panel = read(KOTLIN_SRC / "MainPanelSummaryFormatter.kt")

    assert "object MainRuntimeSummaryFormatter" in formatter
    for method in (
        "mcpStatus",
        "mcpTools",
        "sshStatus",
        "sshConnect",
        "sshDiagnose",
        "sshSelectHost",
        "terminalStatus",
        "terminalHealth",
        "systemLogs",
    ):
        assert f"fun {method}(result: JSONObject)" in formatter
        assert f"MainRuntimeSummaryFormatter.{method}(" in controller

    assert 'addMessage("系统", "MCP 状态：\\n${it.toString(2)}", null)' not in controller
    assert 'addMessage("系统", "SSH 状态：\\n${it.toString(2)}", null)' not in controller
    assert 'addMessage("系统", "终端接口状态：\\n${it.toString(2)}", null)' not in controller
    assert "这里不写死工具清单" in formatter
    assert "未识别的新工具会放到“其他能力”" in formatter
    assert "按用途分组" in panel
    assert "不写死工具清单" in panel
    assert "Agent 真正调用时会用 tool_info 查看参数" in panel


def test_main_failure_controller_is_split_from_activity() -> None:
    controller = read(KOTLIN_SRC / "MainFailureController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainFailureController(" in controller
    assert "fun showLatestFailureAnalysis()" in controller
    assert "fun retryLastFailedStep()" in controller
    assert "fun continueFailedTask()" in controller
    assert "fun cancelRunningTerminalTasks()" in controller
    assert "private lateinit var failureController: MainFailureController" in main
    assert "private fun createFailureController()" in main
    assert "failureController.showLatestFailureAnalysis()" in main
    assert "private fun retryLastFailedStep()" not in main
    assert "private fun continueFailedTask()" not in main
    assert "private fun cancelRunningTerminalTasks()" not in main


def test_main_task_controller_is_split_from_activity() -> None:
    task_controller = read(KOTLIN_SRC / "MainTaskController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainTaskController(" in task_controller
    assert "fun sendUserMessage(text: String)" in task_controller
    assert "fun requestStopCurrentTask()" in task_controller
    assert "fun clearForNewSession()" in task_controller
    assert "fun refreshLiveEvents(seconds: Long)" in task_controller
    assert "fun sendConfirmedMessage(text: String, actionsApproved: Boolean)" in task_controller
    assert "private lateinit var taskController: MainTaskController" in main
    assert "private fun createTaskController()" in main
    assert "taskController.sendUserMessage(text)" in main
    assert "private fun sendMessage()" not in main
    assert "private fun sendConfirmedMessage(" not in main
    assert "private fun queueMessage(" not in main
    assert "private fun requestStopCurrentTask()" not in main
    assert "private fun findConfirmationNeeded(" not in main
    assert "private fun showToolConfirmationDialog(" not in main
    assert "private fun needsActionConfirmation(" not in main


def test_main_conversation_controller_is_split_from_activity() -> None:
    conversation_controller = read(KOTLIN_SRC / "MainConversationController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainConversationController(" in conversation_controller
    assert "data class SavedMessage" in conversation_controller
    assert "fun loadState(): List<SavedMessage>" in conversation_controller
    assert "fun addMessage(role" in conversation_controller
    assert "fun clear()" in conversation_controller
    assert "fun saveState()" in conversation_controller

    assert "private lateinit var conversationController: MainConversationController" in main
    assert "private fun loadState()" not in main
    assert "private fun localizeRole(" not in main
    assert "private fun localizeSavedText(" not in main
    assert "private data class SavedMessage" not in main
    assert '"system" -> "系统"' in conversation_controller
    assert '"you" -> "我"' in conversation_controller
    assert '"agent" -> "助手"' in conversation_controller
    assert '"tools" -> "工具"' in conversation_controller
    assert '"error" -> "错误"' in conversation_controller
    assert '"[API Key 已隐藏]"' in conversation_controller


def test_main_details_dialog_controller_is_split_from_activity() -> None:
    controller = read(KOTLIN_SRC / "MainDetailsDialogController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainDetailsDialogController(" in controller
    assert "fun show(" in controller
    assert "fun showScrollable(" in controller
    assert '.setPositiveButton("确定", null)' in controller
    assert "private lateinit var detailsDialogController: MainDetailsDialogController" in main
    assert "private fun showDetails(" not in main
    assert "private fun showDetailsScrollable(" not in main


def test_main_action_panel_controller_is_split_from_activity() -> None:
    controller = read(KOTLIN_SRC / "MainActionPanelController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainActionPanelController(" in controller
    assert "interface Actions" in controller
    assert "fun show()" in controller
    assert 'addPanelButton("重连 / 自检")' in controller
    assert 'addPanelButton("MCP 工具")' in controller
    assert 'addPanelButton("记忆/经验")' in controller
    assert 'addPanelButton("命令大全")' in controller
    assert 'addPanelButton("继续处理失败")' in controller

    assert "private lateinit var actionPanelController: MainActionPanelController" in main
    assert "private lateinit var commandCatalogDialogController: MainCommandCatalogDialogController" in main
    assert "private fun createCommandCatalogDialogController()" in main
    assert "commandCatalogDialogController.show()" in main
    assert "private fun fillInputWithCommand(command: String)" in main
    assert "private fun createActionPanelController()" in main
    assert "actionPanelController.show()" in main
    assert "fun addPanelButton(" not in main
    assert 'addPanelButton("MCP 工具")' in controller


def test_official_commands_doc_uses_command_catalog() -> None:
    docs = read(KOTLIN_SRC / "MobileAgentDocs.kt")
    parser = read(KOTLIN_SRC / "MainLocalCommandParser.kt")
    dialog = read(KOTLIN_SRC / "MainCommandCatalogDialogController.kt")

    assert "return MainCommandCatalog.docsText()" in docs
    assert '"commands", "-commands", "--commands", "/commands"' in parser
    assert "class MainCommandCatalogDialogController(" in dialog
    assert "copyCommand(item.command)" in dialog
    assert "fillInput(item.command)" in dialog
    assert 'copyButton.text = "复制"' in dialog
    assert 'fillButton.text = "填入"' in dialog


def test_main_settings_command_controller_is_split_from_activity() -> None:
    controller = read(KOTLIN_SRC / "MainSettingsCommandController.kt")
    main = read(KOTLIN_SRC / "MainActivity.kt")

    assert "class MainSettingsCommandController(" in controller
    assert "fun setMaxToolRoundsFromCommand(value: String)" in controller
    assert "fun setPermissionModeFromCommand(mode: String)" in controller
    assert "fun setTerminalFromCommand(value: String)" in controller
    assert "fun setMcpFromCommand(value: String)" in controller
    assert "fun setSshFromCommand(value: String)" in controller
    assert "private fun applyPermissionMode(mode: String)" in controller
    assert "MainLocalCommandParser.normalizePermissionMode(mode)" in controller
    assert "core.setMcpConfig(" in controller
    assert "core.setSshConfig(enabled, host, port, user, keyPath, passphrase)" in controller

    assert "private lateinit var settingsCommandController: MainSettingsCommandController" in main
    assert "private fun createSettingsCommandController()" in main
    assert "settingsCommandController.setTerminalFromCommand(value)" in main
    assert "settingsCommandController.setMcpFromCommand(value)" in main
    assert "settingsCommandController.setSshFromCommand(value)" in main
    assert "private fun applyPermissionMode(" not in main
    assert "val tokens = value.trim().split(Regex" not in main
    assert "nativeCore.setSshConfig(enabled, host, port, user, keyPath, passphrase)" not in main


def test_native_planning_workspace_memory_web_and_pc_dispatchers_split_remaining_domains() -> None:
    planning_dispatcher = read(KOTLIN_SRC / "NativePlanningToolDispatcher.kt")
    workspace_dispatcher = read(KOTLIN_SRC / "NativeWorkspaceToolDispatcher.kt")
    memory_dispatcher = read(KOTLIN_SRC / "NativeMemoryToolDispatcher.kt")
    web_dispatcher = read(KOTLIN_SRC / "NativeWebToolDispatcher.kt")
    pc_bridge_dispatcher = read(KOTLIN_SRC / "NativePcBridgeToolDispatcher.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativePlanningToolDispatcher(" in planning_dispatcher
    assert '"task_plan_update" -> updatePlan(taskPlan, arguments)' in planning_dispatcher
    assert '"task_report_read" -> workspace.taskReportRead(' in planning_dispatcher

    assert "class NativeWorkspaceToolDispatcher(" in workspace_dispatcher
    assert '"write_file" -> workspace.write(' in workspace_dispatcher
    assert '"search_files" -> workspace.search(' in workspace_dispatcher

    assert "class NativeMemoryToolDispatcher(" in memory_dispatcher
    assert '"memory_write" -> memory.writeMemory(arguments)' in memory_dispatcher
    assert '"learning_stop" -> learningStop(arguments)' in memory_dispatcher

    assert "class NativeWebToolDispatcher(" in web_dispatcher
    assert '"web_extract", "page_extract" -> extract(arguments, actionsApproved)' in web_dispatcher
    assert '"http_post" -> post(arguments)' in web_dispatcher

    assert "class NativePcBridgeToolDispatcher(" in pc_bridge_dispatcher
    assert '"pc_bridge_health_check" -> healthCheck(arguments)' in pc_bridge_dispatcher
    assert '"pc_bridge_recover" -> recover(arguments)' in pc_bridge_dispatcher
    assert '"tailscale_ssh_diagnose" -> tailscaleSshDiagnose(arguments)' in pc_bridge_dispatcher

    execute_source = execute_tool_source(core)
    assert "in NativePlanningToolDispatcher.TOOL_NAMES -> planningExecute(" in execute_source
    assert "in NativeWorkspaceToolDispatcher.TOOL_NAMES -> workspaceExecute(" in execute_source
    assert "in NativeMemoryToolDispatcher.TOOL_NAMES -> memoryExecute(" in execute_source
    assert "in NativeWebToolDispatcher.TOOL_NAMES -> webExecute(" in execute_source
    assert "in NativePcBridgeToolDispatcher.TOOL_NAMES -> pcBridgeExecute(" in execute_source
    assert '"task_create" -> workspace.taskCreate(' not in execute_source
    assert '"memory_query" -> memory.query(' not in execute_source
    assert '"web_search" -> webSearch(' not in execute_source
    assert "webClient.search(query, maxResults)" in factory
    assert "val planningToolDispatcher = NativePlanningToolDispatcher" in factory
    assert "val workspaceToolDispatcher = NativeWorkspaceToolDispatcher" in factory
    assert "val memoryToolDispatcher = NativeMemoryToolDispatcher" in factory
    assert "val pcBridgeToolDispatcher = NativePcBridgeToolDispatcher" in factory
    assert "private val planningToolDispatcher = NativePlanningToolDispatcher" not in core
    assert "private val workspaceToolDispatcher = NativeWorkspaceToolDispatcher" not in core
    assert "private val memoryToolDispatcher = NativeMemoryToolDispatcher" not in core
    assert "private val pcBridgeToolDispatcher = NativePcBridgeToolDispatcher" not in core
    assert '"workspace_info" -> workspace.info()' not in execute_source
    assert '"pc_bridge_status" -> pcBridgeStatus(arguments)' not in execute_source


def test_native_web_client_owns_search_extract_and_http_helpers() -> None:
    web_client = read(KOTLIN_SRC / "NativeWebClient.kt")
    page_extractor = read(KOTLIN_SRC / "NativeWebPageExtractor.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeWebClient(" in web_client
    assert "fun search(query: String, maxResults: Int): JSONObject" in web_client
    assert "fun extract(arguments: JSONObject, actionsApproved: Boolean): JSONObject" in web_client
    assert "fun get(arguments: JSONObject): JSONObject" in web_client
    assert "fun post(arguments: JSONObject): JSONObject" in web_client
    assert "private fun directPageExtract(" in web_client
    assert "private fun jinaPageExtract(" in web_client
    assert "private fun termuxPageExtract(" in web_client
    assert "private fun httpRequest(" in web_client
    assert "private fun readBytesLimited(" in web_client
    assert "private fun extractDuckDuckGoUrl(" in web_client
    assert "NativeWebPageExtractor.fromHtml(" in web_client
    assert "NativeWebPageExtractor.contentForMode(" in web_client
    assert "NativeWebPageExtractor.cleanHtml(" in web_client

    assert "object NativeWebPageExtractor" in page_extractor
    assert "fun fromHtml(" in page_extractor
    assert "fun contentForMode(" in page_extractor
    assert "fun compactText(value: String, maxBytes: Int): String" in page_extractor
    assert "fun jinaReaderUrl(url: String): String" in page_extractor
    assert "fun cleanHtml(value: String): String" in page_extractor
    assert "fun htmlDecode(value: String): String" in page_extractor
    assert "private fun extractHtmlTitle(" in page_extractor
    assert "private fun extractHtmlLinks(" in page_extractor
    assert "private fun stripHtmlToText(" in page_extractor
    assert "private fun markdownFromPage(" in page_extractor
    assert "private fun pageExtractionFromHtml(" not in web_client
    assert "private fun extractHtmlTitle(" not in web_client
    assert "private fun extractHtmlLinks(" not in web_client
    assert "private fun stripHtmlToText(" not in web_client
    assert "private fun markdownFromPage(" not in web_client
    assert "private fun pageContentForMode(" not in web_client
    assert "private fun compactText(" not in web_client
    assert "private fun buildJinaReaderUrl(" not in web_client
    assert "private fun cleanHtml(" not in web_client
    assert "private fun htmlDecode(" not in web_client

    assert "val webClient = NativeWebClient" in factory
    assert "search = { query, maxResults -> webClient.search(query, maxResults) }" in factory
    assert "extract = { arguments, actionsApproved -> webClient.extract(arguments, actionsApproved) }" in factory
    assert "get = { arguments -> webClient.get(arguments) }" in factory
    assert "post = { arguments -> webClient.post(arguments) }" in factory
    assert "private val webClient = NativeWebClient" not in core
    assert "private fun webSearch(" not in core
    assert "private fun webExtract(" not in core
    assert "private fun directPageExtract(" not in core
    assert "private fun jinaPageExtract(" not in core
    assert "private fun termuxPageExtract(" not in core
    assert "private fun httpRequest(" not in core
    assert "private fun extractDuckDuckGoUrl(" not in core


def test_native_diagnostics_dispatcher_splits_toolset_docs_and_health_from_execute_tool() -> None:
    diagnostics_dispatcher = read(KOTLIN_SRC / "NativeDiagnosticsToolDispatcher.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeDiagnosticsToolDispatcher(" in diagnostics_dispatcher
    assert '"toolset_request" -> toolsetRequest(sessionId, arguments)' in diagnostics_dispatcher
    assert '"tool_registry" -> toolRegistry(arguments, sessionId)' in diagnostics_dispatcher
    assert '"docs_read" -> docsRead(arguments)' in diagnostics_dispatcher
    assert '"self_health_check" -> selfHealthCheck()' in diagnostics_dispatcher

    execute_source = execute_tool_source(core)
    assert "val diagnosticsToolDispatcher = NativeDiagnosticsToolDispatcher" in factory
    assert "private val diagnosticsToolDispatcher = NativeDiagnosticsToolDispatcher" not in core
    assert "in NativeDiagnosticsToolDispatcher.TOOL_NAMES -> diagnosticsExecute(" in execute_source
    assert '"toolset_request" -> applyToolsetRequest(sessionId, arguments)' not in execute_source
    assert '"docs_index" -> docsIndex()' not in execute_source
    assert '"system_logs" -> systemLogs(arguments)' not in execute_source


def test_native_tool_verifier_is_split_from_core_recovery_loop() -> None:
    verifier = read(KOTLIN_SRC / "NativeToolVerifier.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    execution_manager = read(KOTLIN_SRC / "NativeToolExecutionManager.kt")

    assert "class NativeToolVerifier(" in verifier
    assert "fun verify(name: String, arguments: JSONObject, output: JSONObject)" in verifier
    assert '"write_file" -> verifyWorkspaceWrite(arguments, output)' in verifier
    assert '"terminal_script" -> verifyTerminalScript(arguments, output)' in verifier
    assert '"ssh_run" -> verifySshRun(output)' in verifier
    assert "private fun verifyWorkspaceWrite(" in verifier
    assert "private fun verifyTerminalRun(" in verifier

    assert "private val toolVerifier = NativeToolVerifier(workspace)" in core
    assert "fun verify(name: String, arguments: JSONObject, output: JSONObject)" in execution_manager
    assert "return toolVerifier.verify(name, arguments, output)" not in core
    assert "private val toolExecutionManager = NativeToolExecutionManager" in core
    assert "private fun verifyWorkspaceWrite(" not in core
    assert "private fun verifyTerminalRun(" not in core
    assert "private fun verificationFailed(" not in core


def test_native_toolset_controller_owns_progressive_toolset_state() -> None:
    toolsets = read(KOTLIN_SRC / "NativeToolsetController.kt")
    status_controller = read(KOTLIN_SRC / "NativeStatusController.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeToolsetController(" in toolsets
    assert "fun resolve(sessionId: String?): Set<String>" in toolsets
    assert "fun request(sessionId: String?, arguments: JSONObject): JSONObject" in toolsets
    assert "fun registry(arguments: JSONObject, sessionId: String?): JSONObject" in toolsets
    assert "fun info(arguments: JSONObject): JSONObject" in toolsets
    assert "NativeToolRegistry.toolsForGroups(requestedGroups)" in toolsets
    assert "NativeToolRegistry.indexMetadata(activeTools, category, search, includeSchema)" in toolsets

    assert "private val toolsets = NativeToolsetController(prefs)" in core
    assert "toolsets.resolve(activeSessionId)" in status_controller
    assert "toolsets.resolve(sessionId)" in core
    assert "toolsets.request(sessionId, arguments)" in factory
    assert "toolsets.registry(arguments, sessionId)" in factory
    assert "toolsets.info(arguments)" in factory
    assert "private fun applyToolsetRequest(" not in core
    assert "private fun resolveToolsetForSession(" not in core
    assert "private fun persistToolsetForSession(" not in core
    assert "private fun toolSchemas(" not in core
    assert "private fun toolSchema(" not in core
    assert "private fun toolNames(" not in core


def test_native_terminal_recovery_controller_owns_fuse_and_runtime_monitoring() -> None:
    recovery = read(KOTLIN_SRC / "NativeTerminalRecoveryController.kt")
    tool_recovery = read(KOTLIN_SRC / "NativeToolRecoveryController.kt")
    verification_recovery = read(KOTLIN_SRC / "NativeToolVerificationRecoveryController.kt")
    mcp_recovery = read(KOTLIN_SRC / "NativeToolMcpRecoveryController.kt")
    terminal_auto_recovery = read(KOTLIN_SRC / "NativeToolTerminalAutoRecoveryController.kt")
    remote_runtime = read(KOTLIN_SRC / "NativeRemoteRuntimeFacade.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    execution_manager = read(KOTLIN_SRC / "NativeToolExecutionManager.kt")

    assert "class NativeTerminalRecoveryController(" in recovery
    assert "fun fuseOpen(name: String): JSONObject?" in recovery
    assert "fun recordOutcome(name: String, recovered: Boolean)" in recovery
    assert "fun shouldAutoRecover(name: String, output: JSONObject, actionsApproved: Boolean)" in recovery
    assert "fun runtimeStatus(autoRecover: Boolean, force: Boolean): JSONObject" in recovery
    assert "private const val TERMINAL_RECOVERY_FUSE_FAILURES = 2" in recovery
    assert "private const val TERMINAL_RUNTIME_CACHE_MS = 10_000L" in recovery
    assert "class NativeToolRecoveryController(" in tool_recovery
    assert "fun verifyRecovery(" in tool_recovery
    assert "fun mcpRecovery(" in tool_recovery
    assert "fun terminalRecovery(" in tool_recovery
    assert "class NativeToolVerificationRecoveryController(" in verification_recovery
    assert "fun recover(" in verification_recovery
    assert "class NativeToolMcpRecoveryController(" in mcp_recovery
    assert "fun recover(" in mcp_recovery
    assert "class NativeToolTerminalAutoRecoveryController(" in terminal_auto_recovery
    assert "fun recover(" in terminal_auto_recovery

    assert "private val terminalRecovery = NativeTerminalRecoveryController" in core
    assert "terminalRecovery.runtimeStatus(autoRecover = autoRecover, force = force)" in remote_runtime
    assert "terminalRecovery.runtimeStatus(autoRecover = autoRecover, force = force)" not in core
    assert "private val recoveryController = NativeToolRecoveryController" in execution_manager
    assert "recoveryController.recover(" in execution_manager
    assert "recoveryController.verifyRecovery(" not in execution_manager
    assert "recoveryController.mcpRecovery(" not in execution_manager
    assert "recoveryController.terminalRecovery(" not in execution_manager
    assert "private fun recoverMcpBridgeFailure(" not in execution_manager
    assert "private fun looksLikeMcpBackendError(" not in execution_manager
    assert "shouldAutoRecover = { name, output, actionsApproved ->" in core
    assert "terminalFuseOpen = { name -> terminalRecovery.fuseOpen(name) }" in core
    assert "recoverPcBridge = { arguments -> pcBridgeController.recover(arguments) }" in core
    assert "recover = recoverTerminalBackend" in factory
    assert "diagnose = diagnoseTerminal" in factory
    assert "private fun terminalRecoveryFuseOpen(" not in core
    assert "private fun recordTerminalRecoveryOutcome(" not in core
    assert "private fun shouldAutoRecoverTerminal(" not in core
    assert "private fun terminalRuntimeStatus(" not in core
    assert "private val terminalRecoveryFuses" not in core


def test_mobile_workspace_report_formatter_owns_task_report_rendering() -> None:
    workspace = read(KOTLIN_SRC / "MobileWorkspace.kt")
    tasks = read(KOTLIN_SRC / "MobileWorkspaceTasks.kt")
    formatter = read(KOTLIN_SRC / "MobileWorkspaceReportFormatter.kt")
    names = read(KOTLIN_SRC / "MobileWorkspaceNames.kt")
    history = read(KOTLIN_SRC / "MobileWorkspaceHistory.kt")

    assert "object MobileWorkspaceReportFormatter" in formatter
    assert "fun finalReportMarkdown(report: JSONObject): String" in formatter
    assert "fun traceSummaryMarkdown(report: JSONObject): String" in formatter
    assert "fun loopLogText(report: JSONObject): String" in formatter
    assert "fun failureAnalysisMarkdown(report: JSONObject): String" in formatter
    assert "private fun StringBuilder.appendToolFailureDetails" in formatter
    assert "private fun findToolTraceStep(" in formatter

    assert "class MobileWorkspaceTasks(" in tasks
    for symbol in (
        "fun taskCreate(",
        "fun taskList(",
        "fun taskRecordRun(",
        "fun taskReports(",
        "fun taskReportRead(",
        "fun taskReportSummarize(",
        "fun latestFailureAnalysis(",
        "fun taskUpdate(",
        "fun taskLogAppend(",
        "fun taskArtifactWrite(",
    ):
        assert symbol in tasks

    assert "MobileWorkspaceReportFormatter.finalReportMarkdown(report)" in tasks
    assert "MobileWorkspaceReportFormatter.traceSummaryMarkdown(report)" in tasks
    assert "MobileWorkspaceReportFormatter.loopLogText(report)" in tasks
    assert "MobileWorkspaceReportFormatter.failureAnalysisMarkdown(report)" in tasks
    assert "private val tasks = MobileWorkspaceTasks(" in workspace
    assert "return tasks.taskCreate(title, goal)" in workspace
    assert "return tasks.taskReportRead(task, report, maxBytes)" in workspace
    assert "return tasks.taskArtifactWrite(task, name, content, overwrite)" in workspace
    assert "private fun taskFinalReportMarkdown(" not in workspace
    assert "private fun taskTraceSummaryMarkdown(" not in workspace
    assert "private fun taskLoopLogText(" not in workspace
    assert "private fun taskFailureAnalysisMarkdown(" not in workspace
    assert "private fun StringBuilder.appendToolFailureDetails" not in workspace
    assert "private fun findToolTraceStep(" not in workspace
    assert "private fun resolveTaskDir(" not in workspace
    assert "private fun writeFailureAnalysisIfNeeded(" not in workspace

    assert "object MobileWorkspaceNames" in names
    assert "fun slug(raw: String): String" in names
    assert "fun sanitizeReportName(raw: String): String" in names
    assert "fun sanitizeRelativeFileName(raw: String): String" in names
    assert "fun sanitizeTaskStatus(status: String): String" in names
    assert "fun normalizeTaskReportPath(taskPath: String, raw: String): String" in names
    assert "MobileWorkspaceNames.slug(cleanTitle)" in tasks
    assert "MobileWorkspaceNames.sanitizeReportName(runId)" in tasks
    assert "MobileWorkspaceNames.normalizeTaskReportPath(relativePath(taskDir), report)" in tasks
    assert "MobileWorkspaceNames.sanitizeTaskStatus(cleanStatus)" in tasks
    assert "MobileWorkspaceNames.sanitizeRelativeFileName(" in tasks
    assert "private fun slug(" not in workspace
    assert "private fun sanitizeReportName(" not in workspace
    assert "private fun sanitizeRelativeFileName(" not in workspace
    assert "private fun sanitizeTaskStatus(" not in workspace
    assert "private fun normalizeTaskReportPath(" not in workspace

    assert "class MobileWorkspaceHistory(" in history
    assert "fun countChanges(): Int" in history
    assert "fun history(path: String = \"\", limit: Int = 50): JSONObject" in history
    assert "fun restore(changeId: String): JSONObject" in history
    assert "fun recordChange(" in history
    assert "private fun ensureHistoryRoot()" in history
    assert "change_id escapes history root" in history
    assert "private val history = MobileWorkspaceHistory(" in workspace
    assert "history.countChanges()" in workspace
    assert "return history.history(path, limit)" in workspace
    assert "return history.restore(changeId)" in workspace
    assert "history.recordChange(" in workspace
    assert "private fun ensureHistoryRoot(" not in workspace
    assert "private fun recordChange(" not in workspace


def test_native_mcp_client_owns_mcp_sessions_jsonrpc_and_progressive_loading() -> None:
    mcp_client = read(KOTLIN_SRC / "NativeMcpClient.kt")
    mcp_dispatcher = read(KOTLIN_SRC / "NativeMcpToolDispatcher.kt")
    remote_runtime = read(KOTLIN_SRC / "NativeRemoteRuntimeFacade.kt")
    status_controller = read(KOTLIN_SRC / "NativeStatusController.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    pc_bridge_controller = read(KOTLIN_SRC / "NativePcBridgeController.kt")
    terminal_fallback = read(KOTLIN_SRC / "NativePcBridgeTerminalFallback.kt")

    assert "class NativeMcpClient(" in mcp_client
    assert "private val sessions = ConcurrentHashMap<String, String>()" in mcp_client
    assert "fun configure(arguments: JSONObject): JSONObject" in mcp_client
    assert "fun status(server: String = \"\"): JSONObject" in mcp_client
    assert "fun tools(search: String = \"\", includeSchema: Boolean = false, serverId: String = \"\")" in mcp_client
    assert "fun toolInfo(tool: String, serverId: String = \"\")" in mcp_client
    assert "fun call(tool: String, arguments: JSONObject, timeoutMs: Int, serverId: String = \"\")" in mcp_client
    assert "runtimeConfig.mcpActiveServerId()" in mcp_client
    assert 'arguments.optString("server", "")' in mcp_dispatcher
    assert 'arguments.optString("server", "default")' not in mcp_dispatcher
    assert "private fun ensureSession(" in mcp_client
    assert "private fun initializeSession(" in mcp_client
    assert "private fun postJson(" in mcp_client
    assert "private fun readResponseText(" in mcp_client
    assert "text/event-stream" in mcp_client
    assert "private fun looksCompleteJson(" in mcp_client
    assert "private fun extractJsonPayload(" in mcp_client
    assert '"progressive_loading", true' in mcp_client

    assert "private val mcpClient = NativeMcpClient(runtimeConfig)" in core
    assert "mcpClient.clearSessions()" in core
    assert "mcpClient.runtimeStatus(force = false)" in status_controller
    assert "mcpClient.runtimeStatus(force = false)" not in core
    assert "mcpClient.runtimeStatus(force = true)" in terminal_fallback
    assert "mcpClient.status()" in remote_runtime
    assert "mcpClient.tools(search, includeSchema = true)" in remote_runtime
    assert "mcpClient.status()" not in core
    assert "mcpClient.tools(search, includeSchema = true)" not in core
    assert "private fun mcpRuntimeStatus(" not in core
    assert "private fun mcpStatus(" not in core
    assert "private fun mcpTools(" not in core
    assert "private fun mcpCall(" not in core
    assert "private fun ensureMcpSession(" not in core
    assert "private val mcpSessions" not in core


def test_native_skill_layer_progressively_unifies_plugins_and_procedures() -> None:
    catalog = read(KOTLIN_SRC / "NativeSkillToolCatalog.kt")
    registry = read(KOTLIN_SRC / "MobileSkillRegistry.kt")
    dispatcher = read(KOTLIN_SRC / "NativeSkillToolDispatcher.kt")
    tool_catalog = read(KOTLIN_SRC / "NativeToolCatalog.kt")
    groups = read(KOTLIN_SRC / "NativeToolGroups.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    executor = read(KOTLIN_SRC / "NativeToolExecutor.kt")
    profile = read(KOTLIN_SRC / "NativeAgentProfile.kt")
    core_toolsets = read(KOTLIN_SRC / "NativeCoreToolSets.kt")

    assert "object NativeSkillToolCatalog" in catalog
    for tool in ("skill_list", "skill_read", "skill_run"):
        assert f'name = "{tool}"' in catalog
        assert f'"{tool}"' in tool_catalog
        assert f'"{tool}"' in dispatcher

    assert "class MobileSkillRegistry" in registry
    assert "collectPluginSkills" in registry
    assert "collectProcedureSkills" in registry
    assert '"plugin:$pluginId:$name"' in registry
    assert '"procedure:$key"' in registry
    assert "procedureRunPlan" in registry
    assert "plugins.workflow(pluginId, workflowName)" in registry
    assert "memory.procedureRead(" in registry

    assert "class NativeSkillToolDispatcher" in dispatcher
    assert "skills.list(arguments)" in dispatcher
    assert "skills.read(arguments)" in dispatcher
    assert "runPluginWorkflow(" in dispatcher
    assert "skills.procedureRunPlan(id)" in dispatcher

    assert "NativeSkillToolCatalog.descriptors" in tool_catalog
    assert '"skill_list",' in groups
    assert '"skills" to toolsInCategory("skills")' in groups
    assert "skill_list" in core_toolsets
    assert "skill_read" in core_toolsets

    assert "val skillToolDispatcher = NativeSkillToolDispatcher" in factory
    assert "skillExecute = { name, arguments, actionsApproved, taskPlan, sessionId ->" in factory
    assert "in NativeSkillToolDispatcher.TOOL_NAMES -> skillExecute" in executor
    assert "skill_list" in profile
    assert "skill_read" in profile
    assert "skill_run" in profile
    assert "cache-friendly" in profile


def test_model_request_and_usage_are_cache_prefix_friendly() -> None:
    model_client = read(KOTLIN_SRC / "NativeModelClient.kt")
    context_manager = read(KOTLIN_SRC / "NativeContextManager.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")
    registry = read(KOTLIN_SRC / "NativeToolRegistry.kt")
    formatter = read(KOTLIN_SRC / "MainStatusFormatter.kt")

    assert "val tools = NativeToolRegistry.schemasForTools(enabledTools)" in model_client
    assert ".put(\"tools\", tools)\n            .put(\"tool_choice\", \"auto\")\n            .put(\"messages\", requestMessages)" in model_client
    assert "tool_schema_hash" in model_client
    assert "stable_system_and_sorted_tools_before_dynamic_messages" in model_client
    assert "mobile_agent_request" in model_client
    assert "private fun stableHash(" in model_client

    assert "descriptors.forEach { descriptor ->" in registry
    assert "if (descriptor.name in normalized) array.put(descriptor.schema())" in registry
    assert "if (descriptor.name in normalized) array.put(descriptor.metadata())" in registry

    assert '"cache_diagnostics"' in context_manager
    assert '"hit_rate_basis"' in context_manager
    assert '"latest_cacheable_tokens"' in context_manager
    assert "stable_system_and_sorted_tools_before_dynamic_messages" in context_manager
    assert "fun resetUsage(sessionId: String)" in context_manager
    assert "contextManager.resetUsage(sessionId)" in core

    assert 'cacheSummary(latestHit, latestMiss, "本轮")' in formatter
    assert "sessionHit" not in formatter
    assert "sessionMiss" not in formatter
    assert 'cacheSummary(sessionHit, sessionMiss, "会话")' not in formatter
    assert "$label 命中率" in formatter
    assert "工具 $toolCount" in formatter


def test_native_agent_profile_defaults_to_simplified_chinese_for_chat() -> None:
    profile = read(KOTLIN_SRC / "NativeAgentProfile.kt")
    chat_controller = read(KOTLIN_SRC / "NativeChatController.kt")
    stop_flow = read(KOTLIN_SRC / "NativeStopFlowController.kt")
    verifier = read(KOTLIN_SRC / "NativeToolVerifier.kt")
    trace_formatter = read(KOTLIN_SRC / "ToolTraceFormatter.kt")

    assert 'const val SYSTEM_PROMPT_VERSION = "mobile-agent-core-v18"' in profile
    assert "Default to concise Simplified Chinese" in profile
    assert "Do not use Traditional Chinese" in profile
    assert "If the user asks you to speak Chinese, do not call tools" in profile
    assert "If the user asks you to speak Chinese, do not call tools" in profile
    assert "For pure chat, explanations, planning, product questions, status questions, or preference changes, answer directly" in profile
    assert "Use progressive loading. Start with compact discovery tools." in profile
    assert "done_when" in profile
    assert "Use exactly one state-changing action per loop round" in profile
    assert "Treat injected memory as helpful context, not absolute truth" in profile
    assert "Use memory_update/memory_delete" in profile
    assert "默认使用简体中文回复" in chat_controller
    assert "如果这只是聊天、语言偏好或解释问题，不要为了确认状态而调用工具" in chat_controller
    assert "任务已暂停，避免继续重复失败" in stop_flow
    assert "终端命令验证失败" in verifier
    assert "verificationFailed -> \"失败\"" in trace_formatter
    assert "验证失败" in trace_formatter


def test_native_memory_recall_injection_and_profile_crud_are_explicit() -> None:
    store = read(KOTLIN_SRC / "MobileMemoryStore.kt")
    profile_store = read(KOTLIN_SRC / "MobileMemoryProfileStore.kt")
    memory_text = read(KOTLIN_SRC / "MobileMemoryText.kt")
    catalog = read(KOTLIN_SRC / "NativeMemoryToolCatalog.kt")
    dispatcher = read(KOTLIN_SRC / "NativeMemoryToolDispatcher.kt")
    requester = read(KOTLIN_SRC / "NativeModelRequester.kt")
    context_manager = read(KOTLIN_SRC / "NativeContextManager.kt")

    assert "fun updateMemory(arguments: JSONObject)" in store
    assert "fun deleteMemory(arguments: JSONObject)" in store
    assert "return profileStore.updateMemory(arguments)" in store
    assert "return profileStore.deleteMemory(arguments)" in store
    assert "val hasMemory = memoryItems.length() > 0" in store
    assert "val hasProcedures = procedureItems.length() > 0" in store
    assert "val hasExperience = experienceItems.length() > 0" in store
    assert '"injected", content.isNotBlank()' in store
    assert "## Memory Use Rules" in store
    assert "Treat this as relevant context, not guaranteed truth" in store

    assert "fun updateMemory(arguments: JSONObject)" in profile_store
    assert "fun deleteMemory(arguments: JSONObject)" in profile_store
    assert "memory item not found; provide target plus key or text" in profile_store
    assert "target plus key or text" in catalog
    assert 'name = "memory_update"' in catalog
    assert 'name = "memory_delete"' in catalog
    assert '"memory_update" -> memory.updateMemory(arguments)' in dispatcher
    assert '"memory_delete" -> memory.deleteMemory(arguments)' in dispatcher

    assert r'Regex("[\\p{IsHan}]+")' in memory_text
    assert "score=$score" in memory_text
    assert "[MOBILE_AGENT_RELEVANT_MEMORY_V2]" in requester
    assert "上下文自动压缩摘要" in context_manager
    assert "最近用户消息" in context_manager
    assert "涓" not in context_manager


def test_native_pc_bridge_scripts_are_split_from_core_bridge_flow() -> None:
    scripts = read(KOTLIN_SRC / "NativePcBridgeScripts.kt")
    controller = read(KOTLIN_SRC / "NativePcBridgeController.kt")
    terminal_fallback = read(KOTLIN_SRC / "NativePcBridgeTerminalFallback.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "object NativePcBridgeScripts" in scripts
    assert "fun parseEndpoint(endpoint: String): Triple<Int, String, String>" in scripts
    assert "fun shellQuote(value: String): String" in scripts
    assert "fun mcpDiagnostic(endpoint: String): String" in scripts
    assert "fun killMcpPort(endpoint: String): String" in scripts
    assert "fun tailscaleSshDiagnostic(port: Int, tryFix: Boolean): String" in scripts
    assert "mobile-agent-ssh-probe" in scripts
    assert "Get-NetFirewallRule" in scripts

    assert "NativePcBridgeScripts.parseEndpoint(endpoint)" in controller
    assert "NativePcBridgeScripts.mcpDiagnostic(remoteEndpoint)" in controller
    assert "NativePcBridgeScripts.killMcpPort(remoteEndpoint)" in controller
    assert "NativePcBridgeScripts.tailscaleSshDiagnostic(port, tryFix)" in controller
    assert "NativePcBridgeScripts.shellQuote(target)" in terminal_fallback
    assert "private fun buildMcpDiagnosticScript(" not in core
    assert "private fun buildKillMcpPortScript(" not in core
    assert "private fun buildTailscaleSshDiagnosticScript(" not in core
    assert "private fun parseEndpoint(" not in core


def test_native_pc_bridge_controller_owns_desktop_bridge_workflows() -> None:
    controller = read(KOTLIN_SRC / "NativePcBridgeController.kt")
    terminal_fallback = read(KOTLIN_SRC / "NativePcBridgeTerminalFallback.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativePcBridgeController(" in controller
    assert "fun healthCheck(arguments: JSONObject): JSONObject" in controller
    assert "fun status(arguments: JSONObject): JSONObject" in controller
    assert "fun recover(arguments: JSONObject): JSONObject" in controller
    assert "fun tailscalePreflight(arguments: JSONObject): JSONObject" in controller
    assert "fun tailscaleSshDiagnose(arguments: JSONObject): JSONObject" in controller
    assert "fun fileWorkflow(arguments: JSONObject): JSONObject" in controller
    assert "private val terminalFallback = NativePcBridgeTerminalFallback(" in controller
    assert "terminalFallback.recover(" in controller
    assert "repairSshViaMcp(arguments)" in controller
    assert "private fun mcpSummary(" in controller
    assert '"include_mcp_tools"' in controller
    assert '"include_mcp_config"' in controller
    assert '"summary_compacted"' in controller
    assert 'summarizedStatus.put("tools"' in controller
    assert 'summarizedStatus.put("config"' in controller
    assert '"pc_bridge_offline_report_user"' in controller
    assert "mcpClient.call(" in controller
    assert "class NativePcBridgeTerminalFallback(" in terminal_fallback
    assert "fun recover(" in terminal_fallback
    assert "terminalRecovery.runtimeStatus(autoRecover = true, force = true)" in terminal_fallback
    assert '"mcp-ssh-forward-fallback"' in terminal_fallback
    assert "private fun syncMcpAuthFromSsh(" in controller
    assert "NativePcBridgeScripts.mcpDiagnostic(remoteEndpoint)" in controller
    assert "NativePcBridgeScripts.killMcpPort(remoteEndpoint)" in controller
    assert "NativePcBridgeScripts.tailscaleSshDiagnostic(port, tryFix)" in controller
    assert "private fun terminalForwardFallback(" not in controller

    assert "private val pcBridgeController = NativePcBridgeController" in core
    assert "pcFileWorkflow = { arguments -> pcBridgeController.fileWorkflow(arguments) }" in factory
    assert "healthCheck = { arguments -> pcBridgeController.healthCheck(arguments) }" in factory
    assert "status = { arguments -> pcBridgeController.status(arguments) }" in factory
    assert "recover = { arguments -> pcBridgeController.recover(arguments) }" in factory
    assert "tailscalePreflight = { arguments -> pcBridgeController.tailscalePreflight(arguments) }" in factory
    assert "tailscaleSshDiagnose = { arguments -> pcBridgeController.tailscaleSshDiagnose(arguments) }" in factory
    assert "private fun pcBridgeStatus(" not in core
    assert "private fun pcBridgeRecover(" not in core
    assert "private fun pcBridgeTerminalForwardFallback(" not in core
    assert "private fun syncMcpAuthFromSsh(" not in core
    assert "private fun tailscalePreflight(" not in core
    assert "private fun tailscaleSshDiagnose(" not in core
    assert "private fun pcFileWorkflow(" not in core


def test_desktop_control_mcp_aggregates_windows_som_and_cua_tools() -> None:
    project_root = ROOT.parent
    server = read(project_root / "tools" / "desktop-control-mcp" / "desktop_control_mcp.py")
    launcher = read(project_root / "tools" / "desktop-control-mcp" / "start-desktop-control-mcp.ps1")
    readme = read(project_root / "tools" / "desktop-control-mcp" / "README.md")

    assert "class DesktopControlMcp" in server
    assert "class WindowsMcpProxy" in server
    assert "win__" in server
    assert '"som__som_parse_screenshot"' in server
    assert "cua__status" in server
    assert "cua-driver" in server
    assert "ThreadingHTTPServer" in server
    assert "tools/list" in server
    assert "tools/call" in server

    assert "start-desktop-control-mcp.ps1" in readme
    assert "http://100.113.120.40:8010/mcp" in readme
    assert "autostart kick" in launcher


def test_native_storage_access_owns_shared_storage_permission_flow() -> None:
    storage = read(KOTLIN_SRC / "NativeStorageAccess.kt")
    remote_runtime = read(KOTLIN_SRC / "NativeRemoteRuntimeFacade.kt")
    factory = read(KOTLIN_SRC / "NativeToolDispatchersFactory.kt")
    core = read(KOTLIN_SRC / "NativeAgentCore.kt")

    assert "class NativeStorageAccess(" in storage
    assert "fun status(): JSONObject" in storage
    assert "fun openSettings(): JSONObject" in storage
    assert "Environment.isExternalStorageManager()" in storage
    assert "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" in storage

    assert "private val storageAccess = NativeStorageAccess(context)" in core
    assert "storagePermissionStatus = { storageAccess.status() }" in factory
    assert "openStoragePermissionSettings = { storageAccess.openSettings() }" in factory
    assert "storagePermissionStatusForUi(): JSONObject = storageAccess.status()" in remote_runtime
    assert "openStoragePermissionSettingsForUi(): JSONObject = storageAccess.openSettings()" in remote_runtime
    assert "return storageAccess.status()" not in core
    assert "return storageAccess.openSettings()" not in core
    assert "private fun storagePermissionStatus(" not in core
    assert "private fun openStoragePermissionSettings(" not in core
