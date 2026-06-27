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

from rdm.record.reconcile import StatusReportMixin, aggregate_by_id, load_json_records

# A run whose outcome is one of these counts as a failed attempt at the journey.
_FAILURE_OUTCOMES = {"failure", "failed", "blocked", "abandoned"}

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
    source: str = ""


@dataclass
class NeedValidation:
    """Aggregated formative-validation status for one user need."""

    user_need: str
    status: str = NOT_RUN
    runs: int = 0
    failures: int = 0
    issues: list[dict] = field(default_factory=list)
    personas: list[str] = field(default_factory=list)


@dataclass
class ValidationReport(StatusReportMixin):
    by_id: dict[str, NeedValidation] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    runs_found: int = 0

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
    """Build one ``PersonaRun`` from a parsed ``*-persona.json`` file (skip if it
    names no user need)."""
    uid = str(data.get("user_need", "")).strip()
    if not uid:
        return None
    issues = data.get("usability_issues") or []
    return PersonaRun(
        persona=str(data.get("persona", "")),
        user_need=uid,
        outcome=str(data.get("outcome", "")).lower(),
        usability_issues=[i for i in issues if isinstance(i, dict)],
        source=filename,
    )


def parse_runs(results_dir: Path) -> list[PersonaRun]:
    """Parse all ``*-persona.json`` run files in a results directory."""
    return load_json_records(results_dir, "-persona.json", _build_run)


def reconcile(user_need_ids: set[str], results_dir: Path) -> ValidationReport:
    """Aggregate persona runs into a formative-validation status per user need.

    Status precedence per need: ``failed`` (any run could not complete) >
    ``issues`` (completed but problems observed) > ``clean`` (completed, none) >
    ``not_run`` (no persona attempted it). ``clean`` means "no formative issues
    found" -- it does NOT mean validated.
    """
    runs = parse_runs(Path(results_dir))

    def _fold(need: NeedValidation, run: PersonaRun) -> None:
        need.runs += 1
        if run.persona and run.persona not in need.personas:
            need.personas.append(run.persona)
        need.issues.extend(run.usability_issues)
        if run.outcome in _FAILURE_OUTCOMES:
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
    )
