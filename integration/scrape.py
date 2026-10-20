"""Scrape a value out of `maestro hierarchy` JSON (stdin) — used by run.sh between
Maestro flows for things that must cross to the host.

    scrape.py code        the 6-digit wireless pairing code (Settings dialog)
    scrape.py passphrase  the 32-char one-time export passphrase (dialog body)
    scrape.py expand      "x y" of the Expand chevron on bugbane's pairing notification
                          (several notifications carry an identical "Expand" button)
"""

import json
import os
import re
import sys

# The app's strings for the locale under test (strings_env.py output, see run.sh).
S = dict(l.rstrip("\n").split("\t", 1) for l in open(os.environ["BUGBANE_STRINGS"]))


def _code(text):
    t = text.strip()
    return t if re.fullmatch(r"\d{6}", t) else None


def _passphrase(text):
    # Dialog body is acquisition_export_passphrase with the 32 alphanumerics in its slot.
    if not re.fullmatch("(?s)" + S["s_acquisition_export_passphrase"], text.strip()):
        return None
    tokens = re.findall(r"[0-9A-Za-z]{32}", text)
    return tokens[-1] if tokens else None


MATCHERS = {"code": _code, "passphrase": _passphrase}

PAIRING_TITLE = re.compile("(?s).*(%s|%s).*" % (
    S["s_notification_adb_pairing_service_found_title"], S["s_notification_adb_pairing_working_title"]))


def _bounds(node):
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("attributes", {}).get("bounds") or "")
    return tuple(int(v) for v in m.groups()) if m else None


def _collect(node, out):
    a = node.get("attributes", {})
    b = _bounds(node)
    if b:
        if PAIRING_TITLE.fullmatch(a.get("text") or ""):
            out["titles"].append(b)
        if (a.get("accessibilityText") or a.get("text")) == "Expand":
            out["expands"].append(b)
    for child in node.get("children", []):
        _collect(child, out)


def expand_point(tree):
    found = {"titles": [], "expands": []}
    _collect(tree, found)
    if not found["titles"] or not found["expands"]:
        return None
    ty = (found["titles"][0][1] + found["titles"][0][3]) / 2
    x1, y1, x2, y2 = min(found["expands"], key=lambda b: abs((b[1] + b[3]) / 2 - ty))
    return "%d %d" % ((x1 + x2) // 2, (y1 + y2) // 2)


def find(node, match):
    value = match(node.get("attributes", {}).get("text") or "")
    if value:
        return value
    for child in node.get("children", []):
        value = find(child, match)
        if value:
            return value
    return None


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    match = MATCHERS.get(mode)
    if not match and mode != "expand":
        sys.stderr.write("usage: scrape.py {code|passphrase|expand}\n")
        return 2
    try:
        tree = json.load(sys.stdin)
    except ValueError:
        return 1
    value = expand_point(tree) if mode == "expand" else find(tree, match)
    if not value:
        return 1
    print(value)
    return 0


if __name__ == "__main__":
    sys.exit(main())
