"""
The verification report (DI-64): what was run to verify each design input, as a PDF.

The traceability matrix says *that* a design input is verified; this report
shows the runs behind it: for each design input, every run of a test tagged
with it, with the commit it tested, the worktree state, times, status and
failure message, its labels and links, each step with its status, and every
attachment (text inline, images embedded, anything else by SHA-256).

The data is written as JSON next to a Typst layout that reads it, never
spliced into markup, so nothing a test prints can change the document. It is
compiled with the ``typst`` package (extra ``report``) or, failing that, a
``typst`` executable on PATH (the RDM image has one).
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
from rdm.record.sdd import design_inputs
from rdm.record.verify import build_verification
from rdm.version import __version__

TEXT_TYPES = {"application/json", "application/xml", "application/yaml", "application/x-yaml"}
LAYOUT = "verification_report.typ"
REPORT_PDF = "verification_report.pdf"


class ReportUnavailable(RuntimeError):
    """Neither the ``typst`` package nor a ``typst`` executable is available."""


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def results_sha256(results_dir: Path) -> str:
    """One SHA-256 over every file in the results directory: names and contents, in name order."""
    digest = hashlib.sha256()
    for path in sorted(p for p in Path(results_dir).iterdir() if p.is_file()):
        digest.update(path.name.encode() + b"\0" + _sha256(path).encode() + b"\n")
    return digest.hexdigest()


def _time(ms) -> str | None:
    if not isinstance(ms, (int, float)):
        return None
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3] + " UTC"


def _attachment(item: dict, results_dir: Path) -> dict:
    name, kind = str(item.get("name") or item.get("source") or ""), str(item.get("type") or "")
    source = str(item.get("source") or "")
    path = results_dir / source
    if not source or Path(source).name != source or not path.is_file():
        return {"name": name, "type": kind, "source": source, "kind": "missing", "sha256": None}
    entry = {"name": name, "type": kind, "source": source, "sha256": _sha256(path)}
    if kind.startswith("text/") or kind in TEXT_TYPES:
        return entry | {"kind": "text", "text": path.read_text(encoding="utf-8", errors="replace")}
    if kind in {"image/png", "image/jpeg", "image/gif", "image/svg+xml", "image/webp"}:
        return entry | {"kind": "image"}
    return entry | {"kind": "file"}


def _details(node: dict) -> tuple[str | None, str | None]:
    details = node.get("statusDetails") or {}
    return (details.get("message") or None), (details.get("trace") or None)


def _step(step: dict, results_dir: Path) -> dict:
    message, trace = _details(step)
    return {
        "name": str(step.get("name", "")),
        "status": str(step.get("status", "unknown")),
        "message": message,
        "trace": trace,
        "attachments": [_attachment(a, results_dir) for a in step.get("attachments") or []],
        "steps": [_step(s, results_dir) for s in step.get("steps") or []],
    }


def _run(result: dict, results_dir: Path) -> dict:
    labels = [{"name": str(label.get("name", "")), "value": str(label.get("value", ""))}
              for label in result.get("labels") or [] if isinstance(label, dict)]
    first = {}
    for label in labels:
        first.setdefault(label["name"], label["value"])
    message, trace = _details(result)
    return {
        "name": str(result.get("name", "")),
        "full_name": str(result.get("fullName", "")),
        "status": str(result.get("status", "unknown")),
        "message": message,
        "trace": trace,
        "start": _time(result.get("start")),
        "stop": _time(result.get("stop")),
        "commit": first.get("commit"),
        "dirty": first.get("worktree") == "dirty",
        "labels": labels,
        "links": [{"name": str(link.get("name") or link.get("url", "")), "url": str(link.get("url", "")),
                   "type": str(link.get("type", ""))} for link in result.get("links") or [] if isinstance(link, dict)],
        "steps": [_step(s, results_dir) for s in result.get("steps") or []],
        "attachments": [_attachment(a, results_dir) for a in result.get("attachments") or []],
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


def build_report(dhf_dir: Path, results_dir: Path) -> dict:
    """The report's data: summary, provenance, and each design input with its runs."""
    dhf_dir, results_dir = Path(dhf_dir), Path(results_dir)
    verification = build_verification(dhf_dir, results_dir)
    rows = {row["design_input"]: row for group in verification["groups"] for row in group["design_inputs"]}
    runs: dict[str, list[dict]] = {}
    for result in _results(results_dir):
        tagged = {str(label.get("value", "")).strip() for label in result.get("labels") or []
                  if isinstance(label, dict) and label.get("name") in DESIGN_INPUT_LABELS}
        if tagged:
            run = _run(result, results_dir)
            for di in tagged:
                runs.setdefault(di, []).append(run)

    inputs = [{
        "id": di["id"],
        "text": di["text"],
        "context": di["context"],
        "traces_to": di["traces_to"],
        "status": rows[di["id"]]["status"],
        "outputs": rows[di["id"]]["outputs"],
        "runs": runs.get(di["id"], []),
    } for di in sorted(design_inputs(dhf_dir), key=lambda di: _id_order(di["id"]))]
    commits = sorted({run["commit"] for di in inputs for run in di["runs"] if run["commit"]})
    return {
        "title": "Verification report",
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
        "rdm_version": __version__,
        "results_dir": str(results_dir),
        "results_sha256": results_sha256(results_dir),
        "commits": commits,
        "dirty": any(run["dirty"] for di in inputs for run in di["runs"]),
        "summary": verification["summary"],
        "design_inputs": inputs,
        "orphans": verification["orphans"],
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


def _all_attachments(node: dict):
    yield from node.get("attachments", [])
    for step in node.get("steps", []):
        yield from _all_attachments(step)


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
    runs = sum(len(di["runs"]) for di in report["design_inputs"])
    print(f"Wrote {out}: {summary['verified']} verified, {summary['failed']} failed, {summary['untested']} "
          f"untested of {summary['total']} design input(s); {runs} run(s); results sha256 "
          f"{report['results_sha256'][:12]}")
    return 0
