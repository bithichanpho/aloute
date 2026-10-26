$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $projectRoot
$containerName = "aloute-schema-check-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$port = 55439
$dbName = "aloute_schema_check"
$dbUser = "aloute_schema_check"
$dbPassword = [Guid]::NewGuid().ToString("N") + "aA9!"
$envNames = @(
    "ALOUTE_SCHEMA_VALIDATION_URL",
    "ALOUTE_SCHEMA_VALIDATION_USERNAME",
    "ALOUTE_SCHEMA_VALIDATION_PASSWORD"
)
$oldValues = @{}
foreach ($name in $envNames) { $oldValues[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not available. Start Docker Desktop and rerun this script." }

    Write-Host "Starting a disposable, empty PostgreSQL container (port $port)..."
    docker run -d --rm --name $containerName `
        -e "POSTGRES_DB=$dbName" `
        -e "POSTGRES_USER=$dbUser" `
        -e "POSTGRES_PASSWORD=$dbPassword" `
        -p "127.0.0.1:${port}:5432" postgres:17-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not start disposable PostgreSQL container." }

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

    Write-Host "Running all Flyway migrations on the empty database, then Hibernate schema validation..."
    & .\mvnw.cmd "-Dsurefire.skip=true" "-Dit.test=FreshPostgresSchemaValidationIT" verify
    if ($LASTEXITCODE -ne 0) {
        throw "Fresh PostgreSQL schema validation failed. See Maven output above. The disposable database will be removed."
    }
    Write-Host "PASS: Flyway migrations and Hibernate ddl-auto=validate succeeded on a fresh PostgreSQL database." -ForegroundColor Green
}
finally {
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $oldValues[$name], "Process")
    }
    docker rm -f $containerName *> $null
    Pop-Location
}
