"""Scrape a value out of `maestro hierarchy` JSON (stdin) — used by run.sh between
Maestro flows for things that must cross to the host.

    scrape.py code        the 6-digit wireless pairing code (Settings dialog)
    scrape.py passphrase  the 32-char one-time export passphrase (dialog body)
"""

import json
import re
import sys


def _code(text):
    t = text.strip()
    return t if re.fullmatch(r"\d{6}", t) else None


def _passphrase(text):
    # Dialog body ends with "...shown only once:\n\n<32 alphanumerics>".
    if "shown only once" not in text:
        return None
    tokens = re.findall(r"[0-9A-Za-z]{32}", text)
    return tokens[-1] if tokens else None


MATCHERS = {"code": _code, "passphrase": _passphrase}


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
    match = MATCHERS.get(sys.argv[1] if len(sys.argv) > 1 else "")
    if not match:
        sys.stderr.write("usage: scrape.py {code|passphrase}\n")
        return 2
    try:
        tree = json.load(sys.stdin)
    except ValueError:
        return 1
    value = find(tree, match)
    if not value:
        return 1
    print(value)
    return 0


if __name__ == "__main__":
    sys.exit(main())
