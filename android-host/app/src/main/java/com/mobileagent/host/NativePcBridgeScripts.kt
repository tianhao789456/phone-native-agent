package com.mobileagent.host

import java.net.URL

object NativePcBridgeScripts {
    fun parseEndpoint(endpoint: String): Triple<Int, String, String> {
        return runCatching {
            val url = URL(endpoint)
            val port = if (url.port > 0) url.port else url.defaultPort
            val path = if (url.path.isNullOrBlank()) "/" else url.path
            val query = if (url.query.isNullOrBlank()) "" else "?${url.query}"
            Triple(port, "${url.protocol}://127.0.0.1:$port$path$query", "$path$query")
        }.getOrElse {
            Triple(8931, endpoint, "/mcp")
        }
    }

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun mcpDiagnostic(endpoint: String): String {
        val parsed = parseEndpoint(endpoint)
        return """
${'$'}ErrorActionPreference = 'Continue'
${'$'}ProgressPreference = 'SilentlyContinue'
${'$'}endpoint = '${endpoint.replace("'", "''")}'
${'$'}localEndpoint = '${parsed.second.replace("'", "''")}'
${'$'}port = ${parsed.first}
Write-Output "endpoint=${'$'}endpoint"
Write-Output "local_endpoint=${'$'}localEndpoint"
Write-Output "--- tcp port ---"
Get-NetTCPConnection -LocalPort ${'$'}port -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,State,OwningProcess | Format-List | Out-String -Width 4096
Write-Output "--- processes on port ---"
${'$'}processIds = @(Get-NetTCPConnection -LocalPort ${'$'}port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
foreach (${'$'}processId in ${'$'}processIds) {
  Get-Process -Id ${'$'}processId -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,Path,StartTime | Format-List | Out-String -Width 4096
}
Write-Output "--- likely mcp processes ---"
Get-CimInstance Win32_Process | Where-Object { ${'$'}_.CommandLine -match 'mcp|modelcontext|playwright-mcp|windows-mcp|@modelcontextprotocol|npx .*mcp|uv .*mcp|python .*mcp' } | Select-Object -First 20 ProcessId,Name,CommandLine | Format-List | Out-String -Width 4096
Write-Output "--- tailscale serve ---"
try { tailscale serve status } catch { Write-Output ("tailscale serve status failed: " + ${'$'}_.Exception.Message) }
Write-Output "--- tailscale funnel ---"
try { tailscale funnel status } catch { Write-Output ("tailscale funnel status failed: " + ${'$'}_.Exception.Message) }
Write-Output "--- http probe ---"
try {
  ${'$'}body = '{"jsonrpc":"2.0","id":"mobile-agent-probe","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mobile-agent-ssh-probe","version":"0.1.0"}}}'
  ${'$'}response = Invoke-WebRequest -Uri ${'$'}localEndpoint -Method Post -ContentType 'application/json' -Headers @{Accept='application/json, text/event-stream'} -Body ${'$'}body -TimeoutSec 8
  Write-Output ("HTTP " + [int]${'$'}response.StatusCode)
  Write-Output ${'$'}response.Content
} catch {
  Write-Output ("HTTP_PROBE_ERROR " + ${'$'}_.Exception.GetType().Name + ": " + ${'$'}_.Exception.Message)
  if (${'$'}_.Exception.Response) { Write-Output ("HTTP_STATUS " + [int]${'$'}_.Exception.Response.StatusCode) }
}
""".trimIndent()
    }

    fun killMcpPort(endpoint: String): String {
        val port = parseEndpoint(endpoint).first
        return """
${'$'}ErrorActionPreference = 'Continue'
${'$'}processIds = @(Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
foreach (${'$'}processId in ${'$'}processIds) {
  Write-Output "Stopping PID ${'$'}processId on port $port"
  Stop-Process -Id ${'$'}processId -Force -ErrorAction Continue
}
Start-Sleep -Milliseconds 500
Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,State,OwningProcess | Format-List | Out-String -Width 4096
""".trimIndent()
    }

    fun tailscaleSshDiagnostic(port: Int, tryFix: Boolean): String {
        val fixBlock = if (tryFix) {
            """
Write-Output "--- try fix sshd/firewall ---"
try { Set-Service sshd -StartupType Automatic -ErrorAction Continue } catch { Write-Output ("Set-Service failed: " + ${'$'}_.Exception.Message) }
try { Start-Service sshd -ErrorAction Continue } catch { Write-Output ("Start-Service failed: " + ${'$'}_.Exception.Message) }
try {
  if (-not (Get-NetFirewallRule -DisplayName 'Mobile Agent SSH' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'Mobile Agent SSH' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -ErrorAction Continue | Out-String -Width 4096
  }
} catch { Write-Output ("Firewall rule failed: " + ${'$'}_.Exception.Message) }
""".trimIndent()
        } else {
            ""
        }
        return """
${'$'}ErrorActionPreference = 'Continue'
Write-Output "--- sshd service ---"
Get-Service sshd -ErrorAction SilentlyContinue | Select-Object Name,Status,StartType | Format-List | Out-String -Width 4096
Write-Output "--- ssh port ---"
Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,State,OwningProcess | Format-List | Out-String -Width 4096
Write-Output "--- ssh process ---"
${'$'}processIds = @(Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
foreach (${'$'}processId in ${'$'}processIds) {
  Get-Process -Id ${'$'}processId -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,Path,StartTime | Format-List | Out-String -Width 4096
}
Write-Output "--- firewall ssh rules ---"
Get-NetFirewallRule -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.DisplayName -match 'ssh|OpenSSH|Mobile Agent' } | Select-Object DisplayName,Enabled,Direction,Action,Profile | Format-Table -AutoSize | Out-String -Width 4096
Write-Output "--- tailscale status ---"
try { tailscale status } catch { Write-Output ("tailscale status failed: " + ${'$'}_.Exception.Message) }
Write-Output "--- tailscale ip ---"
try { tailscale ip -4 } catch { Write-Output ("tailscale ip failed: " + ${'$'}_.Exception.Message) }
Write-Output "--- local ip addresses ---"
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object InterfaceAlias,IPAddress,PrefixLength | Format-Table -AutoSize | Out-String -Width 4096
$fixBlock
Write-Output "--- after sshd service ---"
Get-Service sshd -ErrorAction SilentlyContinue | Select-Object Name,Status,StartType | Format-List | Out-String -Width 4096
""".trimIndent()
    }
}
