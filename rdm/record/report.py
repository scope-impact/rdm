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
It is compiled with the ``typst`` package (extra ``report``) or, failing that,
a ``typst`` executable on PATH (the RDM image has one).
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from importlib.resources import files
from pathlib import Path

from rdm.record.allure import DESIGN_INPUT_LABELS
from rdm.record.git import git, repo_root, web_url
from rdm.record.risk import NOT_EVALUATED, read_policy, residual_decision, risks
from rdm.record.sdd import design_inputs
from rdm.record.verify import build_verification
from rdm.version import __version__

LAYOUT = "verification_report.typ"
REPORT_PDF = "verification_report.pdf"
TEXT_TYPES = {"application/json", "application/xml", "application/yaml", "application/x-yaml"}
IMAGE_TYPES = {"image/png", "image/jpeg", "image/gif", "image/svg+xml", "image/webp"}
# A text attachment longer than this is cut; the full file is in the bundle, by checksum.
TEXT_LINES, TEXT_CHARS = 60, 6000
# pytest's captured output: listed by checksum, not printed.
CAPTURED = {"stdout", "stderr", "log"}
# Labels the report already shows elsewhere, and runner internals: not repeated per run.
# Allure's severity is left out too: beside a risk it reads as a harm's severity, and it is not one.
SHOWN_LABELS = {"story", "epic", "feature", "output", "commit", "worktree", "severity"}
RUNNER_LABELS = {"host", "thread", "framework", "language", "suite", "parentSuite", "subSuite", "package"}
FAILED_STATUSES = {"failed", "broken"}


class ReportUnavailable(RuntimeError):
    """Neither the ``typst`` package nor a ``typst`` executable is available."""


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def result_files(results_dir: Path) -> list[dict]:
    """Every file in the results directory, in name order, with its size and SHA-256."""
    return [{"name": p.name, "bytes": p.stat().st_size, "sha256": _sha256(p)}
            for p in sorted(Path(results_dir).iterdir()) if p.is_file()]


def results_sha256(results_dir: Path) -> str:
    """One SHA-256 over every file in the results directory: names and contents, in name order."""
    digest = hashlib.sha256()
    for entry in result_files(results_dir):
        digest.update(entry["name"].encode() + b"\0" + entry["sha256"].encode() + b"\n")
    return digest.hexdigest()


def _time(ms) -> str | None:
    if not isinstance(ms, (int, float)):
        return None
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")


def _duration(start, stop) -> str | None:
    if not isinstance(start, (int, float)) or not isinstance(stop, (int, float)):
        return None
    return f"{(stop - start) / 1000:.2f} s"


def _attachment(item: dict, results_dir: Path, requirement: set[str]) -> dict:
    name, kind = str(item.get("name") or item.get("source") or ""), str(item.get("type") or "")
    source = str(item.get("source") or "")
    path = results_dir / source
    if not source or Path(source).name != source or not path.is_file():
        return {"name": name, "type": kind, "source": source, "kind": "missing", "sha256": None}
    entry = {"name": name, "type": kind, "source": source, "sha256": _sha256(path), "bytes": path.stat().st_size}
    if name in CAPTURED:
        return entry | {"kind": "captured"}
    if name in requirement:
        return entry | {"kind": "requirement"}
    if kind.startswith("text/") or kind in TEXT_TYPES:
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        shown = "\n".join(lines[:TEXT_LINES])[:TEXT_CHARS]
        return entry | {"kind": "text", "text": shown, "truncated": shown != "\n".join(lines),
                        "lines": len(lines)}
    if kind in IMAGE_TYPES:
        return entry | {"kind": "image"}
    return entry | {"kind": "file"}


def _details(node: dict) -> tuple[str | None, str | None]:
    details = node.get("statusDetails") or {}
    return (details.get("message") or None), (details.get("trace") or None)


def _step(step: dict, results_dir: Path, requirement: set[str]) -> dict:
    message, trace = _details(step)
    return {
        "name": str(step.get("name", "")),
        "status": str(step.get("status", "unknown")),
        "message": message,
        "trace": trace,
        "attachments": [_attachment(a, results_dir, requirement) for a in step.get("attachments") or []],
        "steps": [_step(s, results_dir, requirement) for s in step.get("steps") or []],
    }


def _test_id(full_name: str, name: str) -> str:
    """``tests.acceptance.test_x#test_y`` → ``tests/acceptance/test_x.py::test_y``."""
    module, _, function = full_name.partition("#")
    return f"{module.replace('.', '/')}.py::{function}" if module and function else (full_name or name)


def _run(result: dict, results_dir: Path, tagged: set[str]) -> dict:
    labels = [{"name": str(label.get("name", "")), "value": str(label.get("value", ""))}
              for label in result.get("labels") or [] if isinstance(label, dict)]
    first: dict[str, str] = {}
    for label in labels:
        first.setdefault(label["name"], label["value"])
    message, trace = _details(result)
    requirement = {f"requirement {di}" for di in tagged}
    return {
        "test": _test_id(str(result.get("fullName", "")), str(result.get("name", ""))),
        "status": str(result.get("status", "unknown")),
        "message": message,
        "trace": trace,
        "start": _time(result.get("start")),
        "duration": _duration(result.get("start"), result.get("stop")),
        "commit": first.get("commit"),
        "dirty": first.get("worktree") == "dirty",
        "labels": [label for label in labels if label["name"] not in SHOWN_LABELS | RUNNER_LABELS],
        "links": [{"name": str(link.get("name") or link.get("url", "")), "url": str(link.get("url", ""))}
                  for link in result.get("links") or [] if isinstance(link, dict)],
        "steps": [_step(s, results_dir, requirement) for s in result.get("steps") or []],
        "attachments": [_attachment(a, results_dir, requirement) for a in result.get("attachments") or []],
    }


def _results(results_dir: Path) -> list[dict]:
    loaded = []
    for path in sorted(results_dir.glob("*-result.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(data, dict):
            loaded.append(data)
    return sorted(loaded, key=lambda r: (r.get("start") or 0, str(r.get("fullName", ""))))


def _id_order(ident: str) -> tuple:
    """DI-2 before DI-10: the id's prefix, then its number."""
    prefix, _, number = ident.rpartition("-")
    return (prefix, int(number)) if number.isdigit() else (ident, 0)


def _properties(path: Path) -> dict[str, str]:
    """A .properties file, as Allure's environment.properties is written."""
    if not path.is_file():
        return {}
    facts = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, _, value = line.partition("=")
            facts[key.strip()] = value.strip()
    return facts


def _executor(results_dir: Path) -> dict | None:
    try:
        data = json.loads((results_dir / "executor.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return {k: str(data[k]) for k in ("name", "buildName", "buildUrl") if data.get(k)} or None


def _all_attachments(node: dict):
    yield from node.get("attachments", [])
    for step in node.get("steps", []):
        yield from _all_attachments(step)


def _assess(inputs: list[dict], record_commit: str | None, orphans: list[str]) -> tuple[list[str], list[dict]]:
    """The reasons the evidence is not release-grade (none: it is), and the anomalies."""
    runs = [run for di in inputs for run in di["runs"]]
    reasons = []
    unverified = [di["id"] for di in inputs if di["status"] != "verified"]
    if unverified:
        reasons.append(f"{len(unverified)} design input(s) not verified: {', '.join(unverified)}")
    failed = sorted({run["test"] for run in runs if run["status"] in FAILED_STATUSES})
    if failed:
        reasons.append(f"{len(failed)} test(s) failed or broke")
    dirty = sorted({run["test"] for run in runs if run["dirty"]})
    if dirty:
        reasons.append(f"{len(dirty)} test(s) ran with uncommitted changes in the worktree")
    unversioned = sorted({run["test"] for run in runs if not run["commit"]})
    if unversioned:
        reasons.append(f"{len(unversioned)} test(s) recorded no commit")
    if record_commit is None:
        reasons.append("the record's commit is unknown (not a git repository)")
    else:
        other = sorted({run["commit"] for run in runs if run["commit"] and run["commit"] != record_commit})
        if other:
            reasons.append(f"runs tested {len(other)} other commit(s) than the record's "
                           f"({record_commit[:12]}): {', '.join(c[:12] for c in other)}")

    anomalies = []
    for di in inputs:
        if not di["runs"]:
            anomalies.append({"subject": di["id"], "kind": "no run", "detail": "no run of a test tagged with it"})
        for run in di["runs"]:
            if run["status"] != "passed":
                anomalies.append({"subject": f"{di['id']} · {run['test']}", "kind": run["status"],
                                  "detail": run["message"] or ""})
            for attachment in _all_attachments(run):
                if attachment["kind"] == "missing":
                    anomalies.append({"subject": f"{di['id']} · {run['test']}", "kind": "missing attachment",
                                      "detail": attachment["source"] or attachment["name"]})
    for orphan in orphans:
        anomalies.append({"subject": orphan, "kind": "orphan tag",
                          "detail": "a test is tagged with an id no design document declares"})
    return reasons, anomalies


def build_report(dhf_dir: Path, results_dir: Path) -> dict:
    """The report's data: provenance, evidence status, anomalies, traceability, and the runs."""
    dhf_dir, results_dir = Path(dhf_dir), Path(results_dir)
    verification = build_verification(dhf_dir, results_dir)
    rows = {row["design_input"]: row for group in verification["groups"] for row in group["design_inputs"]}
    try:
        policy = read_policy(dhf_dir)
    except ValueError:
        policy = None  # a malformed policy: the release gate reports it; here no residual is evaluated
    verified = {row["design_input"] for row in rows.values() if row["status"] == "verified"}
    register = []
    control_for: dict[str, list[dict]] = {}
    for risk in risks(dhf_dir, policy):
        proposal = risk.status == "proposed" or (policy is not None and policy.status == "proposed")
        entry = {"id": risk.id, "status": "proposed" if proposal else "approved",
                 "residual": residual_decision(risk, policy, verified)}
        register.append(entry)
        for control in risk.controls:
            control_for.setdefault(control, []).append(entry)

    runs: dict[str, list[dict]] = {}
    for result in _results(results_dir):
        tagged = {str(label.get("value", "")).strip() for label in result.get("labels") or []
                  if isinstance(label, dict) and label.get("name") in DESIGN_INPUT_LABELS}
        if tagged:
            run = _run(result, results_dir, tagged)
            for di in tagged:
                runs.setdefault(di, []).append(run)

    inputs = [{
        "id": di["id"],
        "text": di["text"],
        "context": di["context"],
        "traces_to": di["traces_to"],
        "criterion": "risk-based" if di["id"] in control_for else "baseline",
        "control_for": sorted(control_for.get(di["id"], []), key=lambda r: _id_order(r["id"])),
        "status": rows[di["id"]]["status"],
        "outputs": rows[di["id"]]["outputs"],
        "runs": runs.get(di["id"], []),
    } for di in sorted(design_inputs(dhf_dir), key=lambda di: _id_order(di["id"]))]

    root = repo_root(dhf_dir)
    record_commit = git(root, "rev-parse", "HEAD") if root else None
    reasons, anomalies = _assess(inputs, record_commit, verification["orphans"])
    repository = web_url(git(root, "remote", "get-url", "origin")) if root else None
    return {
        "title": "Verification report",
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
        "rdm_version": __version__,
        "repository": repository or (root.name if root else dhf_dir.resolve().name),
        "record_commit": record_commit,
        "commits": sorted({run["commit"] for di in inputs for run in di["runs"] if run["commit"]}),
        "executor": _executor(results_dir),
        "environment": _properties(results_dir / "environment.properties"),
        "results_dir": str(results_dir),
        "results_sha256": results_sha256(results_dir),
        "release_grade": not reasons,
        "risk_register": {
            "risks": len(register),
            "proposed": sum(r["status"] == "proposed" for r in register),
            "not_evaluated": sum(r["residual"] == NOT_EVALUATED for r in register),
            "policy": "none declared" if policy is None else ("proposed" if policy.status == "proposed"
                                                              else "approved"),
        },
        "reasons": reasons,
        "anomalies": anomalies,
        "summary": verification["summary"],
        "design_inputs": inputs,
        "files": result_files(results_dir),
    }


def _compile(workdir: Path, output: Path) -> None:
    try:
        import typst
    except ImportError:
        executable = shutil.which("typst")
        if executable is None:
            raise ReportUnavailable(
                "the verification report needs Typst: install the extra (rdm[report]) or a typst executable")
        subprocess.run([executable, "compile", "--root", str(workdir), str(workdir / LAYOUT), str(output)],
                       check=True, capture_output=True, text=True)
        return
    output.write_bytes(typst.compile(str(workdir / LAYOUT), root=str(workdir)))


def render_pdf(report: dict, results_dir: Path, output: Path) -> Path:
    """Compile the report to ``output`` (a PDF); images are copied in beside the layout."""
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        workdir = Path(tmp)
        (workdir / LAYOUT).write_text((files("rdm.record") / LAYOUT).read_text(encoding="utf-8"), encoding="utf-8")
        (workdir / "attachments").mkdir()
        for di in report["design_inputs"]:
            for run in di["runs"]:
                for attachment in _all_attachments(run):
                    if attachment["kind"] == "image":
                        shutil.copy2(Path(results_dir) / attachment["source"], workdir / "attachments")
        (workdir / "report.json").write_text(json.dumps(report), encoding="utf-8")
        _compile(workdir, output.resolve())
    return output


def write_report(dhf_dir: Path, results_dir: Path, output: Path) -> dict:
    """Build the report data and render it to ``output``; return the data."""
    report = build_report(dhf_dir, results_dir)
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
