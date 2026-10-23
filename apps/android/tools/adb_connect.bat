@echo off
setlocal
rem Usage: adb_connect.bat [IP:PORT]. Without an endpoint, list devices only.
set "EPRINT_ADB="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "EPRINT_ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined EPRINT_ADB if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "EPRINT_ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined EPRINT_ADB for %%I in (adb.exe) do set "EPRINT_ADB=%%~$PATH:I"
if not defined EPRINT_ADB if exist "%~dp0..\..\..\.tools\android-sdk\platform-tools\adb.exe" set "EPRINT_ADB=%~dp0..\..\..\.tools\android-sdk\platform-tools\adb.exe"
if not defined EPRINT_ADB (
    echo adb.exe not found. Set ANDROID_HOME or add platform-tools to PATH.
    exit /b 1
)
if not "%~1"=="" (
    "%EPRINT_ADB%" connect "%~1"
    if errorlevel 1 exit /b 1
)
"%EPRINT_ADB%" devices -l
exit /b %ERRORLEVEL%
