#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -r "$work"' EXIT
curl -fLsS --retry 3 https://zenodo.org/records/3370486/files/vocals-c8df74a5.pth -o "$work/standard.pth"
curl -fLsS --retry 3 https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth -o "$work/high.pth"
(
    cd "$work"
    sha256sum -c <<'CHECKSUMS'
c8df74a528f7bd3be90e21e9ec734d43da3a8f9c58cbb95ac5c17b0ecb52b217  standard.pth
b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083  high.pth
CHECKSUMS
)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.pitchline.android mkdir -p files/models
adb shell -T 'run-as com.pitchline.android sh -c "cat > files/models/standard.pth"' < "$work/standard.pth"
adb shell -T 'run-as com.pitchline.android sh -c "cat > files/models/high.pth"' < "$work/high.pth"
./gradlew --no-daemon -PpitchlineEmulator :app:connectedDebugAndroidTest
