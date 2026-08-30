"""Cross-check bugbane's exported archive against upstream MVT.

Decrypt the age export, unzip it as-is, and run `mvt-android check-androidqf` on it with
bugbane's own bundled indicators. bugbane writes artifacts in androidqf shape (files.json,
packages.json, mounts.json, root_binaries.json, ps.txt, ...), so no conversion is needed: the
decrypted archive itself is what MVT consumes. This proves the export is MVT-native and that
MVT independently corroborates bugbane's detection.

CLI: mvt_crosscheck.py <file.zip.age> <passphrase> <indicators.stix2> <expected-ioc-substring>
"""

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


def mvt_matches(acq_dir, stix2, out_dir):
    """Run MVT and return every matched_indicator value across the _detected.json files."""
    proc = subprocess.run(["mvt-android", "check-androidqf", acq_dir, "-o", out_dir, "-i", stix2, "-n"],
                          check=False, capture_output=True, text=True)
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


def main(path, passphrase, stix2, expected):
    plaintext = pyrage.passphrase.decrypt(open(path, "rb").read(), passphrase)
    with tempfile.TemporaryDirectory() as tmp:
        acq = os.path.join(tmp, "acq")
        zipfile.ZipFile(io.BytesIO(plaintext)).extractall(acq)
        matches = mvt_matches(acq, stix2, os.path.join(tmp, "out"))
    # The planted file is flagged either directly or via a prefix IOC (e.g. its dir).
    hit = [m for m in matches if expected in m or m in expected]
    if not hit:
        raise AssertionError("MVT did not flag %r; matched: %s" % (expected, matches))
    # Specificity: the planted IOC must be the only match — anything else is a
    # false positive in the indicators or the collection.
    stray = [m for m in matches if m not in hit]
    if stray:
        raise AssertionError("MVT matched indicators beyond the planted %r: %s" % (expected, stray))
    print("MVT cross-check OK: %r matched %s, no stray matches" % (expected, hit), flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
