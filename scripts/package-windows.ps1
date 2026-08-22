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

& jlink --add-modules java.se,jdk.crypto.ec,jdk.management --strip-debug --no-header-files --no-man-pages --output (Join-Path $bundleDir "runtime")
if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

Copy-Item -LiteralPath $Jar -Destination (Join-Path $bundleDir "pironi.jar")
# Records which build this is. Without it a user cannot tell a reported bug from a stale
# copy, and neither can we.
[IO.File]::WriteAllText((Join-Path $bundleDir "version.txt"), $Version, (New-Object Text.UTF8Encoding($false)))
Copy-Item -LiteralPath "dist/windows/pironi.bat" -Destination (Join-Path $bundleDir "pironi.bat")
Copy-Item -LiteralPath "dist/windows/README-WINDOWS.txt" -Destination (Join-Path $bundleDir "README-WINDOWS.txt")
Copy-Item -LiteralPath "README.md" -Destination (Join-Path $bundleDir "README.md")
# Every directory under skills/ ships. Naming them one at a time meant a new skill had to be
# added here and in package-unix.sh, and one missing from either shipped on a single platform -
# which is how the Unix bundle came to carry a skill its launcher never read.
if (Test-Path -LiteralPath "skills") {
    $skillsTarget = Join-Path $bundleDir ".pironi\skills"
    New-Item -ItemType Directory -Path $skillsTarget -Force | Out-Null
    Get-ChildItem -LiteralPath "skills" -Directory | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $skillsTarget -Recurse -Force
    }
    Write-Host "bundled skills: $((Get-ChildItem -LiteralPath $skillsTarget -Directory).Count)"
}

if (Test-Path -LiteralPath $archive) {
    Remove-Item -LiteralPath $archive -Force
}
Compress-Archive -LiteralPath $bundleDir -DestinationPath $archive
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$archive.sha256" -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ascii
