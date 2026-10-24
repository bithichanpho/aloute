$ErrorActionPreference = 'Stop'

if (-not (Test-Path .env)) {
    Copy-Item .env.example .env
    Write-Host "Created .env from .env.example. Fill in PostgreSQL/Cloudinary/Mail/JWT values, then run this script again." -ForegroundColor Yellow
    exit 1
}

Write-Host "Building and starting ALOUTE..." -ForegroundColor Cyan
docker compose up -d --build

Write-Host "Containers:" -ForegroundColor Cyan
docker compose ps

Write-Host "Recent application logs:" -ForegroundColor Cyan
docker compose logs --tail=80 app
