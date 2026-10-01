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
      "verdict": "faithful",                 // faithful | partial | unfaithful
      "reviewer": "claude (independent of author)",
      "rationale": "exercises the real ingest path and asserts the grouped shape",
      "test_hash": "sha256:…",               // hash of the tagged test source at review
      "uncovered_clauses": []                // requirement clauses NOT exercised; non-empty -> partial
    }

This module ingests them and the release gate enforces them. Dependency-light
(stdlib + the shared record core) so it stays usable from the lightweight record
layer.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record.allure import scan_source_tags, scan_tagged_sources
from rdm.record.fingerprint import source_fingerprint, text_fingerprint
from rdm.record.reconcile import StatusReportMixin, aggregate_by_id, load_json_records

# Faithfulness statuses.
FAITHFUL = "faithful"
UNFAITHFUL = "unfaithful"   # reviewed but the test does not (or only weakly) verify the input
PARTIAL = "partial"         # the test covers some, but not all, of the requirement's clauses
STALE = "stale"             # the verifying test changed since the verdict was made
UNREVIEWED = "unreviewed"   # no faithfulness verdict on record

# Verdict hash scopes (DI-28). ``function`` pins the tagged test function's
# source only; ``module`` pins the full source of every file containing a
# tagged test, so editing a shared helper or fixture also re-opens the review.
# New verdicts default to module scope; verdicts recorded before the field
# existed are honored as function-scoped (no retroactive staleness).
SCOPE_FUNCTION = "function"
SCOPE_MODULE = "module"
DEFAULT_SCOPE = SCOPE_MODULE
_SCOPES = (SCOPE_FUNCTION, SCOPE_MODULE)

# Change classes of a stale verdict (DI-36), from the normalized fingerprints
# recorded with the verdict: only class A may be carried forward unreviewed.
CLASS_TRIVIAL = "A"       # formatting / comment / docstring-only change
CLASS_TEST = "B"          # the verifying-test source changed
CLASS_REQUIREMENT = "C"   # the requirement text (or an upstream input's) changed
CLASS_UNKNOWN = "D"       # no fingerprints on record: cannot classify
CLASS_LABELS = {
    CLASS_TRIVIAL: "formatting/comment-only change",
    CLASS_TEST: "verifying-test change",
    CLASS_REQUIREMENT: "requirement-text change",
    CLASS_UNKNOWN: "unclassifiable: no fingerprints on record",
}


@dataclass
class Verdict:
    """One faithfulness verdict, parsed from a ``*-faithfulness.json`` file."""

    design_input: str
    verdict: str
    reviewer: str = ""
    rationale: str = ""
    test_hash: str = ""
    # Hash scope the verdict was pinned at ("" = legacy, treated as function).
    hash_scope: str = ""
    # The reviewer's executed mutation probes (DI-27): each a dict with
    # file/find/replace/test (+ result). Killing probes can be replayed.
    probes: list[dict] = field(default_factory=list)
    # Requirement clauses the reviewer found NOT covered by the test. A non-empty
    # list means the test is at best partial, even if `verdict` says faithful.
    uncovered_clauses: list[str] = field(default_factory=list)
    # Normalized fingerprints at review time (DI-36); "" on older verdicts.
    text_fingerprint: str = ""
    tests_fingerprint: str = ""
    # Findings carried over from earlier verdicts (DI-39).
    prior_findings: list[dict] = field(default_factory=list)
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
    hash_scope: str = ""
    probes: list[dict] = field(default_factory=list)
    uncovered_clauses: list[str] = field(default_factory=list)
    text_fingerprint: str = ""
    tests_fingerprint: str = ""
    prior_findings: list[dict] = field(default_factory=list)
    # A/B/C/D for a stale verdict (DI-36); "" otherwise.
    change_class: str = ""


@dataclass
class FaithfulnessReport(StatusReportMixin):
    by_id: dict[str, DesignInputFaithfulness] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    verdicts_found: int = 0

    @property
    def faithful(self) -> list[str]:
        return self._ids_with(FAITHFUL)

    @property
    def unfaithful(self) -> list[str]:
        return self._ids_with(UNFAITHFUL)

    @property
    def partial(self) -> list[str]:
        return self._ids_with(PARTIAL)

    @property
    def stale(self) -> list[str]:
        return self._ids_with(STALE)

    @property
    def unreviewed(self) -> list[str]:
        return self._ids_with(UNREVIEWED)


def hash_for(di_text: str, test_sources: list[str]) -> str:
    """The verdict hash for a design input: its text + its tagged test source(s).

    Stable across reordering (sources are sorted). Changing the requirement text
    OR the verifying test body changes the hash, re-opening the review.
    """
    parts = [di_text.strip(), *sorted(s.strip() for s in test_sources)]
    digest = hashlib.sha256("\n--\n".join(parts).encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def upstream_closure(di_id: str, by_id: dict[str, dict]) -> list[str]:
    """Every declared input ``di_id`` transitively ``depends_on`` (DI-35).

    Sorted, excluding ``di_id`` itself; undeclared ids are skipped (the design
    gate warns on them) and cycles terminate.
    """
    seen: set[str] = set()
    pending = list((by_id.get(di_id) or {}).get("depends_on") or [])
    while pending:
        dep = pending.pop()
        if dep in seen or dep == di_id or dep not in by_id:
            continue
        seen.add(dep)
        pending.extend(by_id[dep].get("depends_on") or [])
    return sorted(seen)


def pinned_text(di: dict, by_id: dict[str, dict]) -> str:
    """The requirement text a verdict is pinned to: the input's own text plus,
    when it ``depends_on`` others, the text of its whole upstream closure.

    An input without dependencies pins exactly its own text, so DI-35 causes
    no retroactive staleness.
    """
    text = di.get("text", "")
    upstream = upstream_closure(di["id"], by_id)
    if not upstream:
        return text
    lines = [f"{u}: {str(by_id[u].get('text', '')).strip()}" for u in upstream]
    return text.strip() + "\n--depends_on--\n" + "\n".join(lines)


def verifying_sources(
    design_inputs: list[dict], tests_dir: Path | None, scope: str = SCOPE_FUNCTION
) -> dict[str, list[str]]:
    """The verifying-test source(s) each design input's pin covers, at ``scope``."""
    if scope == SCOPE_MODULE:
        files_by_id = scan_source_tags(tests_dir) if tests_dir else {}
        sources = {}
        for di_id, paths in files_by_id.items():
            texts = []
            for path in sorted(set(paths)):
                try:
                    texts.append(Path(path).read_text(encoding="utf-8", errors="ignore"))
                except OSError:
                    continue
            sources[di_id] = texts
    else:
        sources = scan_tagged_sources(tests_dir)
    return sources


def current_hashes(
    design_inputs: list[dict], tests_dir: Path | None, scope: str = SCOPE_FUNCTION
) -> dict[str, str]:
    """The hash each declared design input's verdict must match to be current.

    ``function`` scope hashes the tagged test functions' source segments;
    ``module`` scope hashes the full source of every file containing a tagged
    test for the input (so helper/fixture edits also invalidate the verdict).
    A dependent input's hash also covers its upstream closure's text (DI-35).
    """
    sources = verifying_sources(design_inputs, tests_dir, scope)
    by_id = {di["id"]: di for di in design_inputs}
    return {di["id"]: hash_for(pinned_text(di, by_id), sources.get(di["id"], [])) for di in design_inputs}


def current_fingerprints(
    design_inputs: list[dict], tests_dir: Path | None, scope: str = SCOPE_FUNCTION
) -> dict[str, tuple[str, str]]:
    """Normalized ``(text, tests)`` fingerprints per design input (DI-36)."""
    sources = verifying_sources(design_inputs, tests_dir, scope)
    by_id = {di["id"]: di for di in design_inputs}
    return {
        di["id"]: (text_fingerprint(pinned_text(di, by_id)), source_fingerprint(sources.get(di["id"], [])))
        for di in design_inputs
    }


def classify_change(recorded: tuple[str, str], current: tuple[str, str]) -> str:
    """Class of the change behind a stale verdict: requirement beats test."""
    if not recorded[0] or not recorded[1]:
        return CLASS_UNKNOWN
    if recorded[0] != current[0]:
        return CLASS_REQUIREMENT
    if recorded[1] != current[1]:
        return CLASS_TEST
    return CLASS_TRIVIAL


def parse_verdicts(verdicts_dir: Path) -> list[Verdict]:
    """Parse all ``*-faithfulness.json`` files in a directory."""
    def _build(data: dict, filename: str) -> Verdict | None:
        di = str(data.get("design_input", "")).strip()
        if not di:
            return None
        uncovered = data.get("uncovered_clauses") or []
        probes = data.get("probes") or []
        prior = data.get("prior_findings") or []
        return Verdict(
            design_input=di,
            verdict=str(data.get("verdict", "")).strip().lower(),
            reviewer=str(data.get("reviewer", "")).strip(),
            rationale=str(data.get("rationale", "")).strip(),
            test_hash=str(data.get("test_hash", "")).strip(),
            hash_scope=str(data.get("hash_scope", "")).strip().lower(),
            probes=[p for p in probes if isinstance(p, dict)],
            uncovered_clauses=[str(c).strip() for c in uncovered if str(c).strip()],
            text_fingerprint=str(data.get("text_fingerprint", "")).strip(),
            tests_fingerprint=str(data.get("tests_fingerprint", "")).strip(),
            prior_findings=[p for p in prior if isinstance(p, dict)] if isinstance(prior, list) else [],
            source=filename,
        )

    return load_json_records(Path(verdicts_dir), "-faithfulness.json", _build)


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
    # Each verdict is judged at the scope it was recorded at (DI-28); verdicts
    # from before the field existed are function-scoped. Hashes are computed
    # per scope on first use — most records are single-scope.
    expected: dict[str, dict] = {}

    def _expected(scope: str) -> dict:
        if scope not in expected:
            expected[scope] = current_hashes(design_inputs, tests_dir, scope)
        return expected[scope]

    def _fold(agg: DesignInputFaithfulness, v: Verdict) -> None:
        # Last verdict (sorted by filename) wins; record its content.
        agg.verdict = v.verdict
        agg.reviewer = v.reviewer
        agg.rationale = v.rationale
        agg.reviewed_hash = v.test_hash
        agg.hash_scope = v.hash_scope if v.hash_scope in _SCOPES else SCOPE_FUNCTION
        agg.probes = v.probes
        agg.uncovered_clauses = v.uncovered_clauses
        agg.text_fingerprint = v.text_fingerprint
        agg.tests_fingerprint = v.tests_fingerprint
        agg.prior_findings = v.prior_findings

    def _status(agg: DesignInputFaithfulness) -> str:
        if not agg.verdict:
            return UNREVIEWED
        # A verdict only counts for the exact test it reviewed, at the scope
        # it was pinned at.
        scope = agg.hash_scope if agg.hash_scope in _SCOPES else SCOPE_FUNCTION
        if agg.reviewed_hash != _expected(scope).get(agg.design_input, ""):
            return STALE
        # Explicit partial, OR a "faithful" verdict that nonetheless lists
        # uncovered clauses (an inconsistent claim) -> partial.
        if agg.verdict == PARTIAL or (agg.verdict == FAITHFUL and agg.uncovered_clauses):
            return PARTIAL
        if agg.verdict != FAITHFUL:
            return UNFAITHFUL
        return FAITHFUL

    by_id, orphan_ids = aggregate_by_id(
        di_ids,
        verdicts,
        ids_of=lambda v: [v.design_input],
        new=DesignInputFaithfulness,
        fold=_fold,
        status=_status,
    )
    # Classify each stale verdict by what changed (DI-36), at its own scope.
    fingerprints: dict[str, dict] = {}
    for agg in by_id.values():
        if agg.status != STALE:
            continue
        if agg.hash_scope not in fingerprints:
            fingerprints[agg.hash_scope] = current_fingerprints(design_inputs, tests_dir, agg.hash_scope)
        agg.change_class = classify_change(
            (agg.text_fingerprint, agg.tests_fingerprint),
            fingerprints[agg.hash_scope].get(agg.design_input, ("", "")),
        )
    return FaithfulnessReport(
        by_id=by_id,
        orphan_ids=orphan_ids,
        verdicts_found=len(verdicts),
    )
