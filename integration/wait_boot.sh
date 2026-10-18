#!/usr/bin/env bash
# Wait until BOOT_COMPLETED has reached every receiver. sys.boot_completed turns 1
# well before user 0 is unlocked and the broadcast posted, and Settings enables its
# two-pane deep link activity from that broadcast: a Settings deep link issued
# earlier crashes Settings on API 32+ (ActivityNotFoundException for
# SETTINGS_EMBED_DEEP_LINK_ACTIVITY).
serial="${1:?usage: wait_boot.sh <serial>}"
for _ in $(seq 1 90); do
  if adb -s "$serial" shell dumpsys activity users 2>/dev/null | grep -q 'User #0: state=RUNNING_UNLOCKED'; then
    sleep 2  # the broadcast is posted from a handler right after the state flips
    adb -s "$serial" shell am wait-for-broadcast-idle >/dev/null 2>&1
    # SystemUI demo mode: fixed clock, full battery, Wi-Fi, no status icons, so
    # screenshots are stable between runs and clean enough for the website docs.
    adb -s "$serial" shell settings put global sysui_demo_allowed 1
    for cmd in "enter" "clock -e hhmm 1200" "battery -e level 100 -e plugged false" \
               "network -e wifi show -e level 4 -e mobile hide" "notifications -e visible false"; do
      adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command $cmd >/dev/null 2>&1
    done
    exit 0
  fi
  sleep 2
done
echo "user 0 not unlocked after 180s on $serial; continuing"
