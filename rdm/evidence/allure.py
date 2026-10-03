"""
Ingest Allure results into per-design-input verification status.

Allure writes one ``*-result.json`` file per executed test into a results
directory. Each result carries a ``status`` and a list of ``labels``; the
``@allure.story("ID")`` decorator appears as a label named ``story``
(``feature`` and ``epic`` carry the bounded context and user needs, DI-57).
This module maps those IDs to an aggregated
verification status so the DHF can report whether each design input was
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

# Allure statuses: a result with any other is unreadable, since it could be a failed run.
FAILING = {"failed", "broken"}
PASSED = "passed"
STATUSES = ("passed", "failed", "broken", "skipped", "unknown")
RESULT_SUFFIX = "-result.json"  # one Allure result file per executed test

# What rdm.pytest_plugin writes into a run's labels and results, and every
# reader (the graph, the verification report) reads back (DI-57, DI-59, DI-65).
COMMIT_LABEL = "commit"            # the commit under test
OUTPUT_LABEL = "output"            # a design output the test exercises
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
    design_input_ids: list[str] = field(default_factory=list)
    # Design output(s) the test exercises, from @allure.label("output", ...).
    outputs: list[str] = field(default_factory=list)


@dataclass
class DesignInputVerification:
    """Aggregated verification status for one declared design input."""

    design_input_id: str
    status: str = UNTESTED
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    tests: list[str] = field(default_factory=list)
    outputs: list[str] = field(default_factory=list)


@dataclass
class VerificationReport(StatusReportMixin):
    by_id: dict[str, DesignInputVerification] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    results_found: int = 0
    unreadable: list[str] = field(default_factory=list)  # result files that are not a JSON object

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


def readable(data: dict) -> bool:
    """Whether a parsed result says what it is: a status Allure writes, and
    labels (if any) that are a list of name and value text."""
    labels = data.get("labels") or []
    return data.get("status") in STATUSES and all(  # a string or mapping of labels fails too
        isinstance(label, dict) and isinstance(label.get("name"), str) and isinstance(label.get("value"), str)
        for label in labels)


def run_status(data: dict) -> str:
    """A run's result: failed when a verification step at any depth failed or
    broke, whatever the test's own status says -- a failed step fails the test."""
    status = data["status"]
    stack = list(data.get("steps") or [])
    while status == PASSED and stack:
        step = stack.pop()
        if isinstance(step, dict):
            if step.get("status") in FAILING:
                status = "failed"
            stack.extend(step.get("steps") or [])
    return status


def _named_results(results_dir: Path) -> tuple[list[tuple[dict, str]], list[str]]:
    unreadable: list[str] = []
    named = load_json_records(Path(results_dir), RESULT_SUFFIX, lambda data, name: (data, name), unreadable)
    unreadable += [name for data, name in named if not readable(data)]
    return [(data, name) for data, name in named if readable(data)], sorted(unreadable)


def read_results(results_dir: Path) -> tuple[list[dict], list[str]]:
    """Every readable result in a results directory, in file name order, and
    the names of those that cannot be read (sorted): not JSON, nested too deep,
    a symbolic link, or not :func:`readable`. Any of them could hold a failed run."""
    named, unreadable = _named_results(results_dir)
    return [data for data, _ in named], unreadable


def _build_result(data: dict, filename: str) -> TestResult:
    """Build one ``TestResult`` from a parsed Allure result file."""
    return TestResult(
        name=str(data.get("name") or filename),
        status=run_status(data),
        design_input_ids=design_input_tags(data),
        outputs=labelled(data, OUTPUT_LABEL),
    )


def parse_results(results_dir: Path, unreadable: list[str] | None = None) -> list[TestResult]:
    """Parse all ``*-result.json`` files in an Allure results directory; the
    names of those that cannot be read are added to ``unreadable``."""
    named, cannot = _named_results(results_dir)
    if unreadable is not None:
        unreadable.extend(cannot)
    return [_build_result(data, name) for data, name in named]


def reconcile(sdd_ids: set[str], results_dir: Path) -> VerificationReport:
    """Aggregate Allure results into a verification status per design input.

    Status rules per design input:
      - ``failed``   if any covering test failed or is broken,
      - ``verified`` else if any covering test passed,
      - ``untested`` if no covering test ran (or only skipped/unknown).

    IDs referenced by tests but not declared are returned as orphans.
    """
    unreadable: list[str] = []
    results = parse_results(Path(results_dir), unreadable)

    def _fold(verification: DesignInputVerification, result: TestResult) -> None:
        verification.tests.append(result.name)
        for output in result.outputs:
            if output not in verification.outputs:
                verification.outputs.append(output)
        if result.status in FAILING:
            verification.failed += 1
        elif result.status == PASSED:
            verification.passed += 1
        else:
            verification.skipped += 1

    def _status(verification: DesignInputVerification) -> str:
        if verification.failed:
            return FAILED
        if verification.passed:
            return VERIFIED
        return UNTESTED

    by_id, orphan_ids = aggregate_by_id(
        sdd_ids,
        results,
        ids_of=lambda result: result.design_input_ids,
        new=DesignInputVerification,
        fold=_fold,
        status=_status,
    )
    return VerificationReport(
        by_id=by_id,
        orphan_ids=orphan_ids,
        results_found=len(results),
        unreadable=unreadable,
    )


