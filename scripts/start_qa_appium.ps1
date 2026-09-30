[int]$Port = 4724
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
& (Join-Path $env:APPDATA "npm\appium.ps1") --address 127.0.0.1 --port $Port
