$ErrorActionPreference = "Stop"
$path = Join-Path (Get-Location) ".env"
if (-not (Test-Path $path)) { throw "Run this script from the project root; .env was not found." }

$filePath = (Resolve-Path $path).Path
$lines = [System.IO.File]::ReadAllLines($filePath)
$indices = @()
for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match '^\s*MODERATION_SERVICE_URL\s*=') { $indices += $i }
}
if ($indices.Count -eq 0) {
    Write-Host "MODERATION_SERVICE_URL is absent; no change made."
    exit 0
}

# Keep the last declaration, but normalize it even when it is the only declaration.
$keepIndex = $indices[-1]
$keptValue = ($lines[$keepIndex] -split '=', 2)[1].Trim()
if ($keptValue -match '^\[(https?://[^\]]+)\]\((https?://[^)]+)\)$') {
    if ($Matches[1] -eq $Matches[2]) { $keptValue = $Matches[1] }
}
$replacement = "MODERATION_SERVICE_URL=$keptValue"
$filtered = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($indices -contains $i) {
        if ($i -eq $keepIndex) { $filtered.Add($replacement) }
    } else { $filtered.Add($lines[$i]) }
}

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines($filePath, $filtered.ToArray(), $utf8NoBom)
$removed = [Math]::Max(0, $indices.Count - 1)
Write-Host "Normalized MODERATION_SERVICE_URL; removed $removed duplicate declaration(s)." -ForegroundColor Green
