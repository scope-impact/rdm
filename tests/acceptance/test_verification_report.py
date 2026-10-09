"""Acceptance test for the verification report (DI-64, see dhf/).

Tagged `@allure.story("DI-64")`. A committed record and hand-written Allure
results are rendered to PDF and read back: once clean (release-grade), once
with every kind of problem an auditor must see (a failed run, a skipped one, a
design input with no run, uncommitted changes, another commit, a missing
attachment, an orphan tag, and an attempt to inject Typst markup). Skips
cleanly if allure-pytest or pypdf is not installed, or rdm-typst is not built.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import struct
import zlib
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pypdf = pytest.importorskip("pypdf")

from rdm.main import cli  # noqa: E402
from rdm.publishing.bundle import evidence_bundle  # noqa: E402
from rdm.publishing.report import (  # noqa: E402
    TEXT_LINES,
    ReportUnavailable,
    build_report,
    render_pdf,
    results_sha256,
)
from rdm.kernel.version import __version__  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_publishing import rdm_typst  # noqa: E402
from tests.util import git_run  # noqa: E402

OTHER = "f" * 40
INJECTION = '#panic("injected") ] #set page(width: 1cm)'
DI_1 = "  - {id: DI-1, text: 'The device shall sound an alarm.', traces_to: [UN-001, UN-002]}\n"
MORE = ("  - {id: DI-10, text: 'The device shall log every alarm.', traces_to: [UN-002]}\n"
        "  - {id: DI-2, text: 'The device shall silence on acknowledge.', traces_to: [UN-001]}\n"
        "  - {id: DI-3, text: 'The device shall show the time.', traces_to: [UN-001]}\n")


def _png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00" + b"\xff\x00\x00" * 2 + b"\x00" + b"\x00\x00\xff" * 2))
            + chunk(b"IEND", b""))


def _record(tmp_path: Path, inputs: str) -> tuple[Path, str]:
    """A committed record: two user needs, the given design inputs, a risk controlled by DI-1."""
    repo = tmp_path / "device"
    docs = repo / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    (docs / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: 'a need'}\n  - {id: UN-002, text: 'another'}\n---\n")
    (docs / "design" / "alarms.md").write_text(
        f"---\nid: SDS-ALM-001\nkind: design\ncontext: alarms\ndesign_inputs:\n{inputs}---\n")
    (docs / "traceability_matrix.md").write_text("---\nid: TM-001\n---\n# Matrix\n")
    (docs / "risks.md").write_text(
        "---\nid: RR-001\nkind: risk\nstatus: proposed\nrisk_policy:\n  severities: [Minor, Major]\n"
        "  probabilities: [Rare, Often]\n  levels: {Minor: [Low, Low], Major: [Low, High]}\n"
        "  acceptability: {Low: acceptable, High: unacceptable}\nrisks:\n"
        "  - {id: RISK-7, severity: Major, probability: Often, controls: [DI-1], residual: {probability: Rare}}\n"
        "  - {id: RISK-8, severity: Minor, probability: Rare}\n"
        "  - {id: RISK-9, severity: Major, probability: Often, controls: [DI-2], residual: {probability: Rare}}\n---\n")
    (repo / "tests" / "acceptance").mkdir(parents=True)
    (repo / "tests" / "acceptance" / "test_alarm.py").write_text(
        'import allure\n\n\n@allure.story("DI-1")\ndef test_alarm_sounds():\n    pass\n\n\n'
        'class TestAcknowledge:\n    @allure.story("DI-2")\n    def test_silence(self):\n        pass\n\n\n'
        '@allure.story("DI-3")\ndef test_clock():\n    pass\n')
    for args in (["init", "-q"], ["remote", "add", "origin", "git@github.com:acme/device.git"], ["add", "-A"],
                 ["commit", "-qm", "record"]):
        git_run(repo, *args)
    return repo / "dhf", git_run(repo, "rev-parse", "HEAD")


def _environment(results: Path) -> None:
    (results / "executor.json").write_text(json.dumps(
        {"name": "GitHub Actions", "type": "github", "buildName": "Design controls #42",
         "buildUrl": "https://github.com/acme/device/actions/runs/7/attempts/1"}))
    (results / "environment.properties").write_text("os=Linux 6.8 (x86_64)\npython=CPython 3.13.1\nci.actor=octocat\n")


def _passing(results: Path, commit: str, dirty: bool = False) -> None:
    (results / "requirement-attachment.txt").write_text("DI-1 (alarms): The device shall sound an alarm.")
    (results / "stdout-attachment.txt").write_text("captured noise\n" * 50)
    (results / "log-attachment.txt").write_text(f"alarm raised at 12:00\n{INJECTION}\n")
    (results / "long-attachment.txt").write_text("".join(f"line {i}\n" for i in range(TEXT_LINES + 20)))
    (results / "screen-attachment.png").write_bytes(_png())
    (results / "dump-attachment.bin").write_bytes(b"\x00\x01binary")
    labels = [{"name": "story", "value": "DI-1"}, {"name": "commit", "value": commit},
              {"name": "epic", "value": "UN-001"}, {"name": "feature", "value": "alarms"},
              {"name": "severity", "value": "critical"}, {"name": "output", "value": "src/alarm.py"},
              {"name": "host", "value": "runner-vm-17"}, {"name": "thread", "value": "4242-MainThread"}]
    if dirty:
        labels.append({"name": "worktree", "value": "dirty"})
    (results / "a-result.json").write_text(json.dumps({
        "name": "test_alarm_sounds", "fullName": "tests.acceptance.test_alarm#test_alarm_sounds",
        "status": "passed", "start": 1767225600000, "stop": 1767225601500, "labels": labels,
        "links": [{"type": "link", "name": "DI-1 in alarms.md", "url": "https://example.org/alarms.md"}],
        "steps": [
            {"name": "the alarm sounds within a second", "status": "passed",
             "attachments": [{"name": "alarm log", "source": "log-attachment.txt", "type": "text/plain"}],
             "steps": [{"name": "the tone is 1 kHz", "status": "passed"}]},
            {"name": "the screen shows the alarm", "status": "passed",
             "attachments": [{"name": "screen", "source": "screen-attachment.png", "type": "image/png"}]},
        ],
        "attachments": [
            {"name": "requirement DI-1", "source": "requirement-attachment.txt", "type": "text/plain"},
            {"name": "stdout", "source": "stdout-attachment.txt", "type": "text/plain"},
            {"name": "long log", "source": "long-attachment.txt", "type": "text/plain"},
            {"name": "memory dump", "source": "dump-attachment.bin", "type": "application/octet-stream"},
        ]}))


def _problems(results: Path) -> None:
    (results / "b-result.json").write_text(json.dumps({
        "name": "test_silence", "fullName": "tests.acceptance.test_alarm.TestAcknowledge#test_silence",
        "status": "failed",
        "start": 1767225602000, "stop": 1767225603000,
        "labels": [{"name": "story", "value": "DI-2"}, {"name": "commit", "value": OTHER}],
        "statusDetails": {"message": "AssertionError: still sounding\nsecond line", "trace": "Traceback: line 42"},
        "steps": [{"name": "acknowledging silences it", "status": "failed",
                   "statusDetails": {"message": "the alarm kept sounding"},
                   "attachments": [{"name": "lost trace", "source": "gone-attachment.txt", "type": "text/plain"}]}]}))
    (results / "c-result.json").write_text(json.dumps({
        "name": "test_clock", "fullName": "tests.acceptance.test_alarm#test_clock", "status": "skipped",
        "start": 1767225604000, "stop": 1767225604100, "labels": [{"name": "story", "value": "DI-3"}]}))
    (results / "d-result.json").write_text(json.dumps({
        "name": "test_stray", "status": "passed", "labels": [{"name": "story", "value": "DI-9"}]}))


def _text(pdf: Path) -> str:
    """The PDF's text, whitespace collapsed: where a line wraps depends on the font."""
    return " ".join(" ".join(page.extract_text() for page in pypdf.PdfReader(pdf).pages).split())


@allure.story("DI-64")
@allure.label("output", "rdm/publishing/report.py")
@allure.label("output", "rdm/publishing/verification_report.typ")
@allure.label("output", "rdm/publishing/bundle.py")
def test_the_verification_report_is_written_for_an_auditor(tmp_path: Path, monkeypatch) -> None:
    """DI-64: identification, evidence status, anomalies and traceability first;
    then per design input every run with its verification steps and the
    attachments the test made; then every result file by SHA-256."""
    monkeypatch.setenv("RDM_TYPST", rdm_typst())
    clean_dhf, clean_commit = _record(tmp_path / "clean", DI_1)
    clean_results = tmp_path / "clean-results"
    clean_results.mkdir()
    _environment(clean_results)
    _passing(clean_results, clean_commit)
    clean = build_report(clean_dhf, clean_results)

    with verification_step("the header names the repository, the record's commit, the commits tested, the executor and "
                           "environment, the RDM version and one SHA-256 over the result files"):
        attach("header", {k: v for k, v in clean.items() if k not in ("design_inputs", "files")})
        assert clean["repository"] == "https://github.com/acme/device"
        assert clean["record_commit"] == clean_commit and clean["commits"] == [clean_commit]
        assert clean["executor"] == {"name": "GitHub Actions", "type": "github", "buildName": "Design controls #42",
                                     "buildUrl": "https://github.com/acme/device/actions/runs/7/attempts/1"}
        assert clean["environment"] == {"os": "Linux 6.8 (x86_64)", "python": "CPython 3.13.1", "ci.actor": "octocat"}
        assert clean["rdm_version"] == __version__
        digest = hashlib.sha256()
        for path in sorted(clean_results.iterdir()):
            digest.update(path.name.encode() + b"\0" + hashlib.sha256(path.read_bytes()).hexdigest().encode() + b"\n")
        assert clean["results_sha256"] == digest.hexdigest()
    with verification_step("the evidence is release-grade when every input passed at the record's commit, clean"):
        assert (clean["release_grade"], clean["reasons"], clean["anomalies"]) == (True, [], [])
        clean_text = _text(render_pdf(clean, clean_results, tmp_path / "clean.pdf"))
        assert "Release-grade evidence" in clean_text and "Not release-grade" not in clean_text
        assert "Design controls #42" in clean_text and "CPython 3.13.1" in clean_text
        # The run tested the record's commit: the commit is in the header twice, not repeated on the run.
        assert clean_text.count(clean_commit) == 2 and "not the record" not in clean_text

    dhf, commit = _record(tmp_path / "full", DI_1 + MORE)
    results = tmp_path / "full-results"
    results.mkdir()
    _passing(results, commit, dirty=True)
    _problems(results)
    report = build_report(dhf, results)
    by_id = {di["id"]: di for di in report["design_inputs"]}
    with verification_step("otherwise it is not release-grade, and each reason is named"):
        attach("reasons", report["reasons"])
        assert report["release_grade"] is False
        assert report["reasons"] == [
            "3 design input(s) not verified: DI-2, DI-3, DI-10",
            "1 test(s) failed or broke",
            "1 test(s) ran with uncommitted changes in the worktree",
            "2 test(s) recorded no commit",
            f"runs tested 1 other commit(s) than the record's ({commit[:12]}): {OTHER[:12]}",
        ]
        assert report["executor"] is None and report["environment"] == {}
    with verification_step("the anomalies: failed and skipped runs, design inputs with no run, missing attachments, "
                           "orphan tags"):
        assert [(a["subject"], a["kind"]) for a in report["anomalies"]] == [
            ("DI-2 · tests/acceptance/test_alarm.py::TestAcknowledge::test_silence", "failed"),
            ("DI-2 · tests/acceptance/test_alarm.py::TestAcknowledge::test_silence", "missing attachment"),
            ("DI-3 · tests/acceptance/test_alarm.py::test_clock", "skipped"),
            ("DI-10", "no run"),
            ("DI-9", "orphan tag"),
        ]
    with verification_step("traceability: each design input in id order with its user needs, the risks it is a control "
                           "for, and its tests"):
        assert [di["id"] for di in report["design_inputs"]] == ["DI-1", "DI-2", "DI-3", "DI-10"]
        assert by_id["DI-1"]["traces_to"] == ["UN-001", "UN-002"] and by_id["DI-1"]["context"] == "alarms"
        assert [r["id"] for r in by_id["DI-1"]["control_for"]] == ["RISK-7"] and by_id["DI-3"]["control_for"] == []
    with verification_step("each design input is a baseline or a risk-based acceptance criterion; each risk it is a "
                           "control "
                           "for shows its status and residual decision, and the register's state is summarised"):
        # risk-based: a risk allocates it as a control; baseline: none does
        assert [bool(di["control_for"]) for di in report["design_inputs"]] == [True, True, False, False]
        assert by_id["DI-1"]["control_for"] == [{"id": "RISK-7", "status": "proposed", "residual": "acceptable"}]
        # DI-2 failed: the residual of the risk it is a control for is not evaluated.
        assert by_id["DI-2"]["control_for"] == [{"id": "RISK-9", "status": "proposed", "residual": "not evaluated"}]
        assert report["risk_register"] == {"risks": 3, "proposed": 3, "not_evaluated": 1, "policy": "proposed"}
        assert [di["status"] for di in report["design_inputs"]] == ["verified", "failed", "untested", "untested"]
    run, failed = by_id["DI-1"]["runs"][0], by_id["DI-2"]["runs"][0]
    with verification_step("each run: its test's file and function, result, date and duration, failure message and "
                           "trace"):
        assert run["test"] == "tests/acceptance/test_alarm.py::test_alarm_sounds"
        assert failed["test"] == "tests/acceptance/test_alarm.py::TestAcknowledge::test_silence"  # a method
        assert (run["start"], run["duration"]) == ("2026-01-01 00:00:00 UTC", "1.50 s")
        assert (failed["status"], failed["message"], failed["trace"]) == (
            "failed", "AssertionError: still sounding\nsecond line", "Traceback: line 42")
    with verification_step("labels other than those the report already shows, no runner internals and no Allure "
                           "severity; "
                           "its links"):
        assert run["labels"] == []
        assert run["links"] == [{"name": "DI-1 in alarms.md", "url": "https://example.org/alarms.md"}]
    with verification_step("each step is a verification step, nested, with its own result"):
        assert [(s["name"], s["status"]) for s in run["steps"]] == [
            ("the alarm sounds within a second", "passed"), ("the screen shows the alarm", "passed")]
        assert run["steps"][0]["steps"][0]["name"] == "the tone is 1 kHz"
        assert (failed["steps"][0]["status"], failed["steps"][0]["message"]) == ("failed", "the alarm kept sounding")
    with verification_step("attachments the test made: text inline up to a limit, images embedded, other files by "
                           "SHA-256; "
                           "captured output and the copy of the requirement listed by SHA-256 only"):
        kinds = {a["name"]: a for a in run["attachments"] + run["steps"][0]["attachments"]
                 + run["steps"][1]["attachments"]}
        assert {name: a["kind"] for name, a in kinds.items()} == {
            "requirement DI-1": "requirement", "stdout": "captured", "long log": "text", "memory dump": "file",
            "alarm log": "text", "screen": "image"}
        assert kinds["long log"]["truncated"] and kinds["long log"]["text"].count("\n") == TEXT_LINES - 1
        assert not kinds["alarm log"]["truncated"]
        assert kinds["memory dump"]["sha256"] == hashlib.sha256(b"\x00\x01binary").hexdigest()

    pdf = render_pdf(report, results, tmp_path / "report.pdf")
    text = _text(pdf)
    attach("report text", text)
    with verification_step("the PDF shows all of it in that order, with the image embedded and the noise left out"):
        order = ["Not release-grade evidence", "Anomalies", "Traceability", "The device shall sound an alarm.",
                 "Verification steps", "Appendix A"]
        assert [text.index(marker) for marker in order] == sorted(text.index(marker) for marker in order)
        for expected in ("3 design input(s) not verified", "RISK-7", "2026-01-01 00:00:00 UTC", "1.50 s",
                         "uncommitted changes", "not the record", "the tone is 1 kHz",
                         "AssertionError: still sounding", "the alarm kept sounding",
                         "DI-1 in alarms.md", "alarm raised at 12:00", "line 0",
                         f"First lines shown of {TEXT_LINES + 20}",
                         hashlib.sha256(b"\x00\x01binary").hexdigest(), "not found in the results",
                         "No run of a test tagged DI-10", "not recorded (no executor.json", __version__):
            assert expected in text, expected
        assert f"line {TEXT_LINES + 5}" not in text and "captured noise" not in text
        assert "Not printed: requirement DI-1" in text and "runner-vm-17" not in text and "4242-MainThread" not in text
        assert "severity: critical" not in text and "Acceptance criteria" not in text
        assert "risk-based: a risk control" in text and "baseline: from its user needs" in text
        assert "3 rating(s) are proposals no person has approved" in text and "on a proposed rating" in text
        assert "1 residual(s) not evaluated" in text
        assert "Risks controlled" not in text
        assert sum(len(page.images) for page in pypdf.PdfReader(pdf).pages) == 1
    with verification_step("the appendix lists every result file with its SHA-256"):
        appendix = text[text.index("Appendix A"):]
        for path in results.iterdir():
            assert hashlib.sha256(path.read_bytes()).hexdigest() in appendix, path.name
    with verification_step("text from a test reaches the page as text: markup in it is shown, not run"):
        assert '#panic("injected")' in text and "#set page(width: 1cm)" in text
        assert {round(float(p.mediabox.width)) for p in pypdf.PdfReader(pdf).pages} == {595}  # A4, unchanged

    with verification_step("with no Typst available the report says so"):
        monkeypatch.delenv("RDM_TYPST")
        monkeypatch.setenv("PATH", str(tmp_path / "nowhere"))
        with pytest.raises(ReportUnavailable, match=r"rdm-typst"):
            render_pdf(report, results, tmp_path / "none.pdf")
        assert evidence_bundle(dhf, results, tmp_path / "bare")["verification_report"].startswith("not rendered")
        monkeypatch.undo()
        monkeypatch.setenv("RDM_TYPST", rdm_typst())
    with verification_step("the evidence bundle includes the report, and rdm story evidence-report writes it"):
        manifest = evidence_bundle(dhf, results, tmp_path / "bundle")
        assert manifest["verification_report"] == "verification_report.pdf"
        assert "verification_report.pdf" in manifest["files"]
        assert "The device shall sound an alarm." in _text(tmp_path / "bundle" / "verification_report.pdf")
        out = tmp_path / "cli.pdf"
        assert cli(["story", "evidence-report", "--dhf", str(dhf), "--allure-results", str(results),
                    "-o", str(out)]) == 0
        assert out.read_bytes().startswith(b"%PDF")
    with verification_step("the report's one SHA-256 over the results can be checked from the bundle's copy of them"):
        assert results_sha256(tmp_path / "bundle" / "allure-results") == build_report(dhf, results)["results_sha256"]
    with verification_step("a result the report cannot lay out leaves the reason in the bundle, not a crash"):
        odd = tmp_path / "odd-results"
        shutil.copytree(results, odd, symlinks=True)
        (odd / "t2-result.json").write_text(json.dumps(
            {"name": "t2", "status": "passed", "labels": [{"name": "story", "value": "DI-1"}],
             "statusDetails": "not a mapping", "steps": [None], "attachments": [None]}))
        manifest = evidence_bundle(dhf, odd, tmp_path / "odd-bundle")
        assert (tmp_path / "odd-bundle" / "manifest.json").is_file()
        assert manifest["verification_report"] == "verification_report.pdf" or \
            manifest["verification_report"].startswith("not rendered: ")
    with verification_step("a symbolic link in the results is never read: its attachment missing, its file unhashed"):
        secret = tmp_path / "outside.txt"
        secret.write_text("a file outside the results\n")
        (results / "link-attachment.txt").symlink_to(secret)
        (results / "e-result.json").write_text(json.dumps({
            "name": "test_link", "status": "passed", "labels": [{"name": "story", "value": "DI-1"}],
            "attachments": [{"name": "linked", "source": "link-attachment.txt", "type": "text/plain"}]}))
        linked = build_report(dhf, results)
        names = {f["name"] for f in linked["files"]}
        assert "link-attachment.txt" not in names and "e-result.json" in names
        runs = [r for di in linked["design_inputs"] for r in di["runs"] if r["test"] == "test_link"]
        assert [a["kind"] for a in runs[0]["attachments"]] == ["missing"]
    with verification_step("every run counts, tagged with a declared input or not; an unreadable or linked result, and "
                           "uncommitted changes to the record, are each a reason it is not release-grade"):
        untagged = clean_results / "u-result.json"
        untagged.write_text(json.dumps({"name": "test_untagged", "status": "failed",
                                        "labels": [{"name": "commit", "value": clean_commit}]}))
        report = build_report(clean_dhf, clean_results)
        assert report["reasons"] == ["1 test(s) failed or broke"], report["reasons"]
        assert [(a["subject"], a["kind"]) for a in report["anomalies"]] == [("test_untagged", "failed")]
        untagged.write_text(json.dumps({"name": "test_untagged", "status": "passed", "steps": [
            {"name": "a step", "status": "broken"}], "labels": [{"name": "commit", "value": clean_commit}]}))
        report = build_report(clean_dhf, clean_results)
        assert [(a["subject"], a["kind"]) for a in report["anomalies"]] == [("test_untagged", "failed")]
        untagged.unlink()
        (clean_results / "cut-result.json").write_text('{"status": "failed"')
        (tmp_path / "linked-result.json").write_text(json.dumps({"name": "x", "status": "passed", "labels": [
            {"name": "story", "value": "DI-1"}, {"name": "commit", "value": clean_commit}]}))
        (clean_results / "link-result.json").symlink_to(tmp_path / "linked-result.json")
        report = build_report(clean_dhf, clean_results)
        assert report["reasons"] == ["2 result file(s) cannot be read: cut-result.json, link-result.json"]
        assert [(a["subject"], a["kind"]) for a in report["anomalies"]] == [
            ("cut-result.json", "unreadable"), ("link-result.json", "unreadable")]
        (clean_results / "cut-result.json").unlink()
        (clean_results / "link-result.json").unlink()
        design = clean_dhf / "documents" / "design" / "alarms.md"
        design.write_text(design.read_text().replace("sound an alarm", "sound AND flash an alarm"))
        report = build_report(clean_dhf, clean_results)
        assert report["reasons"] == ["the record has uncommitted changes"], report["reasons"]
