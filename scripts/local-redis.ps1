param(
    [ValidateSet('start', 'status', 'stop')]
    [string]$Action = 'start'
)

# Workstation helper. The existing user-owned WSL launcher validates process ownership.
# Does not install software, modify application settings, or delete Redis data.
$ErrorActionPreference = 'Stop'
$redisLauncherCommand = 'exec "$HOME/.local/bin/aloute-redis" ' + $Action
$redisLauncherOutput = @(& wsl.exe -d Ubuntu -- sh -lc $redisLauncherCommand 2>&1)
$redisLauncherExit = $LASTEXITCODE
if ($redisLauncherExit -ne 0) {
    throw 'Local Redis could not complete the requested action. See docs/quality/redis-local-recovery-report.md.'
}

# redis-cli may exit successfully with LOADING while Redis replays its existing AOF.
# A successful launch is not readiness: wait for PONG before a caller starts the backend.
if ($Action -eq 'start') {
    $redisReadinessTimer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($redisLauncherOutput -notcontains 'PONG' -and $redisReadinessTimer.Elapsed.TotalSeconds -lt 20) {
        Start-Sleep -Milliseconds 500
        $redisLauncherOutput = @(& wsl.exe -d Ubuntu -- sh -lc 'exec "$HOME/.local/bin/aloute-redis" status' 2>&1)
        $redisLauncherExit = $LASTEXITCODE
    }
}
if ($Action -in @('start', 'status')) {
    if ($redisLauncherExit -ne 0 -or $redisLauncherOutput -notcontains 'PONG') {
        throw 'Local Redis has not become ready. Check its local log before retrying.'
    }
    Write-Output 'PONG'
} else {
    $redisLauncherOutput | Write-Output
}
