"""
The risk register (DI-43) and its release rules (DI-44).

Risks are frontmatter in ``kind: risk`` documents, as design inputs are in
``kind: design`` ones: hazard → situation → harm, severity × probability,
controls (design input ids), residual probability, acceptance. Levels are
looked up in the risk matrix — the default four-by-four, or a ``risk_matrix``
declared in a DHF document's frontmatter — never typed in by hand.

Only what a machine can check is checked here. Whether a control is real in
the code, and whether a residual is as low as reasonably practicable, are the
reviewer's (see the risk-analysis skill).
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record.sdd import _frontmatter_of

RISK_KIND = "risk"
LEVELS = ("Low", "Medium", "High", "Block")


@dataclass(frozen=True)
class Matrix:
    severities: tuple[str, ...]      # rows
    probabilities: tuple[str, ...]   # columns
    levels: dict                     # severity -> tuple of levels, one per probability
    source: str = "default"

    def level(self, severity: str | None, probability: str | None) -> str | None:
        """The matrix level for a pair, or None when either is not in the matrix."""
        if severity not in self.levels or probability not in self.probabilities:
            return None
        return self.levels[severity][self.probabilities.index(probability)]


DEFAULT_MATRIX = Matrix(
    severities=("Critical", "Serious", "Minor", "Negligible"),
    probabilities=("Rare", "Unlikely", "Possible", "Likely"),
    levels={
        "Critical": ("Medium", "High", "High", "Block"),
        "Serious": ("Low", "Medium", "High", "High"),
        "Minor": ("Low", "Low", "Medium", "Medium"),
        "Negligible": ("Low", "Low", "Low", "Low"),
    },
)


@dataclass
class Risk:
    id: str
    document: str
    hazard: str = ""
    situation: str = ""
    harm: str = ""
    severity: str | None = None
    probability: str | None = None
    recorded_level: str | None = None
    controls: list[str] = field(default_factory=list)
    residual_probability: str | None = None
    accepted_by: str = ""
    acceptance_rationale: str = ""
    level: str | None = None            # computed from the matrix
    residual_level: str | None = None   # computed; the initial level when nothing controls it


def _docs(dhf_dir: Path):
    for md in sorted(Path(dhf_dir).rglob("*.md")):
        yield md, _frontmatter_of(md)


def read_matrix(dhf_dir: Path) -> Matrix:
    """The project's ``risk_matrix`` (the first declared, by path), else the default.
    Raises ``ValueError`` naming the document when a declared matrix is malformed."""
    for md, front in _docs(dhf_dir):
        value = front.get("risk_matrix")
        if value is None:
            continue
        where = md.relative_to(dhf_dir)
        if not isinstance(value, dict):
            raise ValueError(f"risk_matrix in {where} is not a mapping")
        severities = tuple(str(s) for s in value.get("severities") or [])
        probabilities = tuple(str(p) for p in value.get("probabilities") or [])
        rows = value.get("levels") or {}
        if not severities or not probabilities or not isinstance(rows, dict):
            raise ValueError(f"risk_matrix in {where} needs severities, probabilities and levels")
        levels = {}
        for severity in severities:
            row = tuple(str(v) for v in rows.get(severity) or [])
            if len(row) != len(probabilities):
                raise ValueError(f"risk_matrix in {where}: the {severity} row needs one level per probability")
            unknown = sorted(set(row) - set(LEVELS))
            if unknown:
                raise ValueError(f"risk_matrix in {where}: unknown level(s) {', '.join(unknown)} "
                                 f"(use {', '.join(LEVELS)})")
            levels[severity] = row
        return Matrix(severities, probabilities, levels, source=str(where))
    return DEFAULT_MATRIX


def _text(value) -> str:
    return "" if value is None else str(value).strip()


def risks(dhf_dir: Path, matrix: Matrix | None = None) -> list[Risk]:
    """Every risk entry in the register, in document order, each scored."""
    dhf_dir = Path(dhf_dir)
    matrix = matrix or read_matrix(dhf_dir)
    found: list[Risk] = []
    for md, front in _docs(dhf_dir):
        if front.get("kind") != RISK_KIND or not isinstance(front.get("risks"), list):
            continue
        for item in front["risks"]:
            if not isinstance(item, dict):
                continue
            residual = item.get("residual") if isinstance(item.get("residual"), dict) else {}
            acceptance = item.get("acceptance") if isinstance(item.get("acceptance"), dict) else {}
            risk = Risk(
                id=_text(item.get("id")), document=str(md.relative_to(dhf_dir.parent)),
                hazard=_text(item.get("hazard")), situation=_text(item.get("situation")),
                harm=_text(item.get("harm")),
                severity=_text(item.get("severity")) or None, probability=_text(item.get("probability")) or None,
                recorded_level=_text(item.get("level")) or None,
                controls=[_text(c) for c in item.get("controls") or [] if _text(c)],
                residual_probability=_text(residual.get("probability")) or None,
                accepted_by=_text(acceptance.get("by")), acceptance_rationale=_text(acceptance.get("rationale")),
            )
            risk.level = matrix.level(risk.severity, risk.probability)
            if risk.residual_probability:
                risk.residual_level = matrix.level(risk.severity, risk.residual_probability)
            elif not risk.controls:
                risk.residual_level = risk.level
            found.append(risk)
    return found


def blocking(dhf_dir: Path, design_input_ids: set[str]) -> list[str]:
    """The release gate's risk findings (DI-44), one message per problem."""
    try:
        matrix = read_matrix(dhf_dir)
    except ValueError as error:
        return [str(error)]
    register = risks(dhf_dir, matrix)
    messages: list[str] = []
    for risk_id, count in sorted(Counter(r.id for r in register if r.id).items()):
        if count > 1:
            messages.append(f"risk {risk_id} is declared {count} times")
    for r in register:
        name = f"risk {r.id}" if r.id else f"a risk in {r.document} with no id"
        if not r.id:
            messages.append(f"a risk in {r.document} has no id")
        for part in ("hazard", "situation", "harm"):
            if not getattr(r, part):
                messages.append(f"{name} has no {part}")
        if r.level is None:
            messages.append(f"{name}: severity {r.severity!r} × probability {r.probability!r} "
                            f"is not in the risk matrix ({matrix.source})")
        elif r.recorded_level and r.recorded_level != r.level:
            messages.append(f"{name} records level {r.recorded_level}; the matrix says {r.level}")
        if r.level in LEVELS[1:] and not r.controls:
            messages.append(f"{name} is {r.level} and nothing controls it")
        for control in r.controls:
            if control not in design_input_ids:
                messages.append(f"{name} names control {control}, which is not a declared design input")
        if r.controls and not r.residual_probability:
            messages.append(f"{name} has controls but no residual score")
        elif r.residual_probability and r.level is not None and r.residual_level is None:
            messages.append(f"{name}: residual probability {r.residual_probability!r} is not in the risk matrix")
        if r.residual_level == "Block":
            messages.append(f"{name} has a residual level of Block")
        elif r.residual_level in ("Medium", "High") and not (r.accepted_by and r.acceptance_rationale):
            messages.append(f"{name} has a residual level of {r.residual_level} with no acceptance "
                            "(who accepted it and why)")
    return messages
