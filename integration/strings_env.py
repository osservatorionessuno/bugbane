#!/usr/bin/env python3
"""Emit the app's UI strings for one locale as Maestro env vars, so the flows match
whatever language the app shows instead of English literals.

    strings_env.py <lang>  ->  lines "s_<name>\\t<value>"

Only the names referenced in integration/ (as ${s_name}, or s_name in the shell/python
scripts) are emitted. Values are Maestro text matchers (equality or regex): format
placeholders and newlines become ".*". <lang> "en" is the base values/ set; any
other locale overlays values-<lang>/ on it, so untranslated strings fall back.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

lang = sys.argv[1]
here = Path(__file__).resolve().parent
res = here.parent / "app/src/main/res"


def load(xml):
    out = {}
    if not xml.is_file():
        return out
    for el in ET.parse(xml).getroot():
        if el.tag == "string":
            text = "".join(el.itertext())
        elif el.tag == "plurals":
            text = "".join(t for q in el if q.get("quantity") == "other" for t in q.itertext())
        else:
            continue
        if not text:
            continue
        text = text.replace("\\'", "'").replace('\\"', '"').strip()
        text = re.sub(r"%(\d+\$)?[sd]|\\n", ".*", text)
        out[el.get("name")] = text
    return out


strings = load(res / "values/strings.xml")
if lang != "en":
    strings.update(load(res / f"values-{lang}/strings.xml"))

wanted = set()
for f in list(here.glob("maestro/*.yaml")) + list(here.glob("*.sh")) + list(here.glob("*.py")):
    if f.name == "strings_env.py":
        continue
    wanted.update(re.findall(r"\bs_([a-z0-9_]+)", f.read_text()))
missing = sorted(wanted - strings.keys())
if missing:
    sys.exit(f"strings_env.py: not in strings.xml: {', '.join(missing)}")
for name in sorted(wanted):
    print(f"s_{name}\t{strings[name]}")
