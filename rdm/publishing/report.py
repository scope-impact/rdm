"""
The verification report (DI-64): what was run to verify each design input, as a PDF.

Written for the person who judges the evidence without having run it: an
auditor, a notified body, the pull-request reviewer, in the record's language
(``CONTEXT.md``): a design input is an acceptance criterion, baseline or
risk-based; a test verifies it through its verification steps; a design input
allocated to a risk is a control *for* it, and the risk is not shown as
controlled until its residual is evaluated acceptable. It answers the reader's
questions in their order:

1. what is this, and can I rely on it — the repository, the record's commit,
   the commits tested, the executor and environment (DI-65), one SHA-256 over
   the results, and an evidence status: release-grade, or each reason not;
   beside it, the risk register's state (proposals, residuals not evaluated);
2. what went wrong — the anomalies;
3. what traces to what — user need, design input, the risks it is a control
   for, tests, result;
4. the evidence — per design input, each run with its verification steps and
   the attachments the test made;
5. how to check it — every result file and its SHA-256.

What would only repeat the report or bury it is left out: labels it already
shows, runner internals, and pytest's captured output, which is listed by
checksum. The data is written as JSON next to a Typst layout that reads it,
never spliced into markup, so nothing a test prints can change the document.
It is compiled through the typeset port (DI-88): ``rdm-typst`` (``RDM_TYPST``,
or on the PATH), Typst's crates as RDM's typeset provider with the layout's
fonts embedded; the component answers it with the same crate.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
from importlib.resources import files
from pathlib import Path
from typing import Callable, Protocol

from rdm.evidence.allure import (
    FAILING, REQUIREMENT_ATTACHMENT, design_input_tags, full_name, read_results, read_run_facts, run_status,
    run_version,
)
from rdm.specification.tags import find_tests_dir, scan_source_tests
from rdm.kernel.git import repo_root, web_url
from rdm.kernel.record_state import Tracking, record_state
from rdm.kernel.ids import sort_key
from rdm.risk.register import NOT_EVALUATED, policy_or_none, residual_decision, risks
from rdm.specification.sdd import design_inputs
from rdm.release.verify import build_verification
from rdm.kernel.version import __version__

LAYOUT = "verification_report.typ"
REPORT_PDF = "verification_report.pdf"
TEXT_TYPES = {"application/json", "application/xml", "application/yaml", "application/x-yaml"}
IMAGE_TYPES = {"image/png", "image/jpeg", "image/gif", "image/svg+xml", "image/webp"}
# A text attachment longer than this is cut; the full file is in the bundle, by checksum.
TEXT_LINES, TEXT_CHARS = 60, 6000
# pytest's captured output (allure-pytest's names): listed by checksum, not printed.
CAPTURED = {"stdout", "stderr", "log"}
# Labels the report already shows elsewhere, Allure's severity (beside a risk it
# reads as a harm's severity, and it is not one), and runner internals: not
# repeated per run. The layout lists them in its appendix.
SHOWN_LABELS = {"story", "epic", "feature", "output", "commit", "worktree", "severity"}
RUNNER_LABELS = {"host", "thread", "framework", "language", "suite", "parentSuite", "subSuite", "package"}


class ReportUnavailable(RuntimeError):
    """No typeset provider is available."""


def result_files(results_dir: Path) -> list[dict]:
    """Every plain file in the results directory (never a symbolic link), in name order, with its size and SHA-256."""
    entries = []
    for path in sorted(p for p in Path(results_dir).iterdir() if p.is_file() and not p.is_symlink()):
        data = path.read_bytes()
        entries.append({"name": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return entries


def digest(entries: list[dict]) -> str:
    """One SHA-256 over result files' names and SHA-256s, in name order."""
    total = hashlib.sha256()
    for entry in entries:
        total.update(entry["name"].encode() + b"\0" + entry["sha256"].encode() + b"\n")
    return total.hexdigest()


def results_sha256(results_dir: Path) -> str:
    """One SHA-256 over every file in the results directory."""
    return digest(result_files(results_dir))


def _time(ms) -> str | None:
    if not isinstance(ms, (int, float)):
        return None
    return time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime(ms / 1000))


def _duration(start, stop) -> str | None:
    if not isinstance(start, (int, float)) or not isinstance(stop, (int, float)):
        return None
    return f"{(stop - start) / 1000:.2f} s"


class _Reader:
    """Reads one results directory once: every file is hashed once, and an
    attachment's content is decoded from the bytes already read."""

    def __init__(self, results_dir: Path):
        self.dir = results_dir
        self.files = result_files(results_dir)
        self.by_name = {entry["name"]: entry for entry in self.files}

    def attachment(self, item: dict, requirement: set[str]) -> dict:
        name, kind = str(item.get("name") or item.get("source") or ""), str(item.get("type") or "")
        source = str(item.get("source") or "")
        entry = self.by_name.get(source) if source and Path(source).name == source else None
        if entry is None:
            return {"name": name, "type": kind, "source": source, "kind": "missing", "sha256": None}
        found = {"name": name, "type": kind, "source": source, "sha256": entry["sha256"], "bytes": entry["bytes"]}
        if name in CAPTURED:
            return found | {"kind": "captured"}
        if name in requirement:
            return found | {"kind": "requirement"}
        if kind.startswith("text/") or kind in TEXT_TYPES:
            lines = (self.dir / source).read_text(encoding="utf-8", errors="replace").splitlines()
            shown = "\n".join(lines[:TEXT_LINES])[:TEXT_CHARS]
            return found | {"kind": "text", "text": shown, "truncated": shown != "\n".join(lines),
                            "lines": len(lines)}
        return found | {"kind": "image" if kind in IMAGE_TYPES else "file"}


def _details(node: dict) -> tuple[str | None, str | None]:
    details = node.get("statusDetails") or {}
    return (details.get("message") or None), (details.get("trace") or None)


def _step(step: dict, reader: _Reader, requirement: set[str]) -> dict:
    message, trace = _details(step)
    return {
        "name": str(step.get("name", "")),
        "status": str(step.get("status", "unknown")),
        "message": message,
        "trace": trace,
        "attachments": [reader.attachment(a, requirement) for a in step.get("attachments") or []],
        "steps": [_step(s, reader, requirement) for s in step.get("steps") or []],
    }


def _test_ids(dhf_dir: Path, root: Path | None) -> dict[str, str]:
    """The source test each Allure ``fullName`` names, as ``file::name``."""
    tests_dir = find_tests_dir(dhf_dir)
    if tests_dir is None:
        return {}
    base = root or tests_dir.parent
    ids = {}
    for file, name, _tags in scan_source_tests(tests_dir):
        rel = Path(file).resolve().relative_to(base.resolve()).as_posix()
        if (key := full_name(rel, name)) is not None:
            ids[key] = f"{rel}::{name}"
    return ids


def _run(result: dict, reader: _Reader, tagged: list[str], test_ids: dict[str, str]) -> dict:
    labels = [{"name": str(label.get("name", "")), "value": str(label.get("value", ""))}
              for label in result.get("labels") or [] if isinstance(label, dict)]
    commit, dirty = run_version(result)
    message, trace = _details(result)
    requirement = {REQUIREMENT_ATTACHMENT.format(di) for di in tagged}
    name = str(result.get("fullName") or result.get("name") or "")
    return {
        "test": test_ids.get(name, name),
        "status": run_status(result),
        "message": message,
        "trace": trace,
        "start": _time(result.get("start")),
        "duration": _duration(result.get("start"), result.get("stop")),
        "commit": commit,
        "dirty": dirty,
        "labels": [label for label in labels if label["name"] not in SHOWN_LABELS | RUNNER_LABELS],
        "links": [{"name": str(link.get("name") or link.get("url", "")), "url": str(link.get("url", ""))}
                  for link in result.get("links") or [] if isinstance(link, dict)],
        "steps": [_step(s, reader, requirement) for s in result.get("steps") or []],
        "attachments": [reader.attachment(a, requirement) for a in result.get("attachments") or []],
    }


def _all_attachments(node: dict):
    yield from node.get("attachments", [])
    for step in node.get("steps", []):
        yield from _all_attachments(step)


def _assess(inputs: list[dict], others: list[dict], commits: list[str], record: tuple[str | None, bool],
            orphans: list[str], unreadable: list[str]) -> tuple[list[str], list[dict]]:
    """The reasons the evidence is not release-grade (none: it is), and the
    anomalies. Every run counts: those of the design inputs, and ``others``,
    the runs of tests tagged with no declared design input."""
    runs = [run for di in inputs for run in di["runs"]] + others
    record_commit, record_dirty = record
    reasons = []
    unverified = [di["id"] for di in inputs if di["status"] != "verified"]
    if unverified:
        reasons.append(f"{len(unverified)} design input(s) not verified: {', '.join(unverified)}")
    if unreadable:
        reasons.append(f"{len(unreadable)} result file(s) cannot be read: {', '.join(unreadable)}")
    for counts, message in ((lambda r: r["status"] in FAILING, "test(s) failed or broke"),
                            (lambda r: r["dirty"], "test(s) ran with uncommitted changes in the worktree"),
                            (lambda r: not r["commit"], "test(s) recorded no commit")):
        if n := len({run["test"] for run in runs if counts(run)}):
            reasons.append(f"{n} {message}")
    if record_commit is None:
        reasons.append("the record's commit is unknown (not a git repository)")
    elif other := [c for c in commits if c != record_commit]:
        reasons.append(f"runs tested {len(other)} other commit(s) than the record's "
                       f"({record_commit[:12]}): {', '.join(c[:12] for c in other)}")
    if record_dirty:
        reasons.append("the record has uncommitted changes")

    anomalies = []
    for di in inputs:
        if not di["runs"]:
            anomalies.append({"subject": di["id"], "kind": "no run", "detail": "no run of a test tagged with it"})
        for run in di["runs"]:
            subject = f"{di['id']} · {run['test']}"
            if run["status"] != "passed":
                anomalies.append({"subject": subject, "kind": run["status"], "detail": run["message"] or ""})
            for attachment in _all_attachments(run):
                if attachment["kind"] == "missing":
                    anomalies.append({"subject": subject, "kind": "missing attachment",
                                      "detail": attachment["source"] or attachment["name"]})
    for run in others:
        if run["status"] != "passed":
            anomalies.append({"subject": run["test"], "kind": run["status"], "detail": run["message"] or ""})
    for name in unreadable:
        anomalies.append({"subject": name, "kind": "unreadable", "detail": "it could hold a failed run"})
    for orphan in orphans:
        anomalies.append({"subject": orphan, "kind": "orphan tag",
                          "detail": "a test is tagged with an id no design document declares"})
    return reasons, anomalies


def _register(dhf_dir: Path, verified: set[str]) -> tuple[list[dict], dict[str, list[dict]], str]:
    """Each risk's status and residual decision, the risks each design input is
    a control for, and the acceptability criteria's state."""
    policy = policy_or_none(dhf_dir)  # malformed: the release gate reports it; no residual is evaluated
    register, control_for = [], {}
    for risk in risks(dhf_dir, policy):
        # A rating under unapproved acceptability criteria is itself a proposal.
        status = "proposed" if policy is not None and not policy.approved else risk.status
        entry = {"id": risk.id, "status": status, "residual": residual_decision(risk, policy, verified)}
        register.append(entry)
        for control in risk.controls:
            control_for.setdefault(control, []).append(entry)
    state = "none declared" if policy is None else ("approved" if policy.approved else policy.status)
    return register, control_for, state


def build_report(dhf_dir: Path, results_dir: Path, verification: dict | None = None) -> dict:
    """The report's data: provenance, evidence status, anomalies, traceability,
    and the runs. ``verification`` is :func:`build_verification`'s result, when
    the caller already has it."""
    dhf_dir, results_dir = Path(dhf_dir), Path(results_dir)
    verification = verification or build_verification(dhf_dir, results_dir)
    status = {row["design_input"]: row for group in verification["groups"] for row in group["design_inputs"]}
    register, control_for, policy_state = _register(
        dhf_dir, {di for di, row in status.items() if row["status"] == "verified"})

    root = repo_root(dhf_dir)
    reader = _Reader(results_dir)
    test_ids = _test_ids(dhf_dir, root)
    declared = sorted(design_inputs(dhf_dir), key=lambda di: sort_key(di["id"]))
    ids = {di["id"] for di in declared}
    runs: dict[str, list[dict]] = {}
    others: list[dict] = []  # runs of tests tagged with no declared design input: they count too
    results, unreadable = read_results(results_dir)
    for result in sorted(results, key=lambda r: (r.get("start") or 0, str(r.get("fullName", "")))):
        tagged = [di for di in design_input_tags(result) if di in ids]
        run = _run(result, reader, tagged, test_ids)
        for di in tagged:
            runs.setdefault(di, []).append(run)
        if not tagged:
            others.append(run)

    inputs = [{
        "id": di["id"],
        "text": di["text"],
        "context": di["context"],
        "traces_to": di["traces_to"],
        "control_for": sorted(control_for.get(di["id"], []), key=lambda r: sort_key(r["id"])),
        "status": status[di["id"]]["status"],
        "outputs": status[di["id"]]["outputs"],
        "runs": runs.get(di["id"], []),
    } for di in declared]

    state = record_state(dhf_dir)
    head = state.head() if state else None
    record_commit = head.commit if head else None
    # Uncommitted changes to the record itself: what the runs verified is in no commit.
    record_dirty = any(f.changed and f.tracking is not Tracking.IGNORED  # generated files under the DHF are ignored
                       for f in (state.files([dhf_dir]) if state else None) or [])
    commits = sorted({run["commit"] for run in [*others, *(r for di in inputs for r in di["runs"])] if run["commit"]})
    reasons, anomalies = _assess(inputs, others, commits, (record_commit, record_dirty), verification["orphans"],
                                 unreadable)
    executor, environment = read_run_facts(results_dir)
    return {
        # SOURCE_DATE_EPOCH (the reproducible-builds convention) fixes the time, so two
        # reports of one record can be compared byte for byte.
        "generated_at": _time(float(os.environ.get("SOURCE_DATE_EPOCH") or time.time()) * 1000),
        "rdm_version": __version__,
        "repository": (web_url(head.origin) if head else None) or (root or dhf_dir.resolve()).name,
        "record_commit": record_commit,
        "commits": commits,
        "executor": executor,
        "environment": environment,
        "results_dir": str(results_dir),
        "results_sha256": digest(reader.files),
        "release_grade": not reasons,
        "reasons": reasons,
        "risk_register": {
            "risks": len(register),
            "proposed": sum(r["status"] == "proposed" for r in register),
            "not_evaluated": sum(r["residual"] == NOT_EVALUATED for r in register),
            "policy": policy_state,
        },
        "anomalies": anomalies,
        "summary": verification["summary"],
        "left_out": {"shown": sorted(SHOWN_LABELS), "runner": sorted(RUNNER_LABELS)},
        "design_inputs": inputs,
        "files": reader.files,
    }


class Typesetter(Protocol):
    """The typeset port: the layout's files (path relative to its root, POSIX)
    and the one to compile, to a PDF."""

    name: str

    def typeset(self, main: str, files: dict[str, bytes]) -> bytes: ...


class RdmTypst:
    """Typst's crates as RDM's typeset provider, the native program ``rdm-typst``."""

    name = "rdm-typst"

    def __init__(self, program: str) -> None:
        self.program = program

    def typeset(self, main: str, files: dict[str, bytes]) -> bytes:
        with tempfile.TemporaryDirectory() as tmp:
            for path, data in files.items():
                target = Path(tmp) / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            done = subprocess.run([self.program, tmp, main], capture_output=True)
        if done.returncode != 0:
            raise ReportUnavailable(f"{self.name} could not compile the report: "
                                    f"{done.stderr.decode('utf-8', 'replace').strip() or 'no output'}")
        return done.stdout


def typesetter() -> Typesetter | None:
    """The provider found: ``RDM_TYPST``, or ``rdm-typst`` on the PATH."""
    program = os.environ.get("RDM_TYPST") or shutil.which("rdm-typst")
    return RdmTypst(program) if program else None


_typesetter: Callable[[], Typesetter | None] = typesetter


def use(find: Callable[[], Typesetter | None]) -> None:
    """Choose how the provider is found, once, when RDM starts (the component)."""
    global _typesetter
    _typesetter = find


def typeset_files(main: str, files: dict[str, bytes]) -> bytes:
    """``main``, one of ``files``, compiled to a PDF by the typeset provider; none is refused."""
    provider = _typesetter()
    if provider is None:
        raise ReportUnavailable("typesetting needs rdm-typst on the PATH (or RDM_TYPST)")
    return provider.typeset(main, files)


def _compile(workdir: Path, output: Path) -> None:
    files = {p.relative_to(workdir).as_posix(): p.read_bytes() for p in workdir.rglob("*") if p.is_file()}
    output.write_bytes(typeset_files(LAYOUT, files))


def render_pdf(report: dict, results_dir: Path, output: Path) -> Path:
    """Compile the report to ``output`` (a PDF); images are copied in beside the layout."""
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        workdir = Path(tmp)
        (workdir / LAYOUT).write_text((files("rdm.publishing") / LAYOUT).read_text(encoding="utf-8"), encoding="utf-8")
        (workdir / "attachments").mkdir()
        for di in report["design_inputs"]:
            for run in di["runs"]:
                for attachment in _all_attachments(run):
                    if attachment["kind"] == "image":
                        shutil.copy2(Path(results_dir) / attachment["source"], workdir / "attachments")
        (workdir / "report.json").write_text(json.dumps(report), encoding="utf-8")
        _compile(workdir, output.resolve())
    return output


def write_report(dhf_dir: Path, results_dir: Path, output: Path, verification: dict | None = None) -> dict:
    """Build the report data and render it to ``output``; return the data."""
    report = build_report(dhf_dir, results_dir, verification)
    render_pdf(report, Path(results_dir), output)
    return report


def evidence_report_command(dhf_dir: Path | None = None, allure_results_dir: Path | None = None,
                            output: Path | None = None) -> int:
    """Run `rdm story evidence-report --dhf … --allure-results … -o report.pdf`."""
    dhf = Path(dhf_dir or "dhf")
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    if not allure_results_dir or not Path(allure_results_dir).is_dir():
        print("Error: --allure-results <dir> is required (run the acceptance suite first)")
        return 2
    out = Path(output or REPORT_PDF)
    try:
        report = write_report(dhf, Path(allure_results_dir), out)
    except ReportUnavailable as error:
        print(f"Error: {error}")
        return 2
    summary = report["summary"]
    grade = "release-grade" if report["release_grade"] else "NOT release-grade: " + "; ".join(report["reasons"])
    print(f"Wrote {out}: {summary['verified']} verified, {summary['failed']} failed, {summary['untested']} "
          f"untested of {summary['total']} design input(s); results sha256 {report['results_sha256'][:12]}; "
          f"evidence {grade}")
    return 0
