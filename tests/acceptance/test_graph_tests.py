"""Acceptance test for tests as the unit of the verification claim (DI-61, see dhf/).

Tagged `@allure.story("DI-61")`, over the real source scan, projection and
gate shapes, from a committed project with Python and JavaScript tests and
crafted Allure results. Skips cleanly if allure-pytest or the `graph` extra is
not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pytest.importorskip("pyshacl")

from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.util import git_run, write_design_doc  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"
P = "urn:dhf:acme:"
NO_RUN = "tagged test has no run, while other tests in its file ran: its claim was never executed"
UNCLAIMED = "test run exercises a design input its test does not claim"

ALARMS = '''import allure

pytestmark = allure.story("DI-3")


@allure.story("DI-1")
def test_alarm():
    pass


def test_quiet():
    pass


def helper():
    pass


@allure.story("DI-2")
class TestGroup:
    def test_member(self):
        pass

    @allure.story("DI-4")
    def test_own(self):
        pass
'''


def _project(tmp_path: Path) -> tuple[Path, Path]:
    repo = tmp_path / "acme"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / "vv.md").write_text("---\nid: VVP-1\nuser_needs:\n  - {id: UN-1, text: a}\n---\n")
    write_design_doc(docs / "design", "alarms",
                     design_inputs=tuple((f"DI-{n}", ["UN-1"]) for n in range(1, 6)))
    (repo / "tests").mkdir()
    (repo / "tests" / "test_alarms.py").write_text(ALARMS)
    (repo / "tests" / "alarm.test.js").write_text("test('beeps', () => { allure.story('DI-5'); });\n")
    git_run(repo, "init")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-m", "tests")
    results = tmp_path / "results"
    results.mkdir()
    return repo / "dhf", results


def _result(results: Path, name: str, full_name: str, *stories: str) -> None:
    (results / f"{name}-result.json").write_text(json.dumps({
        "name": name, "status": "passed", "fullName": full_name,
        "labels": [{"name": "story", "value": s} for s in stories]}))


@allure.story("DI-61")
@allure.label("output", "rdm/record/allure.py")
@allure.label("output", "rdm/graph/project.py")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_tests_are_functions_and_runs_find_them(tmp_path: Path) -> None:
    """DI-61: each tagged test (function, method, module-marked, or whole file
    by pattern) defined in its file and verifying its inputs; runs linked to
    their test by full name; warnings for a test with no run and for a run
    exercising an input its test does not claim."""
    dhf, results = _project(tmp_path)
    _result(results, "a", "tests.test_alarms#test_alarm", "DI-1", "DI-3")
    _result(results, "m", "tests.test_alarms.TestGroup#test_member", "DI-2", "DI-3", "DI-4")
    _result(results, "x", "tests.elsewhere#test_gone", "DI-1")
    quads = project(dhf, results)
    facts = {(q.subject.value, q.predicate.value.replace(RDM, ""), q.object.value) for q in quads}
    report = validate(quads)
    attach("tests graph", sorted(f for f in facts if f[0].startswith(P + "test")))

    def verifies(test: str) -> set[str]:
        node = P + "test/" + test.replace("::", "%3A%3A")
        return {o.rsplit("/", 1)[1] for s, p, o in facts if s == node and p == "verifies"}

    with verification_step("a Python test function or method, with its own, its class's and the module mark's tags"):
        assert verifies("tests/test_alarms.py::test_alarm") == {"DI-1", "DI-3"}
        assert verifies("tests/test_alarms.py::test_quiet") == {"DI-3"}
        assert verifies("tests/test_alarms.py::TestGroup::test_member") == {"DI-2", "DI-3"}
        assert verifies("tests/test_alarms.py::TestGroup::test_own") == {"DI-2", "DI-3", "DI-4"}
        assert not verifies("tests/test_alarms.py::helper")  # not a test: the module mark does not reach it
    with verification_step("the whole file is the test where tags are read by pattern"):
        assert verifies("tests/alarm.test.js") == {"DI-5"}
    with verification_step("each test is defined in its test file"):
        test = P + "test/tests/test_alarms.py%3A%3Atest_alarm"
        assert (test, "definedIn", P + "testfile/tests/test_alarms.py") in facts
        assert (P + "testfile/tests/test_alarms.py", "path", "tests/test_alarms.py") in facts
    with verification_step("a run links to the test it ran through the result's full name"):
        assert (P + "run/a-result", "runOf", test) in facts
        assert (P + "run/m-result", "runOf", P + "test/tests/test_alarms.py%3A%3ATestGroup%3A%3Atest_member") in facts
        assert not any(s == P + "run/x-result" and p == "runOf" for s, p, _ in facts)  # no such test
    with verification_step("a shape warns on a tagged test with no run, once other tests in its file ran"):
        assert {r.label for r in report if r.message == NO_RUN} == {
            "tests/test_alarms.py::test_quiet", "tests/test_alarms.py::TestGroup::test_own"}
    with verification_step("a shape warns on a run exercising a design input its test does not claim"):
        assert {r.label for r in report if r.message == UNCLAIMED} == {"m"}
        assert all(r.severity == "Warning" for r in report if r.message in (NO_RUN, UNCLAIMED))
