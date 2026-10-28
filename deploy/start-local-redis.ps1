# Start the already-installed Redis service in Ubuntu WSL without Docker.
# Keep WSL alive: systemd services alone do not prevent its idle shutdown.
$ErrorActionPreference = 'Stop'

$keepAlive = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" |
    Where-Object { $_.CommandLine -match '\bUbuntu\b' -and $_.CommandLine -match 'sleep\s+2147483647' } |
    Select-Object -First 1
if (-not $keepAlive) {
    Start-Process -FilePath "$env:SystemRoot\System32\wsl.exe" `
        -ArgumentList @('-d', 'Ubuntu', '--', 'sleep', '2147483647') `
        -WindowStyle Hidden | Out-Null
}

& "$env:SystemRoot\System32\wsl.exe" -d Ubuntu -u root -- systemctl start redis-server
if ($LASTEXITCODE -ne 0) {
    throw 'Could not start redis-server in Ubuntu WSL.'
}

$connection = New-Object System.Net.Sockets.TcpClient
try {
    $pending = $connection.ConnectAsync('127.0.0.1', 6379)
    if (-not $pending.Wait(5000)) { throw 'Redis connection timed out.' }
    $stream = $connection.GetStream()
    $stream.ReadTimeout = 5000
    $ping = [System.Text.Encoding]::ASCII.GetBytes("PING`r`n")
    $stream.Write($ping, 0, $ping.Length)
    $buffer = New-Object byte[] 128
    $received = $stream.Read($buffer, 0, $buffer.Length)
    $response = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $received).Trim()
    if ($response -ne '+PONG') { throw 'Redis did not return PONG.' }
    Write-Output 'Redis ready at localhost:6379 (PONG). Ubuntu WSL is running in the background.'
} finally {
    $connection.Dispose()
}
