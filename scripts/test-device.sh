#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
variant=${PITCHLINE_TEST_VARIANT:-compact}
case "$variant" in
    debug|compact) ;;
    *) echo 'PITCHLINE_TEST_VARIANT must be debug or compact' >&2; exit 1 ;;
esac
task_variant="${variant^}"
adb install -r "app/build/outputs/apk/$variant/app-$variant.apk"
# ModelStore.prepare runs in the target app, exercising download and SHA-256
# verification without run-as or debuggable privileges.
./gradlew --no-daemon -PpitchlineEmulator -PpitchlineTestBuildType="$variant" ":app:connected${task_variant}AndroidTest"
