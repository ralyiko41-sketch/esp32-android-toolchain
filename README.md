# ESP32 IDE — Native Android App v2.0

A fully self-contained ESP32/Arduino IDE for Android.
**No PC server required. Compiles on-device. Flashes via USB.**

---

## Features

| Feature | How it works |
|---|---|
| **Code Editor** | Sora Editor — full C++ syntax highlighting, line numbers, auto-indent |
| **Multi-file tabs** | Room DB — unlimited sketch files, persist across sessions |
| **USB Flash** | Android USB Host API + ESP32 ROM bootloader protocol (pure Kotlin) |
| **Serial Monitor** | Real USB serial via usb-serial-for-android (CH340, CP2102, FTDI, CDC) |
| **Serial Plotter** | MPAndroidChart — live CSV graph from Serial.printf |
| **Compile (offline)** | Downloads arduino-cli ARM64 + ESP32 core once — compiles on your phone forever |
| **Compile (fallback)** | Cloud compile via HTTP if arduino-cli not set up |
| **OTA Flash** | HTTP multipart upload to ArduinoOTA endpoint |
| **Board Manager** | Import boards.txt — loads all 100+ ESP32 variants instantly |
| **Library Manager** | 18 popular libs built-in + add by GitHub URL |
| **Git** | GitHub API — commit, push, clone sketches |
| **Examples** | 11 built-in: Blink, WiFi, MQTT, FreeRTOS, BME280, OTA... |
| **Dark/Light theme** | Toggle in Settings |
| **USB auto-detect** | App opens Flash tab when ESP32 plugged in |

---

## Build Instructions

### Requirements
- **Android Studio Hedgehog** (2023.1.1) or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK 34**
- Internet connection for first Gradle sync

### Step 1 — Open in Android Studio

1. Download and install Android Studio: https://developer.android.com/studio
2. Open Android Studio → **File → Open**
3. Select the `ESP32IDE-Android` folder
4. Wait for Gradle sync to finish (2-5 minutes, downloads dependencies)

### Step 2 — Connect your Android phone

1. On your phone: **Settings → Developer Options → USB Debugging → ON**
2. Connect phone to PC via USB
3. Accept the "Allow USB Debugging" popup on your phone
4. Your phone appears in the device dropdown in Android Studio

### Step 3 — Build & Install

Click the **▶ Run** button (green play button) in Android Studio toolbar

OR via terminal:
```bash
./gradlew installDebug
```

The APK installs directly on your phone.

---

## First Time Setup — Compiler

On first launch, go to **Settings tab** → **Setup Compiler**

This downloads:
- `arduino-cli` ARM64 binary (~15MB)
- ESP32 core + GCC toolchain (~150MB)

**One-time only.** After that, everything compiles 100% offline on your phone.

> Total storage needed: ~200MB in app's internal storage

---

## How to Flash via USB

1. Write or load your sketch in the **Code tab**
2. Tap **COMPILE** — compiles on your phone using arduino-cli
3. Plug your ESP32 into your phone via **OTG USB cable**
4. Go to **Flash tab** — your ESP32 appears automatically
5. Tap **FLASH** — done!

### USB Cable Required
You need a **USB OTG (On-The-Go)** adapter/cable:
- USB-C phone → USB-A ESP32: **USB-C to USB-A OTG cable**
- Or: **USB-C OTG adapter** + regular USB cable

These cost ₹50-200 on Amazon/Flipkart.

---

## Board List — Import boards.txt

Built-in: 37 common ESP32 boards

**For all 100+ boards:**

1. On your PC, find:
   - Windows: `%APPDATA%\Arduino15\packages\esp32\hardware\esp32\<version>\boards.txt`
   - Mac: `~/Library/Arduino15/packages/esp32/hardware/esp32/<version>/boards.txt`
   - Linux: `~/.arduino15/packages/esp32/hardware/esp32/<version>/boards.txt`
2. Send file to phone (WhatsApp, Google Drive, email)
3. In app: **Boards tab → boards.txt tab → Import File**

---

## Serial Monitor

Plug ESP32 via USB OTG → **Monitor tab** → tap **Connect**

Android shows USB permission popup → tap **OK**

Real serial data flows in. Supports all baud rates.

**Serial Plotter:** Send CSV from ESP32:
```cpp
Serial.printf("%d,%.4f\n", raw, voltage);
```
Switch to **Plotter** tab — live graph appears.

---

## OTA Flash (No USB Cable)

Flash the OTA example once via USB, then forever wireless:

1. Load **Examples → OTA Update** sketch
2. Change WiFi credentials, COMPILE + FLASH via USB
3. Open Serial Monitor — note the IP address printed
4. Go to **OTA tab** → enter IP → **Flash via OTA**
5. No USB ever again for that device!

---

## Git Integration

1. Create a GitHub repo
2. **Git tab** → enter Remote URL, Username, Branch
3. Get token: GitHub → Settings → Developer settings → Personal access tokens → `repo` scope
4. **Commit + Push** — all sketches go to GitHub

---

## Project Structure

```
ESP32IDE-Android/
├── app/src/main/
│   ├── java/com/esp32ide/
│   │   ├── MainActivity.kt              # Bottom nav + app host
│   │   ├── ESP32IDEApp.kt               # Application class
│   │   ├── compiler/
│   │   │   ├── ArduinoCompiler.kt       # arduino-cli wrapper (offline compile)
│   │   │   ├── CloudCompiler.kt         # HTTP cloud compile fallback
│   │   │   └── CompilerService.kt       # Background foreground service
│   │   ├── serial/
│   │   │   ├── UsbSerialManager.kt      # USB serial (CH340/CP2102/FTDI/CDC)
│   │   │   └── EspFlasher.kt            # ESP32 ROM bootloader protocol
│   │   ├── data/
│   │   │   ├── SketchDatabase.kt        # Room DB — sketch storage
│   │   │   └── AppPreferences.kt        # SharedPreferences — all settings
│   │   ├── ui/
│   │   │   ├── editor/                  # Code editor + file manager
│   │   │   ├── monitor/                 # Serial monitor + plotter
│   │   │   ├── flash/                   # USB flash screen
│   │   │   ├── boards/                  # Board manager + boards.txt import
│   │   │   ├── libraries/               # Library manager
│   │   │   ├── examples/                # Built-in examples
│   │   │   ├── git/                     # GitHub push/pull
│   │   │   ├── ota/                     # OTA flash
│   │   │   └── settings/                # App settings + compiler setup
│   │   └── utils/
│   │       ├── BoardsParser.kt          # Parse boards.txt
│   │       └── Examples.kt              # Built-in code examples
│   ├── res/                             # Layouts, drawables, colors
│   └── AndroidManifest.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Supported USB Chips (Auto-detected)

| Chip | Used In |
|---|---|
| CH340 / CH341 | Most Chinese ESP32 dev boards |
| CP2102 / CP2104 | NodeMCU, many ESP32 kits |
| FTDI FT232 | Adafruit, SparkFun boards |
| Prolific PL2303 | Some clones |
| CDC ACM | ESP32-S3, ESP32-C3 native USB |

---

## Troubleshooting

**Flash fails "Cannot connect to ROM bootloader":**
- Hold BOOT button while connecting USB
- Use a data USB cable (not charge-only)
- Try 115200 baud in Settings

**No device shown in Flash tab:**
- Check OTG adapter is connected properly
- Grant USB permission when popup appears
- Try a different OTG cable

**Compile fails offline:**
- Go to Settings → Setup Compiler
- Check internet connection during setup
- Needs ~150MB free storage

**Serial Monitor no data:**
- Check baud rate matches your sketch
- Make sure Serial Monitor is disconnected before flashing

---

## License
MIT — free for personal and commercial use.
