$ErrorActionPreference = "Stop"

$Health = Invoke-RestMethod -Uri "http://127.0.0.1:8787/health"
$Chat = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8787/chat" `
  -ContentType "application/json" `
  -Body '{"message":"what time is it?"}'

[pscustomobject]@{
  health_ok = $Health.ok
  tools = $Health.tools
  session_id = $Chat.session_id
  tool_trace = $Chat.tool_trace
  message = $Chat.message
} | ConvertTo-Json -Depth 10

