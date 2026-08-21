#!/usr/bin/env bash
# Bugbane end-to-end integration test, driven entirely through the UI with Maestro.
# Only the host plumbing (install, passphrase scrape, archive pull + decrypt/verify)
# uses adb — every on-device interaction is a Maestro flow.
#
# Runs inside android-emulator-runner's `script:` (the emulator dies when this exits,
# so all evidence is captured before then via the EXIT trap). No `-e`: capture first.
set -uo pipefail

APK="${1:?usage: run.sh <apk>}"
DIR="$(cd "$(dirname "$0")" && pwd)"
FLOWS="$DIR/maestro"
ART="${INTEGRATION_ARTIFACTS:-$DIR/artifacts}"
mkdir -p "$ART"
PKG=org.osservatorionessuno.bugbane

echo "sha=${GITHUB_SHA:-unknown} run=${GITHUB_RUN_NUMBER:-?} ref=${GITHUB_REF_NAME:-?}" > "$ART/RUN_INFO.txt"

capture() {
  adb exec-out screencap -p > "$ART/final.png" 2>/dev/null || true
  adb logcat -d > "$ART/logcat.txt" 2>/dev/null || true
  # Maestro drops screenshots/logs per run under ~/.maestro/tests; keep the latest.
  latest="$(ls -dt "$HOME"/.maestro/tests/* 2>/dev/null | head -1)"
  [ -n "$latest" ] && cp -r "$latest" "$ART/maestro-debug" 2>/dev/null || true
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
adb shell settings put global stay_on_while_plugged_in 3 || true
adb shell settings put global verifier_verify_adb_installs 0 || true
# Underpowered CI emulators throw "isn't responding" ANR dialogs that cover the UI;
# suppress crash/ANR dialogs device-wide so they can't block the flows.
adb shell settings put global hide_error_dialogs 1 || true
# Android 16 throttles notifications when several arrive at once, which can drop
# bugbane's pairing notification; disable it device-wide.
adb shell settings put system notification_cooldown_enabled 0 || true
adb shell settings put system notification_cooldown_all 0 || true
adb install -r -g "$APK"

# Onboard + open the Settings pairing dialog (6-digit code left on screen).
run_flow pair.yaml || exit 1

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
# Enter the code + acquire + export in one flow (no relaunch gap after pairing, where
# the wireless connection drops and the app reverts to the pair page).
echo "::group::maestro connect-acquire.yaml"
maestro test -e CODE="$CODE" "$FLOWS/connect-acquire.yaml"; rc=$?
echo "::endgroup::"
if [ "$rc" -ne 0 ]; then echo "FLOW FAILED: connect-acquire.yaml (rc=$rc)"; exit 1; fi

# Scrape the one-time passphrase from the still-open dialog before closing it.
PASSPHRASE="$(maestro hierarchy 2>/dev/null | python3 "$DIR/scrape.py" passphrase)"
if [ -z "$PASSPHRASE" ]; then echo "PASSPHRASE SCRAPE FAILED"; exit 1; fi
printf '%s' "$PASSPHRASE" > "$ART/passphrase.txt"

run_flow set-password.yaml || exit 1

# Pull the exported archive (newest first) and verify it host-side.
NAME="$(adb shell 'ls -t /sdcard/Download/' | tr -d '\r' | grep -m1 '\.zip\.age$')"
if [ -z "$NAME" ]; then echo "NO EXPORT IN DOWNLOADS"; exit 1; fi
adb pull "/sdcard/Download/$NAME" "$ART/$NAME"
python3 "$DIR/verify_export.py" "$ART/$NAME" "$PASSPHRASE" || exit 1

echo "INTEGRATION E2E PASS"
