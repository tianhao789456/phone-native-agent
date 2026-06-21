package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MobilePluginRegistry(private val context: Context) {
    private val root: File = File(context.filesDir, "plugins")
    private val runtimeConfig = AgentRuntimeConfig(context)

    fun info(): JSONObject {
        ensureRoot()
        return JSONObject()
            .put("ok", true)
            .put("root", root.canonicalPath)
            .put("count", pluginDirs().size)
            .put("enabled", pluginDirs().count { readManifestOrNull(it)?.optBoolean("enabled", true) == true })
    }

    fun list(includeDisabled: Boolean = true, includeDetails: Boolean = false): JSONObject {
        ensureRoot()
        val plugins = JSONArray()
        pluginDirs()
            .mapNotNull { readManifestOrNull(it) }
            .filter { includeDisabled || it.optBoolean("enabled", true) }
            .sortedBy { it.optString("id") }
            .forEach { manifest ->
                plugins.put(if (includeDetails) manifest else compactManifest(manifest))
            }
        return JSONObject()
            .put("ok", true)
            .put("root", root.canonicalPath)
            .put("count", plugins.length())
            .put("progressive_loading", true)
            .put("detail_tool", "plugin_read")
            .put("plugins", plugins)
    }

    fun create(manifest: JSONObject, overwrite: Boolean = false): JSONObject {
        ensureRoot()
        val normalized = normalizeManifest(manifest)
        val id = normalized.optString("id")
        val dir = pluginDir(id)
        val manifestFile = File(dir, "manifest.json")
        if (manifestFile.exists() && !overwrite) {
            return error("Plugin already exists and overwrite is false: $id")
        }
        dir.mkdirs()
        manifestFile.writeText(normalized.toString(2), Charsets.UTF_8)
        File(dir, "README.md").takeIf { !it.exists() }?.writeText("# $id\n\nMobile Agent plugin workspace.\n", Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("path", relativePath(dir))
            .put("manifest", normalized)
    }

    fun read(id: String): JSONObject {
        val dir = pluginDir(validateId(id))
        val manifest = readManifestOrNull(dir) ?: return error("Plugin does not exist: $id")
        return JSONObject()
            .put("ok", true)
            .put("id", manifest.optString("id"))
            .put("path", relativePath(dir))
            .put("manifest", manifest)
    }

    fun validate(id: String): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        val manifest = readManifestOrNull(dir) ?: return error("Plugin does not exist: $cleanId")
        val report = validateManifest(manifest)
            .put("id", cleanId)
            .put("plugin_path", relativePath(dir))
            .put("created_at_ms", System.currentTimeMillis())
        report.put("reports", writeReport(dir, "validate", report))
        return report
    }

    fun test(id: String): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        val manifest = readManifestOrNull(dir) ?: return error("Plugin does not exist: $cleanId")
        val validation = validateManifest(manifest)
        val checks = JSONArray()
        checks.put(check("manifest_valid", validation.optBoolean("ok", false), validation.optJSONArray("issues") ?: JSONArray()))
        checks.put(check("enabled_state_readable", manifest.has("enabled"), JSONArray()))
        checks.put(check("tools_array_readable", manifest.optJSONArray("tools") != null, JSONArray()))
        checks.put(check("workflows_array_readable", manifest.optJSONArray("workflows") != null, JSONArray()))
        val ok = validation.optBoolean("ok", false)
        val report = JSONObject()
            .put("ok", ok)
            .put("id", cleanId)
            .put("plugin_path", relativePath(dir))
            .put("summary", if (ok) "Plugin manifest checks passed." else "Plugin manifest checks failed.")
            .put("validation", validation)
            .put("checks", checks)
            .put("created_at_ms", System.currentTimeMillis())
        report.put("reports", writeReport(dir, "test", report))
        return report
    }

    fun workflow(id: String, workflowName: String): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        val manifest = readManifestOrNull(dir) ?: return error("Plugin does not exist: $cleanId")
        if (!manifest.optBoolean("enabled", true)) return error("Plugin is disabled: $cleanId")
        val workflows = manifest.optJSONArray("workflows") ?: return error("Plugin has no workflows array: $cleanId")
        for (index in 0 until workflows.length()) {
            val workflow = workflows.optJSONObject(index) ?: continue
            if (workflow.optString("name") == workflowName) {
                return JSONObject()
                    .put("ok", true)
                    .put("id", cleanId)
                    .put("workflow", workflow)
                    .put("plugin_path", relativePath(dir))
            }
        }
        return error("Workflow does not exist: $cleanId/$workflowName")
    }

    fun writeWorkflowRunReport(id: String, workflowName: String, report: JSONObject): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        if (!File(dir, "manifest.json").isFile) return error("Plugin does not exist: $cleanId")
        val reports = File(dir, "reports")
        reports.mkdirs()
        val cleanWorkflow = sanitizeReportName(workflowName.ifBlank { "workflow" })
        val latest = File(reports, "run-$cleanWorkflow-latest.json")
        val snapshot = File(reports, "run-$cleanWorkflow-${System.currentTimeMillis()}.json")
        val persisted = JSONObject(report.toString())
        latest.writeText(persisted.toString(2), Charsets.UTF_8)
        snapshot.writeText(persisted.toString(2), Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("latest", "${relativePath(dir)}/reports/${latest.name}")
            .put("snapshot", "${relativePath(dir)}/reports/${snapshot.name}")
    }

    fun reports(id: String, limit: Int = 50): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        if (!File(dir, "manifest.json").isFile) return error("Plugin does not exist: $cleanId")
        val reports = File(dir, "reports")
        val items = JSONArray()
        reports
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, 200))
            ?.forEach { file ->
                items.put(
                    JSONObject()
                        .put("name", file.name)
                        .put("path", "${relativePath(dir)}/reports/${file.name}")
                        .put("size_bytes", file.length())
                        .put("modified_at_ms", file.lastModified())
                )
            }
        return JSONObject()
            .put("ok", true)
            .put("id", cleanId)
            .put("plugin_path", relativePath(dir))
            .put("count", items.length())
            .put("reports", items)
    }

    fun readReport(id: String, report: String, maxBytes: Int = 200000): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        if (!File(dir, "manifest.json").isFile) return error("Plugin does not exist: $cleanId")
        val reports = File(dir, "reports").canonicalFile
        val relative = normalizeReportPath(cleanId, report)
        val file = File(reports, relative).canonicalFile
        if (!file.toPath().startsWith(reports.toPath())) return error("Report path escapes reports root: $report")
        if (!file.isFile) return error("Report does not exist: $report")
        val limit = maxBytes.coerceIn(1_000, 1_000_000)
        if (file.length() > limit) {
            return error("Report is too large: ${file.length()} bytes > $limit bytes")
                .put("size_bytes", file.length())
                .put("max_bytes", limit)
        }
        val text = file.readText(Charsets.UTF_8)
        val output = JSONObject()
            .put("ok", true)
            .put("id", cleanId)
            .put("path", "${relativePath(dir)}/reports/$relative")
            .put("size_bytes", file.length())
            .put("content", text)
        runCatching { JSONObject(text) }.onSuccess { output.put("json", it) }
        return output
    }

    fun setEnabled(id: String, enabled: Boolean): JSONObject {
        val cleanId = validateId(id)
        val dir = pluginDir(cleanId)
        val manifest = readManifestOrNull(dir) ?: return error("Plugin does not exist: $cleanId")
        manifest.put("enabled", enabled)
        manifest.put("updated_at_ms", System.currentTimeMillis())
        File(dir, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("id", cleanId)
            .put("enabled", enabled)
            .put("manifest", manifest)
    }

    private fun normalizeManifest(input: JSONObject): JSONObject {
        val id = validateId(input.optString("id"))
        val now = System.currentTimeMillis()
        val tools = input.optJSONArray("tools") ?: JSONArray()
        val workflows = input.optJSONArray("workflows") ?: JSONArray()
        return JSONObject()
            .put("id", id)
            .put("name", input.optString("name", id).take(120))
            .put("version", input.optString("version", "0.1.0").take(40))
            .put("description", input.optString("description", "").take(500))
            .put("enabled", input.optBoolean("enabled", true))
            .put("tools", tools)
            .put("workflows", workflows)
            .put("created_at_ms", input.optLong("created_at_ms", now))
            .put("updated_at_ms", now)
    }

    private fun compactManifest(manifest: JSONObject): JSONObject {
        val workflows = manifest.optJSONArray("workflows") ?: JSONArray()
        val tools = manifest.optJSONArray("tools") ?: JSONArray()
        val workflowIndex = JSONArray()
        for (index in 0 until workflows.length()) {
            val workflow = workflows.optJSONObject(index) ?: continue
            workflowIndex.put(
                JSONObject()
                    .put("name", workflow.optString("name"))
                    .put("description", workflow.optString("description"))
                    .put("steps", workflow.optJSONArray("steps")?.length() ?: 0)
            )
        }
        return JSONObject()
            .put("id", manifest.optString("id"))
            .put("name", manifest.optString("name"))
            .put("version", manifest.optString("version"))
            .put("description", manifest.optString("description"))
            .put("enabled", manifest.optBoolean("enabled", true))
            .put("tool_count", tools.length())
            .put("workflow_count", workflows.length())
            .put("workflows", workflowIndex)
            .put("updated_at_ms", manifest.optLong("updated_at_ms"))
    }

    private fun validateManifest(manifest: JSONObject): JSONObject {
        val issues = JSONArray()
        val id = runCatching { validateId(manifest.optString("id")) }.getOrElse {
            issues.put("invalid_id: ${it.message}")
            ""
        }
        if (id.isNotBlank() && id != manifest.optString("id")) {
            issues.put("id must already be normalized lowercase: ${manifest.optString("id")}")
        }
        if (manifest.optString("name").isBlank()) issues.put("name is required")
        if (manifest.optString("version").isBlank()) issues.put("version is required")
        val toolNames = mutableSetOf<String>()
        val tools = manifest.optJSONArray("tools")
        if (tools == null) {
            issues.put("tools must be an array")
        } else {
            for (index in 0 until tools.length()) {
                val item = tools.optJSONObject(index)
                if (item == null) {
                    issues.put("tools[$index] must be an object")
                    continue
                }
                val name = item.optString("name")
                if (name.isBlank()) issues.put("tools[$index].name is required")
                if (name.isNotBlank() && !toolNames.add(name)) issues.put("duplicate tool name: $name")
                if (item.optString("description").isBlank()) issues.put("tools[$index].description is recommended")
            }
        }
        val workflows = manifest.optJSONArray("workflows")
        if (workflows == null) {
            issues.put("workflows must be an array")
        } else {
            val workflowNames = mutableSetOf<String>()
            for (index in 0 until workflows.length()) {
                val item = workflows.optJSONObject(index)
                if (item == null) {
                    issues.put("workflows[$index] must be an object")
                    continue
                }
                val name = item.optString("name")
                if (name.isBlank()) issues.put("workflows[$index].name is required")
                if (name.isNotBlank() && !workflowNames.add(name)) issues.put("duplicate workflow name: $name")
                val steps = item.optJSONArray("steps")
                if (steps == null) {
                    issues.put("workflows[$index].steps must be an array")
                } else {
                    validateWorkflowSteps(issues, index, steps)
                }
            }
        }
        return JSONObject()
            .put("ok", issues.length() == 0)
            .put("enabled", manifest.optBoolean("enabled", true))
            .put("issues", issues)
            .put("tool_count", tools?.length() ?: 0)
            .put("workflow_count", workflows?.length() ?: 0)
    }

    private fun validateWorkflowSteps(issues: JSONArray, workflowIndex: Int, steps: JSONArray) {
        for (stepIndex in 0 until steps.length()) {
            val step = steps.optJSONObject(stepIndex)
            if (step == null) {
                issues.put("workflows[$workflowIndex].steps[$stepIndex] must be an object")
                continue
            }
            val tool = step.optString("tool")
            if (tool.isBlank()) {
                issues.put("workflows[$workflowIndex].steps[$stepIndex].tool is required")
            } else {
                validateWorkflowTool(issues, workflowIndex, stepIndex, tool)
            }
            if (step.has("arguments") && step.optJSONObject("arguments") == null) {
                issues.put("workflows[$workflowIndex].steps[$stepIndex].arguments must be an object")
            }
            if (step.has("stop_on_failure") && step.opt("stop_on_failure") !is Boolean) {
                issues.put("workflows[$workflowIndex].steps[$stepIndex].stop_on_failure must be boolean")
            }
        }
    }

    private fun validateWorkflowTool(issues: JSONArray, workflowIndex: Int, stepIndex: Int, tool: String) {
        val descriptor = NativeToolRegistry.descriptor(tool)
        val allowPowerTools = runtimeConfig.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER
        when {
            tool == "plugin_run" || tool.startsWith("plugin_") -> {
                issues.put("workflows[$workflowIndex].steps[$stepIndex].tool is blocked by plugin adapter: $tool")
            }
            descriptor == null -> {
                issues.put("workflows[$workflowIndex].steps[$stepIndex].tool is unknown: $tool")
            }
            descriptor.access != NativeToolAccess.READ_ONLY && !allowPowerTools -> {
                issues.put("workflows[$workflowIndex].steps[$stepIndex].tool is not read_only: $tool")
            }
        }
    }

    private fun check(name: String, ok: Boolean, issues: JSONArray): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("ok", ok)
            .put("issues", issues)
    }

    private fun writeReport(dir: File, type: String, report: JSONObject): JSONObject {
        val reports = File(dir, "reports")
        reports.mkdirs()
        val latest = File(reports, "$type-latest.json")
        val snapshot = File(reports, "$type-${System.currentTimeMillis()}.json")
        latest.writeText(report.toString(2), Charsets.UTF_8)
        snapshot.writeText(report.toString(2), Charsets.UTF_8)
        return JSONObject()
            .put("latest", "${relativePath(dir)}/reports/${latest.name}")
            .put("snapshot", "${relativePath(dir)}/reports/${snapshot.name}")
    }

    private fun sanitizeReportName(raw: String): String {
        return raw
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-')
            .ifBlank { "workflow" }
            .take(80)
    }

    private fun normalizeReportPath(id: String, raw: String): String {
        var path = raw.trim().replace('\\', '/')
        if (path.startsWith("$id/")) path = path.removePrefix("$id/")
        if (path.startsWith("reports/")) path = path.removePrefix("reports/")
        require(path.isNotBlank()) { "report path is required" }
        require(!path.contains("..")) { "report path cannot contain .." }
        require(!path.startsWith("/")) { "report path must be relative" }
        return path
    }

    private fun validateId(raw: String): String {
        val id = raw.trim().lowercase()
        require(id.matches(Regex("[a-z0-9][a-z0-9_.-]{1,63}"))) {
            "plugin id must match [a-z0-9][a-z0-9_.-]{1,63}"
        }
        require(!id.contains("..")) { "plugin id cannot contain .." }
        return id
    }

    private fun ensureRoot() {
        root.mkdirs()
    }

    private fun pluginDirs(): List<File> {
        ensureRoot()
        return root.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    private fun pluginDir(id: String): File {
        ensureRoot()
        val dir = File(root, id).canonicalFile
        val rootPath = root.canonicalFile.toPath()
        if (dir.toPath() != rootPath && !dir.toPath().startsWith(rootPath)) {
            throw SecurityException("plugin id escapes plugin root")
        }
        return dir
    }

    private fun readManifestOrNull(dir: File): JSONObject? {
        val manifest = File(dir, "manifest.json")
        if (!manifest.isFile) return null
        return runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun relativePath(file: File): String {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = file.canonicalFile.toPath()
        return if (targetPath == rootPath) "." else rootPath.relativize(targetPath).toString().replace('\\', '/')
    }

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }
}
