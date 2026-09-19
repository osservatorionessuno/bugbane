# Integration tests

Black-box end-to-end test of the real (minified release) app on emulators, driven
through the UI with [Maestro] — no instrumentation, so it exercises exactly what ships.
It runs the whole user journey and verifies the result off-device:

**pair** (wireless debugging) → **import custom IOCs** (Settings, system file picker) →
**acquire** → **export** (encrypted `.zip.age`) → **set password** → host-side
**decrypt + verify** → **MVT cross-check** with the same feed + custom indicator files.

## Layout

| file | role |
|------|------|
| `run.sh` | orchestrator: install, run the flows, and the few host steps that can't be UI (scrape the pairing code / passphrase, open the notification shade, pull + verify the archive) |
| `maestro/pair.yaml` | onboarding → open the Settings pairing dialog (leaves the 6-digit code on screen) |
| `maestro/connect-acquire.yaml` | enter the code in bugbane's notification → import custom IOCs → acquire → check the custom detection → export (one flow, so the wireless connection can't drop between pairing and acquisition) |
| `maestro/import-iocs.yaml` | Settings → Custom indicators → import `e2e-iocs.stix2` through the system file picker; asserts the row's name, count and SHA-256 |
| `maestro/check-custom-ioc.yaml` | open the analysis and assert the custom set's planted marker is among the detections |
| `fixtures/e2e-iocs.stix2` | the custom set: a file-path IOC (`/data/local/tmp/bugbane-e2e/custom-marker.so`, planted by `run.sh`) and the fixture APK's app id |
| `maestro/set-password.yaml` | set the mandatory acquisition password |
| `scrape.py` | pull the pairing code / passphrase out of `maestro hierarchy` |
| `verify_export.py` | `pyrage`-decrypt the archive and assert its contents |
| `mvt_crosscheck.py` | run upstream `mvt-android check-androidqf` on the decrypted export with the bundled feed and the custom file; every planted IOC must be flagged |

## Analyst mode over Wi-Fi (two emulators)

`analyst.sh` runs the analyst journey with two emulators on one host: A onboards as an
analyst, opens the scan wizard, forms its Wi-Fi Direct group and shows the pairing QR;
B turns on Wireless debugging and pairs by QR; A pairs, connects, acquires B and exports.

Emulators on one host share a virtual Wi-Fi network, so the group, mDNS discovery,
SPAKE2 pairing and the TLS connection are the real ones. Two physical steps are staged:
B does not need to join the group (it is already on the same network), and B "scans"
the QR through its virtual-scene camera — the host decodes the QR from A's screen
(`zbarimg`), re-encodes it (`qrencode`), hangs it on the scene's wall poster
(`adb emu virtualscene-image wall`) and plays the emulator's built-in
`Walk_to_image_room` macro so the poster fills the camera view.

| file | role |
|------|------|
| `analyst.sh` | orchestrator for the two devices (Maestro `--device`), QR relay, export verification |
| `maestro/analyst-onboard.yaml` | analyst onboarding: role slide, PIN-gated password, home |
| `maestro/analyst-wifi.yaml` | wizard → Wi-Fi → group up → pairing QR on screen |
| `maestro/target-wireless-qr.yaml` | target: Wireless debugging on, QR scanner open |
| `maestro/analyst-export.yaml` | confirm the connected device, start the acquisition, wait, export |

`verify_export.py --transport wifi_direct` additionally asserts the index records the
acquired device and the `DIRECT-bb-bugbane-…` network it was reached through.

```sh
# two booted emulators; the target with -camera-back virtualscene
./integration/analyst.sh emulator-5554 emulator-5556 app-production-release.apk suspicious-apk-debug.apk
```

## Run locally

```sh
gradle assembleProductionRelease                 # build the APK
pip install -r integration/requirements.txt      # host: pyrage
# start an emulator, then:
ANDROID_SERIAL=emulator-5554 ./integration/run.sh \
  app/build/outputs/apk/production/release/app-production-release.apk
```

Needs `maestro` and a JDK on `PATH`. Artifacts (screenshots, logcat, the archive) land
in `integration/artifacts/`.

## CI

`.github/workflows/integration-tests.yml` builds the APK and runs `run.sh` on the
`android-emulator-runner` across API 30–36 — `google_apis` (userdebug) on 30–32 where
adbd trusts a single wireless key, `google_apis_playstore` (secure) on 33+.
The `analyst-wifi` job boots two `google_apis` emulators itself and runs `analyst.sh`
on API 34–36, where the emulator's QR pairing scanner and virtual-scene camera work.

[Maestro]: https://maestro.mobile.dev
