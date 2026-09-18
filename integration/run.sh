#!/usr/bin/env bash
# Bugbane end-to-end integration test, driven entirely through the UI with Maestro.
# Only the host plumbing (install, passphrase scrape, archive pull + decrypt/verify)
# uses adb — every on-device interaction is a Maestro flow.
#
# Runs inside android-emulator-runner's `script:` (the emulator dies when this exits,
# so all evidence is captured before then via the EXIT trap). No `-e`: capture first.
set -uo pipefail

APK="${1:?usage: run.sh <apk> [suspicious-fixture-apk]}"
FIXTURE="${2:-}"
SUSPICIOUS_APPID=org.osservatorionessuno.fixture.suspicious
DIR="$(cd "$(dirname "$0")" && pwd)"
FLOWS="$DIR/maestro"
ART="${INTEGRATION_ARTIFACTS:-$DIR/artifacts}"
mkdir -p "$ART" "$ART/screenshots"
PKG=org.osservatorionessuno.bugbane

echo "sha=${GITHUB_SHA:-unknown} run=${GITHUB_RUN_NUMBER:-?} ref=${GITHUB_REF_NAME:-?}" > "$ART/RUN_INFO.txt"

LOGCAT_PID=""
capture() {
  adb exec-out screencap -p > "$ART/final.png" 2>/dev/null || true
  kill "$LOGCAT_PID" 2>/dev/null || true
  # Maestro drops screenshots/logs per run under ~/.maestro/tests; keep the latest.
  latest="$(ls -dt "$HOME"/.maestro/tests/* 2>/dev/null | head -1)"
  [ -n "$latest" ] && cp -r "$latest" "$ART/maestro-debug" 2>/dev/null || true
  # takeScreenshot saves into each flow's own test dir
  # (~/.maestro/tests/<ts>/<flow>/takeScreenshot/screenshots/); harvest them
  # all into the artifacts, depth-agnostically.
  find "$HOME/.maestro/tests" -path '*/takeScreenshot/screenshots/*.png' \
    -exec cp {} "$ART/screenshots/" \; 2>/dev/null || true
}
trap capture EXIT

run_flow() {  # <flow-file>
  echo "::group::maestro $1"
  maestro test "$FLOWS/$1"
  local rc=$?
  echo "::endgroup::"
  [ "$rc" -eq 0 ] || echo "FLOW FAILED: $1 (rc=$rc)"
  return $rc
}

adb wait-for-device
# Underpowered CI emulators throw "isn't responding" ANR dialogs that cover the UI;
# suppress crash/ANR dialogs device-wide before anything else can raise one.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global stay_on_while_plugged_in 3 || true
adb shell settings put global verifier_verify_adb_installs 0 || true
# Maestro clears the device log at every flow start, so stream it for the whole run.
adb logcat > "$ART/logcat.txt" 2>&1 &
LOGCAT_PID=$!
"$DIR/wait_boot.sh" "$(adb get-serialno)"
# Android 16 throttles notifications when several arrive at once, which can drop
# bugbane's pairing notification; disable it device-wide.
adb shell settings put system notification_cooldown_enabled 0 || true
adb shell settings put system notification_cooldown_all 0 || true
adb install -r -g "$APK"
# A benign "suspicious" APK (accessibility service): the acquisition's Packages module
# must flag it and stage it into the archive, asserted host-side in verify_export.py.
[ -n "$FIXTURE" ] && adb install -r -g "$FIXTURE"

# Plant a known Predator file-path IOC (present in the bundled indicator set) so the
# acquisition captures it; bugbane and MVT must both flag it (cross-check below).
adb shell 'mkdir -p /data/local/tmp/wd && echo planted > /data/local/tmp/wd/pred.so' || true
# A user-imported IOC set on top of the feed: plant its file-path marker and push the
# fixture to Downloads, where import-iocs.yaml picks it via the system file picker.
CUSTOM_IOCS="$DIR/fixtures/e2e-iocs.stix2"
CUSTOM_MARKER=/data/local/tmp/bugbane-e2e/custom-marker.so
adb shell "mkdir -p $(dirname "$CUSTOM_MARKER") && echo planted > $CUSTOM_MARKER" || true
adb push "$CUSTOM_IOCS" /sdcard/Download/e2e-iocs.stix2
adb shell content call --uri content://media/external/file --method scan_file \
  --arg /storage/emulated/0/Download/e2e-iocs.stix2 >/dev/null 2>&1 || true
CUSTOM_SHA="$(shasum -a 256 "$CUSTOM_IOCS" | cut -d' ' -f1)"

# Onboard + open the Settings pairing dialog (6-digit code left on screen).
# Restartable (clears app state): one retry absorbs a transient dialog or a slow start.
run_flow pair.yaml || run_flow pair.yaml || exit 1

# Scrape the pairing code from the dialog, then open the notification shade via adb
# (the swipe gesture misses over the pairing dialog on slow CI emulators) and wait for
# bugbane's pairing notification to post (mDNS resolve can lag).
CODE="$(maestro hierarchy 2>/dev/null | python3 "$DIR/scrape.py" code)"
if [ -z "$CODE" ]; then echo "PAIRING CODE SCRAPE FAILED"; exit 1; fi
echo "pairing code = $CODE"
for _ in $(seq 1 30); do
  adb shell cmd statusbar expand-notifications || true
  if maestro hierarchy 2>/dev/null | grep -qE "Enter pairing code|ADB pairing service|Pairing with ADB"; then break; fi
  sleep 3
done
# Expand bugbane's notification so its inline "Enter pairing code" action shows. Other
# notifications carry the same "Expand" button, so pick the one next to bugbane's title.
for _ in 1 2 3; do
  tree="$(maestro hierarchy 2>/dev/null)"
  echo "$tree" | grep -q "Enter pairing code" && break
  point="$(echo "$tree" | python3 "$DIR/scrape.py" expand)" || break
  adb shell input tap $point; sleep 2
done
# Enter the code + import the custom IOCs + acquire + export in one flow (no relaunch
# gap after pairing, where the wireless connection drops and the app reverts to the
# pair page).
echo "::group::maestro connect-acquire.yaml"
maestro test -e CODE="$CODE" -e IOCS_SHA256="$CUSTOM_SHA" "$FLOWS/connect-acquire.yaml"; rc=$?
echo "::endgroup::"
if [ "$rc" -ne 0 ]; then echo "FLOW FAILED: connect-acquire.yaml (rc=$rc)"; exit 1; fi

# Scrape the one-time passphrase from the still-open dialog before closing it.
PASSPHRASE="$(maestro hierarchy 2>/dev/null | python3 "$DIR/scrape.py" passphrase)"
if [ -z "$PASSPHRASE" ]; then echo "PASSPHRASE SCRAPE FAILED"; exit 1; fi
printf '%s' "$PASSPHRASE" > "$ART/passphrase.txt"

run_flow set-password.yaml || exit 1

# Pull the exported archive (newest first) and verify it host-side.
# The archive is moved into Download a moment after the passphrase dialog shows.
for _ in $(seq 1 10); do
  NAME="$(adb shell 'ls -t /sdcard/Download/' | tr -d '\r' | grep -m1 '\.zip\.age$')"
  [ -n "$NAME" ] && break; sleep 3
done
if [ -z "$NAME" ]; then echo "NO EXPORT IN DOWNLOADS"; exit 1; fi
adb pull "/sdcard/Download/$NAME" "$ART/$NAME"
# --sideloaded: the harness adb-installs exactly bugbane, Maestro's on-device driver
# apps (+ the fixture); any other package the acquisition marks as sideloaded is a
# false positive and fails here.
python3 "$DIR/verify_export.py" "$ART/$NAME" "$PASSPHRASE" ${FIXTURE:+"$SUSPICIOUS_APPID"} \
  --sideloaded "$PKG,dev.mobile.maestro,dev.mobile.maestro.test${FIXTURE:+,$SUSPICIOUS_APPID}" || exit 1

# Cross-check: upstream MVT must independently flag both planted IOCs in the decrypted
# export, fed the same two sets bugbane used (bundled feed + the imported custom file).
cp "$(dirname "$DIR")/app/src/main/assets/bundled-indicators/indicators.json" "$ART/indicators.stix2"
python3 "$DIR/mvt_crosscheck.py" "$ART/$NAME" "$PASSPHRASE" \
  -i "$ART/indicators.stix2" -i "$CUSTOM_IOCS" \
  -e "/data/local/tmp/wd/pred.so" -e "$CUSTOM_MARKER" || exit 1
adb shell "rm -rf /data/local/tmp/wd $(dirname "$CUSTOM_MARKER")" || true

echo "INTEGRATION E2E PASS"
