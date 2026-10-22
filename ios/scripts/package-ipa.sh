#!/usr/bin/env bash
# Empaqueta la salida de `xcodebuild build-for-testing` (Debug-iphoneos) para AWS Device Farm:
#   LinguaReader.ipa               app (run type «iOS app»)
#   LinguaReaderUITests.ipa        runner de XCTest UI (framework «XCTest UI»)
#   LinguaReaderTests.xctest.zip   bundle de XCTest (framework «XCTest»)
# Uso: package-ipa.sh <Debug-iphoneos> <carpeta de salida>
set -euo pipefail
products="$(cd "$1" && pwd)"
out="$2"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

package_ipa() {
  local app="$1" name="$2"
  rm -rf "$out/Payload"
  mkdir -p "$out/Payload"
  cp -R "$app" "$out/Payload/"
  (cd "$out" && rm -f "$name.ipa" && zip -qry "$name.ipa" Payload)
  rm -rf "$out/Payload"
  echo "→ $out/$name.ipa"
}

package_ipa "$products/LinguaReader.app" LinguaReader
package_ipa "$products/LinguaReaderUITests-Runner.app" LinguaReaderUITests
(cd "$products/LinguaReader.app/PlugIns" && rm -f "$out/LinguaReaderTests.xctest.zip" && zip -qry "$out/LinguaReaderTests.xctest.zip" LinguaReaderTests.xctest)
echo "→ $out/LinguaReaderTests.xctest.zip"
ls -la "$out"
