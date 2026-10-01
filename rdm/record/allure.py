"""
Ingest Allure results into per-user-need verification status.

Allure writes one ``*-result.json`` file per executed test into a results
directory. Each result carries a ``status`` and a list of ``labels``; the
``@allure.story("ID")`` decorator appears as a label named ``story``
(``feature`` and ``epic`` carry the bounded context and user needs, DI-57).
This module maps those IDs to an aggregated
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

from rdm.record.git import repo_root
from rdm.record.ids import is_id
from rdm.record.reconcile import StatusReportMixin, aggregate_by_id, load_json_records

# Matches @allure.story("ID"): only the story names a design input. Single
# home for the pattern; group(2) is the ID.
ALLURE_PATTERN = re.compile(r'@allure\.(story)\(["\']([^"\']+)["\']\)')

# The Allure label that names a design input (the result-file counterpart of
# ALLURE_PATTERN).
DESIGN_INPUT_LABELS = ("story",)

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
        if label.get("name") in DESIGN_INPUT_LABELS:
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


# Conventional names for a test suite directory. Plural first: it is the
# pytest/Go/Ansible convention and the one rdm's own repositories use.
TESTS_DIR_NAMES = ("tests", "test")


def find_tests_dir(dhf_dir: Path) -> Path | None:
    """Locate the test suite to scan for @allure source tags.

    Anchored to the repository that CONTAINS the DHF: prefer ``<dhf>/../tests``,
    then walk upward — never past the DHF's own git repository root, and never
    outside a repository. The invoking process's working directory is
    deliberately not consulted: a ``<cwd>/tests`` fallback would let an audit
    of another checkout count the caller's test tags as that repository's
    coverage (DI-23).

    ``tests`` and ``test`` are both accepted, in that order per ancestor: Dart,
    Flutter and Maven put the suite in ``test/``, and looking only for the plural
    made every one of those repositories read as having no tests at all.
    """
    try:
        start = Path(dhf_dir).resolve().parent
    except OSError:  # cwd removed under us and dhf_dir is relative
        start = Path(dhf_dir).parent
    root = repo_root(start)
    chain = [start, *start.parents]
    # Without a repository boundary, only the DHF's sibling is trustworthy.
    chain = chain[: chain.index(root) + 1] if root in chain else [start]
    for ancestor in chain:
        for name in TESTS_DIR_NAMES:
            candidate = ancestor / name
            if candidate.exists():
                return candidate
    return None


# Conventional test-file names per ecosystem (DI-31): pytest, JS/TS runners
# (jest/vitest/playwright), Java (JUnit + allure-java), Go, and Ansible task
# files used as acceptance tests. The Ansible case is not exotic: an estate can
# carry its whole design-input acceptance suite as tagged YAML tasks, and while
# these globs omitted YAML every one of those tags scanned as zero -- coverage
# read 0% and every test file read as an orphan, which is the same false
# reading as an unaudited repo, only inverted.
TEST_FILE_GLOBS = (
    "test_*.py", "*_test.py",
    "*.test.js", "*.test.jsx", "*.test.ts", "*.test.tsx",
    "*.spec.js", "*.spec.ts",
    "*Test.java", "*Tests.java",
    "*_test.go",
    "*_test.yml", "*_test.yaml", "test_*.yml", "test_*.yaml",
    "*-tests.yml", "*_tests.yml", "*-tests.yaml", "*_tests.yaml",
    "*_test.dart",
)

# Non-Python tag syntaxes (DI-31): JS/TS runtime calls `allure.story("…")`
# (no decorator @), and Java annotations `@Story("…")`. Only the story names a
# design input; a feature carries the bounded context (DI-57).
POLYGLOT_TAG_PATTERNS = (
    re.compile(r'(?<!@)\ballure\.(story)\(\s*["\']([^"\']+)["\']'),
    re.compile(r'@(Story)\(\s*"([^"]+)"'),
)

# What a design-input / story id looks like. Kept deliberately narrow so
# ordinary Ansible tags (`bootstrap`, `security`) are not mistaken for ids.

# Ansible carries the id in the task's own tag list -- `tags: [DI-5]`, or a
# block/YAML-list form -- so the whole list is captured and split downstream.
YAML_TAG_PATTERN = re.compile(r"^\s*tags:\s*(?:\[([^\]]*)\]|(\S.*))?$", re.M)

def iter_test_files(tests_dir: Path):
    """Every conventional test file under ``tests_dir``, one ecosystem at a time.

    The single source of truth for "what counts as a test file" -- callers must
    not re-glob, because a second, narrower glob elsewhere is how the audit came
    to disagree with the Allure ingest about which files exist.
    """
    seen: set[Path] = set()
    for pattern in TEST_FILE_GLOBS:
        for path in tests_dir.rglob(pattern):
            if path not in seen:
                seen.add(path)
                yield path



def _tag_ids_in(path: Path, content: str) -> list[str]:
    """Every story tag ID a test source file claims, per its language."""
    if path.suffix == ".py":
        return _python_tag_ids(content)
    if path.suffix in (".yml", ".yaml"):
        ids = []
        for m in YAML_TAG_PATTERN.finditer(content):
            listed = m.group(1) or m.group(2) or ""
            for token in re.split(r"[,\s]+", listed.strip()):
                token = token.strip("\"'-").strip()
                if token and is_id(token):
                    ids.append(token)
        return ids
    ids: list[str] = []
    for pattern in POLYGLOT_TAG_PATTERNS:
        ids.extend(m.group(2) for m in pattern.finditer(content))
    return ids


def _allure_tag(node: ast.AST) -> str | None:
    """The id in ``allure.story("ID")``, else None."""
    if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and node.func.attr in DESIGN_INPUT_LABELS
            and isinstance(node.func.value, ast.Name) and node.func.value.id == "allure"
            and node.args and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)):
        return node.args[0].value
    return None


def _module_marks(tree: ast.Module) -> list[str]:
    """The tags of a module-level ``pytestmark = allure.story(...) | [ ... ]``."""
    tags: list[str] = []
    for node in tree.body:
        if (isinstance(node, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == "pytestmark" for t in node.targets)):
            marks = node.value.elts if isinstance(node.value, (ast.List, ast.Tuple)) else [node.value]
            tags.extend(tag for tag in map(_allure_tag, marks) if tag)
    return tags


def _python_tag_ids(content: str) -> list[str]:
    """Tags a Python test file claims (DI-40): allure decorators on functions
    and classes, and a module-level ``pytestmark`` -- never text inside strings
    or comments, so a test that writes fixture files does not claim their ids.
    A file that does not parse falls back to the decorator pattern."""
    try:
        tree = ast.parse(content)
    except (SyntaxError, ValueError):
        return [m.group(2) for m in ALLURE_PATTERN.finditer(content)]
    ids = _module_marks(tree)
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            ids.extend(tag for tag in map(_allure_tag, node.decorator_list) if tag)
    return ids


def _python_tests(content: str) -> list[tuple[str, list[str]]] | None:
    """Each tagged Python test in a file (DI-61): its qualified name
    (``test_x`` or ``TestClass::test_x``) and its tags — its own decorators,
    its class's, and a module-level ``pytestmark``'s, which reaches every
    ``test*`` function and ``Test*`` method. None when the file does not parse."""
    try:
        tree = ast.parse(content)
    except (SyntaxError, ValueError):
        return None
    module = _module_marks(tree)

    def tags_of(node) -> list[str]:
        return [tag for tag in map(_allure_tag, node.decorator_list) if tag]

    tests: list[tuple[str, list[str]]] = []
    functions = (ast.FunctionDef, ast.AsyncFunctionDef)
    for node in tree.body:
        if isinstance(node, functions):
            inherited = module if node.name.startswith("test") else []
            tests.append((node.name, tags_of(node) + inherited))
        elif isinstance(node, ast.ClassDef):
            outer = tags_of(node) + (module if node.name.startswith("Test") else [])
            for item in node.body:
                if isinstance(item, functions):
                    inherited = outer if item.name.startswith("test") else []
                    tests.append((f"{node.name}::{item.name}", tags_of(item) + inherited))
    return [(name, list(dict.fromkeys(tags))) for name, tags in tests if tags]


def scan_source_tests(tests_dir: Path) -> list[tuple[Path, str | None, list[str]]]:
    """Every tagged test under ``tests_dir`` (DI-61): ``(file, name, tags)``,
    where ``name`` is a Python test's qualified name, or None for a file whose
    tags are read by pattern (another language, or Python that does not
    parse) — then the file is the test."""
    found: list[tuple[Path, str | None, list[str]]] = []
    for test_file in sorted(iter_test_files(tests_dir)):
        try:
            content = test_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        tests = _python_tests(content) if test_file.suffix == ".py" else None
        if tests is not None:
            found.extend((test_file, name, tags) for name, tags in tests)
        else:
            tags = list(dict.fromkeys(_tag_ids_in(test_file, content)))
            if tags:
                found.append((test_file, None, tags))
    return found


def scan_source_tags(tests_dir: Path) -> dict[str, list[str]]:
    """Map each story tag ID to the test files that reference it.

    The source-tag counterpart of ``parse_results``: it reports which user needs
    a test *claims* to cover (vs. whether the executed test passed). Reads
    Python decorators, JS/TS allure calls, and Java annotations (DI-31).
    """
    refs: dict[str, list[str]] = {}
    for test_file in sorted(iter_test_files(tests_dir)):
        try:
            content = test_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        # One entry per file, not per tag occurrence: a file may claim the same
        # id many times -- an Ansible suite tags every task in a context with
        # the design input it exercises -- and callers report these as a file
        # count ("tagged (n file(s))").
        for tag_id in dict.fromkeys(_tag_ids_in(test_file, content)):
            refs.setdefault(tag_id, []).append(str(test_file))
    return refs


