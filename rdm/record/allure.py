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

import yaml

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


def _repo_root(path: Path) -> Path | None:
    """The enclosing git repository root (``.git`` may be a dir or a file)."""
    for ancestor in [path, *path.parents]:
        if (ancestor / ".git").exists():
            return ancestor
    return None


def find_tests_dir(dhf_dir: Path) -> Path | None:
    """Locate the test suite to scan for @allure source tags.

    Anchored to the repository that CONTAINS the DHF: prefer ``<dhf>/../tests``,
    then walk upward — never past the DHF's own git repository root, and never
    outside a repository. The invoking process's working directory is
    deliberately not consulted: a ``<cwd>/tests`` fallback would let an audit
    of another checkout count the caller's test tags as that repository's
    coverage (DI-23).
    """
    try:
        start = Path(dhf_dir).resolve().parent
    except OSError:  # cwd removed under us and dhf_dir is relative
        start = Path(dhf_dir).parent
    root = _repo_root(start)
    chain = [start, *start.parents]
    # Without a repository boundary, only the DHF's sibling is trustworthy.
    chain = chain[: chain.index(root) + 1] if root in chain else [start]
    for ancestor in chain:
        candidate = ancestor / "tests"
        if candidate.exists():
            return candidate
    return None


# Conventional test-file names per ecosystem (DI-31): pytest, JS/TS runners
# (jest/vitest/playwright), Java (JUnit + allure-java), Go, and Ansible test
# task files (DI-34).
TEST_FILE_GLOBS = (
    "test_*.py", "*_test.py",
    "*.test.js", "*.test.jsx", "*.test.ts", "*.test.tsx",
    "*.spec.js", "*.spec.ts",
    "*Test.java", "*Tests.java",
    "*_test.go",
    "*_test.yml", "*_test.yaml",
)

# Non-Python tag syntaxes (DI-31): JS/TS runtime calls `allure.story("…")`
# (no decorator @), and Java annotations `@Story("…")` / `@Feature("…")`.
POLYGLOT_TAG_PATTERNS = (
    re.compile(r'(?<!@)\ballure\.(story|feature)\(\s*["\']([^"\']+)["\']'),
    re.compile(r'@(Story|Feature)\(\s*"([^"]+)"'),
)

# Ansible task tags that carry a verification ID (DI-34). Ansible has no
# allure call site — the story ID travels as a task tag (and reaches Allure
# through a run-time callback plugin), so only ID-shaped tags (letters-dash-
# digits, e.g. DI-7) count; operational tags (`always`, `bootstrap`, …) don't.
ANSIBLE_ID_TAG = re.compile(r"^[A-Za-z][A-Za-z0-9_]*-\d+$")

_YAML_SUFFIXES = (".yml", ".yaml")


def _yaml_tag_ids(content: str) -> list[str]:
    """Every ID-shaped Ansible tag in a test task file (DI-34).

    Walks all YAML documents (task lists or plays) collecting `tags` values;
    unparseable YAML yields no tags rather than an error — the file simply
    claims no coverage.
    """
    try:
        documents = list(yaml.safe_load_all(content))
    except yaml.YAMLError:
        return []

    ids: list[str] = []

    def _walk(node) -> None:
        if isinstance(node, dict):
            tags = node.get("tags")
            candidates = tags if isinstance(tags, list) else [tags]
            ids.extend(
                tag for tag in candidates
                if isinstance(tag, str) and ANSIBLE_ID_TAG.match(tag)
            )
            for value in node.values():
                _walk(value)
        elif isinstance(node, list):
            for value in node:
                _walk(value)

    _walk(documents)
    return ids


def _iter_test_files(tests_dir: Path):
    seen: set[Path] = set()
    for pattern in TEST_FILE_GLOBS:
        for path in tests_dir.rglob(pattern):
            if path not in seen:
                seen.add(path)
                yield path


def _tag_ids_in(path: Path, content: str) -> list[str]:
    """Every story/feature tag ID a test source file claims, per its language."""
    if path.suffix == ".py":
        return [m.group(2) for m in ALLURE_PATTERN.finditer(content)]
    if path.suffix in _YAML_SUFFIXES:
        return _yaml_tag_ids(content)
    ids: list[str] = []
    for pattern in POLYGLOT_TAG_PATTERNS:
        ids.extend(m.group(2) for m in pattern.finditer(content))
    return ids


def scan_source_tags(tests_dir: Path) -> dict[str, list[str]]:
    """Map each story/feature tag ID to the test files that reference it.

    The source-tag counterpart of ``parse_results``: it reports which user needs
    a test *claims* to cover (vs. whether the executed test passed). Reads
    Python decorators, JS/TS allure calls, and Java annotations (DI-31).
    """
    refs: dict[str, list[str]] = {}
    for test_file in sorted(_iter_test_files(tests_dir)):
        try:
            content = test_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for tag_id in _tag_ids_in(test_file, content):
            refs.setdefault(tag_id, []).append(str(test_file))
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
    """Map each story/feature tag ID to the *source* of the test(s) tagged
    with it (the function-scope counterpart of ``scan_source_tags``).

    For Python, captures the tagged function body (AST), so a verdict pinned to
    this source only re-opens when the *tagged* function changes. For other
    languages (DI-31: JS/TS allure calls, Java annotations) there is no
    cross-language AST, so the whole file is the source segment — stated, not
    silent: a verdict on a non-Python test re-opens on any edit to its file.
    """
    sources: dict[str, list[str]] = {}
    if tests_dir is None or not tests_dir.exists():
        return sources
    for test_file in sorted(_iter_test_files(tests_dir)):
        try:
            text = test_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if test_file.suffix != ".py":
            for tag_id in _tag_ids_in(test_file, text):
                sources.setdefault(tag_id, []).append(text)
            continue
        try:
            tree = ast.parse(text)
        except SyntaxError:
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
