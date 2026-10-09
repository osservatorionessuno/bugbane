"""Cross-check that bugbane's decrypted acquisition.json imports into Colander.

acquisition.json doubles as a Colander feed. Load it the way Colander's feed import
does (ColanderFeed.load with reset_ids), turn each artifact into the SHA256 observable
Colander creates for it, and check the feed lists exactly the artifacts in hashes.csv
(command.log aside), all extracted from the one acquired device.

CLI: colander_crosscheck.py <file.zip.age> <passphrase>
"""

import io
import json
import sys
import zipfile

import pyrage
from colander_data_converter.base.models import Artifact, ColanderFeed, Device, Observable
from colander_data_converter.base.types.observable import ObservableTypes


def main(path, passphrase):
    plaintext = pyrage.passphrase.decrypt(open(path, "rb").read(), passphrase)
    z = zipfile.ZipFile(io.BytesIO(plaintext))
    feed = ColanderFeed.load(json.loads(z.read("acquisition.json")), reset_ids=True)
    devices = [e for e in feed.entities.values() if isinstance(e, Device)]
    artifacts = [e for e in feed.entities.values() if isinstance(e, Artifact)]
    if len(devices) != 1:
        raise AssertionError("expected one device in the Colander feed, got %d" % len(devices))
    for a in artifacts:
        if a.extracted_from != devices[0]:
            raise AssertionError("%s not extracted from the acquired device" % a.name)
        # What colander/core/feed/importer.py makes of a new artifact.
        Observable(type=ObservableTypes.SHA256.value, name=a.sha256, attributes=a.attributes or {})
    hashed = {line.rsplit(",", 1)[0].strip('"') for line in z.read("hashes.csv").decode().splitlines() if line}
    fed = {a.name for a in artifacts}
    if hashed - {"command.log"} != fed:
        raise AssertionError("Colander feed artifacts differ from hashes.csv: %s" % sorted(hashed ^ fed))
    print("Colander cross-check OK: device %r, %d artifacts" % (devices[0].name, len(artifacts)), flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
