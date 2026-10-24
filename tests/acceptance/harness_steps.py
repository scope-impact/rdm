"""Step definitions for slice 01's spec (dhf/plan/01-harness/spec.feature).

The scenarios are the acceptance criteria (ADR 0002); these steps only drive
RDM and look at what it did. Every outcome is a domain event, so one step
(`the event is "..."`) checks them all, against the events the last command
produced (``world["events"]``) and the message each carries.
"""

from __future__ import annotations

import textwrap
from pathlib import Path

import pytest
from pytest_bdd import given, parsers, then, when

from rdm.specification.design_gate import run_design_gate
from rdm.specification.sdd import declarations, design_inputs
from tests.acceptance.evidence import attach

VV_PLAN = """---
id: VVP-1
title: V&V plan
user_needs:
  - {id: UN-001, text: "A need."}
---

# V&V plan
"""
DESIGN_DOC = """---
id: SDS-1
kind: design
context: specification
design_inputs: []
---

# Specification

Design outputs.
"""
REVIEW = "---\nid: DR-1\ntitle: Review\n---\n\n# Design Review 1\n\nApproved.\n"


@pytest.fixture
def world(tmp_path: Path) -> dict:
    """A project with a minimal record (``dhf/``) and what the steps learn."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "verification_and_validation_plan.md").write_text(VV_PLAN)
    (dhf / "documents" / "design" / "specification.md").write_text(DESIGN_DOC)
    (dhf / "documents" / "design_review.md").write_text(REVIEW)
    return {"root": tmp_path, "dhf": dhf, "events": []}


def write(world: dict, relative: str, text: str) -> Path:
    path = world["root"] / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


def spec_with(world: dict, body: str, slice_dir: str = "07-example") -> Path:
    path = write(world, f"dhf/plan/{slice_dir}/spec.feature", textwrap.dedent(body).lstrip("\n"))
    world["spec"] = path
    return path


def named(world: dict, name: str):
    found = [e for e in world["events"] if e.name == name]
    assert found, f"no {name!r} among {[e.name for e in world['events']]}"
    return found[0]


# -- outcomes ------------------------------------------------------------------

@then(parsers.parse('the event is "{name}"'))
def the_event_is(world, name):
    attach("events", [f"{e.name}: {e.message}" for e in world["events"]])
    world["event"] = named(world, name)


# -- DI-90: the record reads the spec --------------------------------------------

@given(parsers.parse('a slice "{path}" containing:'))
def a_slice_containing(world, path, docstring):
    world["spec"] = write(world, path, docstring + "\n")


@given("DI-500 is declared by a Rule in a slice's spec")
def di500_in_a_spec(world):
    spec_with(world, """
        Feature: F

          @DI-500 @UN-001 @context-specification
          Rule: RDM shall do one thing

            @item-07-001
            Scenario: it does it
              Given a
        """)


@given("DI-500 is also declared in a design document's design inputs")
def di500_in_a_design_document(world):
    doc = world["dhf"] / "documents" / "design" / "specification.md"
    declared = 'design_inputs:\n  - {id: DI-500, text: "x", traces_to: [UN-001]}'
    doc.write_text(DESIGN_DOC.replace("design_inputs: []", declared))
    world["document"] = doc


@given("a slice's spec with a Rule tagged with no design-input id")
def rule_without_id(world):
    spec_with(world, """
        Feature: F

          @UN-001 @context-specification
          Rule: RDM shall do one thing

            @item-07-001
            Scenario: it does it
              Given a
        """)
    world["line"] = 4


@given(parsers.parse('a slice\'s spec with a Rule tagged "@DI-500" and no context tag'))
def rule_without_owner(world):
    spec_with(world, """
        Feature: F

          @DI-500 @UN-001
          Rule: RDM shall do one thing

            @item-07-001
            Scenario: it does it
              Given a
        """)


@given("a slice's spec whose third line is not Gherkin")
def unreadable_spec(world):
    spec_with(world, """
        Feature: F

          @DI-500 @UN-001 has spaces
          Rule: RDM shall do one thing

            Scenario: it does it
              Given a
        """)


@when("the record is read")
def read_record(world):
    world["inputs"] = {di["id"]: di for di in design_inputs(world["dhf"])}
    world["declared"] = declarations(world["dhf"])
    attach("design inputs", world["inputs"])


@when("the design gate runs")
def design_gate_runs(world):
    result = run_design_gate(world["dhf"])
    world["events"] = [e for a in result.artifacts for e in a.events] + list(result.task_warnings)
    world["inputs"] = {di["id"]: di for di in design_inputs(world["dhf"])}


@then(parsers.parse('the design inputs include {ident} with the text "{text}"'))
def includes_input(world, ident, text):
    assert world["inputs"][ident]["text"] == text


@then(parsers.parse("{ident} traces to {need} and is owned by the {context} context"))
def traces_and_owned(world, ident, need, context):
    assert world["inputs"][ident]["traces_to"] == [need]
    assert world["inputs"][ident]["context"] == context


@then(parsers.parse('{ident} is declared in "{path}"'))
def declared_in(world, ident, path):
    assert world["declared"][ident] == [str(Path(path).relative_to("dhf"))]


@then("it names both the spec and the design document")
def names_spec_and_document(world):
    message = world["event"].message
    assert "plan/07-example/spec.feature" in message and "documents/design/specification.md" in message


@then("it names the spec and the Rule's line")
def names_spec_and_line(world):
    assert f"plan/07-example/spec.feature:{world['line']}" in world["event"].message


@then(parsers.re(r"it names (?P<ident>DI-\d+|@item-\d+-\d+)$"))
def names_ident(world, ident):
    assert ident in world["event"].message


@then(parsers.parse("it names the spec and line {line:d}"))
def names_spec_and_given_line(world, line):
    assert f"plan/07-example/spec.feature:{line}" in world["event"].message


@then("no design input is read from that spec")
def nothing_read(world):
    assert "DI-500" not in world["inputs"]


# -- the harness, run as its command (bb harness <command>) ------------------------

ROOT = Path(__file__).resolve().parents[2]
EVENT_LINE = __import__("re").compile(r"^\[(?P<name>[^\]]+)\] (?P<message>.*)$")


class Event:
    def __init__(self, name: str, message: str):
        self.name, self.message = name, message


def harness(world: dict, *args: str) -> None:
    """Run ``bb harness <args>`` from the project's root; keep its events."""
    import shutil
    import subprocess

    bb = shutil.which("bb")
    if bb is None:
        pytest.skip("babashka (bb) is not installed: the harness runs on it")
    done = subprocess.run([bb, "--config", str(ROOT / "bb.edn"), "harness", *args], cwd=world["root"],
                          capture_output=True, text=True, timeout=120)
    world["done"] = done
    world["events"] = [Event(m["name"], m["message"]) for line in done.stderr.splitlines()
                       if (m := EVENT_LINE.match(line))]
    attach("harness " + " ".join(args), f"exit {done.returncode}\n--- stdout\n{done.stdout}\n--- stderr\n{done.stderr}")


def checklist_args(world: dict) -> list[str]:
    world["checklist"] = world["root"] / "out" / "checklist.json"
    return ["--out", str(world["checklist"])]


def options(world: dict, args: list[str]) -> list[str]:
    """What a command is given besides its words: the checklist's file, a
    cycle's verdicts."""
    if args[0] == "checklist":
        return checklist_args(world)
    if args[0] == "score" and world.get("verdicts"):
        return ["--verdicts", str(world["verdicts"])]
    return []


SCENARIO = "    Scenario: {title}\n      Given a\n"


def plain_spec(world: dict, scenarios: list[tuple[str | None, str]], slice_dir: str = "07-example",
               rules: int = 1) -> Path:
    """A slice's spec: ``scenarios`` as (item id or None, title), spread over
    ``rules`` Rules tagged DI-501, DI-502, …; and its plan, ready."""
    body = "Feature: F\n"
    per = -(-len(scenarios) // rules) if scenarios else 0
    for r in range(rules):
        body += f"\n  @DI-{501 + r} @UN-001 @context-specification\n  Rule: Rule {r + 1}\n"
        for item, title in scenarios[r * per:(r + 1) * per]:
            body += "\n" + (f"    @{item}\n" if item else "") + SCENARIO.format(title=title)
    write(world, f"dhf/plan/{slice_dir}/plan.md", "# Plan\n\nstatus: ready-for-development\n")
    return spec_with(world, body, slice_dir)


def scenario_tags(spec: Path) -> dict[str, str | None]:
    """Each scenario's title, with the item tag on the line above it (or None)."""
    lines = spec.read_text().splitlines()
    found = {}
    for n, line in enumerate(lines):
        if line.strip().startswith("Scenario:"):
            above = lines[n - 1].strip() if n else ""
            found[line.strip().removeprefix("Scenario:").strip()] = above[1:] if above.startswith("@item-") else None
    return found


@when(parsers.parse('"harness {command}" runs'))
def harness_runs(world, command):
    args = command.split()
    harness(world, *args, *options(world, args))


@when(parsers.parse('"harness {command}" runs twice'))
def harness_runs_twice(world, command):
    args = command.split()
    world["before"] = world["spec"].read_bytes() if world.get("spec") else None
    harness(world, *args, *options(world, args))
    world["first"] = world["checklist"].read_bytes() if args[0] == "checklist" else None
    harness(world, *args, *options(world, args))


# -- DI-91: the stamper -------------------------------------------------------------

@given(parsers.parse('a slice "{path}" whose only scenario carries no item id'))
def only_scenario_unstamped(world, path):
    plain_spec(world, [(None, "the only one")], Path(path).parent.name)


@then(parsers.parse('the line above that scenario is "{tag}", indented as the scenario is'))
def line_above(world, tag):
    lines = world["spec"].read_text().splitlines()
    n = next(i for i, line in enumerate(lines) if "Scenario: the only one" in line)
    indent = lines[n][: len(lines[n]) - len(lines[n].lstrip())]
    assert lines[n - 1] == indent + tag


@given("a slice's spec whose scenarios carry @item-07-001 and @item-07-004")
def spec_with_gap(world):
    plain_spec(world, [("item-07-001", "first"), ("item-07-004", "second"), (None, "third")])


@given("a third scenario carries no item id")
def third_unstamped(world):
    assert scenario_tags(world["spec"])["third"] is None


@then(parsers.parse("the third scenario is stamped @{item}"))
def third_stamped(world, item):
    assert scenario_tags(world["spec"])["third"] == item


@then("the ids already there are unchanged")
def ids_unchanged(world):
    tags = scenario_tags(world["spec"])
    assert (tags["first"], tags["second"]) == ("item-07-001", "item-07-004")


@given("a slice's spec in which every scenario carries an item id")
def all_stamped(world):
    plain_spec(world, [("item-07-001", "first"), ("item-07-002", "second")])


@then("the spec is byte for byte what it was before the first run")
def spec_unchanged(world):
    assert world["spec"].read_bytes() == world["before"]


@given("a stamped spec whose scenarios are reworded and reordered by hand")
def reworded(world):
    plain_spec(world, [("item-07-002", "second, reworded"), ("item-07-001", "first, reworded"), (None, "new")])


@then("every scenario keeps the item id it carried")
def keeps_ids(world):
    tags = scenario_tags(world["spec"])
    assert tags["second, reworded"] == "item-07-002" and tags["first, reworded"] == "item-07-001"
    assert tags["new"] == "item-07-003"


@given(parsers.parse('a record with no slice\'s spec under "{where}"'))
def no_spec(world, where):
    (world["root"] / where).mkdir(parents=True, exist_ok=True)


@then("it names the directory it searched")
def names_directory(world):
    assert "dhf/plan" in world["event"].message


# -- DI-92: the checklist -----------------------------------------------------------

@given("a slice's spec with two Rules over three stamped scenarios")
def two_rules(world):
    plain_spec(world, [("item-07-001", "one"), ("item-07-002", "two"), ("item-07-003", "three")], rules=2)


@then("the checklist has three items, in the order of the spec")
def three_items(world):
    import json

    world["items"] = json.loads(world["checklist"].read_text())["items"]
    attach("checklist", world["items"])
    assert [i["item"] for i in world["items"]] == ["item-07-001", "item-07-002", "item-07-003"]


@then("each item carries its item id, slice, scenario title, Rule title and design-input id")
def item_fields(world):
    assert world["items"][0] == {"item": "item-07-001", "slice": "07-example", "scenario": "one",
                                 "rule": "Rule 1", "design_input": "DI-501"}
    assert world["items"][2]["rule"] == "Rule 2" and world["items"][2]["design_input"] == "DI-502"


@then("the counts of slices and items are reported on standard error")
def counts(world):
    assert "1 slice(s), 3 item(s)" in world["done"].stderr


@given("a slice's spec with a scenario that carries no item id")
def one_unstamped(world):
    plain_spec(world, [("item-07-001", "stamped"), (None, "unstamped")])
    world["line"] = scenario_line(world["spec"], "unstamped")


def scenario_line(spec: Path, title: str) -> int:
    return next(n for n, line in enumerate(spec.read_text().splitlines(), 1) if f"Scenario: {title}" in line)


@then("it names the scenario and its line")
def names_scenario_line(world):
    assert "unstamped" in world["event"].message and f"spec.feature:{world['line']}" in world["event"].message


@then(parsers.parse('it says to run "{command}"'))
def says_to_run(world, command):
    assert command in world["event"].message


@then("no checklist is written")
def no_checklist(world):
    assert world["done"].returncode != 0 and not world["checklist"].exists()


@given("a slice's spec with a Feature and no scenario")
def empty_spec(world):
    plain_spec(world, [])


@then("it names the spec")
def names_spec(world):
    assert "plan/07-example/spec.feature" in world["event"].message


@given(parsers.parse('a slice whose plan\'s status is "{status}"'))
def slice_with_status(world, status):
    number = "08" if status == "planned" else "09"
    plain_spec(world, [(f"item-{number}-001", f"in {status}")], f"{number}-{status}")
    write(world, f"dhf/plan/{number}-{status}/plan.md", f"# Plan\n\nstatus: {status}\n")


@then("only the ready slice's scenarios are items")
def only_ready(world):
    import json

    items = json.loads(world["checklist"].read_text())["items"]
    assert [i["item"] for i in items] == ["item-09-001"]


@then(parsers.parse('the warning "{name}" names the planned slice'))
def warning_names(world, name):
    assert "08-planned" in named(world, name).message


@given("an unchanged set of slices' specs")
def unchanged_specs(world):
    plain_spec(world, [("item-07-001", "one"), ("item-07-002", "two")], rules=2)


@then("the two checklists are byte for byte the same")
def same_bytes(world):
    assert world["checklist"].read_bytes() == world["first"]


# -- DI-93: scoring, DI-94: the scoreboard ----------------------------------------

def cycle_dir(world: dict, cycle: str) -> Path:
    return world["root"] / "harness" / "cycles" / cycle


def a_checklist(world: dict, cycle: str, items: list[str]) -> None:
    import json

    entries = [{"item": i, "slice": "07-example", "scenario": f"scenario {i}", "rule": "Rule 1",
                "design_input": "DI-501"} for i in items]
    path = cycle_dir(world, cycle) / "checklist.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"items": entries}, indent=2))
    world["items"] = items


def verdicts(world: dict, given: dict[str, str]) -> None:
    import json

    world["verdicts"] = write(world, "verdicts.json", json.dumps(given))


def score_record(world: dict, cycle: str) -> dict:
    import json

    record = json.loads((cycle_dir(world, cycle) / "score.json").read_text())
    attach(f"cycle {cycle}", record)
    return record


ITEMS = ["item-07-001", "item-07-002", "item-07-003"]


@given("a checklist of three items and a verdict of pass or fail for each")
def three_with_verdicts(world):
    a_checklist(world, "03", ITEMS)
    verdicts(world, {"item-07-001": "pass", "item-07-002": "fail", "item-07-003": "pass"})


@given("a checklist of three items and no verdicts at all")
def three_no_verdicts(world):
    a_checklist(world, "03", ITEMS)
    verdicts(world, {})


@given("a checklist of three items and verdicts for two of them")
def three_two_verdicts(world):
    a_checklist(world, "03", ITEMS)
    verdicts(world, {"item-07-001": "pass", "item-07-002": "pass"})


@given("a checklist that does not carry @item-07-099")
def checklist_without_099(world):
    a_checklist(world, "03", ITEMS)


@given("a verdict for @item-07-099")
def verdict_099(world):
    verdicts(world, {"item-07-001": "pass", "item-07-099": "pass"})


@when(parsers.parse('"harness score {cycle}" runs with those verdicts'))
def score_with_verdicts(world, cycle):
    harness(world, "score", cycle, "--verdicts", str(world["verdicts"]))


@then(parsers.parse("the record of cycle {cycle} holds each item's verdict, with the checklist it was scored against"))
def record_holds(world, cycle):
    record = score_record(world, cycle)
    assert {i: v["verdict"] for i, v in record["verdicts"].items()} == {
        "item-07-001": "pass", "item-07-002": "fail", "item-07-003": "pass"}
    assert [i["item"] for i in record["checklist"]] == ITEMS


@then(parsers.parse("no record of cycle {cycle} is written"))
def no_record(world, cycle):
    assert world["done"].returncode != 0 and not (cycle_dir(world, cycle) / "score.json").exists()


@then("the third item is recorded as a failure marked unreached")
def third_unreached(world):
    assert score_record(world, "03")["verdicts"]["item-07-003"] == {"verdict": "fail", "unreached": True}


@then("the cycle's pass rate is over three items")
def rate_over_three(world):
    record = score_record(world, "03")
    assert (record["passed"], record["total"]) == (2, 3)


@given("an acceptance run's results in which one scenario passed, one failed and one did not run")
def acceptance_results(world):
    import json

    results = world["root"] / "allure-results"
    results.mkdir()
    for n, (item, status) in enumerate([("item-07-001", "passed"), ("item-07-002", "failed")]):
        (results / f"{n}-result.json").write_text(json.dumps({
            "name": f"scenario {item}", "status": status,
            "labels": [{"name": "story", "value": "DI-501"}, {"name": "tag", "value": item}]}))
    world["results"] = results
    a_checklist(world, "03", ITEMS)


@when(parsers.parse('"harness score {cycle}" runs with that run\'s results as its verdicts'))
def score_from_results(world, cycle):
    harness(world, "score", cycle, "--results", str(world["results"]))


@then("the passed scenario is a pass, the failed one a failure, "
      "and the one that did not run a failure marked unreached")
def graded(world):
    assert score_record(world, "03")["verdicts"] == {
        "item-07-001": {"verdict": "pass", "unreached": False},
        "item-07-002": {"verdict": "fail", "unreached": False},
        "item-07-003": {"verdict": "fail", "unreached": True}}


def scored(world: dict, cycle: str, given: dict[str, str]) -> None:
    a_checklist(world, cycle, list(given))
    verdicts(world, given)
    harness(world, "score", cycle, "--verdicts", str(world["verdicts"]))
    assert world["done"].returncode == 0, world["done"].stderr


@given(parsers.parse("cycle {cycle} scored {passes:d} passes and {fails:d} failure"))
def cycle_scored(world, cycle, passes, fails):
    scored(world, cycle, {f"item-07-{n:03d}": "pass" if n <= passes else "fail" for n in range(1, passes + fails + 1)})


@then(parsers.parse("it reports {passed:d} of {total:d} passed"))
def reports_rate(world, passed, total):
    assert f"{passed} of {total} passed" in world["done"].stdout


@given(parsers.parse("cycle {earlier} and cycle {later} scored the same three items"))
def same_three(world, earlier, later):
    world["cycles"] = (earlier, later)


@given("one item went from failure to pass and another from pass to failure")
def one_up_one_down(world):
    earlier, later = world["cycles"]
    scored(world, earlier, {"item-07-001": "fail", "item-07-002": "pass", "item-07-003": "pass"})
    scored(world, later, {"item-07-001": "pass", "item-07-002": "fail", "item-07-003": "pass"})


@then(parsers.parse("it reports {better:d} got better and {worse:d} got worse"))
def better_worse(world, better, worse):
    assert f"{better} got better, {worse} got worse" in world["done"].stdout


@then("it reports no net change")
def no_net(world):
    import re

    assert not re.search(r"\bnet\b[^,.]*[-+]?\d", world["done"].stdout), world["done"].stdout


@given(parsers.parse("cycle {earlier} scored three items and cycle {later} scored those three and one more"))
def denominator_moves(world, earlier, later):
    scored(world, earlier, {"item-07-001": "pass", "item-07-002": "pass", "item-07-003": "fail"})
    scored(world, later, {"item-07-001": "pass", "item-07-002": "pass", "item-07-003": "fail", "item-07-004": "pass"})


@then(parsers.parse("it states that the denominator moved from {before:d} to {after:d}"))
def denominator_stated(world, before, after):
    assert f"the denominator moved from {before} to {after}" in world["done"].stdout


@then("the new item is counted separately from the comparison")
def new_counted_apart(world):
    out = world["done"].stdout
    assert "on 3 shared item(s)" in out and "1 new item(s), not compared: 1 passed" in out


# -- DI-95..DI-100: the gate and its configuration ---------------------------------

import json as _json  # noqa: E402
import socket as _socket  # noqa: E402
import subprocess as _subprocess  # noqa: E402
import sys as _sys  # noqa: E402

REAL_RDM = [_sys.executable, "-m", "rdm.main"]
FAKE_RDM = '''
import json, os, pathlib, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
here = pathlib.Path(__file__).parent
cue = json.loads((here / "fake_rdm.json").read_text())
args = sys.argv[1:]
if args[:2] == ["graph", "build"]:
    sys.exit(0)
if args[:2] == ["graph", "serve"]:
    host, port = args[args.index("--bind") + 1].rsplit(":", 1)
    (here / "served.pid").write_text(str(os.getpid()))
    time.sleep(3600 if cue.get("never") else cue.get("delay", 0))
    body = json.dumps({"head": {"vars": ["text"]},
                       "results": {"bindings": [{"text": {"type": "literal", "value": cue["text"]}}]}}).encode()
    class Answer(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/sparql-results+json")
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *a):
            pass
    ThreadingHTTPServer((host, int(port)), Answer).serve_forever()
os.execv(sys.executable, [sys.executable, "-m", "rdm.main", *args])
'''
DI90 = "RDM shall read a design input from a Rule"


def edn(value) -> str:
    """Python values as EDN: dict keys become keywords."""
    if isinstance(value, dict):
        return "{" + " ".join(f":{k} {edn(v)}" for k, v in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + " ".join(edn(v) for v in value) + "]"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str) and value.startswith(":"):
        return value
    return _json.dumps(value)


def free_port() -> int:
    with _socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def known_record(world: dict, name: str, duplicate: bool = False) -> str:
    """A record the design gate must pass, or (with a duplicate id) fail."""
    dhf = world["root"] / "harness" / "known" / name
    for path in (world["dhf"] / "documents").rglob("*.md"):
        target = dhf / "documents" / path.relative_to(world["dhf"] / "documents")
        target.parent.mkdir(parents=True, exist_ok=True)
        text = path.read_text()
        if duplicate and path.name == "specification.md":
            text = text.replace("design_inputs: []", "design_inputs:\n  - {id: DI-1, text: a, traces_to: [UN-001]}\n"
                                                     "  - {id: DI-1, text: b, traces_to: [UN-001]}")
        target.write_text(text)
    return str(dhf.relative_to(world["root"]))


def settings(world: dict, **changes) -> dict:
    """The world's harness configuration: a suite that leaves a mark, a free
    port, a short budget, one known answer, the record declaring DI-90, and
    RDM run for real; ``changes`` over it."""
    if "config" not in world:
        spec_with(world, f"""
            Feature: F

              @DI-90 @UN-001 @context-specification
              Rule: {DI90}

                @item-07-001
                Scenario: one
                  Given a
            """)
        write(world, "dhf/plan/07-example/plan.md", "# Plan\n\nstatus: ready-for-development\n")
        world["config"] = {"suite": "touch suite-ran", "port": free_port(), "boot-budget-seconds": 10,
                           "poll-seconds": 0.5,
                           "known-answers": [{"record": known_record(world, "good"), "gate": ":design",
                                              "verdict": ":pass"}],
                           "rdm": REAL_RDM}
    world["config"].update(changes)
    write(world, "harness/config.edn", edn(world["config"]) + "\n")
    return world["config"]


def fake_rdm(world: dict, **cue) -> None:
    fake = write(world, "fake/fake_rdm.py", FAKE_RDM)
    write(world, "fake/fake_rdm.json", _json.dumps({"text": DI90, **cue}))
    settings(world, rdm=[_sys.executable, str(fake)])


def names(world: dict) -> list[str]:
    return [e.name for e in world["events"]]


def index(world: dict, prefix: str) -> int:
    return next((n for n, name in enumerate(names(world)) if name.startswith(prefix)), -1)


@when('"harness verify" runs')
def verify_runs(world):
    settings(world)
    harness(world, "verify")


@then(parsers.parse('the event "{name}" happens'))
def happens(world, name):
    the_event_is(world, name)


@then(parsers.parse('the verdict is "{name}"'))
def verdict_is(world, name):
    assert name in names(world), names(world)
    assert (world["done"].returncode == 0) == (name == "Build Ratified")


@given("the harness's known answers: a record that must pass the gates and records that must fail them, "
       "each with the verdict it must get")
def known_answers(world):
    settings(world)
    settings(world, **{"known-answers": world["config"]["known-answers"] + [
        {"record": known_record(world, "duplicate", duplicate=True), "gate": ":design", "verdict": ":fail"}]})
    fake_rdm(world)


@then(parsers.parse('the event "{name}" happens before any other step\'s event'))
def happens_first(world, name):
    the_event_is(world, name)
    first = index(world, name)
    assert all(index(world, step) == -1 or index(world, step) > first
               for step in ("Suite", "Served Graph", "Identity"))


@then("the suite runs only because the instrument was checked")
def suite_after_instrument(world):
    assert index(world, "Instrument Checked") < index(world, "Suite Passed")
    assert (world["root"] / "suite-ran").exists()


@given("a known record that must fail the design gate passes it")
def wrong_known_answer(world):
    settings(world)
    settings(world, **{"known-answers": [{"record": known_record(world, "good"), "gate": ":design",
                                          "verdict": ":fail"}]})


@then("it names the known record and the verdict it got")
def names_known_record(world):
    assert "harness/known/good" in world["event"].message and "pass" in world["event"].message


@then("no suite is run and nothing is started")
def no_suite(world):
    assert not (world["root"] / "suite-ran").exists()
    assert index(world, "Suite") == -1 and index(world, "Served Graph") == -1


@given("the project declares its suite command in its harness configuration")
def suite_declared(world):
    fake_rdm(world)


@given("that command exits 0")
def exits_zero(world):
    assert world["config"]["suite"] == "touch suite-ran"


@given(parsers.parse('the suite command prints "{text}" and exits 1'))
def suite_red(world, text):
    settings(world, suite=f"echo {text}; exit 1")


@then("nothing is started")
def nothing_started(world):
    assert index(world, "Served Graph") == -1


@given("the harness configuration declares no suite command")
def no_suite_declared(world):
    settings(world)
    del world["config"]["suite"]
    settings(world)


@then("it names the missing setting")
def names_missing(world):
    assert ":suite" in world["event"].message


@given("a boot budget of 10 seconds polled every half second")
def budget_10(world):
    settings(world, **{"boot-budget-seconds": 10, "poll-seconds": 0.5})


@given("the served graph answers on the third poll")
def third_poll(world):
    fake_rdm(world, delay=0.75)


@then(parsers.parse("it reports that it answered on poll {poll:d} of {polls:d}, leaving {left:d} unused"))
def margin(world, poll, polls, left):
    assert f"answered on poll {poll} of {polls}, leaving {left} unused" in world["event"].message


@given("another process is listening on the configured port")
def port_taken(world):
    fake_rdm(world)
    listener = _subprocess.Popen([_sys.executable, "-c", "import socket,time\ns=socket.socket()\n"
                                  f"s.bind(('127.0.0.1',{world['config']['port']}));s.listen();time.sleep(60)"])
    world["listener"] = listener
    import time

    for _ in range(50):
        with _socket.socket() as s:
            if s.connect_ex(("127.0.0.1", world["config"]["port"])) == 0:
                break
        time.sleep(0.05)


@then("it names the port and the process holding it")
def names_port_holder(world):
    message = world["event"].message
    assert str(world["config"]["port"]) in message and str(world["listener"].pid) in message


@then("it starts nothing and stops nothing")
def starts_nothing(world):
    assert world["listener"].poll() is None
    world["listener"].kill()
    assert index(world, "Served Graph Answered") == -1 and not (world["root"] / "fake" / "served.pid").exists()


@given("the served graph does not answer within the boot budget")
def never_answers(world):
    fake_rdm(world, never=True)
    settings(world, **{"boot-budget-seconds": 1, "poll-seconds": 0.5})


@then("it names the budget")
def names_budget(world):
    assert "1s" in world["event"].message


@then("the process it started is stopped")
def started_stopped(world):
    import os

    pid = int((world["root"] / "fake" / "served.pid").read_text())
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return
    raise AssertionError(f"process {pid} still runs")


@given("the record declares DI-90 with its text")
def record_declares_di90(world):
    settings(world)


@when("\"harness verify\" asks the served graph for DI-90's text")
def verify_asks(world):
    verify_runs(world)


@then("the answer is the record's text for DI-90")
def answer_is_text(world):
    the_event_is(world, "Identity Proven")
    assert DI90 in world["event"].message


@then("the event \"Identity Proven\" happens, naming the design input it asked about")
def identity_names(world):
    the_event_is(world, "Identity Proven")
    assert "DI-90" in world["event"].message


@given("the served graph answers with a different text for DI-90")
def different_text(world):
    fake_rdm(world, text="Some other record's design input")


@then("it shows the text served and the text the record holds")
def shows_both(world):
    message = world["event"].message
    assert "Some other record's design input" in message and DI90 in message


def git_round(world: dict) -> None:
    settings(world)
    fake_rdm(world)
    run = lambda *a: _subprocess.run(["git", *a], cwd=world["root"], check=True, capture_output=True)  # noqa: E731
    run("init", "-q")
    run("add", "-A")
    run("-c", "user.email=a@b", "-c", "user.name=a", "commit", "-qm", "base")
    world["base"] = _subprocess.run(["git", "rev-parse", "HEAD"], cwd=world["root"], capture_output=True,
                                    text=True).stdout.strip()


@given("a build round based on a commit")
def round_based(world):
    git_round(world)


@given("a slice's spec has changed since that commit")
def spec_changed(world):
    world["spec"].write_text(world["spec"].read_text() + "\n    # a change\n")


@given("a build round whose changes since its base touch only code and tests")
def code_only(world):
    git_round(world)
    write(world, "src/code.txt", "code\n")


@when('"harness verify --base" that commit runs')
def verify_base(world):
    harness(world, "verify", "--base", world["base"])


@then("it names each plan or spec file changed since the base")
def names_changed(world):
    assert "dhf/plan/07-example/spec.feature" in world["event"].message


@then("nothing else is run")
def nothing_else(world):
    assert index(world, "Instrument") == -1 and not (world["root"] / "suite-ran").exists()


@given("a harness configuration declaring the suite command, the port, the boot budget and the known answers")
def full_config(world):
    fake_rdm(world)


@then("it prints each setting and its value before the first step")
def prints_settings(world):
    out = world["done"].stdout
    for name in ("suite", "port", "boot-budget-seconds", "poll-seconds", "known-answers", "rdm"):
        assert f":{name} " in out, out


@given("a harness configuration with a setting the harness does not know")
def unknown_setting(world):
    settings(world, colour="blue")


@when('any "harness" command runs')
def any_command(world):
    harness(world, "stamp")


@then("it names the setting")
def names_setting(world):
    assert ":colour" in world["event"].message
