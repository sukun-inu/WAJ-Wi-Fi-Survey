<#
.SYNOPSIS
  Builds a distributable, self-contained Windows app image (exe) for OpenSiteSurvey.

.DESCRIPTION
  1. Runs the full Maven build (mvnw) to produce a fat/shaded jar.
  2. Uses the JDK bundled under .tools/jdk21 (jpackage) to bundle that jar with a
     trimmed Java runtime into a standalone app-image under dist/<version>/.
  3. With -Msi, also produces a single-file .msi installer. jpackage needs WiX
     Toolset's candle.exe/light.exe for this - rather than requiring a system-wide
     WiX install, this script downloads the portable WiX v3.11 binaries zip (no
     installer of its own) into .tools/wix on first use, the same "vendor it
     locally instead of installing system-wide" approach already used for the
     JDK and Maven under .tools/. That download needs network access; skip -Msi
     if you only want the (default, network-free) app-image build.

.PARAMETER SkipTests
  Skip the test phase during the Maven build.

.PARAMETER Msi
  Also build a .msi installer (in addition to the app-image), via a vendored WiX Toolset.
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Msi
)

# Fixed so every version's MSI shares one upgrade code - without this, jpackage generates a
# random one per build and Windows would treat each version as an unrelated app (installing
# side-by-side, no clean upgrade) instead of upgrading in place.
$wixUpgradeCode = "5a05334e-e304-4445-b5b0-70848cbe7659"
$wixVersion = "3.11.1"
$wixZipUrl = "https://github.com/wixtoolset/wix3/releases/download/wix3111rtm/wix311-binaries.zip"

function Ensure-Wix {
    param([string]$RootDir)
    $wixDir = Join-Path $RootDir ".tools\wix"
    $candle = Join-Path $wixDir "candle.exe"
    if (Test-Path $candle) {
        return $wixDir
    }
    Write-Host "== Downloading WiX Toolset v$wixVersion binaries (one-time, into .tools/wix) ==" -ForegroundColor Cyan
    New-Item -ItemType Directory -Path $wixDir -Force | Out-Null
    $zipPath = Join-Path $RootDir "target\wix311-binaries.zip"
    New-Item -ItemType Directory -Path (Split-Path $zipPath) -Force | Out-Null

    # GitHub's release-asset redirect target has been observed to 504 transiently - retry a few
    # times with a short backoff rather than failing the whole release build over a blip.
    $lastError = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -Uri $wixZipUrl -OutFile $zipPath -UseBasicParsing -TimeoutSec 60
            $lastError = $null
            break
        } catch {
            $lastError = $_
            Write-Host "  download attempt $attempt/3 failed: $($_.Exception.Message)" -ForegroundColor Yellow
            if ($attempt -lt 3) { Start-Sleep -Seconds 5 }
        }
    }
    if ($lastError) {
        throw "Failed to download WiX Toolset after 3 attempts: $lastError"
    }
    Expand-Archive -Path $zipPath -DestinationPath $wixDir -Force
    Remove-Item $zipPath -Force
    if (-not (Test-Path $candle)) {
        throw "WiX download/extract succeeded but candle.exe is still missing from $wixDir"
    }
    return $wixDir
}

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

$appName = "OpenSiteSurvey"
$iconPath = Join-Path $root "icon\icon.ico"
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
    --main-class com.opensitesurvey.tool.Launcher `
    --app-version $appVersion `
    --vendor "OpenSiteSurvey" `
    --description "Wi-Fi site survey and monitoring tool" `
    --icon $iconPath
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (exit $LASTEXITCODE)" }

$appImageDir = Join-Path $distDir $appName
$zipPath = Join-Path $distDir "$($appName -replace ' ', '-')-$version-win64.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path $appImageDir -DestinationPath $zipPath

$msiPath = $null
if ($Msi) {
    # A fixed upgrade code only lets Windows Installer upgrade in place when the new MSI's
    # ProductVersion is strictly greater than what's installed - jpackage's generated WiX
    # Upgrade-table rows exclude the equal-version boundary, and jpackage also randomizes
    # ProductCode on every run, so rebuilding -Msi again at the *same* $appVersion (e.g. a
    # pom.xml still on 0.1.0-SNAPSHOT across two dev builds) would neither upgrade nor block -
    # it would register as a second, unrelated Programs-and-Features entry. Warn up front
    # rather than let that surprise show up only after installing.
    try {
        $existing = Get-Package -Name $appName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($existing -and $existing.Version -eq $appVersion) {
            Write-Host "WARNING: '$appName' $appVersion is already installed. Windows Installer will NOT upgrade it in place from an MSI built at the same version - bump the version in pom.xml first if this MSI is meant to replace it." -ForegroundColor Yellow
        }
    } catch {
        # best-effort check only - Get-Package's provider can be unavailable/slow on some
        # systems, and that's not worth failing the whole release build over.
    }

    $wixDir = Ensure-Wix -RootDir $root
    Write-Host "== Running jpackage (--type msi) ==" -ForegroundColor Cyan
    # jpackage shells out to WiX's own candle.exe/light.exe rather than taking a path to them
    # directly, so they need to be discoverable on PATH for this call.
    $env:PATH = "$wixDir;$env:PATH"
    & $jpackage `
        --type msi `
        --input $stagingDir `
        --dest $distDir `
        --name $appName `
        --main-jar (Split-Path $shadedJar -Leaf) `
        --main-class com.opensitesurvey.tool.Launcher `
        --app-version $appVersion `
        --vendor "OpenSiteSurvey" `
        --description "Wi-Fi site survey and monitoring tool" `
        --icon $iconPath `
        --win-menu `
        --win-shortcut `
        --win-dir-chooser `
        --win-upgrade-uuid $wixUpgradeCode
    if ($LASTEXITCODE -ne 0) { throw "jpackage --type msi failed (exit $LASTEXITCODE)" }
    $msiPath = Join-Path $distDir "$appName-$appVersion.msi"
}

Write-Host "== Done ==" -ForegroundColor Green
Write-Host "App image : $appImageDir"
Write-Host "Zip       : $zipPath"
Write-Host "Launcher  : $(Join-Path $appImageDir "$appName.exe")"
if ($msiPath) {
    Write-Host "MSI       : $msiPath"
} else {
    Write-Host "(Run with -Msi to also produce a .msi installer.)"
}
