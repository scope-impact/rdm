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
    status: str = "proposed"

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
    recorded_residual_level: str | None = None
    controls: list[str] = field(default_factory=list)
    residual_severity: str | None = None      # defaults to severity
    residual_probability: str | None = None
    accepted_by: str = ""
    acceptance_rationale: str = ""
    status: str = "proposed"
    invalid_id: str = ""                # what was given as the id, when it was neither text nor a number
    level: str | None = None            # evaluated against the policy
    residual_level: str | None = None   # evaluated; the initial level when nothing controls it


def _text(value) -> str:
    return "" if value is None else str(value).strip()


def _words(value) -> str:
    """Text only: a list, mapping or number does not say what a hazard is."""
    return value.strip() if isinstance(value, str) else ""


def _id(value) -> str:
    """An id is text or a number; a list, mapping or boolean is none."""
    return _text(value) if isinstance(value, (str, int, float)) and not isinstance(value, bool) else ""


def _values(value) -> list[str]:
    """A list's items as text; one value, not a list, is that one value."""
    items = value if isinstance(value, list) else [] if value in (None, "") else [value]
    return [_text(v) for v in items if _text(v)]


def _is_register(front: dict) -> bool:
    return _text(front.get("kind")).lower() == RISK_KIND


def malformed_registers(dhf_dir: Path) -> list[str]:
    """Each risk document whose risks are not a list of mappings, or that has
    none; a risk document that only declares the policy is not a register."""
    return [str(md.relative_to(Path(dhf_dir).parent)) for md, front in documents(dhf_dir)
            if _is_register(front) and ("risks" in front or "risk_policy" not in front)
            and not (isinstance(front.get("risks"), list) and all(isinstance(r, dict) for r in front["risks"]))]


def read_policy(dhf_dir: Path) -> Policy | None:
    """The declared ``risk_policy``, or None when there is none. Raises
    ``ValueError`` naming the document when it is malformed, or naming both
    documents when a second one declares a policy."""
    declared = [(md, front) for md, front in documents(dhf_dir) if front.get("risk_policy") is not None]
    if len(declared) > 1:
        first, second = (str(md.relative_to(dhf_dir)) for md, _ in declared[:2])
        raise ValueError(f"risk_policy is declared twice, in {first} and in {second}: declare one")
    for md, front in declared:
        value = front["risk_policy"]
        where = str(md.relative_to(dhf_dir))
        if not isinstance(value, dict):
            raise ValueError(f"risk_policy in {where} is not a mapping")
        severities = tuple(_text(s) for s in value.get("severities") or [])
        probabilities = tuple(_text(p) for p in value.get("probabilities") or [])
        rows, verdicts = value.get("levels"), value.get("acceptability")
        rows = {_text(k): v for k, v in rows.items()} if isinstance(rows, dict) else rows
        if not severities or not probabilities or not isinstance(rows, dict) or not isinstance(verdicts, dict):
            raise ValueError(f"risk_policy in {where} needs severities, probabilities, levels and acceptability")
        for axis, names in (("severity", severities), ("probability", probabilities)):
            twice = sorted(name for name, n in Counter(names).items() if n > 1)
            if twice:
                raise ValueError(f"risk_policy in {where} lists the {axis} {', '.join(twice)} more than once")
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
        status = _text(front.get("status")) or "proposed"
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
        if not _is_register(front) or not isinstance(front.get("risks"), list):
            continue
        for item in front["risks"]:
            if not isinstance(item, dict):
                continue
            residual = item.get("residual") if isinstance(item.get("residual"), dict) else {}
            acceptance = item.get("acceptance") if isinstance(item.get("acceptance"), dict) else {}
            given = item.get("id")
            risk = Risk(
                id=_id(given), invalid_id="" if _id(given) or given in (None, "") else _text(given),
                document=str(md.relative_to(dhf_dir.parent)),
                category=_text(item.get("category")), stride=_text(item.get("stride")),
                linked=_values(item.get("linked")),
                hazard=_words(item.get("hazard")), situation=_words(item.get("situation")),
                harm=_words(item.get("harm")),
                severity=_text(item.get("severity")) or None, probability=_text(item.get("probability")) or None,
                recorded_level=_text(item.get("level")) or None,
                recorded_residual_level=_text(residual.get("level")) or None,
                controls=_values(item.get("controls")),
                residual_severity=_text(residual.get("severity")) or None,
                residual_probability=_text(residual.get("probability")) or None,
                accepted_by=_words(acceptance.get("by")), acceptance_rationale=_words(acceptance.get("rationale")),
                status=_text(item.get("status")) or _text(front.get("status")) or "proposed",
            )
            if policy is not None:
                risk.level = policy.level(risk.severity, risk.probability)
                if not risk.controls:  # only a control reduces a risk
                    risk.residual_level = risk.level
                elif risk.residual_probability:
                    risk.residual_level = policy.level(risk.residual_severity or risk.severity,
                                                       risk.residual_probability)
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
POLICY_MALFORMED = "Risk Not Evaluated / Policy Malformed"
NO_POLICY = "Risk Not Evaluated / No Policy"
ID_MISSING = "Risk Not Evaluated / Id Missing"
DUPLICATE_ID = "Risk Not Evaluated / Duplicate Id"
MALFORMED_REGISTER = "Risk Not Evaluated / Malformed Register"
INCOMPLETE = "Risk Not Evaluated / Incomplete"
CATEGORY_MISSING = "Risk Not Evaluated / Category Missing"
STRIDE_MISSING = "Risk Not Evaluated / STRIDE Missing"
UNKNOWN_LINK = "Risk Not Evaluated / Unknown Link"
SCORE_NOT_IN_POLICY = "Risk Not Evaluated / Score Not In Policy"
LEVEL_MISMATCH = "Risk Not Evaluated / Level Mismatch"
UNKNOWN_CONTROL = "Risk Not Evaluated / Unknown Control"
RESIDUAL_UNSCORED = "Risk Not Evaluated / Residual Unscored"
CONTROL_UNVERIFIED = "Risk Not Evaluated / Control Unverified"
RESIDUAL_UNACCEPTABLE = "Risk Not Evaluated / Residual Unacceptable"
ACCEPTANCE_MISSING = "Risk Not Evaluated / Acceptance Missing"
UNKNOWN_STATUS = "Risk Not Evaluated / Unknown Status"
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
    found: list[Finding] = [Finding(MALFORMED_REGISTER, f"the risks in {where} are not a list of risks")
                            for where in malformed_registers(dhf_dir)]
    if not register:
        return register, found
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

        if r.invalid_id:
            block(ID_MISSING, f"a risk in {r.document} has an id that is not text or a number: {r.invalid_id}")
        elif not r.id:
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
        elif r.residual_level and r.recorded_residual_level and r.recorded_residual_level != r.residual_level:
            block(LEVEL_MISMATCH, f"{name} records residual level {r.recorded_residual_level}; "
                  f"the risk policy says {r.residual_level}")
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

