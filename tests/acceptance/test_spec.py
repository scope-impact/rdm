"""Acceptance tests for reading the factory's spec (see .pi/spec/specification).

Over the real reader (``rdm.specification.spec``), a small Löyly-shaped spec
written here, and Löyly's own spec in the ``loyly`` submodule when it is
checked out. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from rdm.specification.spec import read_spec  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
LOYLY_PLAN = ROOT / "loyly" / ".pi" / "plan"

# Löyly's own checklist golden on spec-v2: 201 stamped items, per context.
LOYLY_COUNTS = {"specification": 15, "measurement": 43, "grader-lifecycle": 11, "regeneration-cycle": 76,
                "cycle-preparation": 4, "cycle-runner": 52}


def _context(plan: Path, entry: str, context: str, feature: str, **files: str) -> Path:
    """One slice of a spec: ``plan/<entry>`` links to ``../spec/<context>``."""
    folder = plan.parent / "spec" / context
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "spec.feature").write_text(feature)
    for name, text in files.items():
        path = folder / name.replace("__", "/")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    (plan / entry).symlink_to(Path("..") / "spec" / context)
    return folder


GATE = """Feature: Gate
  Rule: Booting

    @minimum @allium-rule-success.BootFresh @allium-guarantee.Fresh
    @item-03-001
    Scenario: boots fresh
      Given a candidate

    @item-03-009 @item-03-002
    Scenario:   refuses a busy seam
      Given a busy seam

  Rule:
    Scenario: has no stamp
"""

STAMPS = """Feature: Stamps
    @item-04-001
    Scenario: stamped outside any rule
"""


@allure.tag("item-01-001")
@allure.label("output", "rdm/specification/spec.py")
def test_the_spec_is_read_as_the_factory_checklist_reads_it(tmp_path: Path) -> None:
    """The requirements of a Löyly-shaped spec are read as Löyly's checklist
    reads them, and the reading imports no planning tool."""
    plan = tmp_path / ".pi" / "plan"
    plan.mkdir(parents=True)
    _context(plan, "04-stamps", "stamps", STAMPS)
    _context(plan, "03-gate", "gate", GATE, **{"drafts.feature": "Feature: d\n  @item-03-050\n  Scenario: draft\n"})
    _context(plan, "05-later", "later", "Feature: l\n  @item-05-001\n  Scenario: not yet\n",
             **{"plan.md": "# Later\n\nstatus: planned\n"})
    _context(plan, "06-someday", "someday", "Feature: s\n  @item-06-001\n  Scenario: someday\n",
             **{"plan.md": "---\nstatus: backlog\n---\n"})
    (plan / "notes").mkdir()  # an entry whose name starts with no digit is no slice
    (plan / "notes" / "spec.feature").write_text("Feature: n\n  @item-09-001\n  Scenario: ignored\n")
    spec = read_spec(plan, tmp_path)
    attach("items", [vars(i) for i in spec.items])

    with verification_step("slices are read in plan order; a planned or backlog slice is skipped and recorded, "
                           "and drafts.feature and an entry that is no slice are never read"):
        assert [i.requirement for i in spec.items] == ["item-03-001", "item-03-002", "item-04-001"]
        assert spec.skipped == [("05-later", "planned"), ("06-someday", "backlog")]
        assert [c for _, c in spec.contexts] == ["gate", "stamps"]
    with verification_step("each requirement keeps its Rule, trimmed title and source line; the last item tag "
                           "before a scenario is its id; the Rule is reset per file"):
        first, second, third = spec.items
        assert (first.rule, first.title, first.source) == ("Booting", "boots fresh", ".pi/plan/03-gate/spec.feature:6")
        assert (second.id, second.title) == ("item-03-002", "refuses a busy seam")
        assert third.rule is None and third.context == "stamps"
    with verification_step("the Allium obligations a scenario's tags name are read"):
        assert first.obligations == (("rule-success", "BootFresh"), ("guarantee", "Fresh"))
        assert second.obligations == ()
    with verification_step("an unstamped scenario is listed, never dropped quietly; no id is stamped twice"):
        assert spec.unstamped == [(".pi/plan/03-gate/spec.feature:14", "has no stamp")]
        assert spec.duplicates() == {}
    with verification_step("reading the spec imports no planning tool"):
        probe = ("import sys; from pathlib import Path; from rdm.specification.spec import read_spec; "
                 f"read_spec(Path({str(plan)!r})); "
                 "print(sorted(m for m in sys.modules if 'backlog' in m.lower() or 'jira' in m.lower()))")
        out = subprocess.run([sys.executable, "-c", probe], capture_output=True, text=True, check=True).stdout
        assert out.strip() == "[]"
    if LOYLY_PLAN.is_dir():
        with verification_step("Löyly's own spec reads as its checklist golden: 201 stamped items, per context"):
            loyly = read_spec(LOYLY_PLAN, ROOT / "loyly")
            counts = {}
            for item in loyly.items:
                counts[item.context] = counts.get(item.context, 0) + 1
            attach("Löyly counts", counts)
            assert len(loyly.items) == 201 and counts == LOYLY_COUNTS
            assert loyly.unstamped == [] and loyly.duplicates() == {} and loyly.problems == []


AREA = """{:desk
 {:screens
  {:desk/operator-desk
   {:description "The operator's view: derive a checklist."
    :grain/allium [{:spec "x" :kind :surface :name "Desk"}]
    :commands #{:desk/derive}}}}}
"""

COMMANDS = """;; the command and what it produces
{:desk {:commands {:desk/derive {:produces #{:desk/derived}}}}}
"""

STORM = """{:phases [{:id :p :events [{:id :derived :name "Checklist derived"
                                  :caused-by {:kind :actor :name "Operator"}}
                                 {:id :other :name "Policy fired" :caused-by {:kind :policy :name "x"}}]}]}
"""

DESK_ALLIUM = """rule Derive {
    when: DeriveChecklist(operator, cut)
    requires: true
}

rule Unrelated {
    when: Something(x)
}

-- The desk.
surface Desk {
    facing operator: Operator

    provides:
        DeriveChecklist(operator, cut)

    @guarantee NeverWritten
        -- derived, never written
}

-- A seam, facing a system: no screen links it.
surface Seam {
    facing orc: OrcRuntime
    provides:
        Something(x)
}
"""

LONE_ALLIUM = """-- An actor reads the report.
surface Report {
    facing reader: Reader

    @guarantee Readable
}
"""


@allure.tag("item-01-005")
@allure.label("output", "rdm/specification/spec.py")
@allure.label("output", "rdm/kernel/edn.py")
def test_user_needs_are_read_from_the_event_model_allium_and_storm(tmp_path: Path) -> None:
    """User needs are the event model's screens with the surfaces they link
    and the storm events behind them, or each surface of a context with no
    screens; a requirement addresses the needs whose rules or guarantees it
    covers, else its context's needs."""
    plan = tmp_path / ".pi" / "plan"
    plan.mkdir(parents=True)
    (tmp_path / ".pi" / "spec").mkdir()
    (tmp_path / ".pi" / "spec" / "event-storm.edn").write_text(STORM)
    _context(plan, "01-desk", "desk",
             "Feature: Desk\n  @allium-rule-success.Derive\n  @item-01-001\n  Scenario: derives\n\n"
             "  @allium-rule-failure.Unrelated\n  @item-01-002\n  Scenario: seam refuses\n",
             **{"allium__desk.allium": DESK_ALLIUM, "event-model__area.edn": AREA,
                "event-model__desk.edn": COMMANDS})
    _context(plan, "02-report", "report",
             "Feature: Report\n  @allium-guarantee.Readable\n  @item-02-001\n  Scenario: reads\n",
             **{"allium__report.allium": LONE_ALLIUM})
    spec = read_spec(plan, tmp_path)
    needs = {n.id: n for n in spec.needs}
    attach("needs", {k: vars(v) for k, v in needs.items()})

    with verification_step("a screen is a need, with its linked surfaces, actors, the rules they provide "
                           "(by trigger), their guarantees, its commands and the storm events they produce"):
        desk = needs["desk/operator-desk"]
        assert desk.text == "The operator's view: derive a checklist." and desk.surfaces == ["Desk"]
        assert desk.actors == ["Operator"] and desk.rules == ["Derive"] and desk.guarantees == ["NeverWritten"]
        assert desk.commands == ["desk/derive"] and desk.storm_events == ["Checklist derived (Operator)"]
        assert "Seam" not in needs  # a surface no screen links is no need of a context with screens
    with verification_step("in a context with no screens each surface is a need, its doc comment its text"):
        assert needs["Report"].text == "An actor reads the report." and needs["Report"].actors == ["Reader"]
    with verification_step("a requirement addresses the needs whose rules or guarantees it covers, else the "
                           "needs of its own context"):
        by_id = {i.requirement: i for i in spec.items}
        assert spec.needs_of(by_id["item-01-001"]) == ["desk/operator-desk"]
        assert spec.needs_of(by_id["item-01-002"]) == ["desk/operator-desk"]  # its context's need
        assert spec.directly_addressed(desk) == ["item-01-001"]
        assert spec.needs_of(by_id["item-02-001"]) == ["Report"]
    if LOYLY_PLAN.is_dir():
        with verification_step("Löyly's own spec: six needs, one screen per context, each directly addressed"):
            loyly = read_spec(LOYLY_PLAN, ROOT / "loyly")
            attach("Löyly needs", {n.id: loyly.directly_addressed(n) for n in loyly.needs})
            assert len(loyly.needs) == 6 and all(loyly.directly_addressed(n) for n in loyly.needs)


@allure.tag("item-01-001")
@allure.label("output", "rdm/specification/gherkin.py")
@allure.label("output", "rdm/specification/spec.py")
def test_plain_gherkin_is_read_by_glob_with_any_id_convention(tmp_path: Path) -> None:
    """Any feature files, wherever the configuration's globs find them, or
    spec/ and features/ at the repository root: each scenario tagged with an
    id-shaped tag (or the configured pattern) a requirement, its context the
    directory below the root, untagged scenarios listed."""
    from rdm.specification.spec import find_spec

    (tmp_path / ".git").mkdir()
    dhf = tmp_path / "dhf"
    dhf.mkdir()
    features = tmp_path / "features" / "alarms"
    features.mkdir(parents=True)
    (features / "sound.feature").write_text(
        "@needs\nFeature: Alarms sound\n  A nurse is alerted to a dangerous vital.\n\n"
        "  Rule: Thresholds\n    @smoke @REQ-1\n    Scenario: a high heart rate alarms\n      Given x\n\n"
        "    Scenario: not yet tagged\n")
    (tmp_path / "features" / "top.feature").write_text("Feature: Top\n  @REQ-2\n  Scenario: at the root\n")

    with verification_step("without configuration, features/ at the repository root is found; any id-shaped "
                           "tag is a requirement id, other tags are kept, an untagged scenario is listed"):
        config = find_spec(dhf)
        spec = read_spec(config)
        attach("items", [vars(i) for i in spec.items])
        reqs = {i.requirement: i for i in spec.items}
        assert sorted(reqs) == ["REQ-1", "REQ-2"]
        first = reqs["REQ-1"]
        assert (first.context, first.feature, first.rule, first.title) == (
            "alarms", "Alarms sound", "Thresholds", "a high heart rate alarms")
        assert first.source == "features/alarms/sound.feature:7" and "@smoke" in first.tags
        assert reqs["REQ-2"].context == ""
        assert spec.unstamped == [("features/alarms/sound.feature:10", "not yet tagged")]
    with verification_step("the configuration names the globs, files to exclude and the id pattern"):
        (dhf / "config.yml").write_text(
            "spec:\n  features: ['../features/**/*.feature']\n  exclude: ['top.feature']\n  id: 'REQ-1'\n")
        spec = read_spec(find_spec(dhf))
        assert [i.requirement for i in spec.items] == ["REQ-1"]


@allure.tag("item-01-005")
@allure.label("output", "rdm/specification/spec.py")
def test_by_default_each_feature_is_a_user_need(tmp_path: Path) -> None:
    """Without a needs adapter each Gherkin Feature is a user need, its
    description the need's text, addressed by the scenarios under it."""
    (tmp_path / ".git").mkdir()
    (tmp_path / "dhf").mkdir()
    spec_dir = tmp_path / "spec" / "alarms"
    spec_dir.mkdir(parents=True)
    (spec_dir / "sound.feature").write_text(
        "Feature: Alarms sound\n  A nurse is alerted to a dangerous vital.\n\n"
        "  @REQ-1\n  Scenario: a high heart rate alarms\n")
    (spec_dir / "quiet.feature").write_text("Feature: Quiet hours\n  @REQ-2\n  Scenario: night mode\n")
    from rdm.specification.sdd import load_spec

    spec = load_spec(tmp_path / "dhf")
    needs = {n.id: n for n in spec.needs}
    attach("needs", {k: vars(v) for k, v in needs.items()})
    with verification_step("each Feature is a need, its description its text, in its file's context"):
        assert sorted(needs) == ["Alarms sound", "Quiet hours"]
        assert needs["Alarms sound"].text == "A nurse is alerted to a dangerous vital."
        assert needs["Alarms sound"].context == "alarms"
    with verification_step("a requirement addresses the Feature it sits under"):
        by_id = {i.requirement: i for i in spec.items}
        assert spec.needs_of(by_id["REQ-1"]) == ["Alarms sound"]
        assert spec.directly_addressed(needs["Quiet hours"]) == ["REQ-2"]
