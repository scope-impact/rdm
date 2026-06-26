"""
Faithfulness gate: independent confirmation that each design input's verifying
test actually *verifies* it -- not a hollow, tautological, or gamed assertion.

This is the agentic-native §820.30(e) review. A reviewer **independent of the
test author** (the ``test-faithfulness`` skill driving a second agent, or a
human) examines each pair of (design-input text, verifying-test source) and
records a verdict. Verifying that "the tests pass" is the release gate's job;
verifying that "the tests *mean something*" is this gate's job -- the missing
guard when an agent wrote both the requirement's test and the code under it.

A verdict is **pinned to a hash of the verifying-test source at review time**, so
any later edit to the test re-opens the review (the verdict goes ``stale``),
exactly as an edit to an approved design document re-opens the design gate. This
keeps approval *in-band*: an auditor can recompute the hash from the repo alone.

Verdicts are produced as ``*-faithfulness.json`` records:

    {
      "design_input": "DI-1",
      "verdict": "faithful",                 // faithful | unfaithful | weak
      "reviewer": "claude (independent of author)",
      "rationale": "exercises the real ingest path and asserts the grouped shape",
      "test_hash": "sha256:…",               // hash of the tagged test source at review
      "reviewed_tests": ["test_compile_verification_from_the_record"]
    }

This module ingests them and the release gate enforces them. Dependency-light
(stdlib only) so it stays usable from the lightweight record layer.
"""

from __future__ import annotations

import ast
import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record.reconcile import StatusReportMixin, aggregate_by_id

# Faithfulness statuses.
FAITHFUL = "faithful"
UNFAITHFUL = "unfaithful"   # reviewed and judged not to verify the input (or "weak")
STALE = "stale"             # the verifying test changed since the verdict was made
UNREVIEWED = "unreviewed"   # no faithfulness verdict on record

# The verdict value a reviewer must record for the input to count as faithful.
_FAITHFUL_VALUES = {FAITHFUL}

# @allure.story("…") / @allure.feature("…") decorator names that carry the DI id.
_TAG_ATTRS = ("story", "feature")


@dataclass
class Verdict:
    """One faithfulness verdict, parsed from a ``*-faithfulness.json`` file."""

    design_input: str
    verdict: str
    reviewer: str = ""
    rationale: str = ""
    test_hash: str = ""
    reviewed_tests: list[str] = field(default_factory=list)
    source: str = ""


@dataclass
class DesignInputFaithfulness:
    """Aggregated faithfulness state for one declared design input."""

    design_input: str
    status: str = UNREVIEWED
    verdict: str = ""
    reviewer: str = ""
    rationale: str = ""
    reviewed_hash: str = ""
    current_hash: str = ""
    reviewed_tests: list[str] = field(default_factory=list)


@dataclass
class FaithfulnessReport(StatusReportMixin):
    by_user_need: dict[str, DesignInputFaithfulness] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    verdicts_found: int = 0

    @property
    def faithful(self) -> list[str]:
        return self._ids_with(FAITHFUL)

    @property
    def unfaithful(self) -> list[str]:
        return self._ids_with(UNFAITHFUL)

    @property
    def stale(self) -> list[str]:
        return self._ids_with(STALE)

    @property
    def unreviewed(self) -> list[str]:
        return self._ids_with(UNREVIEWED)


def _allure_tag_id(decorator: ast.expr) -> str | None:
    """Return the DI id from an ``@allure.story("ID")`` / ``.feature`` decorator."""
    if not isinstance(decorator, ast.Call) or not decorator.args:
        return None
    func = decorator.func
    if not isinstance(func, ast.Attribute) or func.attr not in _TAG_ATTRS:
        return None
    first = decorator.args[0]
    if isinstance(first, ast.Constant) and isinstance(first.value, str):
        return first.value.strip()
    return None


def tagged_test_sources(tests_dir: Path | None) -> dict[str, list[str]]:
    """Map each design-input id to the source of the test function(s) tagged with it.

    Uses the AST so it captures the *function body* (what actually gets verified),
    not merely the file -- editing an unrelated test in the same file does not
    restale a verdict, but weakening the tagged function does.
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
            ids = [i for d in node.decorator_list if (i := _allure_tag_id(d))]
            if not ids:
                continue
            segment = ast.get_source_segment(text, node) or ""
            for di_id in ids:
                sources.setdefault(di_id, []).append(segment)
    return sources


def hash_for(di_text: str, test_sources: list[str]) -> str:
    """The verdict hash for a design input: its text + its tagged test source(s).

    Stable across reordering (sources are sorted). Changing the requirement text
    OR the verifying test body changes the hash, re-opening the review.
    """
    parts = [di_text.strip(), *sorted(s.strip() for s in test_sources)]
    digest = hashlib.sha256("\n--\n".join(parts).encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def current_hashes(design_inputs: list[dict], tests_dir: Path | None) -> dict[str, str]:
    """The hash each declared design input's verdict must match to be current."""
    sources = tagged_test_sources(tests_dir)
    return {di["id"]: hash_for(di.get("text", ""), sources.get(di["id"], [])) for di in design_inputs}


def parse_verdicts(verdicts_dir: Path) -> list[Verdict]:
    """Parse all ``*-faithfulness.json`` files in a directory."""
    verdicts: list[Verdict] = []
    if not verdicts_dir.exists():
        return verdicts
    for vf in sorted(verdicts_dir.glob("*-faithfulness.json")):
        try:
            data = json.loads(vf.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        di = str(data.get("design_input", "")).strip()
        if not di:
            continue
        reviewed = data.get("reviewed_tests") or []
        verdicts.append(
            Verdict(
                design_input=di,
                verdict=str(data.get("verdict", "")).strip().lower(),
                reviewer=str(data.get("reviewer", "")).strip(),
                rationale=str(data.get("rationale", "")).strip(),
                test_hash=str(data.get("test_hash", "")).strip(),
                reviewed_tests=[str(t).strip() for t in reviewed if str(t).strip()],
                source=vf.name,
            )
        )
    return verdicts


def reconcile(design_inputs: list[dict], verdicts_dir: Path, tests_dir: Path | None) -> FaithfulnessReport:
    """Aggregate faithfulness verdicts into a status per declared design input.

    Status rules per design input:
      - ``unreviewed`` if no verdict is on record,
      - ``unfaithful`` if the latest verdict is not ``faithful``,
      - ``stale``      if the verdict's hash no longer matches the current test,
      - ``faithful``   if a current verdict judges the test to verify the input.
    """
    verdicts = parse_verdicts(Path(verdicts_dir))
    di_ids = {di["id"] for di in design_inputs}
    expected = current_hashes(design_inputs, tests_dir)

    def _new(di_id: str) -> DesignInputFaithfulness:
        return DesignInputFaithfulness(design_input=di_id, current_hash=expected.get(di_id, ""))

    def _fold(agg: DesignInputFaithfulness, v: Verdict) -> None:
        # Last verdict (sorted by filename) wins; record its content.
        agg.verdict = v.verdict
        agg.reviewer = v.reviewer
        agg.rationale = v.rationale
        agg.reviewed_hash = v.test_hash
        agg.reviewed_tests = v.reviewed_tests

    def _status(agg: DesignInputFaithfulness) -> str:
        if not agg.verdict:
            return UNREVIEWED
        if agg.verdict not in _FAITHFUL_VALUES:
            return UNFAITHFUL
        if agg.reviewed_hash != agg.current_hash:
            return STALE
        return FAITHFUL

    by_di, orphan_ids = aggregate_by_id(
        di_ids,
        verdicts,
        ids_of=lambda v: [v.design_input],
        new=_new,
        fold=_fold,
        status=_status,
    )
    return FaithfulnessReport(
        by_user_need=by_di,
        orphan_ids=orphan_ids,
        verdicts_found=len(verdicts),
    )
