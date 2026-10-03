"""Acceptance test for the unit tests' code coverage per component (DI-76, see dhf/).

Tagged `@allure.story("DI-76")`. verify, given one Cobertura XML or LCOV
report of the unit tests' coverage, adds to the verification data the lines
each C4 component's code ran of those measured, mapping files to components
with the C4 model, and the traceability matrix shows it. Coverage is unit-test
evidence: it is never read from, or written to, Allure. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

allure = pytest.importorskip("allure")

from rdm.main import cli  # noqa: E402
from rdm.specification import design_gate as design_gate_module  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.util import write_allure_result, write_design_doc  # noqa: E402


def _component(ident: str, alias: str, name: str, group: str, code: str) -> dict:
    return {"id": ident, "name": name, "tags": "Element,Component", "group": group,
            "properties": {"structurizr.dsl.identifier": alias, "code": code}}


WORKSPACE = {"model": {"softwareSystems": [{
    "id": "1", "name": "Pump", "properties": {"structurizr.dsl.identifier": "pump"},
    "containers": [{"id": "2", "name": "Firmware", "properties": {"structurizr.dsl.identifier": "firmware"},
                    "components": [_component("3", "ui", "Keypad UI", "programming", "src/ui/"),
                                   _component("4", "dosing", "Dose control", "delivery", "src/dose/limits.c"),
                                   _component("5", "log", "Event log", "delivery", "src/log.c")]}]}]}}


def _project(tmp_path: Path) -> Path:
    """A project with a record, a passing run, a C4 model of three components,
    and their code."""
    dhf = tmp_path / "dhf"
    docs = dhf / "documents"
    write_design_doc(docs / "design", "core", design_inputs=(("DI-1", ["UN-001"]),))
    (docs / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: a need}\n---\n\nplan\n")
    write_allure_result(dhf / "allure-results", "r1", "passed", "DI-1")
    (dhf / "c4").mkdir()
    (dhf / "c4" / "workspace.json").write_text(json.dumps(WORKSPACE))
    for code in ("src/ui/keypad.c", "src/dose/limits.c", "src/log.c", "vendor/lib.c"):
        (tmp_path / code).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / code).write_text("int x;\n")
    return dhf


def _cobertura(tmp_path: Path) -> Path:
    """coverage.py's shape: file names relative to a source root (the second
    of two, the first naming no file of the project)."""
    def cls(filename: str, hits: list[int]) -> str:
        lines = "".join(f'<line number="{n}" hits="{h}"/>' for n, h in enumerate(hits, 1))
        return f'<class name="c" filename="{filename}"><methods/><lines>{lines}</lines></class>'
    report = tmp_path / "coverage.xml"
    report.write_text(
        f'<?xml version="1.0" ?>\n<coverage version="7"><sources><source>{tmp_path / "elsewhere"}</source>'
        f'<source>{tmp_path / "src"}</source></sources><packages><package name="p"><classes>'
        + cls("ui/keypad.c", [3, 0, 1]) + cls("dose/limits.c", [1, 1, 0, 0])
        + f'<class name="v" filename="{tmp_path / "vendor" / "lib.c"}"><lines><line number="1" hits="9"/></lines>'
        '</class></classes></package></packages></coverage>\n')
    return report


def _lcov(tmp_path: Path) -> Path:
    """gcov's and Istanbul's shape: absolute file paths."""
    report = tmp_path / "lcov.info"
    report.write_text(
        f"TN:\nSF:{tmp_path / 'src' / 'ui' / 'keypad.c'}\nDA:1,1\nDA:2,0\nend_of_record\n"
        f"TN:\nSF:{tmp_path / 'src' / 'dose' / 'limits.c'}\nDA:1,2,abc\nDA:2,1\nDA:3,5\nend_of_record\n"
        f"TN:\nSF:{tmp_path / 'vendor' / 'lib.c'}\nDA:1,0\nend_of_record\n")
    return report


def _verify(dhf: Path, out: Path, *report: str) -> int:
    return cli(["story", "verify", "--dhf", str(dhf), "--allure-results", str(dhf / "allure-results"),
                "-o", str(out), *(["--unit-coverage", report[0]] if report else [])])


@allure.story("DI-76")
@allure.label("output", "rdm/evidence/unit_coverage.py")
@allure.label("output", "rdm/release/verify.py")
def test_unit_coverage_per_component_in_the_verification_data(tmp_path: Path, monkeypatch, capsys) -> None:
    """DI-76: verify, given the unit tests' coverage as one Cobertura XML or
    LCOV report, adds per C4 component with code the lines its unit tests ran
    of those measured, lists apart a component the report does not measure,
    takes an unreadable report as an error, and the matrix shows it."""
    dhf = _project(tmp_path)
    out = tmp_path / "verification.yml"
    unmeasured = [{"component": "log", "name": "Event log", "context": "delivery"}]

    with verification_step("a Cobertura report gives each component the lines its unit tests ran of those measured"):
        assert _verify(dhf, out, str(_cobertura(tmp_path))) == 0
        coverage = yaml.safe_load(out.read_text())["unit_coverage"]
        attach("unit coverage (Cobertura)", coverage)
        assert coverage["components"] == [
            {"component": "dosing", "name": "Dose control", "context": "delivery",
             "executed": 2, "measured": 4, "percent": 50},
            {"component": "ui", "name": "Keypad UI", "context": "programming",
             "executed": 2, "measured": 3, "percent": 66}]
    with verification_step("an LCOV report gives each component the lines its unit tests ran of those measured"):
        assert _verify(dhf, out, str(_lcov(tmp_path))) == 0
        lcov = yaml.safe_load(out.read_text())["unit_coverage"]
        attach("unit coverage (LCOV)", lcov)
        assert [(c["component"], c["executed"], c["measured"], c["percent"]) for c in lcov["components"]] == [
            ("dosing", 3, 3, 100), ("ui", 1, 2, 50)]
    with verification_step("a component whose code the report does not measure is listed apart"):
        assert coverage["unmeasured"] == unmeasured and lcov["unmeasured"] == unmeasured
    with verification_step("a file of no component is ignored"):
        names = {c["component"] for c in coverage["components"] + lcov["components"]}
        assert names == {"dosing", "ui"}
        assert "vendor" not in out.read_text()
    with verification_step("a report that cannot be read is an error naming it, and verify exits 2"):
        bad = {"missing.xml": None, "binary.info": b"\xff\xfe\x00SF", "broken.xml": b"<coverage><sources>",
               "other.xml": b"<report/>", "notes.txt": b"no coverage here\n", "bad.info": b"SF:a.c\nDA:1\n"}
        for name, content in bad.items():
            if content is not None:
                (tmp_path / name).write_bytes(content)
            out.unlink(missing_ok=True)
            capsys.readouterr()
            assert _verify(dhf, out, str(tmp_path / name)) == 2, name
            printed = capsys.readouterr().out
            attach(f"verify on {name}", printed)
            assert printed.startswith("Error: ") and name in printed, printed
            assert not out.exists()
    with verification_step("without a coverage report the verification data has no unit_coverage"):
        assert _verify(dhf, out) == 0
        data = yaml.safe_load(out.read_text())
        assert "unit_coverage" not in data and data["summary"]["verified"] == 1
    with verification_step("the scaffold's traceability matrix shows each component's unit coverage and the "
                           "components not measured"):
        assert _verify(dhf, out, str(_cobertura(tmp_path))) == 0
        template = Path(design_gate_module.__file__).parent / "init_files" / "documents" / "traceability_matrix.md"
        (tmp_path / "matrix.md").write_text(template.read_text())
        (tmp_path / "config.yml").write_text("")
        (tmp_path / "device.yml").write_text("name: Acme Pump\nversion: '1.0'\n")
        monkeypatch.chdir(tmp_path)
        capsys.readouterr()
        assert cli(["render", "matrix.md", "config.yml", "device.yml", "verification.yml"]) == 0, \
            capsys.readouterr().err
        matrix = capsys.readouterr().out
        attach("rendered matrix", matrix)
        assert "# Unit-test code coverage" in matrix
        assert "| Component | Context | Lines run | Lines measured | Coverage |" in matrix
        assert "| Dose control | delivery | 2 | 4 | 50% |" in matrix
        assert "| Keypad UI | programming | 2 | 3 | 66% |" in matrix
        assert "- Event log (delivery)" in matrix
