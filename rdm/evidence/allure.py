"""
Ingest Allure results into per-user-need verification status.

Allure writes one ``*-result.json`` file per executed test into a results
directory. Each result carries a ``status`` and a list of ``labels``; the
``@allure.story("ID")`` decorator appears as a label named ``story``
(``feature`` and ``epic`` carry the bounded context and user needs, DI-57).
This module maps those IDs to an aggregated
verification status so the DHF can report whether each SDD user need was
actually *verified* (executed and passed), not merely referenced by a tag.

This is the executed-evidence counterpart to the test tags
(``rdm.specification.tags``): a tag says a test *claims* to verify a design
input; the Allure result says whether that test actually *passed*.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from rdm.kernel.reconcile import StatusReportMixin, aggregate_by_id, load_json_records
from rdm.specification.tags import DESIGN_INPUT_LABELS

# Allure statuses.
FAILING = {"failed", "broken"}
_FAILING = FAILING
_PASSING = {"passed"}

# What rdm.pytest_plugin writes into a run's labels and results, and every
# reader (the graph, the verification report) reads back (DI-57, DI-59, DI-65).
COMMIT_LABEL = "commit"            # the commit under test
WORKTREE_LABEL = "worktree"        # "dirty" when it had uncommitted changes
DIRTY = "dirty"
REQUIREMENT_ATTACHMENT = "requirement {}"   # the plugin's copy of a design input's text
EXECUTOR_FILE = "executor.json"             # Allure's: who or what ran the tests
ENVIRONMENT_FILE = "environment.properties"  # Allure's: the configuration and tools
_COMMIT_SHA = re.compile(r"[0-9a-f]{7,40}")

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


def labelled(data: dict, name: str) -> list[str]:
    """The non-empty values of one label on an Allure result, in order."""
    return [value for label in data.get("labels") or [] if isinstance(label, dict) and label.get("name") == name
            and (value := str(label.get("value", "")).strip())]


def design_input_tags(data: dict) -> list[str]:
    """The design inputs an Allure result is tagged with (its story labels)."""
    return [value for name in DESIGN_INPUT_LABELS for value in labelled(data, name)]


def run_version(data: dict) -> tuple[str | None, bool]:
    """The commit an Allure result tested (None when it recorded none, or a
    value that is no commit) and whether the worktree had uncommitted changes."""
    commits = [c for c in labelled(data, COMMIT_LABEL) if _COMMIT_SHA.fullmatch(c)]
    return (commits[0] if commits else None), DIRTY in labelled(data, WORKTREE_LABEL)


def full_name(rel: str, name: str | None) -> str | None:
    """The ``fullName`` Allure gives a run of the Python test ``name`` in the
    repo-relative file ``rel``: ``tests.x.TestClass#test_y`` for
    ``tests/x.py`` and ``TestClass::test_y``."""
    if not name or not rel.endswith(".py"):
        return None
    *owner, function = name.split("::")
    return ".".join([rel[:-3].replace("/", "."), *owner]) + "#" + function


def write_run_facts(results_dir: Path, executor: dict, environment: dict[str, str]) -> None:
    """Write Allure's executor.json and environment.properties (DI-65)."""
    results_dir.mkdir(parents=True, exist_ok=True)
    (results_dir / EXECUTOR_FILE).write_text(json.dumps(executor, indent=2), encoding="utf-8")
    lines = (f"{key}={' '.join(str(value).split())}\n" for key, value in environment.items())  # one line each
    (results_dir / ENVIRONMENT_FILE).write_text("".join(lines), encoding="utf-8")


def read_run_facts(results_dir: Path) -> tuple[dict | None, dict[str, str]]:
    """The executor and environment a results directory records, as written by
    :func:`write_run_facts` (or by any Allure integration)."""
    try:
        executor = json.loads((results_dir / EXECUTOR_FILE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        executor = None
    if not isinstance(executor, dict):
        executor = None
    environment: dict[str, str] = {}
    path = results_dir / ENVIRONMENT_FILE
    for line in path.read_text(encoding="utf-8").splitlines() if path.is_file() else []:
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, _, value = line.partition("=")
            environment[key.strip()] = value.strip()
    return executor, environment


def _build_result(data: dict, filename: str) -> TestResult:
    """Build one ``TestResult`` from a parsed Allure result file."""
    return TestResult(
        name=str(data.get("name", "")),
        status=str(data.get("status", "unknown")),
        user_need_ids=design_input_tags(data),
        source=filename,
        outputs=labelled(data, "output"),
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


