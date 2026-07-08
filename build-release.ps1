<#
.SYNOPSIS
  Builds a distributable, self-contained Windows app image (exe) for WAJ Wi-Fi Survey.

.DESCRIPTION
  1. Runs the full Maven build (mvnw) to produce a fat/shaded jar.
  2. Uses the JDK bundled under .tools/jdk21 (jpackage) to bundle that jar with a
     trimmed Java runtime into a standalone app-image under dist/<version>/.
  No system-wide JDK, Maven, or WiX installation is required.

.PARAMETER SkipTests
  Skip the test phase during the Maven build.
#>
[CmdletBinding()]
param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

[xml]$pom = Get-Content (Join-Path $root "pom.xml")
$version = $pom.project.version
$artifactId = $pom.project.artifactId

# jpackage --app-version only accepts N, N.N or N.N.N (numeric) - strip -SNAPSHOT etc.
$appVersion = ($version -replace '-SNAPSHOT$', '')
if ($appVersion -notmatch '^[0-9]+(\.[0-9]+){0,2}$') {
    $appVersion = "0.0.1"
}

Write-Host "== Building $artifactId $version (jpackage app-version: $appVersion) ==" -ForegroundColor Cyan

$mvnArgs = @("clean", "package")
if ($SkipTests) { $mvnArgs += "-DskipTests" }
& "$root\mvnw.cmd" @mvnArgs
if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }

$shadedJar = Join-Path $root "target\$artifactId-$version-shaded.jar"
if (-not (Test-Path $shadedJar)) {
    throw "Shaded jar not found at $shadedJar - check the maven-shade-plugin output above."
}

# jpackage bundles everything under --input recursively, so stage only the one
# fat jar rather than pointing it at the whole target/ directory.
$stagingDir = Join-Path $root "target\jpackage-input"
if (Test-Path $stagingDir) { Remove-Item $stagingDir -Recurse -Force }
New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null
Copy-Item $shadedJar -Destination $stagingDir

$appName = "WAJ WiFi Survey"
$distDir = Join-Path $root "dist\$version"
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

$jpackage = Join-Path $root ".tools\jdk21\bin\jpackage.exe"
Write-Host "== Running jpackage ==" -ForegroundColor Cyan
& $jpackage `
    --type app-image `
    --input $stagingDir `
    --dest $distDir `
    --name $appName `
    --main-jar (Split-Path $shadedJar -Leaf) `
    --main-class com.waj.tool.Launcher `
    --app-version $appVersion `
    --vendor "WAJ Tool" `
    --description "TamoGraph-inspired Wi-Fi site survey tool"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (exit $LASTEXITCODE)" }

$appImageDir = Join-Path $distDir $appName
$zipPath = Join-Path $distDir "$($appName -replace ' ', '-')-$version-win64.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path $appImageDir -DestinationPath $zipPath

Write-Host "== Done ==" -ForegroundColor Green
Write-Host "App image : $appImageDir"
Write-Host "Zip       : $zipPath"
Write-Host "Launcher  : $(Join-Path $appImageDir "$appName.exe")"
