@echo off
rem Convenience wrapper: build the APK with the bundled PowerShell build script.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
endlocal
