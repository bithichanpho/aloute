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

Write-Host 'Sending real text samples to the configured moderation provider...'
Write-Host 'The returned classification is model-dependent; this script reports results instead of assuming a fixed score.'
$samples = @(
    [pscustomobject]@{ Name = 'SAFE'; Text = 'I am sharing a photo from my weekend trip with my family.' },
    [pscustomobject]@{ Name = 'HARASSMENT'; Text = 'You are worthless and everyone should hate you.' },
    [pscustomobject]@{ Name = 'THREAT'; Text = 'I will find you and beat you up.' }
)

foreach ($sample in $samples) {
    $body = @{ text = $sample.Text } | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Method Post -Uri 'http://localhost:8000/moderate' `
        -Headers @{ 'X-Moderation-Token' = $token } `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30

    if ($null -eq $result.label -or $null -eq $result.score) {
        throw ("{0}: moderation returned a result without label or score" -f $sample.Name)
    }
    $score = [double]$result.score
    if ($score -lt 0 -or $score -gt 1) {
        throw ("{0}: moderation returned a score outside the 0..1 range" -f $sample.Name)
    }
    if ($score -ge 0.8) { $status = 'HIDDEN' }
    elseif ($score -ge 0.5) { $status = 'FLAGGED' }
    else { $status = 'APPROVED' }
    Write-Host ("{0}: label={1}; score={2:N4}; appStatus={3}" -f $sample.Name, $result.label, $score, $status)
}

Write-Host ''
Write-Host 'Smoke test completed. This calls the moderation API only; publish a post in the ALOUTE UI to verify the end-to-end database state and worker logs.'
