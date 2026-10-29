#!/usr/bin/env bash
# Fetches the prebuilt ncnn (Vulkan) libraries that the native module links against.
# Bash twin of setup-native.ps1, used by CI and on macOS/Linux.
set -euo pipefail
version=20260526
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/cpp/third_party/ncnn"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ -f "$dest/.version" ] && [ "$(cat "$dest/.version")" = "$version" ]; then
  echo "ncnn $version already installed"
  exit 0
fi

curl -fsSL "https://github.com/Tencent/ncnn/releases/download/$version/ncnn-$version-android-vulkan.zip" -o "$tmp/ncnn.zip"
unzip -q "$tmp/ncnn.zip" -d "$tmp"
mkdir -p "$dest"
for abi in arm64-v8a armeabi-v7a x86_64; do
  rm -rf "${dest:?}/$abi"
  cp -R "$tmp/ncnn-$version-android-vulkan/$abi" "$dest/$abi"
done
echo "$version" > "$dest/.version"
echo "ncnn $version installed to $dest"
