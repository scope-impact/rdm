"""Acceptance tests for the risk context's design inputs (DI-43, DI-44, see dhf/).

Tagged `@allure.story`, over the real register reader and the real release
gate. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest
import yaml

from rdm.record.risk import read_policy, risks
from rdm.gates.design_gate import run_release_gate
from tests.acceptance.test_graph_shapes import _dhf, _results

allure = pytest.importorskip("allure")

POLICY = {
    "severities": ["Critical", "Serious", "Minor", "Negligible"],
    "probabilities": ["Rare", "Unlikely", "Possible", "Likely"],
    "levels": {"Critical": ["Medium", "High", "High", "Block"], "Serious": ["Low", "Medium", "High", "High"],
               "Minor": ["Low", "Low", "Medium", "Medium"], "Negligible": ["Low", "Low", "Low", "Low"]},
    "acceptability": {"Low": "acceptable", "Medium": "justify", "High": "justify", "Block": "unacceptable"},
}


def _policy(dhf: Path, policy: dict | None = None, **front) -> Path:
    path = dhf / "documents" / "risk" / "policy.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("---\n" + yaml.safe_dump({"id": "RMP-1", **front, "risk_policy": policy or POLICY},
                                             sort_keys=False) + "---\n# Policy\n")
    return path


def _register(dhf: Path, entries: list[dict], name: str = "risks", **front) -> Path:
    path = dhf / "documents" / "risk" / f"{name}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("---\n" + yaml.safe_dump({"id": f"RMF-{name}", "kind": "risk", **front, "risks": entries},
                                             sort_keys=False) + "---\n# Risks\n")
    return path


def _risk(rid: str | None, **overrides) -> dict:
    entry = {"id": rid, "category": "safety", "hazard": "h", "situation": "s", "harm": "x",
             "severity": "Serious", "probability": "Possible", "controls": ["DI-1"],
             "residual": {"probability": "Rare"}}
    entry.update(overrides)
    return {k: v for k, v in entry.items() if v is not None}


ACCEPT = {"by": "QA lead", "rationale": "ALARP"}

# Each case: register entries, whether to declare the policy, and the gate's
# expected risk findings (a set of exact messages, or one message containing
# the text). Shared with DI-45's test. DI-1 passes; DI-2 has no passing test.
CASES = {
    "sound": ([_risk("RISK-OK-1"),
               _risk("RISK-OK-2", severity="Minor", probability="Rare", controls=None, residual=None),
               _risk("RISK-OK-3", residual={"probability": "Unlikely"}, acceptance=ACCEPT),
               _risk("RISK-OK-4", category="security", stride="Tampering", linked=["RISK-OK-1"]),
               # a control that limits the harm itself: residual severity drops
               _risk("RISK-OK-5", residual={"severity": "Minor", "probability": "Possible"}, acceptance=ACCEPT)],
              True, set()),
    "no-policy": ([_risk("RISK-P-1")], False,
                  {"the risk register has risks but no risk_policy is declared (acceptability criteria missing)",
                   "risk RISK-P-1 cannot be evaluated: no risk policy"}),
    "duplicate": ([_risk("RISK-D-1"), _risk("RISK-D-1")], True, {"risk RISK-D-1 is declared 2 times"}),
    "no-hazard": ([_risk("RISK-C-1", hazard="")], True, {"risk RISK-C-1 has no hazard"}),
    "no-situation": ([_risk("RISK-C-2", situation=None)], True, {"risk RISK-C-2 has no situation"}),
    "no-harm": ([_risk("RISK-C-3", harm=" ")], True, {"risk RISK-C-3 has no harm"}),
    "no-category": ([_risk("RISK-B-1", category=None)], True, "needs a category of safety or security"),
    "bad-category": ([_risk("RISK-B-2", category="ops")], True, "needs a category of safety or security (got ops)"),
    "no-stride": ([_risk("RISK-B-3", category="security")], True, "is a security risk with no STRIDE category"),
    "bad-stride": ([_risk("RISK-B-4", category="security", stride="Hacking")], True, "no STRIDE category"),
    "bad-link": ([_risk("RISK-B-5", linked=["RISK-NOPE-9"])], True,
                 {"risk RISK-B-5 links RISK-NOPE-9, which is not a declared risk"}),
    "unknown-severity": ([_risk("RISK-S-1", severity="Awful")], True, "is not defined by the risk policy"),
    "unknown-probability": ([_risk("RISK-S-2", probability=None)], True, "is not defined by the risk policy"),
    "mis-scored": ([_risk("RISK-S-3", level="Low")], True,
                   {"risk RISK-S-3 records level Low; the risk policy says High"}),
    # Nothing controls it, so the initial risk is the residual: no acceptance makes Block pass.
    "uncontrolled-unacceptable": ([_risk("RISK-U-1", severity="Critical", probability="Likely", controls=None,
                                         residual=None, acceptance=ACCEPT)], True,
                                  {"risk RISK-U-1 has an unacceptable residual level of Block"}),
    "uncontrolled-unaccepted": ([_risk("RISK-U-3", probability="Unlikely", controls=None, residual=None)], True,
                                "has a residual level of Medium that needs an acceptance"),
    "undeclared-control": ([_risk("RISK-U-2", controls=["DI-1", "DI-77"])], True,
                           {"risk RISK-U-2 names control DI-77, which is not a declared design input"}),
    "no-residual": ([_risk("RISK-R-1", residual=None)], True, {"risk RISK-R-1 has controls but no residual score"}),
    "bad-residual": ([_risk("RISK-R-2", residual={"probability": "Never"})], True,
                     "residual 'Serious' × 'Never' is not defined"),
    "unverified-control": ([_risk("RISK-R-6", controls=["DI-1", "DI-2"])], True,
                           {"risk RISK-R-6: residual not evaluated — control DI-2 has no passing test"}),
    "block-residual": ([_risk("RISK-R-3", severity="Critical", probability="Likely",
                              residual={"probability": "Likely"}, acceptance=ACCEPT)], True,
                       {"risk RISK-R-3 has an unacceptable residual level of Block"}),
    "unaccepted-medium": ([_risk("RISK-R-4", residual={"probability": "Unlikely"})], True,
                          "has a residual level of Medium that needs an acceptance"),
    "half-accepted-high": ([_risk("RISK-R-5", residual={"probability": "Possible"}, acceptance={"by": "QA"})],
                           True, "has a residual level of High that needs an acceptance"),
    "bad-status": ([_risk("RISK-T-1", status="maybe")], True, {"risk RISK-T-1 has an unknown status 'maybe' "
                                                                "(proposed or approved)"}),
}


def _gate(tmp_path: Path, name: str, entries: list[dict], with_policy: bool = True, **front):
    dhf = _dhf(tmp_path / name)
    if with_policy:
        _policy(dhf)
    _register(dhf, entries, **front)
    return dhf, run_release_gate(dhf, _results(tmp_path / name, {"DI-1": ["passed"]}))


@allure.story("DI-43")
@allure.label("output", "rdm/record/risk.py")
def test_register_is_read_and_evaluated_against_the_declared_policy(tmp_path: Path) -> None:
    """DI-43: every field of a risk is read from kind: risk frontmatter;
    levels come only from a declared risk policy (no default); residual
    severity defaults to the initial one; status falls back to the document's."""
    dhf = tmp_path / "dhf"
    _register(dhf, [
        {"id": "RISK-A-1", "category": "security", "stride": "Spoofing", "linked": ["RISK-A-2"],
         "hazard": "Hz", "situation": "Si", "harm": "Ha", "severity": "Serious", "probability": "Possible",
         "level": "High", "controls": ["DI-1", "DI-2"], "residual": {"probability": "Unlikely"},
         "acceptance": {"by": "QA lead", "rationale": "ALARP"}, "status": "approved"},
        {"id": "RISK-A-2", "category": "safety", "hazard": "Hz", "situation": "Si", "harm": "Ha",
         "severity": "Serious", "probability": "Possible", "controls": ["DI-1"],
         "residual": {"severity": "Minor", "probability": "Possible"}},
        {"id": "RISK-A-3", "category": "safety", "hazard": "Hz", "situation": "Si", "harm": "Ha",
         "severity": "Minor", "probability": "Rare"},
    ], status="proposed")
    (dhf / "documents" / "notes.md").write_text("---\nid: N-1\nrisks: [{id: RISK-NOT-1}]\n---\n")  # not kind: risk

    # No policy declared: everything is read, nothing is evaluated.
    assert read_policy(dhf) is None
    unevaluated = {r.id: r for r in risks(dhf)}
    assert set(unevaluated) == {"RISK-A-1", "RISK-A-2", "RISK-A-3"}
    assert all(r.level is None and r.residual_level is None for r in unevaluated.values())

    a = unevaluated["RISK-A-1"]
    assert (a.category, a.stride, a.linked) == ("security", "Spoofing", ["RISK-A-2"])
    assert (a.hazard, a.situation, a.harm, a.severity, a.probability) == ("Hz", "Si", "Ha", "Serious", "Possible")
    assert a.recorded_level == "High" and a.controls == ["DI-1", "DI-2"]
    assert (a.residual_severity, a.residual_probability) == (None, "Unlikely")
    assert (a.accepted_by, a.acceptance_rationale) == ("QA lead", "ALARP")
    assert a.status == "approved" and unevaluated["RISK-A-2"].status == "proposed"  # the document's status
    assert a.document == "dhf/documents/risk/risks.md"

    # With a declared policy: levels from its cells; residual severity defaults
    # to the initial one, and is used where recorded.
    _policy(dhf, status="proposed")
    policy = read_policy(dhf)
    assert policy.source == "documents/risk/policy.md" and policy.status == "proposed"
    assert policy.acceptability["Medium"] == "justify"
    found = {r.id: r for r in risks(dhf, policy)}
    assert (found["RISK-A-1"].level, found["RISK-A-1"].residual_level) == ("High", "Medium")  # Serious × Unlikely
    assert (found["RISK-A-2"].level, found["RISK-A-2"].residual_level) == ("High", "Medium")  # Minor × Possible
    assert (found["RISK-A-3"].level, found["RISK-A-3"].residual_level) == ("Low", "Low")      # uncontrolled

    # Level names are the project's own.
    _policy(dhf, {"severities": ["Bad", "Mild"], "probabilities": ["Seldom", "Often"],
                  "levels": {"Bad": ["Amber", "Red"], "Mild": ["Green", "Amber"]},
                  "acceptability": {"Green": "acceptable", "Amber": "justify", "Red": "unacceptable"}})
    _register(dhf, [_risk("RISK-B-1", severity="Bad", probability="Often", residual={"probability": "Seldom"})])
    custom = risks(dhf, read_policy(dhf))[0]
    assert (custom.level, custom.residual_level) == ("Red", "Amber")

    # A malformed policy is refused, naming where it is.
    for bad in ({"severities": ["Bad"], "probabilities": ["Often"], "levels": {"Bad": ["Red"]}},  # no acceptability
                {"severities": ["Bad"], "probabilities": ["Often", "Seldom"], "levels": {"Bad": ["Red"]},
                 "acceptability": {"Red": "unacceptable"}},
                {"severities": ["Bad"], "probabilities": ["Often"], "levels": {"Bad": ["Red"]},
                 "acceptability": {"Red": "fine"}}):
        _policy(dhf, bad)
        with pytest.raises(ValueError, match="documents/risk/policy.md"):
            read_policy(dhf)


@allure.story("DI-44")
@allure.label("output", "rdm/record/risk.py")
def test_release_gate_blocks_on_the_risk_rules(tmp_path: Path) -> None:
    """DI-44: missing criteria, duplicate or missing ids, a broken chain or
    branch, an undefined or mis-scored risk, an undeclared or unverified
    control, a missing residual, an unacceptable or unaccepted residual and an
    unknown status each block; proposed ratings warn; a sound register passes."""
    for name, (entries, with_policy, expected) in CASES.items():
        _, gate = _gate(tmp_path, name, entries, with_policy)
        found = [m for m in gate.blocking if "risk" in m]
        if isinstance(expected, set):
            assert set(found) == expected, (name, found)
        else:
            assert len(found) == 1 and expected in found[0], (name, found)
        for message in found:  # each per-risk message names the risk, for the graph's agreement test
            assert re.search(r"RISK-[A-Z]+-\d", message) or "no risk_policy is declared" in message, message

    # Proposed ratings — on a risk, or on the policy — warn and do not block.
    dhf, _ = _gate(tmp_path, "proposed", [_risk("RISK-W-1", status="proposed"), _risk("RISK-W-2")])
    _policy(dhf, status="proposed")
    gate = run_release_gate(dhf, _results(tmp_path / "proposed", {"DI-1": ["passed"], "DI-2": ["passed"]}))
    assert gate.passed, gate.blocking
    assert "risk RISK-W-1 is proposed: a person has not approved its rating" in gate.warnings
    assert "the risk policy in documents/risk/policy.md is proposed: a person has not approved it" in gate.warnings
    assert not any("RISK-W-2" in w for w in gate.warnings)

    # No register, no risk findings — the policy is required only when there are risks.
    dhf = _dhf(tmp_path / "none")
    assert not [m for m in run_release_gate(dhf, _results(tmp_path / "none", {"DI-1": ["passed"],
                                                                              "DI-2": ["passed"]})).blocking
                if "risk" in m]

    # A risk with no id is named by its document; a malformed policy blocks with its reason.
    dhf, gate = _gate(tmp_path, "anonymous", [_risk(None)])
    assert "a risk in dhf/documents/risk/risks.md has no id" in gate.blocking
    (dhf / "documents" / "risk" / "policy.md").write_text("---\nid: RMP\nrisk_policy: [1, 2]\n---\n")
    gate = run_release_gate(dhf, _results(tmp_path / "anonymous", {"DI-1": ["passed"]}))
    assert any("risk_policy in documents/risk/policy.md" in m for m in gate.blocking)
