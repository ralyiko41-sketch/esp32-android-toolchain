# ESP32 Android-Native Toolchain Integration

## Overview

This guide explains how to build and integrate a GCC toolchain that runs natively on Android ARM64 using GitHub Actions.

## The Problem

The standard ESP32 GCC toolchain from Espressif is compiled for glibc (Linux desktop) and crashes on Android with:
```
fork/exec .../xtensa-esp32-elf-gcc: no such file or directory
```
or
```
exec format error
```

## Solution: Canadian Cross Compilation

We build a toolchain that:
- **Host**: `aarch64-linux-android` (runs on Android ARM64 devices)
- **Target**: `xtensa-esp32-elf` (generates code for ESP32)
- **Linked against**: Android NDK Bionic libc

## GitHub Actions Workflow

The workflow `.github/workflows/build-toolchain.yml`:

1. Runs on `ubuntu-latest` (GitHub Actions free tier)
2. Installs Android NDK r26
3. Downloads GCC 8.4.0 + Espressif xtensa patches
4. Builds GCC/binutils with `--host=aarch64-linux-android`
5. Combines with ESP32 target libraries from original toolchain
6. Packages as `toolchain-android-arm64.tar.gz`

## Running the Workflow

```bash
# Via GitHub web UI
Actions > Build ESP32 Android Toolchain > Run workflow

# Or via CLI
gh workflow run build-toolchain.yml
```

## Extracting the Built Artifact

After workflow completes, download the artifact and extract:

```bash
# Download from Actions or releases
wget https://github.com/esp32ide/ESP32IDE-Android/releases/download/toolchain/toolchain-android-arm64.tar.gz

# Extract on desktop for inspection
tar -xzf toolchain-android-arm64.tar.gz

# Verify structure
ls -la xtensa-esp32-elf/bin/
# Should show: xtensa-esp32-elf-gcc, xtensa-esp32-elf-g++, xtensa-esp32-elf-gcc-ar, etc.

# Verify Android compatibility
file xtensa-esp32-elf/bin/xtensa-esp32-elf-gcc
# Should show: ELF 64-bit LSB executable, ARM aarch64, dynamically linked (Bionic)
```

## Integrating into Android App

### Option A: Bundle in app assets

```
app/src/main/assets/
└── toolchain/
    └── xtensa-esp32-elf/
        ├── bin/
        ├── lib/
        ├── include/
        └── share/
```

### Option B: Download at runtime (recommended)

Your GitHub repo URL for the release:

```kotlin
url = "https://github.com/YOUR_USERNAME/ESP32IDE-Android/releases/download/toolchain/toolchain-android-arm64.tar.gz",
```

Replace `YOUR_USERNAME` with your actual GitHub username or organization name.

## Key Changes to ArduinoCompiler.kt

1. **Updated `ToolDef`** to point to Android-native toolchain URL
2. **Modified extraction** to handle the nested structure
3. **Enhanced permissions** for xtensa-specific binaries

## Required Android Permissions

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

## Testing the Toolchain

```kotlin
// In your Kotlin code or via adb shell
adb shell
cd /data/data/com.esp32ide/files/arduino-data/packages/esp32/tools/xtensa-esp32-elf/esp-2021r2-patch5/bin
./xtensa-esp32-elf-gcc --version
```

Should output version info without crash.

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `no such file or directory` | glibc binary on Android | Use Android-native toolchain |
| `exec format error` | Wrong architecture | Ensure arm64 toolchain used |
| `cannot locate cc1` | libexec not found | Check toolchain structure |
| `linker not found` | Missing ld | Rebuild binutils with NDK |

## Alternative: Using Termux Packages

If Canadian Cross build fails, an alternative is to use pre-built Termux packages:
```bash
# Termux provides gcc, binutils for aarch64 Android
pkg install gcc binutils
# But these target Android, not ESP32 - still need xtensa patches
```

## Next Steps

1. Run the GitHub Actions workflow
2. Upload the artifact as a release
3. Update the URL in `ArduinoCompiler.kt` if using different release tag
4. Test compilation on device