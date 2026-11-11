"""Acceptance test for the verification report (item-07-009,
spec/publishing/).

A committed record and hand-written Allure
results are rendered to PDF and read back: once clean (release-grade), once
with every kind of problem an auditor must see (a failed run, a skipped one, a
requirement with no run, uncommitted changes, another commit, a missing
attachment, an orphan tag, and an attempt to inject Typst markup). Skips
cleanly if allure-pytest, typst or pypdf is not installed.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import struct
import sys
import zlib
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pytest.importorskip("typst")
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
from tests.util import git_run, write_spec  # noqa: E402

OTHER = "f" * 40
INJECTION = '#panic("injected") ] #set page(width: 1cm)'
ITEM_1 = (("item-01-001", "The device shall sound an alarm.", ["Alarm"]),)
MORE = (("item-01-002", "The device shall silence on acknowledge.", ["Alarm"]),
        ("item-01-003", "The device shall show the time.", ["Alarm"]),
        ("item-01-010", "The device shall log every alarm.", ["Log"]))
NEEDS = {"Alarm": ("alarms", "Nurse", "A nurse is alerted."), "Log": ("alarms", "Nurse", "A nurse reads the log.")}


def _png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00" + b"\xff\x00\x00" * 2 + b"\x00" + b"\x00\x00\xff" * 2))
            + chunk(b"IEND", b""))


def _record(tmp_path: Path, items: tuple) -> tuple[Path, str]:
    """A committed record: a spec of two user needs and the given requirements,
    a risk controlled by item-01-001."""
    repo = tmp_path / "device"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    write_spec(repo, items, NEEDS, {"01": "alarms"})
    (docs / "traceability_matrix.md").write_text("---\nid: TM-001\n---\n# Matrix\n")
    (docs / "risks.md").write_text(
        "---\nid: RR-001\nkind: risk\nstatus: proposed\nrisk_policy:\n  severities: [Minor, Major]\n"
        "  probabilities: [Rare, Often]\n  levels: {Minor: [Low, Low], Major: [Low, High]}\n"
        "  acceptability: {Low: acceptable, High: unacceptable}\nrisks:\n"
        "  - {id: RISK-7, severity: Major, probability: Often, controls: [item-01-001],"
        " residual: {probability: Rare}}\n"
        "  - {id: RISK-8, severity: Minor, probability: Rare}\n"
        "  - {id: RISK-9, severity: Major, probability: Often, controls: [item-01-002],"
        " residual: {probability: Rare}}\n"
        "---\n")
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
    (results / "requirement-attachment.txt").write_text("item-01-001 (alarms): The device shall sound an alarm.")
    (results / "stdout-attachment.txt").write_text("captured noise\n" * 50)
    (results / "log-attachment.txt").write_text(f"alarm raised at 12:00\n{INJECTION}\n")
    (results / "long-attachment.txt").write_text("".join(f"line {i}\n" for i in range(TEXT_LINES + 20)))
    (results / "screen-attachment.png").write_bytes(_png())
    (results / "dump-attachment.bin").write_bytes(b"\x00\x01binary")
    labels = [{"name": "tag", "value": "item-01-001"}, {"name": "commit", "value": commit},
              {"name": "severity", "value": "critical"}, {"name": "output", "value": "src/alarm.py"},
              {"name": "host", "value": "runner-vm-17"}, {"name": "thread", "value": "4242-MainThread"}]
    if dirty:
        labels.append({"name": "worktree", "value": "dirty"})
    (results / "a-result.json").write_text(json.dumps({
        "name": "test_alarm_sounds", "fullName": "tests.acceptance.test_alarm#test_alarm_sounds",
        "status": "passed", "start": 1767225600000, "stop": 1767225601500, "labels": labels,
        "links": [{"type": "link", "name": "item-01-001 in spec.feature", "url": "https://example.org/spec.feature"}],
        "steps": [
            {"name": "the alarm sounds within a second", "status": "passed",
             "attachments": [{"name": "alarm log", "source": "log-attachment.txt", "type": "text/plain"}],
             "steps": [{"name": "the tone is 1 kHz", "status": "passed"}]},
            {"name": "the screen shows the alarm", "status": "passed",
             "attachments": [{"name": "screen", "source": "screen-attachment.png", "type": "image/png"}]},
        ],
        "attachments": [
            {"name": "requirement item-01-001", "source": "requirement-attachment.txt", "type": "text/plain"},
            {"name": "stdout", "source": "stdout-attachment.txt", "type": "text/plain"},
            {"name": "long log", "source": "long-attachment.txt", "type": "text/plain"},
            {"name": "memory dump", "source": "dump-attachment.bin", "type": "application/octet-stream"},
        ]}))


def _problems(results: Path) -> None:
    (results / "b-result.json").write_text(json.dumps({
        "name": "test_silence", "fullName": "tests.acceptance.test_alarm.TestAcknowledge#test_silence",
        "status": "failed",
        "start": 1767225602000, "stop": 1767225603000,
        "labels": [{"name": "tag", "value": "item-01-002"}, {"name": "commit", "value": OTHER}],
        "statusDetails": {"message": "AssertionError: still sounding\nsecond line", "trace": "Traceback: line 42"},
        "steps": [{"name": "acknowledging silences it", "status": "failed",
                   "statusDetails": {"message": "the alarm kept sounding"},
                   "attachments": [{"name": "lost trace", "source": "gone-attachment.txt", "type": "text/plain"}]}]}))
    (results / "c-result.json").write_text(json.dumps({
        "name": "test_clock", "fullName": "tests.acceptance.test_alarm#test_clock", "status": "skipped",
        "start": 1767225604000, "stop": 1767225604100, "labels": [{"name": "tag", "value": "item-01-003"}]}))
    (results / "d-result.json").write_text(json.dumps({
        "name": "test_stray", "status": "passed", "labels": [{"name": "tag", "value": "item-01-009"}]}))


def _text(pdf: Path) -> str:
    """The PDF's text, whitespace collapsed: where a line wraps depends on the font."""
    return " ".join(" ".join(page.extract_text() for page in pypdf.PdfReader(pdf).pages).split())


@allure.tag("item-07-009")
@allure.label("output", "rdm/publishing/report.py")
@allure.label("output", "rdm/publishing/verification_report.typ")
@allure.label("output", "rdm/publishing/bundle.py")
def test_the_verification_report_is_written_for_an_auditor(tmp_path: Path, monkeypatch) -> None:
    """item-07-009: identification, evidence status, anomalies and traceability
    first; then per requirement every run with its verification steps and the
    attachments the test made; then every result file by SHA-256."""
    clean_dhf, clean_commit = _record(tmp_path / "clean", ITEM_1)
    clean_results = tmp_path / "clean-results"
    clean_results.mkdir()
    _environment(clean_results)
    _passing(clean_results, clean_commit)
    clean = build_report(clean_dhf, clean_results)

    with verification_step("the header names the repository, the record's commit, the commits tested, the executor and "
                           "environment, the RDM version and one SHA-256 over the result files"):
        attach("header", {k: v for k, v in clean.items() if k not in ("requirements", "files")})
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
    with verification_step("the evidence is release-grade when every requirement passed at the record's commit, clean"):
        assert (clean["release_grade"], clean["reasons"], clean["anomalies"]) == (True, [], [])
        clean_text = _text(render_pdf(clean, clean_results, tmp_path / "clean.pdf"))
        assert "Release-grade evidence" in clean_text and "Not release-grade" not in clean_text
        assert "Design controls #42" in clean_text and "CPython 3.13.1" in clean_text
        # The run tested the record's commit: the commit is in the header twice, not repeated on the run.
        assert clean_text.count(clean_commit) == 2 and "not the product" not in clean_text

    dhf, commit = _record(tmp_path / "full", ITEM_1 + MORE)
    results = tmp_path / "full-results"
    results.mkdir()
    _passing(results, commit, dirty=True)
    _problems(results)
    report = build_report(dhf, results)
    by_id = {req["id"]: req for req in report["requirements"]}
    with verification_step("otherwise it is not release-grade, and each reason is named"):
        attach("reasons", report["reasons"])
        assert report["release_grade"] is False
        assert report["reasons"] == [
            "3 requirement(s) not verified: item-01-002, item-01-003, item-01-010",
            "1 test(s) failed or broke",
            "1 test(s) ran with uncommitted changes in the worktree",
            "2 test(s) recorded no commit",
            f"runs tested 1 other commit(s) than the product's ({commit[:12]}): {OTHER[:12]}",
        ]
        assert report["executor"] is None and report["environment"] == {}
    with verification_step("the anomalies: failed and skipped runs, requirements with no run, missing attachments, "
                           "orphan tags"):
        assert [(a["subject"], a["kind"]) for a in report["anomalies"]] == [
            ("item-01-002 · tests/acceptance/test_alarm.py::TestAcknowledge::test_silence", "failed"),
            ("item-01-002 · tests/acceptance/test_alarm.py::TestAcknowledge::test_silence", "missing attachment"),
            ("item-01-003 · tests/acceptance/test_alarm.py::test_clock", "skipped"),
            ("item-01-010", "no run"),
            ("item-01-009", "orphan tag"),
        ]
    with verification_step("traceability: each requirement in spec order with its user needs, the risks it is a "
                           "control "
                           "for, and its tests"):
        assert [req["id"] for req in report["requirements"]] == ["item-01-001", "item-01-002", "item-01-003",
                                                              "item-01-010"]
        assert by_id["item-01-001"]["addresses"] == ["Alarm"] and by_id["item-01-001"]["context"] == "alarms"
        assert [r["id"] for r in by_id["item-01-001"]["control_for"]] == ["RISK-7"]
        assert by_id["item-01-003"]["control_for"] == []
    with verification_step("each requirement is a baseline or a risk-based acceptance criterion; each risk it is a "
                           "control "
                           "for shows its status and residual decision, and the register's state is summarised"):
        # risk-based: a risk allocates it as a control; baseline: none does
        assert [bool(req["control_for"]) for req in report["requirements"]] == [True, True, False, False]
        assert by_id["item-01-001"]["control_for"] == [{"id": "RISK-7", "status": "proposed", "residual": "acceptable"}]
        # item-01-002 failed: the residual of the risk it is a control for is not evaluated.
        assert by_id["item-01-002"]["control_for"] == [
            {"id": "RISK-9", "status": "proposed", "residual": "not evaluated"}]
        assert report["risk_register"] == {"risks": 3, "proposed": 3, "not_evaluated": 1, "policy": "proposed"}
        assert [req["status"] for req in report["requirements"]] == ["verified", "failed", "untested", "untested"]
    run, failed = by_id["item-01-001"]["runs"][0], by_id["item-01-002"]["runs"][0]
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
        assert run["links"] == [{"name": "item-01-001 in spec.feature", "url": "https://example.org/spec.feature"}]
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
            "requirement item-01-001": "requirement", "stdout": "captured", "long log": "text", "memory dump": "file",
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
        for expected in ("3 requirement(s) not verified", "RISK-7", "2026-01-01 00:00:00 UTC", "1.50 s",
                         "uncommitted changes", "not the product", "the tone is 1 kHz",
                         "AssertionError: still sounding", "the alarm kept sounding",
                         "item-01-001 in spec.feature", "alarm raised at 12:00", "line 0",
                         f"First lines shown of {TEXT_LINES + 20}",
                         hashlib.sha256(b"\x00\x01binary").hexdigest(), "not found in the results",
                         "No run of a test tagged item-01-010", "not recorded (no executor.json", __version__):
            assert expected in text, expected
        assert f"line {TEXT_LINES + 5}" not in text and "captured noise" not in text
        assert "Not printed: requirement item-01-001" in text
        assert "runner-vm-17" not in text and "4242-MainThread" not in text
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

    with verification_step("without the typst package a typst executable is used, and with neither the report says so"):
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
            {"name": "t2", "status": "passed", "labels": [{"name": "tag", "value": "item-01-001"}],
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
            "name": "test_link", "status": "passed", "labels": [{"name": "tag", "value": "item-01-001"}],
            "attachments": [{"name": "linked", "source": "link-attachment.txt", "type": "text/plain"}]}))
        linked = build_report(dhf, results)
        names = {f["name"] for f in linked["files"]}
        assert "link-attachment.txt" not in names and "e-result.json" in names
        runs = [r for req in linked["requirements"] for r in req["runs"] if r["test"] == "test_link"]
        assert [a["kind"] for a in runs[0]["attachments"]] == ["missing"]
    with verification_step("every run counts, tagged with a declared requirement or not; an unreadable or linked "
                           "result, and uncommitted changes to the record, are each a reason it is not release-grade"):
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
            {"name": "tag", "value": "item-01-001"}, {"name": "commit", "value": clean_commit}]}))
        (clean_results / "link-result.json").symlink_to(tmp_path / "linked-result.json")
        report = build_report(clean_dhf, clean_results)
        assert report["reasons"] == ["2 result file(s) cannot be read: cut-result.json, link-result.json"]
        assert [(a["subject"], a["kind"]) for a in report["anomalies"]] == [
            ("cut-result.json", "unreadable"), ("link-result.json", "unreadable")]
        (clean_results / "cut-result.json").unlink()
        (clean_results / "link-result.json").unlink()
        feature = clean_dhf.parent / "spec" / "alarms" / "Alarm.feature"
        feature.write_text(feature.read_text().replace("sound an alarm", "sound AND flash an alarm"))
        report = build_report(clean_dhf, clean_results)
        assert report["reasons"] == ["the record has uncommitted changes"], report["reasons"]


@allure.tag("item-07-009")
@allure.label("output", "rdm/publishing/report.py")
def test_a_spec_in_another_repository_is_the_product(tmp_path: Path) -> None:
    """item-07-009: when the spec lives in a repository of its own (a submodule,
    another checkout), the runs must have tested that repository's commit, the
    product's; the header names it beside the record's."""
    product = tmp_path / "product"
    write_spec(product, (("item-01-001", "The device shall sound an alarm.", ["Alarm"]),),
               {"Alarm": NEEDS["Alarm"]}, {"01": "alarms"})
    git_run(product, "init")
    git_run(product, "add", "-A")
    git_run(product, "commit", "-m", "spec")
    record = tmp_path / "record"
    (record / "dhf" / "documents").mkdir(parents=True)
    (record / "dhf" / "config.yml").write_text(f"spec: {product / 'spec'}\n")
    git_run(record, "init")
    git_run(record, "add", "-A")
    git_run(record, "commit", "-m", "record")
    product_sha = git_run(product, "rev-parse", "HEAD").strip()
    record_sha = git_run(record, "rev-parse", "HEAD").strip()
    results = tmp_path / "results"
    results.mkdir()
    (results / "a-result.json").write_text(json.dumps({
        "name": "test_alarm", "status": "passed", "start": 1, "stop": 2,
        "labels": [{"name": "tag", "value": "item-01-001"}, {"name": "commit", "value": product_sha}]}))
    report = build_report(record / "dhf", results)
    attach("evidence status", report["reasons"])

    with verification_step("the product's commit is the spec repository's; the record's stays its own"):
        assert report["product_commit"] == product_sha and report["record_commit"] == record_sha
    with verification_step("runs of the product's commit are release-grade evidence"):
        assert report["reasons"] == []
    with verification_step("the header names the product's commit beside the record's"):
        pdf = tmp_path / "report.pdf"
        render_pdf(report, results, pdf)
        text = " ".join(page.extract_text() for page in pypdf.PdfReader(pdf).pages)
        assert "Product commit" in text and product_sha in text and record_sha in text
