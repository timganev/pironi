param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$Output,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Platform
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "JAR not found: $Jar"
}

$bundleName = "pironi-$Version-$Platform"
$bundleDir = Join-Path $Output $bundleName
$archive = Join-Path $Output "$bundleName.zip"

if (Test-Path -LiteralPath $bundleDir) {
    Remove-Item -LiteralPath $bundleDir -Recurse -Force
}
New-Item -ItemType Directory -Path $bundleDir -Force | Out-Null

& jlink --add-modules java.se,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --output (Join-Path $bundleDir "runtime")
if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

Copy-Item -LiteralPath $Jar -Destination (Join-Path $bundleDir "pironi.jar")
Copy-Item -LiteralPath "dist/windows/pironi.bat" -Destination (Join-Path $bundleDir "pironi.bat")
Copy-Item -LiteralPath "dist/windows/README-WINDOWS.txt" -Destination (Join-Path $bundleDir "README-WINDOWS.txt")
Copy-Item -LiteralPath "README.md" -Destination (Join-Path $bundleDir "README.md")

if (Test-Path -LiteralPath $archive) {
    Remove-Item -LiteralPath $archive -Force
}
Compress-Archive -LiteralPath $bundleDir -DestinationPath $archive
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$archive.sha256" -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ascii

