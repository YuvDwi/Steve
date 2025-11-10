# Building Steve AI Mod for Windows 11 x64

This guide provides step-by-step instructions for creating a release build of the Steve AI Minecraft mod on Windows 11 x64.

## Prerequisites

### Required Software

1. **Java Development Kit (JDK) 17 or later**
   - Download from: https://adoptium.net/temurin/releases/
   - Select: Windows x64, JDK 17 or 21 (LTS)
   - During installation, ensure "Add to PATH" is selected

2. **Git for Windows** (if cloning from repository)
   - Download from: https://git-scm.com/download/win

### Verification

Open PowerShell or Command Prompt and verify Java installation:

```powershell
java -version
```

You should see output showing Java 17 or later:
```
openjdk version "17.0.x" or "21.0.x"
```

## Building the Mod

### Option 1: Using Windows PowerShell

1. **Open PowerShell** in the project directory

2. **Run the build command:**
   ```powershell
   .\gradlew.bat build
   ```

3. **Wait for the build to complete** (first build may take 5-10 minutes as it downloads dependencies)

### Option 2: Using Command Prompt (cmd)

1. **Open Command Prompt** in the project directory

2. **Run the build command:**
   ```cmd
   gradlew.bat build
   ```

3. **Wait for the build to complete**

## Build Output

After a successful build, you'll find the mod JAR file at:

```
build\libs\steve-ai-mod-1.0.0.jar
```

This JAR file is your **release build** ready for distribution.

## Clean Build

If you need to rebuild from scratch:

```powershell
.\gradlew.bat clean build
```

## Release Build Optimizations

The build is already configured with release optimizations:
- Parallel builds enabled for faster compilation
- Gradle caching enabled
- 3GB JVM heap for optimal build performance

## Installation

To use the built mod:

1. Ensure you have **Minecraft 1.20.1** with **Forge 47.2.0** installed
2. Copy `steve-ai-mod-1.0.0.jar` to your Minecraft `mods` folder:
   ```
   %APPDATA%\.minecraft\mods\
   ```
3. Configure the mod as described in the main README.md

## Troubleshooting

### "JAVA_HOME not set" Error

Set JAVA_HOME environment variable:

1. Open System Properties → Advanced → Environment Variables
2. Add new System Variable:
   - Variable name: `JAVA_HOME`
   - Variable value: `C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot` (or your JDK path)
3. Restart PowerShell/Command Prompt

### "gradlew.bat: command not found"

Ensure you're running the command from the project root directory where `gradlew.bat` is located.

### Build Fails with "OutOfMemoryError"

Increase JVM heap size in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4G
```

### Network/Firewall Issues

If the build fails downloading dependencies:
- Ensure your firewall allows Gradle to access:
  - https://maven.minecraftforge.net
  - https://repo.maven.apache.org
- Try using a VPN or different network if corporate firewall blocks Maven repositories

## Advanced: Creating a Distribution Package

To create a complete distribution package:

```powershell
# Build the mod
.\gradlew.bat build

# Create distribution folder
mkdir release
Copy-Item "build\libs\steve-ai-mod-1.0.0.jar" -Destination "release\"
Copy-Item "README.md" -Destination "release\"
Copy-Item "LICENSE" -Destination "release\" -ErrorAction SilentlyContinue

# Compress to ZIP
Compress-Archive -Path "release\*" -DestinationPath "steve-ai-mod-1.0.0-windows.zip"
```

## System Requirements

### For Building:
- **OS:** Windows 11 x64 (or Windows 10 x64)
- **RAM:** 4GB minimum, 8GB recommended
- **Disk Space:** 2GB for Gradle cache and build artifacts
- **Java:** JDK 17 or later

### For Running the Mod:
- **Minecraft:** 1.20.1
- **Forge:** 47.2.0
- **Java:** JRE/JDK 17 or later
- **API Key:** OpenAI, Groq, or Gemini (configured in mod config)

## Version Information

- **Mod Version:** 1.0.0
- **Minecraft Version:** 1.20.1
- **Forge Version:** 47.2.0
- **Java Version:** 17+
- **ForgeGradle:** 6.x

## Additional Resources

- **Minecraft Forge:** https://files.minecraftforge.net/
- **ForgeGradle Documentation:** https://docs.minecraftforge.net/en/fg-6.x/
- **Main README:** See README.md in project root
- **Issue Tracker:** https://github.com/YuvDwi/Steve/issues

## License

MIT License - See LICENSE file for details
