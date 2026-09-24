# Real-app UI automation with Appium

`device_smoke.py` drives the installed Wallet app on an Android emulator/device using Appium's UiAutomator2 driver. These are black-box UI tests: they use the real APK, real backend configuration, and the signed-in app state; they do not call app internals or use fake services. The runner keeps app data (`noReset=true`) and does not submit transaction or budget forms.

## One-time setup (Windows / PowerShell)

Prerequisites: Node.js/npm, Python 3, Android Studio with an Android SDK and an AVD, and the Wallet APK installed on the target. Appium also needs `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and `JAVA_HOME` in its process environment. In this workspace they are:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Install Appium and its Android driver once:

```powershell
npm install --global appium@3.7.0
appium driver install uiautomator2
appium driver doctor uiautomator2
```

The doctor should report zero required fixes. `bundletool`, `ffmpeg`, and GStreamer checks are optional for this test suite.

Create a local Python environment and install the pinned client:

```powershell
python -m venv .venv-appium
.\.venv-appium\Scripts\python.exe -m pip install -r scripts/requirements-appium.txt
```

## Run tests

Use two PowerShell terminals. In **Terminal 1**, set paths and start the Appium server:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
appium --address 127.0.0.1 --port 4723
```

Leave it running. In **Terminal 2**, boot/unlock the emulator, verify its serial, install the APK if needed, and run the suite:

```powershell
adb devices -l
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5554
```

Run extended input checks and all Statistics tabs with `--extended`. Repeat only one group with `--only budget` or `--only statistics`; launch remains a prerequisite. For example:

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5554 --extended
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5554 --only statistics
```

The runner exits with code 0 when every executed assertion passes, and nonzero on a failure or blocked launch. Inspect each failure's screenshot and XML before deciding whether it is an app defect, a test locator issue, or an environment/setup issue. A passing run covers only listed UI cases, not every SMS or financial business-rule scenario.

## Evidence and safety

Screenshots, accessibility XML, `session.json`, and `results.json` are saved under `app/build/device-smoke/<timestamp>/`. Evidence can show real balances and SMS content; do not share it publicly. The app remains signed in and existing data is retained. Budget forms are cancelled without saving. Do not replay the SMS corpus or use bulk import against a personal account. A debug APK test is not release certification.

See [SCENARIOS.md](SCENARIOS.md) for requirements-based coverage and unexecuted cases. Sample SMS files are fixtures, not expected results; use an isolated account and controlled emulator messages for write-path testing.
