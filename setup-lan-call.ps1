[CmdletBinding()]
param(
    [string]$LanIp = $env:ALOUTE_LAN_IP,
    [ValidateRange(1024,65535)][int]$Port = 8080,
    [switch]$ForceNewCertificate,
    [switch]$TrustCertificate,
    [switch]$PrepareOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($LanIp)) {
    $networks = @(Get-NetIPConfiguration | Where-Object {
        $_.IPv4Address -and $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' -and
        $_.InterfaceAlias -notmatch '(?i)VMware|VirtualBox|WSL|Hyper-V|vEthernet|Loopback|Bluetooth'
    })
    $LanIp = @($networks | ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -notmatch '^(127\.|169\.254\.)' } | Select-Object -First 1)[0]
}
$parsedIp = $null
if (-not [System.Net.IPAddress]::TryParse($LanIp, [ref]$parsedIp) -or
    $parsedIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'Connect to LAN/Wi-Fi or pass -LanIp with this computer''s IPv4 address.'
}
if (-not (Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq $LanIp)) {
    throw 'LanIp must be assigned to this computer.'
}
if (-not (Get-Command New-SelfSignedCertificate -ErrorAction SilentlyContinue)) {
    throw 'Run this helper using Windows PowerShell with the PKI module installed.'
}

$certDir = Join-Path $projectRoot 'dev-certs'
$keystorePath = Join-Path $certDir 'aloute-lan.p12'
$caCertPath = Join-Path $certDir 'aloute-lan-ca.cer'
$passwordPath = Join-Path $certDir 'keystore-password.txt'
$ipPath = Join-Path $certDir 'lan-ip.txt'
$existing = (Test-Path -LiteralPath $keystorePath) -and (Test-Path -LiteralPath $caCertPath) -and
    (Test-Path -LiteralPath $passwordPath) -and (Test-Path -LiteralPath $ipPath)
if ($existing -and (Get-Content -LiteralPath $ipPath -Raw).Trim() -ne $LanIp -and -not $ForceNewCertificate) {
    throw 'LAN address changed. Review the new IP, then pass -ForceNewCertificate and re-trust the new public CA on each device.'
}
if (-not $existing -or $ForceNewCertificate) {
    New-Item -ItemType Directory -Path $certDir -Force | Out-Null
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 32
        $random.GetBytes($bytes)
        $password = [BitConverter]::ToString($bytes).Replace('-', '')
    } finally { $random.Dispose() }
    $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
    $ca = New-SelfSignedCertificate -Type Custom -Subject 'CN=Aloute LAN Development CA' `
        -FriendlyName 'Aloute LAN Development CA' -CertStoreLocation 'Cert:\CurrentUser\My' `
        -KeyAlgorithm RSA -KeyLength 3072 -KeyExportPolicy Exportable `
        -KeyUsage CertSign, CRLSign, DigitalSignature `
        -TextExtension @('2.5.29.19={critical}{text}ca=true&pathlength=0') -NotAfter (Get-Date).AddYears(2)
    $leaf = New-SelfSignedCertificate -Type Custom -Subject "CN=Aloute LAN $LanIp" `
        -FriendlyName 'Aloute LAN HTTPS' -CertStoreLocation 'Cert:\CurrentUser\My' -Signer $ca `
        -KeyAlgorithm RSA -KeyLength 2048 -KeyExportPolicy Exportable -KeyUsage DigitalSignature, KeyEncipherment `
        -TextExtension @("2.5.29.17={text}IPAddress=$LanIp&IPAddress=127.0.0.1&DNS=localhost", '2.5.29.37={text}1.3.6.1.5.5.7.3.1') `
        -NotAfter (Get-Date).AddDays(365)
    Export-PfxCertificate -Cert $leaf -FilePath $keystorePath -Password $securePassword | Out-Null
    Export-Certificate -Cert $ca -FilePath $caCertPath | Out-Null
    Set-Content -LiteralPath $passwordPath -Value $password -NoNewline
    Set-Content -LiteralPath $ipPath -Value $LanIp -NoNewline
}
if ($TrustCertificate) {
    Import-Certificate -FilePath $caCertPath -CertStoreLocation 'Cert:\CurrentUser\Root' | Out-Null
}
Write-Host "LAN URL: https://${LanIp}:${Port}"
Write-Host "Public CA: $caCertPath"
Write-Host 'Trust this public CA on both devices before calling. Share only the CER, never the P12 or password.'
Write-Host "Allow inbound TCP $Port on your private LAN if needed. This script does not change firewall rules."
if ($PrepareOnly) { return }

# Process-local overrides. Existing database, Redis and moderation configuration stays in place.
$env:SPRING_PROFILES_ACTIVE = 'lan-call'
$env:SPRING_DOCKER_COMPOSE_ENABLED = 'false'
$env:SERVER_PORT = "$Port"
$env:ALOUTE_LAN_KEYSTORE = ([System.Uri](Get-Item -LiteralPath $keystorePath).FullName).AbsoluteUri
$env:ALOUTE_LAN_KEYSTORE_PASSWORD = (Get-Content -LiteralPath $passwordPath -Raw).Trim()
$env:APP_CORS_ALLOWED_ORIGINS = "https://${LanIp}:${Port},https://localhost:${Port}"
$env:FRONTEND_BASE_URL = "https://${LanIp}:${Port}"
$env:APP_SECURITY_COOKIE_SECURE = 'true'
$env:APP_WEBSOCKET_BROKER_RELAY_ENABLED = 'false'
$env:APP_CALLS_DISTRIBUTED_STATE_ENABLED = 'false'
$env:RATE_LIMIT_REDIS_REQUIRED = 'false'
Push-Location $projectRoot
try {
    if (Get-Command mvn -ErrorAction SilentlyContinue) { & mvn spring-boot:run }
    else {
        # The wrapper invokes powershell.exe by name, including when this script runs in pwsh.
        $env:PATH = "$env:SystemRoot\System32\WindowsPowerShell\v1.0;$env:PATH"
        & (Join-Path $projectRoot 'mvnw.cmd') spring-boot:run
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
