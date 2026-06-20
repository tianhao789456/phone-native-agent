$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$VenvPython = Join-Path $Root ".venv\Scripts\python.exe"
$Python = if (Test-Path -LiteralPath $VenvPython) { $VenvPython } else { "python" }

Set-Location -LiteralPath $Root
& $Python -m mobile_agent.hosts.http_server --mock --host 127.0.0.1 --port 8787
