#!/usr/bin/env python3
"""Perceive a web page: print its accessibility tree and save a screenshot.

Used by the usability-persona skill to let the persona "see" the screen before
acting. Chromium is expected to be preinstalled (PLAYWRIGHT_BROWSERS_PATH).

    python snapshot.py --url http://localhost:8000 --screenshot step1.png
"""

from __future__ import annotations

import argparse
import json
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="URL to open")
    parser.add_argument("--screenshot", default="snapshot.png", help="screenshot output path")
    parser.add_argument("--timeout", type=int, default=15000, help="navigation timeout (ms)")
    args = parser.parse_args()

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print(
            "playwright is not installed. In Claude Code: `pip install playwright` "
            "(the browser is preinstalled at PLAYWRIGHT_BROWSERS_PATH).",
            file=sys.stderr,
        )
        return 2

    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        page.goto(args.url, wait_until="load", timeout=args.timeout)
        page.screenshot(path=args.screenshot, full_page=True)
        tree = page.accessibility.snapshot()
        browser.close()

    print(json.dumps({"url": args.url, "screenshot": args.screenshot, "accessibility": tree}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
