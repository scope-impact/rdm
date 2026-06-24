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

import json
from dataclasses import dataclass, field
from pathlib import Path

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
class ValidationReport:
    by_user_need: dict[str, NeedValidation] = field(default_factory=dict)
    orphan_ids: list[str] = field(default_factory=list)
    runs_found: int = 0

    def _ids_with(self, status: str) -> list[str]:
        return sorted(uid for uid, v in self.by_user_need.items() if v.status == status)

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


def parse_runs(results_dir: Path) -> list[PersonaRun]:
    """Parse all ``*-persona.json`` run files in a results directory."""
    runs: list[PersonaRun] = []
    if not results_dir.exists():
        return runs
    for run_file in sorted(results_dir.glob("*-persona.json")):
        try:
            data = json.loads(run_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        uid = str(data.get("user_need", "")).strip()
        if not uid:
            continue
        issues = data.get("usability_issues") or []
        runs.append(
            PersonaRun(
                persona=str(data.get("persona", "")),
                user_need=uid,
                outcome=str(data.get("outcome", "")).lower(),
                usability_issues=[i for i in issues if isinstance(i, dict)],
                source=run_file.name,
            )
        )
    return runs


def reconcile(user_need_ids: set[str], results_dir: Path) -> ValidationReport:
    """Aggregate persona runs into a formative-validation status per user need.

    Status precedence per need: ``failed`` (any run could not complete) >
    ``issues`` (completed but problems observed) > ``clean`` (completed, none) >
    ``not_run`` (no persona attempted it). ``clean`` means "no formative issues
    found" -- it does NOT mean validated.
    """
    runs = parse_runs(Path(results_dir))
    aggregated = {uid: NeedValidation(uid) for uid in user_need_ids}
    referenced: set[str] = set()

    for run in runs:
        referenced.add(run.user_need)
        need = aggregated.get(run.user_need)
        if need is None:
            continue  # orphan, handled below
        need.runs += 1
        if run.persona and run.persona not in need.personas:
            need.personas.append(run.persona)
        need.issues.extend(run.usability_issues)
        if run.outcome in _FAILURE_OUTCOMES:
            need.failures += 1

    for need in aggregated.values():
        if need.runs == 0:
            need.status = NOT_RUN
        elif need.failures:
            need.status = FAILED
        elif need.issues:
            need.status = ISSUES
        else:
            need.status = CLEAN

    return ValidationReport(
        by_user_need=aggregated,
        orphan_ids=sorted(referenced - user_need_ids),
        runs_found=len(runs),
    )
