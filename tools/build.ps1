# Builds DeepSeek 用量监测 (DSMonitor) without Gradle/Android Studio.
#
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1
#
# Requirements: JDK 17+ (javac/keytool/java on PATH or JAVA_HOME set) and an Android SDK with
# platform android-34 plus build-tools 35.0.0 (aapt2, d8, zipalign, apksigner).
# Install those with:  sdkmanager --install "platforms;android-34" "build-tools;35.0.0"

param(
    [string]$Sdk = '',
    [string]$BuildTools = '35.0.0',
    [string]$Keystore = '',
    [string]$StorePass = 'dsmonitor',
    [string]$KeyAlias = 'dsmonitor',
    [string]$VersionName = '1.2.6',
    [int]$VersionCode = 43,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $Sdk) {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    $candidates += 'C:\Android\Sdk'
    $candidates += 'F:\Android\sdk'
    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'platforms\android-34\android.jar')) { $Sdk = $candidate; break }
    }
    if (-not $Sdk -and $candidates.Count -gt 0) { $Sdk = $candidates[0] }
}

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$app = Join-Path $root 'app'
$res = Join-Path $app 'src\main\res'
$src = Join-Path $app 'src\main\java'
$manifest = Join-Path $app 'src\main\AndroidManifest.xml'
$build = Join-Path $root '.build'
$dist = Join-Path $root 'dist'
$bt = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
if (-not $Keystore) { $Keystore = Join-Path $root 'keystore\dsmonitor.jks' }
$keystore = $Keystore
$apk = Join-Path $dist "DSMonitor-$VersionName.apk"

foreach ($tool in @('aapt2.exe', 'd8.bat', 'zipalign.exe', 'apksigner.bat')) {
    if (-not (Test-Path (Join-Path $bt $tool))) { throw "missing $tool in $bt" }
}
if (-not (Test-Path $androidJar)) { throw "missing $androidJar (install platforms;android-34)" }

New-Item -ItemType Directory -Force -Path $build, $dist, (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null
if (Test-Path "$build\classes") { Remove-Item "$build\classes" -Recurse -Force; New-Item -ItemType Directory -Force -Path "$build\classes" | Out-Null }

Write-Host '[1/7] aapt2 compile'
& (Join-Path $bt 'aapt2.exe') compile --dir $res -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '[2/7] aapt2 link'
& (Join-Path $bt 'aapt2.exe') link -o "$build\base.apk" -I $androidJar --manifest $manifest -R "$build\res.zip" `
    --java "$build\gen" --min-sdk-version 23 --target-sdk-version 34 `
    --version-code $VersionCode --version-name $VersionName `
    --custom-package com.pigeoncookie.dsmonitor --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[3/7] javac'
$sources = @(Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName })
$sources += Join-Path $build 'gen\com\pigeoncookie\dsmonitor\R.java'
$sources | Set-Content "$build\sources.txt" -Encoding ascii
javac -nowarn --release 11 -classpath $androidJar -d "$build\classes" "@$build\sources.txt"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[4/7] d8 (dex)'
# The class list is packed into one archive first: passing ~90 paths to d8.bat overflows cmd's
# command-line limit on Windows.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$classArchive = Join-Path $build 'classes.jar'
if (Test-Path $classArchive) { Remove-Item $classArchive -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory("$build\classes", $classArchive,
    [System.IO.Compression.CompressionLevel]::Optimal, $false)
if (Test-Path "$build\dex") { Remove-Item "$build\dex" -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\dex" | Out-Null
& (Join-Path $bt 'd8.bat') --release --min-api 23 --lib $androidJar --output "$build\dex" $classArchive
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[5/7] package classes.dex into the APK'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item "$build\base.apk" "$build\app-unsigned.apk" -Force
$zip = [System.IO.Compression.ZipFile]::Open("$build\app-unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
$es = $entry.Open()
$fs = [System.IO.File]::OpenRead("$build\dex\classes.dex")
$fs.CopyTo($es); $fs.Close(); $es.Close(); $zip.Dispose()

Write-Host '[6/7] zipalign'
& (Join-Path $bt 'zipalign.exe') -f -p 4 "$build\app-unsigned.apk" "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '[7/7] sign'
if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $keystore) | Out-Null
    Write-Host "      creating a local keystore (alias $KeyAlias)"
    keytool -genkeypair -keystore $keystore -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $StorePass -keypass $StorePass `
        -dname 'CN=DS Monitor, OU=Dev, O=PigeonCookie, L=Beijing, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
& (Join-Path $bt 'apksigner.bat') sign --ks $keystore --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" `
    --ks-key-alias $KeyAlias --out $apk "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& (Join-Path $bt 'apksigner.bat') verify $apk

if (-not $SkipTests) {
    Write-Host 'running core logic tests'
    $tests = Join-Path $build 'tests'
    if (Test-Path $tests) { Remove-Item $tests -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tests | Out-Null
    $coreSources = @(Get-ChildItem (Join-Path $src 'com\pigeoncookie\dsmonitor\core\*.java') | ForEach-Object { $_.FullName })
    $coreSources += @(Join-Path $src 'com\pigeoncookie\dsmonitor\data\HourlyCache.java')
    javac -nowarn --release 11 -classpath $androidJar -d $tests ($coreSources + (Join-Path $root 'tools\jvm-tests\CoreTest.java'))
    if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }
    java -cp $tests com.pigeoncookie.dsmonitor.core.CoreTest
    if ($LASTEXITCODE -ne 0) { throw 'core tests failed' }
}

$size = [math]::Round((Get-Item $apk).Length / 1KB, 1)
Write-Host ''
Write-Host "APK  -> $apk ($size KB)"
Write-Host "install with: adb install -r `"$apk`""
exit 0
