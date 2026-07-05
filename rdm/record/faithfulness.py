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


def current_hashes(
    design_inputs: list[dict], tests_dir: Path | None, scope: str = SCOPE_FUNCTION
) -> dict[str, str]:
    """The hash each declared design input's verdict must match to be current.

    ``function`` scope hashes the tagged test functions' source segments;
    ``module`` scope hashes the full source of every file containing a tagged
    test for the input (so helper/fixture edits also invalidate the verdict).
    """
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
    return {di["id"]: hash_for(di.get("text", ""), sources.get(di["id"], [])) for di in design_inputs}


def parse_verdicts(verdicts_dir: Path) -> list[Verdict]:
    """Parse all ``*-faithfulness.json`` files in a directory."""
    def _build(data: dict, filename: str) -> Verdict | None:
        di = str(data.get("design_input", "")).strip()
        if not di:
            return None
        uncovered = data.get("uncovered_clauses") or []
        probes = data.get("probes") or []
        return Verdict(
            design_input=di,
            verdict=str(data.get("verdict", "")).strip().lower(),
            reviewer=str(data.get("reviewer", "")).strip(),
            rationale=str(data.get("rationale", "")).strip(),
            test_hash=str(data.get("test_hash", "")).strip(),
            hash_scope=str(data.get("hash_scope", "")).strip().lower(),
            probes=[p for p in probes if isinstance(p, dict)],
            uncovered_clauses=[str(c).strip() for c in uncovered if str(c).strip()],
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
    # from before the field existed are function-scoped.
    expected = {
        SCOPE_FUNCTION: current_hashes(design_inputs, tests_dir, SCOPE_FUNCTION),
        SCOPE_MODULE: current_hashes(design_inputs, tests_dir, SCOPE_MODULE),
    }

    def _fold(agg: DesignInputFaithfulness, v: Verdict) -> None:
        # Last verdict (sorted by filename) wins; record its content.
        agg.verdict = v.verdict
        agg.reviewer = v.reviewer
        agg.rationale = v.rationale
        agg.reviewed_hash = v.test_hash
        agg.hash_scope = v.hash_scope if v.hash_scope in _SCOPES else SCOPE_FUNCTION
        agg.probes = v.probes
        agg.uncovered_clauses = v.uncovered_clauses

    def _status(agg: DesignInputFaithfulness) -> str:
        if not agg.verdict:
            return UNREVIEWED
        # A verdict only counts for the exact test it reviewed, at the scope
        # it was pinned at.
        scope = agg.hash_scope if agg.hash_scope in _SCOPES else SCOPE_FUNCTION
        if agg.reviewed_hash != expected[scope].get(agg.design_input, ""):
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
    return FaithfulnessReport(
        by_id=by_id,
        orphan_ids=orphan_ids,
        verdicts_found=len(verdicts),
    )
