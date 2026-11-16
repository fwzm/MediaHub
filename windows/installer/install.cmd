@echo off
rem Double-click entry point for the MediaHub per-user installer.
rem Everything the installer does is in install.ps1; this wrapper only bypasses the default
rem "do not run scripts" policy for this one file, keeps a transcript of the run, and keeps the
rem window open when something fails so the message can be read.
setlocal
set "LOG=%TEMP%\MediaHub-install.log"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Launch > "%LOG%" 2>&1
set "rc=%errorlevel%"
type "%LOG%"
if not "%rc%"=="0" (
  echo.
  echo MediaHub installation failed ^(exit code %rc%^). The full log is %LOG%.
  pause
) else (
  echo.
  echo MediaHub is installed. Uninstall it from Settings ^> Apps ^> Installed apps.
  timeout /t 5 >nul
)
endlocal
