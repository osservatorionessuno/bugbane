#!/usr/bin/env bash
# Analyst-mode end-to-end test on two emulators over (virtual) Wi-Fi: emulator A runs
# bugbane as an analyst and acquires emulator B through the Wi-Fi Direct wizard.
#
# Nothing in the app is faked. Emulators on one host share a virtual Wi-Fi network,
# so B sees A's group SSID as reachable, mDNS crosses, and the ADB pairing and TLS
# connection are the real ones. The only stand-ins are for the two physical steps:
#   - B "scans" the pairing QR through its virtual-scene camera, whose wall poster the
#     host sets to the QR decoded from A's screen, then the emulator's built-in
#     "Walk_to_image_room" macro brings the poster into view;
#   - B never joins the group (it's already on the same virtual network).
#
# Usage: analyst.sh <serial-A> <serial-B> <apk> [suspicious-fixture-apk]
# Needs: adb, maestro, zbarimg, qrencode, python3 (+ integration/requirements.txt).
set -uo pipefail

A="${1:?usage: analyst.sh <serial-A> <serial-B> <apk> [fixture-apk]}"
B="${2:?serial of the target emulator}"
APK="${3:?apk}"
FIXTURE="${4:-}"
SUSPICIOUS_APPID=org.osservatorionessuno.fixture.suspicious
DIR="$(cd "$(dirname "$0")" && pwd)"
FLOWS="$DIR/maestro"
ART="${INTEGRATION_ARTIFACTS:-$DIR/artifacts}"
mkdir -p "$ART" "$ART/screenshots"
PKG=org.osservatorionessuno.bugbane
MACRO="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/emulator/resources/macros/Walk_to_image_room"

echo "sha=${GITHUB_SHA:-unknown} run=${GITHUB_RUN_NUMBER:-?} ref=${GITHUB_REF_NAME:-?} A=$A B=$B" > "$ART/RUN_INFO.txt"

LOGCAT_PIDS=()
capture() {
  for s in "$A" "$B"; do
    adb -s "$s" exec-out screencap -p > "$ART/final-$s.png" 2>/dev/null || true
  done
  kill ${LOGCAT_PIDS[@]+"${LOGCAT_PIDS[@]}"} 2>/dev/null || true
  # Maestro keeps per-flow logs and hierarchies under ~/.maestro/tests.
  cp -r "$HOME/.maestro/tests" "$ART/maestro-debug" 2>/dev/null || true
  find "$HOME/.maestro/tests" -path '*/takeScreenshot/screenshots/*.png' \
    -exec cp {} "$ART/screenshots/" \; 2>/dev/null || true
}
trap capture EXIT

flow() {  # <serial> <flow-file> [maestro args...]
  local serial="$1" f="$2"; shift 2
  echo "::group::maestro [$serial] $f"
  maestro --device "$serial" test "$@" "$FLOWS/$f"
  local rc=$?
  echo "::endgroup::"
  [ "$rc" -eq 0 ] || echo "FLOW FAILED: $f on $serial (rc=$rc)"
  return $rc
}

for s in "$A" "$B"; do
  adb -s "$s" wait-for-device
  adb -s "$s" shell settings put global hide_error_dialogs 1 || true
  adb -s "$s" shell settings put global stay_on_while_plugged_in 3 || true
  adb -s "$s" shell svc power stayon true || true
  # Maestro clears the device log at every flow start, so stream it for the whole run.
  adb -s "$s" logcat > "$ART/logcat-$s.txt" 2>&1 &
  LOGCAT_PIDS+=($!)
  "$DIR/wait_boot.sh" "$s"
done

# The idle emulator blanks its screen and shows the swipe keyguard; once the PIN is
# set that keyguard turns secure and the app launches behind it. Dismiss it while it
# is still insecure, and only type the PIN as a fallback while it is showing, so the
# digits never land in an app field.
keyguard_showing() { adb -s "$A" shell dumpsys window | grep -q 'isKeyguardShowing=true'; }
unlock_a() {
  adb -s "$A" shell input keyevent KEYCODE_WAKEUP
  for _ in 1 2 3; do
    keyguard_showing || return 0
    adb -s "$A" shell wm dismiss-keyguard; sleep 2
    keyguard_showing || return 0
    adb -s "$A" shell input swipe 540 1800 540 700; sleep 2
    adb -s "$A" shell input text 1234; adb -s "$A" shell input keyevent KEYCODE_ENTER; sleep 3
  done
  keyguard_showing && echo "WARNING: keyguard still showing on $A"
  return 0
}

# --- analyst (A): the app, a PIN for the lock gate, the Wi-Fi permission --------------
adb -s "$A" install -r -g "$APK"
unlock_a
adb -s "$A" shell locksettings set-pin 1234
unlock_a
# Nearby devices from API 33; fine location is only declared up to API 32.
if [ "$(adb -s "$A" shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]; then
  adb -s "$A" shell pm grant "$PKG" android.permission.NEARBY_WIFI_DEVICES
else
  adb -s "$A" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
fi

# --- target (B): developer options, the fixture and a planted IOC -----------------------
adb -s "$B" shell settings put global development_settings_enabled 1
adb -s "$B" shell settings put global verifier_verify_adb_installs 0 || true
[ -n "$FIXTURE" ] && adb -s "$B" install -r -g "$FIXTURE"
adb -s "$B" shell 'mkdir -p /data/local/tmp/wd && echo planted > /data/local/tmp/wd/pred.so' || true

unlock_a; # Emulators on one host share a virtual Wi-Fi network, but unicast between them can
# take a while to route; prime it both ways and fail loudly if it never does.
ip_of() { adb -s "$1" shell ip -4 -o addr show wlan0 | awk '{print $4}' | cut -d/ -f1 | tr -d '\r'; }
IP_A="$(ip_of "$A")"; IP_B="$(ip_of "$B")"; echo "wlan0: A=$IP_A B=$IP_B"
# Each emulator's netsim daemon runs its own DHCP server from the same pool, so two
# emulators started together can end up with the same address (runs 117/121:
# "Host unreachable" on every pairing attempt). Ask B for a new lease, then give up
# loudly rather than fail later in pairing.
for _ in 1 2 3; do
  [ "$IP_A" != "$IP_B" ] && break
  echo "both emulators hold $IP_A; requesting a new lease on B"
  adb -s "$B" shell svc wifi disable; sleep 3; adb -s "$B" shell svc wifi enable
  for _ in $(seq 1 20); do sleep 2; IP_B="$(ip_of "$B")"; [ -n "$IP_B" ] && break; done
  echo "wlan0: A=$IP_A B=$IP_B"
done
[ "$IP_A" != "$IP_B" ] || { echo "BOTH EMULATORS HOLD $IP_A"; exit 1; }
for _ in $(seq 1 12); do
  adb -s "$B" shell ping -c 1 -W 2 "$IP_A" >/dev/null 2>&1
  adb -s "$A" shell ping -c 1 -W 2 "$IP_B" >/dev/null 2>&1 && { echo "A reaches B"; break; }
  sleep 5
done
adb -s "$A" shell ping -c 2 -W 2 "$IP_B" | tail -2

# Restartable (clears app state): one retry absorbs a transient dialog or a slow start.
unlock_a; flow "$A" analyst-onboard.yaml || { unlock_a; flow "$A" analyst-onboard.yaml; } || exit 1
unlock_a; flow "$A" analyst-wifi.yaml || exit 1

# The pairing QR is on A's screen: decode it, re-encode it, hang it on B's camera wall.
adb -s "$A" exec-out screencap -p > "$ART/pairing-qr.png"
PAYLOAD="$(zbarimg -q --raw "$ART/pairing-qr.png" | grep -m1 '^WIFI:T:ADB;')"
if [ -z "$PAYLOAD" ]; then echo "PAIRING QR DECODE FAILED"; exit 1; fi
echo "pairing payload = ${PAYLOAD%%P:*}P:…"
# The emulator caches poster textures by path, so the file name must be new each run.
POSTER="$ART/poster-$(date +%s).png"
qrencode -s 16 -m 4 -l M -o "$POSTER" "$PAYLOAD"
adb -s "$B" emu virtualscene-image wall "$POSTER"

flow "$B" target-wireless-qr.yaml || exit 1

# The emulator camera sometimes fails to start, and the analyst can miss the target's
# mDNS announcement on the virtual Wi-Fi (run 117: advertised, never seen). Scanning
# again re-announces the pairing service, so rescan until the analyst leaves the QR step.
a_waiting() { maestro --device "$A" hierarchy 2>/dev/null | grep -q 'Waiting for the device to scan the code'; }
paired=""
for attempt in 1 2 3 4; do
  if [ "$attempt" -gt 1 ]; then
    adb -s "$B" shell input keyevent KEYCODE_BACK; sleep 2
    flow "$B" target-qr-reopen.yaml || exit 1
  fi
  adb -s "$B" logcat -c
  adb -s "$B" emu automation play "$MACRO"
  scanned=""
  for _ in $(seq 1 15); do
    sleep 3
    if adb -s "$B" logcat -d | grep -q 'Got Qr pairing code'; then scanned=1; break; fi
  done
  [ -n "$scanned" ] || { echo "QR not scanned on attempt $attempt; reopening the scanner"; continue; }
  for _ in $(seq 1 12); do
    sleep 5
    unlock_a; a_waiting || { paired=1; break; }
  done
  [ -n "$paired" ] && break
  echo "analyst did not react to scan $attempt; rescanning"
done
[ -n "$paired" ] || { echo "ANALYST NEVER LEFT THE QR STEP"; exit 1; }

# A pairs and connects on its own once B advertises, then shows the device to confirm.
unlock_a; flow "$A" analyst-export.yaml || exit 1

PASSPHRASE="$(maestro --device "$A" hierarchy 2>/dev/null | python3 "$DIR/scrape.py" passphrase)"
if [ -z "$PASSPHRASE" ]; then echo "PASSPHRASE SCRAPE FAILED"; exit 1; fi
printf '%s' "$PASSPHRASE" > "$ART/passphrase.txt"

# The archive is moved into Download a moment after the passphrase dialog shows.
for _ in $(seq 1 10); do
  NAME="$(adb -s "$A" shell 'ls -t /sdcard/Download/' | tr -d '\r' | grep -m1 '\.zip\.age$')"
  [ -n "$NAME" ] && break; sleep 3
done
if [ -z "$NAME" ]; then echo "NO EXPORT IN DOWNLOADS"; exit 1; fi
adb -s "$A" pull "/sdcard/Download/$NAME" "$ART/$NAME"
# The acquired device is B. Maestro removes its driver from B when B's flow ends, so
# the only sideloaded package left is the fixture.
python3 "$DIR/verify_export.py" "$ART/$NAME" "$PASSPHRASE" ${FIXTURE:+"$SUSPICIOUS_APPID"} \
  --sideloaded "${FIXTURE:+$SUSPICIOUS_APPID}" --transport wifi_direct || exit 1

cp "$(dirname "$DIR")/app/src/main/assets/bundled-indicators/indicators.json" "$ART/indicators.stix2"
python3 "$DIR/mvt_crosscheck.py" "$ART/$NAME" "$PASSPHRASE" "$ART/indicators.stix2" "/data/local/tmp/wd/pred.so" || exit 1
adb -s "$B" shell 'rm -rf /data/local/tmp/wd' || true

echo "ANALYST WIFI E2E PASS"
