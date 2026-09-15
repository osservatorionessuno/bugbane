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

[Maestro]: https://maestro.mobile.dev
