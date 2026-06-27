"""
Ingest Allure results into per-user-need verification status.

Allure writes one ``*-result.json`` file per executed test into a results
directory. Each result carries a ``status`` and a list of ``labels``; the
``@allure.story("ID")`` / ``@allure.feature("ID")`` decorators appear as labels
named ``story`` / ``feature``. This module maps those IDs to an aggregated
verification status so the DHF can report whether each SDD user need was
actually *verified* (executed and passed), not merely referenced by a tag.

This is the executed-evidence counterpart to the source-tag scan
(``scan_source_tags``): tags say a test *claims* to cover a user need; the Allure
result says whether that test actually *passed*.
"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record.reconcile import StatusReportMixin, aggregate_by_id, load_json_records

# Matches @allure.story("ID") / @allure.feature("ID"). Single home for the
# pattern (story_audit.audit re-exports it); group(2) is the ID.
ALLURE_PATTERN = re.compile(r'@allure\.(story|feature)\(["\']([^"\']+)["\']\)')

# Allure label names that carry user-need IDs (the result-file counterpart of
# ALLURE_PATTERN, which matches both @allure.story and @allure.feature).
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
    # Design output(s) the test exercises, from @allure.label("output", ...).
    outputs: list[str] = field(default_factory=list)


@dataclass
class UserNeedVerification:
    """Aggregated verification status for one declared ID (a design input)."""

    user_need_id: str
    status: str = UNTESTED
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    tests: list[str] = field(default_factory=list)
    outputs: list[str] = field(default_factory=list)


@dataclass
class VerificationReport(StatusReportMixin):
    by_id: dict[str, UserNeedVerification] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    results_found: int = 0

    @property
    def verified(self) -> list[str]:
        return self._ids_with(VERIFIED)

    @property
    def failed(self) -> list[str]:
        return self._ids_with(FAILED)

    @property
    def untested(self) -> list[str]:
        return self._ids_with(UNTESTED)


def _build_result(data: dict, filename: str) -> TestResult:
    """Build one ``TestResult`` from a parsed Allure result file."""
    ids: list[str] = []
    outputs: list[str] = []
    for label in data.get("labels", []) or []:
        if not isinstance(label, dict):
            continue
        value = str(label.get("value", "")).strip()
        if not value:
            continue
        if label.get("name") in USER_NEED_LABELS:
            ids.append(value)
        elif label.get("name") == "output":
            outputs.append(value)
    return TestResult(
        name=str(data.get("name", "")),
        status=str(data.get("status", "unknown")),
        user_need_ids=ids,
        source=filename,
        outputs=outputs,
    )


def parse_results(results_dir: Path) -> list[TestResult]:
    """Parse all ``*-result.json`` files in an Allure results directory."""
    return load_json_records(results_dir, "-result.json", _build_result)


def reconcile(sdd_ids: set[str], results_dir: Path) -> VerificationReport:
    """Aggregate Allure results into a verification status per SDD user need.

    Status rules per user need:
      - ``failed``   if any covering test failed or is broken,
      - ``verified`` else if any covering test passed,
      - ``untested`` if no covering test ran (or only skipped/unknown).

    IDs referenced by tests but not declared in the SDD are returned as orphans.
    """
    results = parse_results(Path(results_dir))

    def _fold(verification: UserNeedVerification, result: TestResult) -> None:
        verification.tests.append(result.name or result.source)
        for output in result.outputs:
            if output not in verification.outputs:
                verification.outputs.append(output)
        if result.status in _FAILING:
            verification.failed += 1
        elif result.status in _PASSING:
            verification.passed += 1
        else:
            verification.skipped += 1

    def _status(verification: UserNeedVerification) -> str:
        if verification.failed:
            return FAILED
        if verification.passed:
            return VERIFIED
        return UNTESTED

    by_id, orphan_ids = aggregate_by_id(
        sdd_ids,
        results,
        ids_of=lambda result: result.user_need_ids,
        new=UserNeedVerification,
        fold=_fold,
        status=_status,
    )
    return VerificationReport(
        by_id=by_id,
        orphan_ids=orphan_ids,
        results_found=len(results),
    )


def find_tests_dir(dhf_dir: Path) -> Path | None:
    """Locate the test suite to scan for @allure source tags.

    Prefer ``<dhf>/../tests``; fall back to ``<cwd>/tests``. ``Path.cwd()`` is
    evaluated lazily and guarded, because a prior test may have removed the
    working directory (which would otherwise raise from the cwd lookup).
    """
    sibling = dhf_dir.parent / "tests"
    if sibling.exists():
        return sibling
    try:
        cwd_tests = Path.cwd() / "tests"
    except OSError:
        return None
    return cwd_tests if cwd_tests.exists() else None


def scan_source_tags(tests_dir: Path) -> dict[str, list[str]]:
    """Map each @allure story/feature ID to the test files that reference it.

    The source-tag counterpart of ``parse_results``: it reports which user needs
    a test *claims* to cover (vs. whether the executed test passed).
    """
    refs: dict[str, list[str]] = {}
    for py_file in tests_dir.rglob("test_*.py"):
        try:
            content = py_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for match in ALLURE_PATTERN.finditer(content):
            refs.setdefault(match.group(2), []).append(str(py_file))
    return refs


def _decorator_tag_id(decorator: ast.expr) -> str | None:
    """Return the ID from an ``@allure.story("ID")`` / ``.feature`` decorator node."""
    if not isinstance(decorator, ast.Call) or not decorator.args:
        return None
    func = decorator.func
    if not isinstance(func, ast.Attribute) or func.attr not in USER_NEED_LABELS:
        return None
    first = decorator.args[0]
    if isinstance(first, ast.Constant) and isinstance(first.value, str):
        return first.value.strip()
    return None


def scan_tagged_sources(tests_dir: Path | None) -> dict[str, list[str]]:
    """Map each @allure story/feature ID to the *source* of the test function(s)
    tagged with it (the AST counterpart of ``scan_source_tags``).

    Captures the function body, not just the file, so a faithfulness verdict
    pinned to this source only re-opens when the *tagged* function changes -- not
    when an unrelated function in the same file is edited.
    """
    sources: dict[str, list[str]] = {}
    if tests_dir is None or not tests_dir.exists():
        return sources
    for py_file in sorted(tests_dir.rglob("test_*.py")):
        try:
            text = py_file.read_text(encoding="utf-8", errors="ignore")
            tree = ast.parse(text)
        except (OSError, SyntaxError):
            continue
        for node in ast.walk(tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            ids = [i for d in node.decorator_list if (i := _decorator_tag_id(d))]
            if not ids:
                continue
            segment = ast.get_source_segment(text, node) or ""
            for tag_id in ids:
                sources.setdefault(tag_id, []).append(segment)
    return sources
