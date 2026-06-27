# Android — Build & Test

## One-time installation (WSL / Linux)

### 1. System packages

```bash
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk-headless unzip
```

### 2. Android SDK command-line tools

```bash
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip
unzip cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip
```

### 3. Environment variables

Add to `~/.bashrc` (or `~/.zshrc`):

```bash
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
```

Then reload: `source ~/.bashrc`

### 4. SDK components and licenses

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

---

## Build

From `src/android/`:

```bash
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

First run downloads Gradle 8.7 automatically (~130 MB, one time only).

---

## Deploy to a physical device

### First-time phone setup

1. **Settings > About phone** — tap **Build number** 7 times to enable Developer options
2. **Settings > Developer options** — enable **USB debugging**
3. Connect via USB and accept the "Allow USB debugging" prompt on the phone

### USB forwarding to WSL (one-time Windows install)

WSL2 doesn't see USB devices by default. `usbipd` bridges them in.

1. In an **elevated PowerShell** on Windows, install usbipd:
   ```powershell
   winget install --interactive --exact dorssel.usbipd-win
   ```

2. Each time you want to deploy, run the attach script from an **elevated PowerShell**:
   ```powershell
   .\src\android\attach-device.ps1
   ```
   The script auto-detects your Android device and forwards it to WSL. If auto-detect fails, it prompts for the BUSID from `usbipd list`.

3. Back in WSL, verify the device is visible:
   ```bash
   adb devices
   ```

> **Note:** You need to re-run `attach-device.ps1` each time you reboot Windows or replug the phone.

### Install

```bash
./deploy.sh
```

Or step by step:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Testing

No automated tests yet. After installing:

- Verify **"Hello, World!"** appears centered on screen
- Verify the app is listed as **FiberSocial** in the app drawer

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `JAVA_HOME` not set | Run `source ~/.bashrc` or open a new terminal after editing it |
| `ANDROID_HOME` not set | Same as above |
| Device not detected by `adb` | Run `attach-device.ps1` from elevated PowerShell, then `adb kill-server && adb start-server` in WSL |
| `usbipd attach` succeeds but `adb devices` is empty | Unplug and replug the phone, re-run the attach script |
| Gradle sync fails on first run | Check internet connection — it downloads ~130 MB of Gradle + deps |
| Build error about `androidX` | Ensure `gradle.properties` has `android.useAndroidX=true` |
