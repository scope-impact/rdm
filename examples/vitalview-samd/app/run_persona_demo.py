#!/usr/bin/env python3
"""Reference driver: run the usability personas against the VitalView demo app.

This is a deterministic, scripted demonstration of the persona -> Playwright ->
evidence loop (handy as a CI smoke). The real runner is the LLM-driven
`usability-persona` skill; this script just exercises the same path so the loop
is reproducible without an agent.

    cd examples/vitalview-samd/app
    python -m http.server 8099 &        # serve the app
    python run_persona_demo.py          # drive it, write persona-results/*.json
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from glob import glob
from pathlib import Path

URL = os.environ.get("VV_URL", "http://localhost:8099/")
REPO = Path(__file__).resolve().parents[3]
RESULTS = REPO / "examples/vitalview-samd/persona-results"
WRITER = REPO / ".claude/skills/usability-persona/scripts/write_evidence.py"


def _launch(p):
    """Launch Chromium, falling back to a preinstalled browser if needed."""
    try:
        return p.chromium.launch()
    except Exception:
        browsers = os.environ.get("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
        matches = sorted(glob(f"{browsers}/chromium-*/chrome-linux/chrome"))
        if not matches:
            raise
        return p.chromium.launch(executable_path=matches[-1], args=["--no-sandbox"])


def _write(persona: str, un: str, outcome: str, issues: list[dict]) -> None:
    subprocess.run(
        [sys.executable, str(WRITER), "--results-dir", str(RESULTS),
         "--persona", persona, "--user-need", un, "--outcome", outcome,
         "--issues", json.dumps(issues)],
        check=True,
    )


def main() -> int:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = _launch(p)
        page = browser.new_page()

        # UN-001 (icu-nurse): notice + acknowledge a critical alert.
        page.goto(URL)
        page.fill("#username", "nurse")
        page.fill("#password", "demo")
        page.click("#signin")
        page.wait_for_selector("#patients")
        page.click("text=C. Diaz")
        page.wait_for_selector("#alert")
        issues_001 = [{"severity": "difficulty", "step": 3,
                       "note": "alert acknowledge control labelled 'Ack' — terse; took a beat to recognise"}]
        page.click("#ack")
        _write("icu-nurse", "UN-001", "success" if page.is_visible("#ackdone") else "failed", issues_001)

        # UN-002 (physician): sign in, find a patient, read vitals.
        page.goto(URL)
        page.fill("#username", "physician")
        page.fill("#password", "demo")
        page.click("#signin")
        page.wait_for_selector("#patients")
        issues_002 = [{"severity": "confusion", "step": 2,
                       "note": "expected a 'Search' field; the box is labelled 'Filter'"}]
        page.fill("#filter", "Fischer")
        page.click("text=E. Fischer")
        page.wait_for_selector("#vitals")
        ok = "SpO2" in page.inner_text("#vitals")
        _write("physician", "UN-002", "success" if ok else "failed", issues_002)

        browser.close()
    print("journeys complete — run `rdm story persona` to report")
    return 0


if __name__ == "__main__":
    sys.exit(main())
