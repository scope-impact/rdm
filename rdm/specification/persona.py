"""
Ingest AI-persona simulated-use runs as FORMATIVE usability validation evidence.

An AI persona (an LLM driving the device UI, e.g. via Playwright) attempts a
user-need journey as a represented user and emits one ``*-persona.json`` result
per run, tagged to the user need it exercised. This module reconciles those runs
against the user-need registry into a per-need formative-validation status.

IMPORTANT -- this is NOT summative validation. Summative usability validation
(IEC 62366-1) requires real, representative users in simulated use; an AI
persona cannot be the validation record of truth. Persona runs are *formative*
evidence: they surface use errors and usability problems early and act as
continuous simulated-use regression. The human summative study remains the
record. See docs/ai-persona-usability-validation.md.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from rdm.kernel.reconcile import StatusReportMixin, aggregate_by_id, load_json_records

# Only a run that says it completed did: any other outcome, or none, is a
# failed attempt at the journey.
_COMPLETED_OUTCOMES = {"completed", "complete", "success", "succeeded", "passed"}

# Per-user-need formative status.
NOT_RUN = "not_run"          # no persona attempted this user need
FAILED = "failed"            # a persona could not complete the journey
ISSUES = "issues"            # completed, but usability problems were observed
CLEAN = "clean"              # completed with no observed issues (NOT "validated")


@dataclass
class PersonaRun:
    """One AI-persona attempt at a user-need journey."""

    persona: str
    user_need: str
    outcome: str
    usability_issues: list[dict] = field(default_factory=list)


@dataclass
class NeedValidation:
    """Aggregated formative-validation status for one user need."""

    user_need: str
    status: str = NOT_RUN
    runs: int = 0
    failures: int = 0
    issues: list[dict] = field(default_factory=list)


@dataclass
class ValidationReport(StatusReportMixin):
    by_id: dict[str, NeedValidation] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    runs_found: int = 0
    # Run files that could not be read as a run: reported, never counted as clean.
    unreadable: list[str] = field(default_factory=list)

    @property
    def not_run(self) -> list[str]:
        return self._ids_with(NOT_RUN)

    @property
    def failed(self) -> list[str]:
        return self._ids_with(FAILED)

    @property
    def with_issues(self) -> list[str]:
        return self._ids_with(ISSUES)

    @property
    def clean(self) -> list[str]:
        return self._ids_with(CLEAN)


def _build_run(data: dict, filename: str) -> PersonaRun | None:
    """Build one ``PersonaRun`` from a parsed ``*-persona.json`` file; ``None``
    when it is not a run: it names no user need, or its usability issues are
    not a list."""
    uid = str(data.get("user_need") or "").strip()
    issues = data.get("usability_issues")
    if not uid or (issues is not None and not isinstance(issues, list)):
        return None
    return PersonaRun(
        persona=str(data.get("persona", "")),
        user_need=uid,
        outcome=str(data.get("outcome", "")).lower(),
        usability_issues=[i if isinstance(i, dict) else {"note": str(i)} for i in issues or []],
    )


def parse_runs(results_dir: Path, unreadable: list[str] | None = None) -> list[PersonaRun]:
    """Parse all ``*-persona.json`` run files in a results directory, adding the
    name of each file that cannot be read as a run to ``unreadable`` when given."""

    def build(data: dict, filename: str) -> PersonaRun | None:
        run = _build_run(data, filename)
        if run is None and unreadable is not None:
            unreadable.append(filename)
        return run

    return load_json_records(results_dir, "-persona.json", build, unreadable)


def reconcile(user_need_ids: set[str], results_dir: Path) -> ValidationReport:
    """Aggregate persona runs into a formative-validation status per user need.

    Status precedence per need: ``failed`` (any run could not complete) >
    ``issues`` (completed but problems observed) > ``clean`` (completed, none) >
    ``not_run`` (no persona attempted it). ``clean`` means "no formative issues
    found" -- it does NOT mean validated. A run file that cannot be read as a
    run is listed in ``unreadable``, never counted.
    """
    unreadable: list[str] = []
    runs = parse_runs(Path(results_dir), unreadable)

    def _fold(need: NeedValidation, run: PersonaRun) -> None:
        need.runs += 1
        need.issues.extend(run.usability_issues)
        if run.outcome not in _COMPLETED_OUTCOMES:
            need.failures += 1

    def _status(need: NeedValidation) -> str:
        if need.runs == 0:
            return NOT_RUN
        if need.failures:
            return FAILED
        if need.issues:
            return ISSUES
        return CLEAN

    by_id, orphan_ids = aggregate_by_id(
        user_need_ids,
        runs,
        ids_of=lambda run: [run.user_need],
        new=NeedValidation,
        fold=_fold,
        status=_status,
    )
    return ValidationReport(
        by_id=by_id,
        orphan_ids=orphan_ids,
        runs_found=len(runs),
        unreadable=sorted(unreadable),
    )
