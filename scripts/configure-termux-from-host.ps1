param(
  [string]$EnvPath = "",
  [string]$PhoneCtl = ""
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$DefaultEnv = Join-Path $Root ".env"
$RootEnv = if ($EnvPath) { $EnvPath } else { $DefaultEnv }
if (-not (Test-Path -LiteralPath $RootEnv)) {
  throw "Missing $RootEnv"
}

$pairs = @{}
foreach ($line in Get-Content -LiteralPath $RootEnv) {
  if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
    $pairs[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
  }
}

if (-not $pairs.ContainsKey("DEEPSEEK_API_KEY") -and -not $pairs.ContainsKey("OPENAI_API_KEY")) {
  throw "No DEEPSEEK_API_KEY or OPENAI_API_KEY found in $RootEnv"
}

$provider = if ($pairs.ContainsKey("DEEPSEEK_API_KEY")) { "openai_compat" } else { "openai_responses" }
$model = if ($provider -eq "openai_compat") { "deepseek-v4-flash" } else { "gpt-5.4-mini" }
$baseUrl = if ($provider -eq "openai_compat") { "https://api.deepseek.com" } else { "https://api.openai.com/v1" }
$apiKeyName = if ($pairs.ContainsKey("DEEPSEEK_API_KEY")) { "DEEPSEEK_API_KEY" } else { "OPENAI_API_KEY" }
$apiKey = $pairs[$apiKeyName]

$remote = @"
cat > ~/.mobile-agent.env <<'EOF'
$apiKeyName=$apiKey
MOBILE_AGENT_PROVIDER=$provider
MOBILE_AGENT_MODEL=$model
MOBILE_AGENT_BASE_URL=$baseUrl
EOF
chmod 600 ~/.mobile-agent.env
sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' ~/.mobile-agent.env
"@

$PhoneCtlPath = if ($PhoneCtl) {
  $PhoneCtl
} else {
  Join-Path (Split-Path -Parent $Root) "tools\phone-control\phonectl.ps1"
}

if (-not (Test-Path -LiteralPath $PhoneCtlPath)) {
  throw "Missing phonectl script. Pass -PhoneCtl <path> or run this from the companion workspace."
}

powershell -ExecutionPolicy Bypass -File $PhoneCtlPath termux $remote
