# Run the existing FastAPI moderation service in Ubuntu WSL without Docker.
[CmdletBinding()]
param(
    [switch]$InstallDependencies,
    [ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$Distribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$wsl = Join-Path $env:SystemRoot 'System32/wsl.exe'
if (-not (Test-Path -LiteralPath $wsl)) { throw 'WSL is not installed.' }

function Test-ModerationHealth {
    try {
        $health = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8000/healthz' -TimeoutSec 2
        return $health.status -eq 'ok' -and $health.provider -eq 'moderation-api'
    } catch { return $false }
}
if (Test-ModerationHealth) {
    Write-Output 'Moderation is ready at http://127.0.0.1:8000.'
    return
}
if ($InstallDependencies) {
    & $wsl -d $Distribution -u root --exec apt-get update
    if ($LASTEXITCODE -ne 0) { throw 'Ubuntu package index update failed.' }
    & $wsl -d $Distribution -u root --exec apt-get install -y python3-venv ffmpeg
    if ($LASTEXITCODE -ne 0) { throw 'Could not install Python venv and FFmpeg in WSL.' }
}

$keepAlive = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" |
    Where-Object { $_.CommandLine -match [regex]::Escape($Distribution) -and $_.CommandLine -match 'sleep\s+2147483647' } |
    Select-Object -First 1
if (-not $keepAlive) {
    Start-Process -FilePath $wsl -ArgumentList @('-d', $Distribution, '--exec', 'sleep', '2147483647') `
        -WindowStyle Hidden | Out-Null
}
$linuxScript = & $wsl -d $Distribution --exec wslpath -a -u (Join-Path $PSScriptRoot 'start-local-moderation.sh')
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the moderation helper path inside WSL.' }
& $wsl -d $Distribution --exec bash $linuxScript.Trim()
if ($LASTEXITCODE -ne 0) { throw 'Moderation startup failed. Check the prerequisite message above.' }
$logDirectory = Join-Path $projectRoot 'target'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$serviceArguments = @('-d', $Distribution, '--exec', 'bash', 'deploy/start-local-moderation.sh', '--serve')
$service = Start-Process -FilePath $wsl -ArgumentList $serviceArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDirectory 'local-moderation.stdout.log') `
    -RedirectStandardError (Join-Path $logDirectory 'local-moderation.log')

$deadline = (Get-Date).AddSeconds(35)
do {
    if (Test-ModerationHealth) {
        Write-Output 'Moderation ready at http://127.0.0.1:8000 (healthz OK). Docker is not required.'
        return
    }
    if ($service.HasExited) { throw 'Moderation exited before becoming healthy. Inspect target/local-moderation.log locally.' }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
throw "Moderation did not become healthy. Inspect target/local-moderation.log locally; do not share credentials."
