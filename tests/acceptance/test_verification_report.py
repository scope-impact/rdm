"""Acceptance test for the verification report (DI-64, see dhf/).

Tagged `@allure.story("DI-64")`. A small record and hand-written Allure
results (passing, failing and untested design inputs; nested steps; text,
image, binary and missing attachments; an attempt to inject Typst markup) are
rendered to PDF and read back. Skips cleanly if allure-pytest, typst or pypdf
is not installed.
"""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pytest.importorskip("typst")
pypdf = pytest.importorskip("pypdf")

from rdm.main import cli  # noqa: E402
from rdm.record.bundle import evidence_bundle  # noqa: E402
from rdm.record.report import ReportUnavailable, build_report, render_pdf, results_sha256  # noqa: E402
from rdm.version import __version__  # noqa: E402
from tests.acceptance.evidence import attach, clause  # noqa: E402

COMMIT = "0123456789abcdef0123456789abcdef01234567"
INJECTION = '#panic("injected") ] #set page(width: 1cm)'


def _png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00" + b"\xff\x00\x00" * 2 + b"\x00" + b"\x00\x00\xff" * 2))
            + chunk(b"IEND", b""))


def _record(tmp_path: Path) -> tuple[Path, Path]:
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: 'a need'}\n  - {id: UN-002, text: 'another'}\n---\n")
    (dhf / "documents" / "design" / "alarms.md").write_text(
        "---\nid: SDS-ALM-001\nkind: design\ncontext: alarms\ndesign_inputs:\n"
        "  - {id: DI-10, text: 'The device shall log every alarm.', traces_to: [UN-002]}\n"
        "  - {id: DI-2, text: 'The device shall silence on acknowledge.', traces_to: [UN-001]}\n"
        "  - {id: DI-1, text: 'The device shall sound an alarm.', traces_to: [UN-001, UN-002]}\n---\n")
    results = tmp_path / "allure-results"
    results.mkdir()
    (results / "log-attachment.txt").write_text(f"alarm raised at 12:00\n{INJECTION}\n")
    (results / "requirement-attachment.txt").write_text("DI-1: The device shall sound an alarm.")
    (results / "screen-attachment.png").write_bytes(_png())
    (results / "dump-attachment.bin").write_bytes(b"\x00\x01binary")
    labels = [{"name": "story", "value": "DI-1"}, {"name": "commit", "value": COMMIT},
              {"name": "worktree", "value": "dirty"}, {"name": "epic", "value": "UN-001"},
              {"name": "feature", "value": "alarms"}, {"name": "severity", "value": "critical"},
              {"name": "output", "value": "src/alarm.py"}]
    (results / "a-result.json").write_text(json.dumps({
        "name": "test_alarm_sounds", "fullName": "tests.test_alarm#test_alarm_sounds", "status": "passed",
        "start": 1767225600000, "stop": 1767225601500, "labels": labels,
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
            {"name": "memory dump", "source": "dump-attachment.bin", "type": "application/octet-stream"},
            {"name": "lost trace", "source": "gone-attachment.txt", "type": "text/plain"},
        ]}))
    (results / "b-result.json").write_text(json.dumps({
        "name": "test_silence", "fullName": "tests.test_alarm#test_silence", "status": "failed",
        "start": 1767225602000, "stop": 1767225603000,
        "labels": [{"name": "story", "value": "DI-2"}, {"name": "commit", "value": COMMIT}],
        "statusDetails": {"message": "AssertionError: still sounding", "trace": "Traceback: test_alarm.py:42"},
        "steps": [{"name": "acknowledging silences it", "status": "failed",
                   "statusDetails": {"message": "the alarm kept sounding"}}]}))
    (results / "c-result.json").write_text(json.dumps({
        "name": "test_stray", "status": "passed", "labels": [{"name": "story", "value": "DI-9"}]}))
    return dhf, results


def _text(pdf: Path) -> str:
    return "\n".join(page.extract_text() for page in pypdf.PdfReader(pdf).pages)


@allure.story("DI-64")
@allure.label("output", "rdm/record/report.py")
@allure.label("output", "rdm/record/verification_report.typ")
@allure.label("output", "rdm/record/bundle.py")
def test_the_verification_report_shows_every_run_and_its_evidence(tmp_path: Path, monkeypatch) -> None:
    """DI-64: per design input, every run of its tests with commit, worktree,
    times, status, failure, labels, links, steps and attachments, rendered to
    PDF under a header naming the results by SHA-256; in the evidence bundle."""
    dhf, results = _record(tmp_path)
    report = build_report(dhf, results)
    by_id = {di["id"]: di for di in report["design_inputs"]}

    with clause("each design input, in id order, with its text, owning context and user needs"):
        assert [di["id"] for di in report["design_inputs"]] == ["DI-1", "DI-2", "DI-10"]
        assert by_id["DI-1"]["text"] == "The device shall sound an alarm."
        assert by_id["DI-1"]["context"] == "alarms" and by_id["DI-1"]["traces_to"] == ["UN-001", "UN-002"]
        assert [di["status"] for di in report["design_inputs"]] == ["verified", "failed", "untested"]
        assert by_id["DI-10"]["runs"] == [] and report["orphans"] == ["DI-9"]
    run, failed = by_id["DI-1"]["runs"][0], by_id["DI-2"]["runs"][0]
    with clause("each run: the commit, the worktree state, start and stop times, status and failure message"):
        attach("DI-1 run", {k: v for k, v in run.items() if k not in ("steps", "attachments")})
        assert (run["commit"], run["dirty"], failed["dirty"]) == (COMMIT, True, False)
        assert (run["start"], run["stop"]) == ("2026-01-01 00:00:00.000 UTC", "2026-01-01 00:00:01.500 UTC")
        assert (failed["status"], failed["message"], failed["trace"]) == (
            "failed", "AssertionError: still sounding", "Traceback: test_alarm.py:42")
    with clause("every label and link the run carries"):
        assert {(label["name"], label["value"]) for label in run["labels"]} >= {
            ("epic", "UN-001"), ("feature", "alarms"), ("severity", "critical"), ("output", "src/alarm.py")}
        assert run["links"] == [{"name": "DI-1 in alarms.md", "url": "https://example.org/alarms.md", "type": "link"}]
    with clause("every step, nested, with its own status and failure message"):
        assert [(s["name"], s["status"]) for s in run["steps"]] == [
            ("the alarm sounds within a second", "passed"), ("the screen shows the alarm", "passed")]
        assert run["steps"][0]["steps"][0]["name"] == "the tone is 1 kHz"
        assert (failed["steps"][0]["status"], failed["steps"][0]["message"]) == ("failed", "the alarm kept sounding")
    with clause("every attachment: text inline, images embedded, other files by SHA-256, a missing one marked"):
        kinds = {a["name"]: a for a in run["attachments"] + run["steps"][0]["attachments"]
                 + run["steps"][1]["attachments"]}
        assert kinds["alarm log"]["kind"] == "text" and "alarm raised at 12:00" in kinds["alarm log"]["text"]
        assert kinds["screen"]["kind"] == "image"
        assert (kinds["memory dump"]["kind"], kinds["memory dump"]["sha256"]) == (
            "file", hashlib.sha256(b"\x00\x01binary").hexdigest())
        assert kinds["lost trace"]["kind"] == "missing"
    with clause("the header: the verification summary, the RDM version and one SHA-256 over the result files"):
        digest = hashlib.sha256()
        for path in sorted(results.iterdir()):
            digest.update(path.name.encode() + b"\0" + hashlib.sha256(path.read_bytes()).hexdigest().encode() + b"\n")
        assert report["results_sha256"] == digest.hexdigest() == results_sha256(results)
        assert report["summary"] | {} == {"verified": 1, "failed": 1, "untested": 1, "total": 3, "results_found": 3}
        assert report["rdm_version"] == __version__ and report["commits"] == [COMMIT]
        (results / "dump-attachment.bin").write_bytes(b"tampered")
        assert results_sha256(results) != report["results_sha256"]
        (results / "dump-attachment.bin").write_bytes(b"\x00\x01binary")

    pdf = render_pdf(report, results, tmp_path / "report.pdf")
    text = _text(pdf)
    attach("report text", text)
    with clause("the PDF shows all of it, and embeds the image"):
        for expected in ("The device shall sound an alarm.", COMMIT, "uncommitted changes", "2026-01-01 00:00:01.500",
                         "the tone is 1 kHz", "AssertionError: still sounding", "the alarm kept sounding",
                         "severity: critical", "DI-1 in alarms.md", "alarm raised at 12:00",
                         hashlib.sha256(b"\x00\x01binary").hexdigest(), "not found in the results",
                         report["results_sha256"], __version__, "No run of a test tagged DI-10", "DI-9"):
            assert expected in text, expected
        assert text.count(COMMIT) == 5  # the header; each of the two runs, in its Commit and Labels rows
        assert sum(len(page.images) for page in pypdf.PdfReader(pdf).pages) == 1
    with clause("text from a test reaches the page as text: markup in it is shown, not run"):
        assert '#panic("injected")' in text and "#set page(width: 1cm)" in text
        assert {round(float(p.mediabox.width)) for p in pypdf.PdfReader(pdf).pages} == {595}  # A4, unchanged

    with clause("without the typst package a typst executable is used, and with neither the report says so"):
        monkeypatch.setitem(sys.modules, "typst", None)
        bin_dir = tmp_path / "bin"
        bin_dir.mkdir()
        (bin_dir / "typst").write_text('#!/bin/sh\n[ "$1" = compile ] && [ "$2" = --root ] && '
                                       '[ -f "$3/report.json" ] && echo "%PDF-stub" > "$5"\n')
        (bin_dir / "typst").chmod(0o755)
        monkeypatch.setenv("PATH", str(bin_dir))
        assert render_pdf(report, results, tmp_path / "stub.pdf").read_text() == "%PDF-stub\n"
        monkeypatch.setenv("PATH", str(tmp_path / "nowhere"))
        with pytest.raises(ReportUnavailable, match=r"rdm\[report\]"):
            render_pdf(report, results, tmp_path / "none.pdf")
        assert evidence_bundle(dhf, results, tmp_path / "bare")["verification_report"].startswith("not rendered")
        monkeypatch.undo()
    with clause("the evidence bundle includes the report, and rdm story evidence-report writes it"):
        manifest = evidence_bundle(dhf, results, tmp_path / "bundle")
        assert manifest["verification_report"] == "verification_report.pdf"
        assert "verification_report.pdf" in manifest["files"]
        assert "The device shall sound an alarm." in _text(tmp_path / "bundle" / "verification_report.pdf")
        out = tmp_path / "cli.pdf"
        assert cli(["story", "evidence-report", "--dhf", str(dhf), "--allure-results", str(results),
                    "-o", str(out)]) == 0
        assert out.read_bytes().startswith(b"%PDF")
