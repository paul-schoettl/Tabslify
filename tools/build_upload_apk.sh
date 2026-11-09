#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./gradlew :app:assembleDebug
python3 "$ROOT/tools/upload_apk.py" "$@"
