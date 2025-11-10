@echo off
REM Release Build Script for Steve AI Mod (Windows 11 x64)
REM This script automates the release build process

echo ========================================
echo Steve AI Mod - Release Build Script
echo ========================================
echo.

REM Check if Java is installed
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Java is not installed or not in PATH
    echo Please install Java 17 or later from: https://adoptium.net/
    pause
    exit /b 1
)

echo [1/4] Java version check...
java -version
echo.

echo [2/4] Cleaning previous build...
call gradlew.bat clean --no-daemon
if %errorlevel% neq 0 (
    echo ERROR: Clean failed
    pause
    exit /b 1
)
echo.

echo [3/4] Building release JAR...
call gradlew.bat build --no-daemon
if %errorlevel% neq 0 (
    echo ERROR: Build failed
    echo.
    echo Troubleshooting tips:
    echo - Check your internet connection
    echo - Ensure firewall allows access to Maven repositories
    echo - Try running: gradlew.bat build --refresh-dependencies
    pause
    exit /b 1
)
echo.

echo [4/4] Verifying build output...
if exist "build\libs\steve-ai-mod-1.0.0.jar" (
    echo SUCCESS: Release build completed!
    echo.
    echo Build output: build\libs\steve-ai-mod-1.0.0.jar
    echo File size:
    dir "build\libs\steve-ai-mod-1.0.0.jar" | find "steve-ai-mod"
    echo.
    echo To install: Copy the JAR to your Minecraft mods folder
    echo Location: %%APPDATA%%\.minecraft\mods\
) else (
    echo ERROR: Build artifact not found
    echo Expected: build\libs\steve-ai-mod-1.0.0.jar
    pause
    exit /b 1
)

echo.
echo ========================================
echo Build Complete!
echo ========================================
pause
