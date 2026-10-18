# Fetches the prebuilt ncnn (Vulkan) libraries that the native module links against.
# Models (app/src/main/assets/models) and fonts are committed; ncnn is ~60 MB so it is not.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '20260526'
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root 'app\src\main\cpp\third_party\ncnn'
$tmp = Join-Path $env:TEMP "ncnn-$version"
New-Item -ItemType Directory -Force $tmp, $dest | Out-Null

$zip = Join-Path $tmp 'ncnn.zip'
Invoke-WebRequest "https://github.com/Tencent/ncnn/releases/download/$version/ncnn-$version-android-vulkan.zip" -OutFile $zip
Expand-Archive $zip $tmp -Force
foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64') {
    $target = Join-Path $dest $abi
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Copy-Item (Join-Path $tmp "ncnn-$version-android-vulkan\$abi") $target -Recurse
}
Write-Host "ncnn $version installed to $dest"
