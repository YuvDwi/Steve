# Release Build Script for Steve AI Mod (Windows 11 x64)
# PowerShell version with enhanced features

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Steve AI Mod - Release Build Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Function to check command success
function Test-LastCommand {
    param([string]$ErrorMessage)
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: $ErrorMessage" -ForegroundColor Red
        exit 1
    }
}

# Check Java installation
Write-Host "[1/5] Checking Java installation..." -ForegroundColor Yellow
try {
    $javaVersion = java -version 2>&1 | Select-Object -First 1
    Write-Host "✓ $javaVersion" -ForegroundColor Green

    # Verify Java 17+
    if ($javaVersion -match "version `"(\d+)") {
        $majorVersion = [int]$matches[1]
        if ($majorVersion -lt 17) {
            Write-Host "ERROR: Java 17 or later required. Found: Java $majorVersion" -ForegroundColor Red
            Write-Host "Download from: https://adoptium.net/" -ForegroundColor Yellow
            exit 1
        }
    }
} catch {
    Write-Host "ERROR: Java is not installed or not in PATH" -ForegroundColor Red
    Write-Host "Download from: https://adoptium.net/" -ForegroundColor Yellow
    exit 1
}
Write-Host ""

# Check Gradle wrapper
Write-Host "[2/5] Checking Gradle wrapper..." -ForegroundColor Yellow
if (-not (Test-Path "gradlew.bat")) {
    Write-Host "ERROR: gradlew.bat not found. Ensure you're in the project root directory." -ForegroundColor Red
    exit 1
}
Write-Host "✓ Gradle wrapper found" -ForegroundColor Green
Write-Host ""

# Clean previous build
Write-Host "[3/5] Cleaning previous build artifacts..." -ForegroundColor Yellow
& .\gradlew.bat clean --no-daemon
Test-LastCommand "Clean failed"
Write-Host "✓ Clean completed" -ForegroundColor Green
Write-Host ""

# Build release JAR
Write-Host "[4/5] Building release JAR (this may take a few minutes)..." -ForegroundColor Yellow
$buildStart = Get-Date
& .\gradlew.bat build --no-daemon
Test-LastCommand "Build failed. Check error messages above."
$buildEnd = Get-Date
$buildDuration = ($buildEnd - $buildStart).TotalSeconds
Write-Host "✓ Build completed in $([math]::Round($buildDuration, 1)) seconds" -ForegroundColor Green
Write-Host ""

# Verify build output
Write-Host "[5/5] Verifying build output..." -ForegroundColor Yellow
$jarPath = "build\libs\steve-ai-mod-1.0.0.jar"

if (Test-Path $jarPath) {
    $jarFile = Get-Item $jarPath
    $jarSizeMB = [math]::Round($jarFile.Length / 1MB, 2)

    Write-Host "✓ Release build successful!" -ForegroundColor Green
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "BUILD OUTPUT" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "File:     $jarPath" -ForegroundColor White
    Write-Host "Size:     $jarSizeMB MB" -ForegroundColor White
    Write-Host "Created:  $($jarFile.LastWriteTime)" -ForegroundColor White
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "INSTALLATION INSTRUCTIONS" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "1. Copy the JAR file to your Minecraft mods folder:" -ForegroundColor White
    Write-Host "   $env:APPDATA\.minecraft\mods\" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "2. Ensure you have:" -ForegroundColor White
    Write-Host "   - Minecraft 1.20.1" -ForegroundColor Yellow
    Write-Host "   - Forge 47.2.0" -ForegroundColor Yellow
    Write-Host "   - API key configured in config/steve-common.toml" -ForegroundColor Yellow
    Write-Host ""

    # Optional: Open build folder
    $openFolder = Read-Host "Open build folder? (Y/N)"
    if ($openFolder -eq "Y" -or $openFolder -eq "y") {
        Start-Process "build\libs"
    }

    # Optional: Create release package
    Write-Host ""
    $createPackage = Read-Host "Create distribution ZIP package? (Y/N)"
    if ($createPackage -eq "Y" -or $createPackage -eq "y") {
        Write-Host "Creating distribution package..." -ForegroundColor Yellow

        $releaseDir = "release"
        if (Test-Path $releaseDir) {
            Remove-Item $releaseDir -Recurse -Force
        }
        New-Item -ItemType Directory -Path $releaseDir | Out-Null

        Copy-Item $jarPath -Destination $releaseDir
        Copy-Item "README.md" -Destination $releaseDir
        Copy-Item "BUILD_WINDOWS.md" -Destination $releaseDir
        if (Test-Path "LICENSE") {
            Copy-Item "LICENSE" -Destination $releaseDir
        }

        $zipPath = "steve-ai-mod-1.0.0-windows-x64.zip"
        if (Test-Path $zipPath) {
            Remove-Item $zipPath -Force
        }

        Compress-Archive -Path "$releaseDir\*" -DestinationPath $zipPath
        Write-Host "✓ Distribution package created: $zipPath" -ForegroundColor Green

        $zipFile = Get-Item $zipPath
        $zipSizeMB = [math]::Round($zipFile.Length / 1MB, 2)
        Write-Host "  Package size: $zipSizeMB MB" -ForegroundColor White
    }

} else {
    Write-Host "ERROR: Build artifact not found at $jarPath" -ForegroundColor Red
    Write-Host "The build may have failed. Check the error messages above." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Build process completed successfully!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
