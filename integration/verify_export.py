"""Host-side verification of an exported acquisition: decrypt the age archive
with the scraped passphrase and check the ZIP holds a real acquisition.

CLI: python3 integration/verify_export.py <file.zip.age> <passphrase>
"""

import io
import json
import sys
import zipfile

import pyrage

REQUIRED_ENTRIES = ["acquisition.json", "dumpsys.txt", "getprop.txt", "packages.json", "bugreport.zip"]
MIN_BUGREPORT_BYTES = 100_000


def verify(path, passphrase, suspicious_appid=None):
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
    bugreport = z.getinfo("bugreport.zip").file_size
    if bugreport < MIN_BUGREPORT_BYTES:
        raise AssertionError("bugreport.zip suspiciously small: %d bytes" % bugreport)
    print("verified %s: %d entries, bugreport.zip=%d bytes, uuid=%s"
          % (path, len(names), bugreport, index["uuid"]), flush=True)


if __name__ == "__main__":
    verify(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
