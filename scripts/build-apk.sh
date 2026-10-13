#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./android/gradlew -p android assemblePreview --console=plain --max-workers=4
mkdir -p artifacts
cp android/app/build/outputs/apk/preview/app-preview.apk artifacts/tizen-pocket-preview.apk
(cd artifacts && sha256sum tizen-pocket-preview.apk > SHA256SUMS)
