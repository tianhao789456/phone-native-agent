param(
  [switch]$Install,
  [string]$Package = "com.mobileagent.host",
  [int]$BridgePort = 8790,
  [int]$TimeoutSec = 20
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
. (Join-Path $ScriptDir "android-env.ps1")

function Invoke-JsonPost {
  param(
    [string]$Url,
    [hashtable]$Payload,
    [int]$TimeoutSec = 20
  )
  $body = $Payload | ConvertTo-Json -Depth 12
  Invoke-RestMethod -Uri $Url -Method Post -Body $body -ContentType "application/json; charset=utf-8" -TimeoutSec $TimeoutSec
}

$apk = Join-Path $ProjectRoot "android-host\app\build\outputs\apk\debug\app-debug.apk"
if ($Install) {
  if (-not (Test-Path -LiteralPath $apk)) {
    throw "Debug APK not found: $apk"
  }
  adb install -r $apk | Out-Host
}

adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
adb forward "tcp:$BridgePort" "tcp:$BridgePort" | Out-Null

$base = "http://127.0.0.1:$BridgePort"
$status = Invoke-RestMethod -Uri "$base/status" -TimeoutSec 5
$health = Invoke-JsonPost "$base/native-tools/call" @{
  tool = "self_health_check"
  arguments = @{}
  actions_approved = $true
} $TimeoutSec
$snapshot = Invoke-JsonPost "$base/native-tools/call" @{
  tool = "accessibility_snapshot_v2"
  arguments = @{ max_nodes = 40 }
  actions_approved = $true
} $TimeoutSec

$summary = [ordered]@{
  status_ok = [bool]$status.ok
  accessibility_connected = [bool]$status.accessibility.connected
  health_ok = [bool]$health.ok
  health_healthy = [bool]$health.result.healthy
  health_issues = @($health.result.issues)
  snapshot_ok = [bool]$snapshot.ok
  snapshot_result_ok = [bool]$snapshot.result.ok
  snapshot_version = [string]$snapshot.result.version
  snapshot_node_count = [int]$snapshot.result.node_count
  snapshot_package = [string]$snapshot.result.package
  plugin_count = 0
  plugin_enabled = $false
  mcp_configured = $false
  mcp_available = $false
}

$pluginInfo = Invoke-JsonPost "$base/native-tools/call" @{
  tool = "plugin_info"
  arguments = @{}
  actions_approved = $true
} $TimeoutSec
if ($pluginInfo.ok) {
  $summary.plugin_count = [int]$pluginInfo.result.count
  $summary.plugin_enabled = [bool]$pluginInfo.result.enabled
}

$pluginList = Invoke-JsonPost "$base/native-tools/call" @{
  tool = "plugin_list"
  arguments = @{ include_details = $false }
  actions_approved = $true
} $TimeoutSec


$mcpStatus = Invoke-JsonPost "$base/native-tools/call" @{
  tool = "mcp_status"
  arguments = @{}
  actions_approved = $true
} $TimeoutSec
$summary.mcp_configured = [bool]$mcpStatus.result.configured
$summary.mcp_available = [bool]($mcpStatus.result.available -eq $true)

$summary | ConvertTo-Json -Depth 8

if (-not $status.ok) { throw "HostBridge status failed." }
if (-not $status.accessibility.connected) { throw "Accessibility service is not connected." }
if (-not $health.ok) { throw "self_health_check tool call failed." }
if (-not $snapshot.ok -or -not $snapshot.result.ok) { throw "accessibility_snapshot_v2 tool call failed." }
if ($snapshot.result.version -ne "accessibility_snapshot_v2") { throw "Unexpected snapshot version: $($snapshot.result.version)" }
if ([int]$snapshot.result.node_count -le 0) { throw "Accessibility snapshot returned no nodes." }
