$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root   = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Sdk    = Join-Path $Root 'sdk'
$Dl     = Join-Path $Root 'dl'
$CtZip  = Join-Path $Dl 'cmdline-tools.zip'
$CtUrl  = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$CtHome = Join-Path $Root 'cmdline-tools\latest'

New-Item -ItemType Directory -Force -Path $Dl, $Sdk | Out-Null

if (-not (Test-Path $CtZip)) {
    Write-Host "[1/4] downloading commandline-tools ..."
    Invoke-WebRequest -Uri $CtUrl -OutFile $CtZip
} else {
    Write-Host "[1/4] commandline-tools zip already present"
}
Write-Host ("      size = {0:N1} MB" -f ((Get-Item $CtZip).Length / 1MB))

if (-not (Test-Path (Join-Path $CtHome 'bin\sdkmanager.bat'))) {
    Write-Host "[2/4] extracting ..."
    $tmp = Join-Path $Root 'ct-tmp'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $CtZip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path $CtHome | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools\*') $CtHome -Force
    Remove-Item $tmp -Recurse -Force
} else {
    Write-Host "[2/4] commandline-tools already extracted"
}

$SdkManager = Join-Path $CtHome 'bin\sdkmanager.bat'

Write-Host "[3/4] accepting licenses ..."
$yes = ("y`r`n" * 60)
$yes | & $SdkManager --sdk_root="$Sdk" --licenses 2>&1 | Select-Object -Last 5

Write-Host "[4/4] installing platforms;android-34 build-tools;34.0.0 platform-tools ..."
& $SdkManager --sdk_root="$Sdk" --install 'platforms;android-34' 'build-tools;34.0.0' 2>&1 | Select-Object -Last 20

Write-Host "=== result ==="
Test-Path (Join-Path $Sdk 'platforms\android-34\android.jar')
Test-Path (Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe')
Test-Path (Join-Path $Sdk 'build-tools\34.0.0\d8.bat')
Test-Path (Join-Path $Sdk 'build-tools\34.0.0\apksigner.bat')
Write-Host "SETUP-DONE"
