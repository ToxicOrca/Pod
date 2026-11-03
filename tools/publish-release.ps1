<#
.SYNOPSIS
  Builds a signed release APK and publishes it to your Pod server, where every
  family phone offers it as a one-tap update.

.EXAMPLE
  .\tools\publish-release.ps1 -Notes "Map themes, driving speed, recovery kit"

  Before the first run:
  - android\keystore.properties points at your release key (and sets your app ID).
  - Copy tools\publish.config.example.ps1 to tools\publish.config.ps1 (never committed)
    and fill in your server. Two ways to publish:
      * Remote: set $PublishHost. The APK and latest.json are copied with scp to
        <host>:upload/ and $RemoteCommand is run over ssh to install them.
      * Local:  leave $PublishHost empty and set $LocalAppDir to the server's
        deploy/app folder (for example a mounted share).
  - Bump versionCode (+1) and versionName in android\app\build.gradle.kts.
#>
param(
    [Parameter(Mandatory = $true)][string]$Notes
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"

# Server details live in a private, git-ignored config file.
$config = Join-Path $PSScriptRoot "publish.config.ps1"
if (-not (Test-Path $config)) { throw "tools\publish.config.ps1 not found. Copy tools\publish.config.example.ps1 and fill it in." }
$PublishHost = ""; $RemoteCommand = ""; $LocalAppDir = ""; $PublicUrl = ""; $ExpectedAppId = ""
. $config
if (-not $PublicUrl) { throw "Set `$PublicUrl in tools\publish.config.ps1" }
if (-not $ExpectedAppId) { throw "Set `$ExpectedAppId in tools\publish.config.ps1 (the app ID your phones have installed)" }

if (-not (Test-Path (Join-Path $android "keystore.properties"))) {
    throw "android\keystore.properties not found. Releases must be signed with your release key, or phones will refuse the update."
}

Write-Host "==> Building signed release APK"
Push-Location $android
try {
    & .\gradlew.bat --no-daemon assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }

$outDir = Join-Path $android "app\build\outputs\apk\release"
$meta = Get-Content (Join-Path $outDir "output-metadata.json") -Raw | ConvertFrom-Json
$el = $meta.elements[0]
if ($el.outputFile -like "*unsigned*") { throw "The APK is unsigned. Check keystore.properties." }
$apkPath = Join-Path $outDir $el.outputFile
# A different app ID would install as a separate app instead of updating (phones would refuse it anyway).
if ([string]$meta.applicationId -ne $ExpectedAppId) {
    throw "Built app ID is '$($meta.applicationId)', expected '$ExpectedAppId'. Check applicationId in android\keystore.properties."
}
$versionCode = [int]$el.versionCode
$versionName = [string]$el.versionName
$apkName = "pod-$versionName.apk"

# Refuse to publish something phones would ignore.
$current = $null
try { $current = Invoke-RestMethod -Uri "$PublicUrl/app/latest.json" -TimeoutSec 10 } catch { Write-Host "    (no previous release on the server)" }
if ($current -and [int]$current.versionCode -ge $versionCode) {
    throw "Server already has versionCode $($current.versionCode). Bump versionCode in android\app\build.gradle.kts first."
}

$sha = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLower()
$manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    notes       = $Notes
    apk         = $apkName
    sha256      = $sha
} | ConvertTo-Json

$tmp = Join-Path $env:TEMP "pod-publish"
New-Item -ItemType Directory -Force $tmp | Out-Null
Copy-Item $apkPath (Join-Path $tmp $apkName) -Force
# UTF-8 without BOM (a BOM would break the app's JSON parser).
[IO.File]::WriteAllText((Join-Path $tmp "latest.json"), $manifest)

if ($PublishHost) {
    Write-Host "==> Uploading $apkName ($versionName, code $versionCode) to $PublishHost"
    scp (Join-Path $tmp $apkName) (Join-Path $tmp "latest.json") "${PublishHost}:upload/"
    if ($LASTEXITCODE -ne 0) { throw "scp failed" }
    ssh $PublishHost "$RemoteCommand $apkName"
    if ($LASTEXITCODE -ne 0) { throw "Publishing on the server failed" }
} elseif ($LocalAppDir) {
    Write-Host "==> Copying $apkName ($versionName, code $versionCode) to $LocalAppDir"
    # APK first, manifest last, so phones never see a manifest pointing at a missing file.
    Copy-Item (Join-Path $tmp $apkName) (Join-Path $LocalAppDir $apkName) -Force
    Copy-Item (Join-Path $tmp "latest.json") (Join-Path $LocalAppDir "latest.json") -Force
} else {
    throw "Set `$PublishHost or `$LocalAppDir in tools\publish.config.ps1"
}

Write-Host "==> Published. Phones will offer the update within a few hours (or right away when Pod is opened)."
Write-Host "    Check: $PublicUrl/app/latest.json"
