# Installs the ferry server (web UI included) on Windows.
#
#   irm https://raw.githubusercontent.com/manosriram/ferry/main/install.ps1 | iex
#
# Options (environment variables): FERRY_VERSION (default latest), FERRY_BIN_DIR,
# FERRY_REPO (default manosriram/ferry), FERRY_BASE_URL (mirror / testing).
$ErrorActionPreference = 'Stop'

$repo = if ($env:FERRY_REPO) { $env:FERRY_REPO } else { 'manosriram/ferry' }
$version = if ($env:FERRY_VERSION) { $env:FERRY_VERSION } else { 'latest' }
$dir = if ($env:FERRY_BIN_DIR) { $env:FERRY_BIN_DIR } else { Join-Path $env:LOCALAPPDATA 'ferry' }
$asset = 'ferry-windows-amd64.exe' # Windows on ARM runs it through x64 emulation

$base = if ($env:FERRY_BASE_URL) { $env:FERRY_BASE_URL }
        elseif ($version -eq 'latest') { "https://github.com/$repo/releases/latest/download" }
        else { "https://github.com/$repo/releases/download/$version" }

$tmp = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "Downloading $asset ($version)..."
    Invoke-WebRequest "$base/$asset" -OutFile "$tmp\ferry.exe" -UseBasicParsing
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile "$tmp\SHA256SUMS" -UseBasicParsing

    $line = Get-Content "$tmp\SHA256SUMS" | Where-Object { ($_ -split '\s+')[1] -in @($asset, "*$asset") }
    if (-not $line) { throw "$asset is not listed in SHA256SUMS" }
    $expected = ($line -split '\s+')[0].ToLower()
    $actual = (Get-FileHash "$tmp\ferry.exe" -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) { throw "checksum mismatch for $asset; not installing" }

    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Move-Item -Force "$tmp\ferry.exe" "$dir\ferry.exe"
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($userPath -split ';') -contains $dir)) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$dir", 'User')
    Write-Host "Added $dir to your PATH (open a new terminal to use it)."
}

Write-Host ""
Write-Host "Installed $(& "$dir\ferry.exe" -version) to $dir\ferry.exe"
Write-Host "Run it:  ferry -addr :8080 -data $env:LOCALAPPDATA\ferry\data"
Write-Host "then open http://localhost:8080 and dock your devices."
