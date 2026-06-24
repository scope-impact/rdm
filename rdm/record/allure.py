"""
Ingest Allure results into per-user-need verification status.

Allure writes one ``*-result.json`` file per executed test into a results
directory. Each result carries a ``status`` and a list of ``labels``; the
``@allure.story("ID")`` / ``@allure.feature("ID")`` decorators appear as labels
named ``story`` / ``feature``. This module maps those IDs to an aggregated
verification status so the DHF can report whether each SDD user need was
actually *verified* (executed and passed), not merely referenced by a tag.

This is the executed-evidence counterpart to the source-tag scan in
``story_audit.audit``: tags say a test *claims* to cover a user need; the Allure
result says whether that test actually *passed*.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# Allure label names that carry user-need IDs (mirrors audit.ALLURE_PATTERN,
# which matches both @allure.story and @allure.feature).
USER_NEED_LABELS = ("story", "feature")

# Allure statuses.
_FAILING = {"failed", "broken"}
_PASSING = {"passed"}

# Verification status values.
VERIFIED = "verified"
FAILED = "failed"
UNTESTED = "untested"


@dataclass
class TestResult:
    """A single executed test, parsed from one Allure result file."""

    name: str
    status: str
    user_need_ids: list[str] = field(default_factory=list)
    source: str = ""


@dataclass
class UserNeedVerification:
    """Aggregated verification status for one user need."""

    user_need_id: str
    status: str = UNTESTED
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    tests: list[str] = field(default_factory=list)


@dataclass
class VerificationReport:
    by_user_need: dict[str, UserNeedVerification] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    results_found: int = 0

    def _ids_with(self, status: str) -> list[str]:
        return sorted(uid for uid, v in self.by_user_need.items() if v.status == status)

    @property
    def verified(self) -> list[str]:
        return self._ids_with(VERIFIED)

    @property
    def failed(self) -> list[str]:
        return self._ids_with(FAILED)

    @property
    def untested(self) -> list[str]:
        return self._ids_with(UNTESTED)


def parse_results(results_dir: Path) -> list[TestResult]:
    """Parse all ``*-result.json`` files in an Allure results directory."""
    results: list[TestResult] = []
    if not results_dir.exists():
        return results
    for result_file in sorted(results_dir.glob("*-result.json")):
        try:
            data = json.loads(result_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        ids: list[str] = []
        for label in data.get("labels", []) or []:
            if isinstance(label, dict) and label.get("name") in USER_NEED_LABELS:
                value = str(label.get("value", "")).strip()
                if value:
                    ids.append(value)
        results.append(
            TestResult(
                name=str(data.get("name", "")),
                status=str(data.get("status", "unknown")),
                user_need_ids=ids,
                source=result_file.name,
            )
        )
    return results


def reconcile(sdd_ids: set[str], results_dir: Path) -> VerificationReport:
    """Aggregate Allure results into a verification status per SDD user need.

    Status rules per user need:
      - ``failed``   if any covering test failed or is broken,
      - ``verified`` else if any covering test passed,
      - ``untested`` if no covering test ran (or only skipped/unknown).

    IDs referenced by tests but not declared in the SDD are returned as orphans.
    """
    results = parse_results(Path(results_dir))
    aggregated = {uid: UserNeedVerification(uid) for uid in sdd_ids}
    referenced: set[str] = set()

    for result in results:
        for uid in result.user_need_ids:
            referenced.add(uid)
            verification = aggregated.get(uid)
            if verification is None:
                continue  # orphan, handled below
            verification.tests.append(result.name or result.source)
            if result.status in _FAILING:
                verification.failed += 1
            elif result.status in _PASSING:
                verification.passed += 1
            else:
                verification.skipped += 1

    for verification in aggregated.values():
        if verification.failed:
            verification.status = FAILED
        elif verification.passed:
            verification.status = VERIFIED
        else:
            verification.status = UNTESTED

    return VerificationReport(
        by_user_need=aggregated,
        orphan_ids=sorted(referenced - sdd_ids),
        results_found=len(results),
    )
