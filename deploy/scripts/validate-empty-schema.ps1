param(
    [ValidateSet('17', '18')]
    [string]$PostgresVersion = '18',
    [switch]$Offline
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $projectRoot
$containerName = "aloute-schema-check-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$dbName = "aloute_quality_schema_check"
$dbUser = "aloute_schema_check"
$dbPassword = [Guid]::NewGuid().ToString("N") + "aA9!"
$envNames = @(
    "ALOUTE_SCHEMA_VALIDATION_URL",
    "ALOUTE_SCHEMA_VALIDATION_USERNAME",
    "ALOUTE_SCHEMA_VALIDATION_PASSWORD"
)
$oldValues = @{}
$containerCreated = $false
foreach ($name in $envNames) { $oldValues[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not available. Start Docker Desktop and rerun this script." }

    Write-Host "Starting a disposable PostgreSQL $PostgresVersion container on an available loopback port..."
    docker run -d --rm --name $containerName `
        -e "POSTGRES_DB=$dbName" `
        -e "POSTGRES_USER=$dbUser" `
        -e "POSTGRES_PASSWORD=$dbPassword" `
        -p "127.0.0.1::5432" "postgres:${PostgresVersion}-alpine" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not start disposable PostgreSQL container." }
    $containerCreated = $true
    $binding = (& docker port $containerName 5432/tcp | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not resolve the disposable database loopback port.'
    }
    if ($binding -match '^127\.0\.0\.1:([0-9]+)$') { $port = $Matches[1] }
    else { throw 'The disposable database must be bound to IPv4 loopback only.' }

    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        docker exec $containerName pg_isready -U $dbUser -d $dbName *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw "Disposable PostgreSQL did not become ready. Container: $containerName" }

    $env:ALOUTE_SCHEMA_VALIDATION_URL = "jdbc:postgresql://127.0.0.1:${port}/${dbName}"
    $env:ALOUTE_SCHEMA_VALIDATION_USERNAME = $dbUser
    $env:ALOUTE_SCHEMA_VALIDATION_PASSWORD = $dbPassword

    Write-Host "Checking immutable SQL, fresh schema, upgrades, late compatibility migration and concurrent starts..."
    $migrationMavenArguments = @('--batch-mode', '-Dtest=MigrationHistoryContractTest', '-Dit.test=FreshPostgresSchemaValidationIT,MigrationCompatibilityIT,NotificationOutboxMigrationIT,NotificationDeliveryTransactionIT', '-DargLine=-Duser.timezone=UTC', 'verify')
    if ($Offline) { $migrationMavenArguments = @('-o') + $migrationMavenArguments }
    & .\mvnw.cmd @migrationMavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Fresh PostgreSQL schema validation failed. See Maven output above. The disposable database will be removed."
    }
    Write-Host "PASS: Flyway compatibility and Hibernate validation succeeded on disposable PostgreSQL $PostgresVersion." -ForegroundColor Green
}
finally {
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $oldValues[$name], "Process")
    }
    if ($containerCreated) { docker rm -f $containerName *> $null }
    Pop-Location
}
