@echo off
setlocal enabledelayedexpansion

echo ==========================================
echo       Beam Cross-Platform Builder
echo ==========================================
echo.

set OUTPUT_DIR=build_output
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

:: Fetch dynamic version from git (matches GitHub Release flow)
for /f "tokens=*" %%a in ('git describe --tags --always --dirty') do set FULL_VERSION=%%a
set VERSION=!FULL_VERSION:v=!

:: Extract clean numeric version for AssemblyVersion (e.g. 0.1.5)
for /f "tokens=1 delims=-" %%v in ("!VERSION!") do set BASE_VERSION=%%v
if "!BASE_VERSION!"=="" set BASE_VERSION=0.0.0

echo Building version: !FULL_VERSION! (Base: !BASE_VERSION!)
echo.

echo [1/2] Building Windows Application (.exe)...
:: Enabled verbose output for better debugging
dotnet publish Beam.Windows/Beam.Windows.csproj -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true /p:IncludeNativeLibrariesForSelfExtract=true /p:Version=!BASE_VERSION! /p:InformationalVersion=!FULL_VERSION! -o "%OUTPUT_DIR%\windows_temp" -v n
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Windows build failed.
    exit /b %ERRORLEVEL%
)
move /Y "%OUTPUT_DIR%\windows_temp\Beam.Windows.exe" "%OUTPUT_DIR%\" >nul
rmdir /S /Q "%OUTPUT_DIR%\windows_temp"
echo.

echo [2/2] Building Android Application (.apk)...
cd Beam.Android
:: Enabled stacktrace and info logging for better debugging
call gradlew.bat assembleDebug -PversionName=!FULL_VERSION! --stacktrace --info
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Android build failed. 
    cd ..
    exit /b %ERRORLEVEL%
)
cd ..
copy /Y "Beam.Android\app\build\outputs\apk\debug\app-debug.apk" "%OUTPUT_DIR%\Beam.Android.apk" >nul
echo.

echo ==========================================
echo               Build Complete!
echo ==========================================
echo Output directory: %~dp0%OUTPUT_DIR%
echo.
echo Found:
dir /b "%OUTPUT_DIR%\*.exe" "%OUTPUT_DIR%\*.apk"
echo.
pause
