"""
The risk register (DI-43) and its release rules (DI-44).

Risks are frontmatter in ``kind: risk`` documents, as design inputs are in
``kind: design`` ones: a safety or security (STRIDE) risk, hazard → situation
→ harm, severity × probability, controls (design input ids), residual,
acceptance, status. Risks are evaluated only against a ``risk_policy`` the
project declares — there is no default, because acceptability criteria are
the project's, set before any individual decision.

Only what a machine can check is checked here. Whether a control is
effective, and whether a residual is as low as reasonably practicable, are
the reviewer's (see the requirements and risk-analysis skills).
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from rdm.kernel.events import Event
from rdm.kernel.frontmatter import documents

RISK_KIND = "risk"
CATEGORIES = ("safety", "security")
STRIDE = ("Spoofing", "Tampering", "Repudiation", "Information disclosure", "Denial of service",
          "Elevation of privilege")
ACCEPTABILITY = ("acceptable", "justify", "unacceptable")
STATUSES = ("proposed", "approved")
# Residual decisions (DI-45); only the first two let a release through.
ACCEPTABLE, ACCEPTED, NEEDS_ACCEPTANCE, UNACCEPTABLE, NOT_EVALUATED = (
    "acceptable", "accepted", "needs acceptance", "unacceptable", "not evaluated")


@dataclass(frozen=True)
class Policy:
    probabilities: tuple[str, ...]   # columns
    levels: dict                     # severity (a row) -> tuple of levels, one per probability
    acceptability: dict              # level -> acceptable | justify | unacceptable
    source: str
    status: str = "approved"

    @property
    def approved(self) -> bool:
        """Whether a person approved the acceptability criteria: any other
        status leaves the ratings made under them a proposal."""
        return self.status == "approved"

    def level(self, severity: str | None, probability: str | None) -> str | None:
        """The policy's level for a pair, or None when either is not defined."""
        if severity not in self.levels or probability not in self.probabilities:
            return None
        return self.levels[severity][self.probabilities.index(probability)]


@dataclass
class Risk:
    id: str
    document: str
    category: str = ""
    stride: str = ""
    linked: list[str] = field(default_factory=list)
    hazard: str = ""
    situation: str = ""
    harm: str = ""
    severity: str | None = None
    probability: str | None = None
    recorded_level: str | None = None
    controls: list[str] = field(default_factory=list)
    residual_severity: str | None = None      # defaults to severity
    residual_probability: str | None = None
    accepted_by: str = ""
    acceptance_rationale: str = ""
    status: str = "approved"
    level: str | None = None            # evaluated against the policy
    residual_level: str | None = None   # evaluated; the initial level when nothing controls it


def _text(value) -> str:
    return "" if value is None else str(value).strip()


def read_policy(dhf_dir: Path) -> Policy | None:
    """The declared ``risk_policy`` (the first, by path), or None when there is
    none. Raises ``ValueError`` naming the document when it is malformed."""
    for md, front in documents(dhf_dir):
        value = front.get("risk_policy")
        if value is None:
            continue
        where = str(md.relative_to(dhf_dir))
        if not isinstance(value, dict):
            raise ValueError(f"risk_policy in {where} is not a mapping")
        severities = tuple(_text(s) for s in value.get("severities") or [])
        probabilities = tuple(_text(p) for p in value.get("probabilities") or [])
        rows, verdicts = value.get("levels"), value.get("acceptability")
        if not severities or not probabilities or not isinstance(rows, dict) or not isinstance(verdicts, dict):
            raise ValueError(f"risk_policy in {where} needs severities, probabilities, levels and acceptability")
        levels = {}
        for severity in severities:
            row = tuple(_text(v) for v in rows.get(severity) or [])
            if len(row) != len(probabilities):
                raise ValueError(f"risk_policy in {where}: the {severity} row needs one level per probability")
            levels[severity] = row
        acceptability = {_text(k): _text(v) for k, v in verdicts.items()}
        for level in sorted({lv for row in levels.values() for lv in row}):
            if acceptability.get(level) not in ACCEPTABILITY:
                raise ValueError(f"risk_policy in {where}: level {level} needs an acceptability of "
                                 f"{', '.join(ACCEPTABILITY)}")
        status = _text(front.get("status")) or "approved"
        return Policy(probabilities, levels, acceptability, where, status)
    return None


def policy_or_none(dhf_dir: Path) -> Policy | None:
    """The policy, or None when there is none or it is malformed: for readers
    that only show the register (the release gate reports a malformed one)."""
    try:
        return read_policy(dhf_dir)
    except ValueError:
        return None


def risks(dhf_dir: Path, policy: Policy | None = None) -> list[Risk]:
    """Every risk entry in the register, in document order, evaluated against
    ``policy`` (no levels without one)."""
    dhf_dir = Path(dhf_dir)
    found: list[Risk] = []
    for md, front in documents(dhf_dir):
        if front.get("kind") != RISK_KIND or not isinstance(front.get("risks"), list):
            continue
        for item in front["risks"]:
            if not isinstance(item, dict):
                continue
            residual = item.get("residual") if isinstance(item.get("residual"), dict) else {}
            acceptance = item.get("acceptance") if isinstance(item.get("acceptance"), dict) else {}
            risk = Risk(
                id=_text(item.get("id")), document=str(md.relative_to(dhf_dir.parent)),
                category=_text(item.get("category")), stride=_text(item.get("stride")),
                linked=[_text(r) for r in item.get("linked") or [] if _text(r)],
                hazard=_text(item.get("hazard")), situation=_text(item.get("situation")),
                harm=_text(item.get("harm")),
                severity=_text(item.get("severity")) or None, probability=_text(item.get("probability")) or None,
                recorded_level=_text(item.get("level")) or None,
                controls=[_text(c) for c in item.get("controls") or [] if _text(c)],
                residual_severity=_text(residual.get("severity")) or None,
                residual_probability=_text(residual.get("probability")) or None,
                accepted_by=_text(acceptance.get("by")), acceptance_rationale=_text(acceptance.get("rationale")),
                status=_text(item.get("status")) or _text(front.get("status")) or "approved",
            )
            if policy is not None:
                risk.level = policy.level(risk.severity, risk.probability)
                if risk.residual_probability:
                    risk.residual_level = policy.level(risk.residual_severity or risk.severity,
                                                       risk.residual_probability)
                elif not risk.controls:
                    risk.residual_level = risk.level
            found.append(risk)
    return found


def residual_decision(risk: Risk, policy: Policy | None, verified: set[str]) -> str:
    """Whether the residual (the initial risk, when nothing controls it) lets a
    release through: never evaluated before every control is verified."""
    if policy is None or risk.residual_level is None:
        return NOT_EVALUATED
    if any(control not in verified for control in risk.controls):
        return NOT_EVALUATED
    verdict = policy.acceptability.get(risk.residual_level)
    if verdict == "acceptable":
        return ACCEPTABLE
    if verdict == "justify":
        return ACCEPTED if risk.accepted_by and risk.acceptance_rationale else NEEDS_ACCEPTANCE
    return UNACCEPTABLE


# Events (risk.md, "Commands and events"): each rule broken, and the two warnings.
_NOT_EVALUATED = "Risk Not Evaluated / "
POLICY_MALFORMED = _NOT_EVALUATED + "Policy Malformed"
NO_POLICY = _NOT_EVALUATED + "No Policy"
ID_MISSING = _NOT_EVALUATED + "Id Missing"
DUPLICATE_ID = _NOT_EVALUATED + "Duplicate Id"
INCOMPLETE = _NOT_EVALUATED + "Incomplete"
CATEGORY_MISSING = _NOT_EVALUATED + "Category Missing"
STRIDE_MISSING = _NOT_EVALUATED + "STRIDE Missing"
UNKNOWN_LINK = _NOT_EVALUATED + "Unknown Link"
SCORE_NOT_IN_POLICY = _NOT_EVALUATED + "Score Not In Policy"
LEVEL_MISMATCH = _NOT_EVALUATED + "Level Mismatch"
UNKNOWN_CONTROL = _NOT_EVALUATED + "Unknown Control"
RESIDUAL_UNSCORED = _NOT_EVALUATED + "Residual Unscored"
CONTROL_UNVERIFIED = _NOT_EVALUATED + "Control Unverified"
RESIDUAL_UNACCEPTABLE = _NOT_EVALUATED + "Residual Unacceptable"
ACCEPTANCE_MISSING = _NOT_EVALUATED + "Acceptance Missing"
UNKNOWN_STATUS = _NOT_EVALUATED + "Unknown Status"
POLICY_NOT_APPROVED = "Risk Warned / Policy Not Approved"
RISK_PROPOSED = "Risk Warned / Risk Proposed"


@dataclass(frozen=True)
class Finding(Event):
    """One event of the risk rules, about one risk (an index into the
    register) or, with None, the register as a whole."""

    risk: int | None = None


def assess(dhf_dir: Path, design_input_ids: set[str], verified: set[str]) -> tuple[list[Risk], list[Finding]]:
    """The register and every finding of the risk rules (DI-44, DI-50) — the one
    place the rules are written: the release gate reports these findings, and
    the graph projects them for its shapes (DI-45)."""
    try:
        policy = read_policy(dhf_dir)
    except ValueError as error:
        return risks(dhf_dir, None), [Finding(POLICY_MALFORMED, str(error))]
    register = risks(dhf_dir, policy)
    if not register:
        return register, []
    found: list[Finding] = []
    if policy is None:
        found.append(Finding(NO_POLICY, "the risk register has risks but no risk_policy is declared "
                             "(acceptability criteria missing)"))
    elif not policy.approved:
        found.append(Finding(POLICY_NOT_APPROVED,
                             f"the risk policy in {policy.source} is {policy.status}: a person has not approved it"))
    ids = Counter(r.id for r in register if r.id)
    reported: set[str] = set()  # each duplicated id once
    for index, r in enumerate(register):
        def block(event: str, message: str, index: int = index) -> None:
            found.append(Finding(event, message, risk=index))

        if not r.id:
            block(ID_MISSING, f"a risk in {r.document} has no id")
        elif ids[r.id] > 1 and r.id not in reported:
            reported.add(r.id)
            block(DUPLICATE_ID, f"risk {r.id} is declared {ids[r.id]} times")
        name = f"risk {r.id}" if r.id else f"a risk in {r.document} with no id"
        for part in ("hazard", "situation", "harm"):
            if not getattr(r, part):
                block(INCOMPLETE, f"{name} has no {part}")
        if r.category not in CATEGORIES:
            block(CATEGORY_MISSING, f"{name} needs a category of safety or security (got {r.category or 'none'})")
        elif r.category == "security" and r.stride not in STRIDE:
            block(STRIDE_MISSING, f"{name} is a security risk with no STRIDE category ({', '.join(STRIDE)})")
        for link in r.linked:
            if link not in ids:
                block(UNKNOWN_LINK, f"{name} links {link}, which is not a declared risk")
        if policy is None:
            block(NO_POLICY, f"{name} cannot be evaluated: no risk policy")
        elif r.level is None:
            block(SCORE_NOT_IN_POLICY, f"{name}: severity {r.severity!r} × probability {r.probability!r} "
                  f"is not defined by the risk policy ({policy.source})")
        elif r.recorded_level and r.recorded_level != r.level:
            block(LEVEL_MISMATCH, f"{name} records level {r.recorded_level}; the risk policy says {r.level}")
        for control in r.controls:
            if control not in design_input_ids:
                block(UNKNOWN_CONTROL, f"{name} names control {control}, which is not a declared design input")
        if r.controls and not r.residual_probability:
            block(RESIDUAL_UNSCORED, f"{name} has controls but no residual score")
        elif policy is not None and r.level is not None and r.residual_probability and r.residual_level is None:
            block(SCORE_NOT_IN_POLICY, f"{name}: residual {r.residual_severity or r.severity!r} × "
                  f"{r.residual_probability!r} is not defined by the risk policy")
        unverified = [c for c in r.controls if c in design_input_ids and c not in verified]
        if unverified and r.residual_level is not None:
            block(CONTROL_UNVERIFIED,
                  f"{name}: residual not evaluated — control {', '.join(unverified)} has no passing test")
        decision = residual_decision(r, policy, verified)
        if decision == UNACCEPTABLE:
            block(RESIDUAL_UNACCEPTABLE, f"{name} has an unacceptable residual level of {r.residual_level}")
        elif decision == NEEDS_ACCEPTANCE:
            block(ACCEPTANCE_MISSING, f"{name} has a residual level of {r.residual_level} that needs an acceptance "
                  "(who accepted it and why)")
        if r.status not in STATUSES:
            block(UNKNOWN_STATUS, f"{name} has an unknown status {r.status!r} (proposed or approved)")
        elif r.status == "proposed":
            found.append(Finding(RISK_PROPOSED, f"{name} is proposed: a person has not approved its rating",
                                 risk=index))
    return register, found

