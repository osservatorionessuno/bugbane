"""Cross-check bugbane's exported archive against upstream MVT.

Decrypt the age export, unzip it as-is, and run `mvt-android check-androidqf` on it with
the same indicator files bugbane used (its bundled feed plus any set the test imported
through Settings). bugbane writes artifacts in androidqf shape (files.json, packages.json,
mounts.json, root_binaries.json, ps.txt, ...), so no conversion is needed: the decrypted
archive itself is what MVT consumes. This proves the export is MVT-native and that MVT
independently corroborates bugbane's detections.

CLI: mvt_crosscheck.py <file.zip.age> <passphrase> -i <indicators.stix2> [-i ...]
                       -e <expected-ioc-substring> [-e ...]
Every -e value must be matched (directly or via a prefix IOC such as its directory).
"""

import argparse
import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile

import pyrage

# MVT catches each module's exception, logs it, and still exits 0; scan its output so a
# module that crashes on bugbane's data (e.g. a missing field) fails the cross-check.
MODULE_ERRORS = ("Error when checking indicators", "Error when serializing", "Error when running")


def mvt_matches(acq_dir, stix_files, out_dir):
    """Run MVT and return every matched_indicator value across the _detected.json files."""
    cmd = ["mvt-android", "check-androidqf", acq_dir, "-o", out_dir, "-n"]
    for stix in stix_files:
        cmd += ["-i", stix]
    proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
    log = proc.stdout + proc.stderr
    bad = [ln for ln in log.splitlines() if any(m in ln for m in MODULE_ERRORS)]
    if bad:
        raise AssertionError("MVT module error on bugbane artifacts:\n" + "\n".join(bad))
    matches = []
    for name in os.listdir(out_dir):
        if not name.endswith("_detected.json"):
            continue
        for det in json.load(open(os.path.join(out_dir, name))):
            mi = det.get("matched_indicator")
            if mi:
                matches.append(mi.get("value", ""))
    return matches


def main(path, passphrase, stix_files, expected_values):
    plaintext = pyrage.passphrase.decrypt(open(path, "rb").read(), passphrase)
    with tempfile.TemporaryDirectory() as tmp:
        acq = os.path.join(tmp, "acq")
        zipfile.ZipFile(io.BytesIO(plaintext)).extractall(acq)
        matches = mvt_matches(acq, stix_files, os.path.join(tmp, "out"))
    for expected in expected_values:
        # The planted file is flagged either directly or via a prefix IOC (e.g. its dir).
        hit = [m for m in matches if expected in m or m in expected]
        if not hit:
            raise AssertionError("MVT did not flag %r; matched: %s" % (expected, matches))
        print("MVT cross-check OK: %r matched %s" % (expected, hit), flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("archive")
    ap.add_argument("passphrase")
    ap.add_argument("-i", "--indicators", action="append", required=True, help="STIX2 file (repeatable)")
    ap.add_argument("-e", "--expect", action="append", required=True, help="IOC value MVT must flag (repeatable)")
    args = ap.parse_args()
    main(args.archive, args.passphrase, args.indicators, args.expect)
