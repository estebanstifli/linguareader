#!/usr/bin/env bash
# Deja un iPhone del simulador arrancado y escribe su UDID en el fichero indicado. Pensado para correr en segundo
# plano mientras Gradle enlaza el framework (.github/workflows/ios.yml): el primer arranque tarda ~1 min y así no
# cuenta en el tiempo total del job.
# Uso: prepare-simulator.sh <fichero-udid> [nombre del iPhone, por defecto "iPhone 16"]
set -euo pipefail
out="$1"
name="${2:-iPhone 16}"

if ! xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1; then
  echo "Xcode first launch tasks pending; running them"
  sudo xcodebuild -runFirstLaunch
fi

udid=$(xcrun simctl list devices available -j | jq -r --arg n "$name" '.devices | to_entries[] | select(.key | contains("iOS")) | .value[] | select(.name == $n) | .udid' | head -1)
if [ -z "$udid" ]; then
  runtime=$(xcrun simctl list runtimes -j | jq -r '[.runtimes[] | select(.platform == "iOS" and .isAvailable)] | sort_by(.version) | last | .identifier')
  type="com.apple.CoreSimulator.SimDeviceType.${name// /-}"
  echo "No '$name' simulator; creating one on $runtime"
  udid=$(xcrun simctl create "CI $name" "$type" "$runtime")
fi

xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b
xcrun simctl list devices | grep -i "$name" || true
echo "$udid" > "$out"
echo "Simulator '$name' ($udid) is booted"
