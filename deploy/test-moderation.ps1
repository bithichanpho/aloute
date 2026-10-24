$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing .env at $envFile" }

$tokenLine = Get-Content -LiteralPath $envFile -Encoding UTF8 | Where-Object { $_ -match '^\s*MODERATION_API_TOKEN\s*=' } | Select-Object -First 1
if (-not $tokenLine) { throw 'MODERATION_API_TOKEN is missing from .env' }
$token = ($tokenLine -split '=', 2)[1].Trim().Trim('"').Trim("'")
if ([string]::IsNullOrWhiteSpace($token)) { throw 'MODERATION_API_TOKEN is empty' }

Write-Host 'Checking moderation container health...'
$health = Invoke-RestMethod -Method Get -Uri 'http://localhost:8000/healthz' -TimeoutSec 5
if ($health.status -ne 'ok') { throw 'Moderation health check failed' }

Write-Host 'Checking authenticated text moderation...'
# ASCII-only text avoids Windows PowerShell 5.1 misreading UTF-8-without-BOM scripts.
$body = @{ text = 'ALOUTE moderation system test.' } | ConvertTo-Json -Compress
$result = Invoke-RestMethod -Method Post -Uri 'http://localhost:8000/moderate' `
    -Headers @{ 'X-Moderation-Token' = $token } `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30

if ($null -eq $result.label -or $null -eq $result.score) {
    throw 'Moderation returned a result without label or score'
}
$score = [double]$result.score
if ($score -lt 0 -or $score -gt 1) {
    throw 'Moderation returned a score outside the 0..1 range'
}
Write-Host ("Moderation endpoint OK. label={0}; score={1}" -f $result.label, $score)
