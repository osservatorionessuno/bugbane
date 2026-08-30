"""Host-side verification of an exported acquisition: decrypt the age archive
with the scraped passphrase and check the ZIP holds a real acquisition.

CLI: python3 integration/verify_export.py <file.zip.age> <passphrase> [suspicious-appid]
         [--sideloaded a,b,...]

--sideloaded is the exact set of app ids the harness itself installed over adb.
packages.json must mark exactly those as sideloaded (installer "null" and not
system — libmvt's PACKAGES_ADB_INSTALLED predicate); anything else is a false
positive and fails the run.
"""

import argparse
import io
import json
import zipfile

import pyrage

REQUIRED_ENTRIES = ["acquisition.json", "dumpsys.txt", "getprop.txt", "packages.json", "bugreport.zip"]
MIN_BUGREPORT_BYTES = 100_000


def verify(path, passphrase, suspicious_appid=None, sideloaded=None):
    with open(path, "rb") as f:
        ciphertext = f.read()
    plaintext = pyrage.passphrase.decrypt(ciphertext, passphrase)
    z = zipfile.ZipFile(io.BytesIO(plaintext))
    names = z.namelist()
    missing = [e for e in REQUIRED_ENTRIES if e not in names]
    if missing:
        raise AssertionError("missing entries: %s (have: %s)" % (missing, names))
    # The suspicious APK heuristic staged the fixture's APK into the archive.
    if suspicious_appid:
        staged = [n for n in names if n.startswith("apks/") and suspicious_appid in n]
        if not staged:
            apks = [n for n in names if n.startswith("apks/")]
            raise AssertionError("suspicious APK %s not staged; apks/: %s" % (suspicious_appid, apks))
        print("suspicious APK staged: %s" % staged, flush=True)
    index = json.loads(z.read("acquisition.json"))
    if not index.get("uuid"):
        raise AssertionError("acquisition.json has no uuid")
    # D4: the acquirer's own adb key is captured, so analysis can exclude it
    # (libmvt demotes it to LOG instead of flagging the acquirer as an intruder).
    if not index.get("adb_host_public_key"):
        raise AssertionError("acquisition.json missing adb_host_public_key")
    if "adb_host_key.pub" not in names:
        raise AssertionError("export missing adb_host_key.pub artifact")
    # Specificity: the only sideloaded packages must be the ones the harness planted.
    # This is what catches heuristic false positives (e.g. a system package the
    # collection pass mislabels as non-system, as with com.android.privatespace).
    if sideloaded is not None:
        records = json.loads(z.read("packages.json"))
        found = {r.get("name", "") for r in records
                 if r.get("installer") == "null" and not r.get("system")}
        expected = set(sideloaded)
        unexpected = sorted(found - expected)
        missing = sorted(expected - found)
        if unexpected or missing:
            raise AssertionError("sideloaded package set mismatch: unexpected=%s missing=%s (expected exactly %s)"
                                 % (unexpected, missing, sorted(expected)))
        print("sideloaded set exact: %s" % sorted(expected), flush=True)
    bugreport = z.getinfo("bugreport.zip").file_size
    if bugreport < MIN_BUGREPORT_BYTES:
        raise AssertionError("bugreport.zip suspiciously small: %d bytes" % bugreport)
    print("verified %s: %d entries, bugreport.zip=%d bytes, uuid=%s"
          % (path, len(names), bugreport, index["uuid"]), flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("archive")
    ap.add_argument("passphrase")
    ap.add_argument("suspicious_appid", nargs="?", default=None)
    ap.add_argument("--sideloaded", default=None,
                    help="comma-separated exact set of app ids expected sideloaded")
    args = ap.parse_args()
    verify(args.archive, args.passphrase, args.suspicious_appid,
           args.sideloaded.split(",") if args.sideloaded else None)
