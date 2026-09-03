"""Serialise a design history file as data, for templates and queries.

Every other command prints a report for a person to read. This one emits the
model itself, so a view is rendered from the same parsers the gates use rather
than from a bespoke extractor per view -- the failure mode this exists to end,
where each new report re-implements markdown parsing and re-discovers the same
schema quirks.

Deliberately free of project policy. The export carries what the records state;
an acceptability matrix, severity scale or escalation path is the project's own
and belongs in its ``config.yml``, which ``rdm render`` already loads alongside
the data. So this stays valid whatever a project's scoring policy is.

Usage::

    rdm record export --dhf dhf > dhf.yml
    rdm render report.html.jinja config.yml dhf.yml > report.html
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml

from rdm.record.allure import find_tests_dir, scan_source_tags
from rdm.record.sdd import (
    context_of,
    design_inputs,
    find_design_docs,
    parse_frontmatter,
    realises_by_context,
    registry_user_needs,
    satisfies_for,
    user_needs_from_doc,
)
from rdm.story_audit.backlog_parser import find_risk_clusters, parse_risk_cluster


def _cluster_of(risk_id: str) -> str | None:
    """The cluster segment of a risk id: the last word before its number.

    Derived from the id rather than a known list, so a project's own cluster
    names -- and any namespace segments before them -- work without the tool
    being taught them.
    """
    parts = risk_id.split("-")
    return parts[-2] if len(parts) >= 3 else None


def _repo_relative(path: Path, dhf_dir: Path) -> str:
    """A path relative to the directory holding the DHF, else as given.

    Never relative to the working directory: that would make the same tree
    export differently depending on where the command was run.
    """
    try:
        return str(Path(path).resolve().relative_to(Path(dhf_dir).resolve().parent))
    except (ValueError, OSError):
        return str(path)


def _needs_detail(dhf_dir: Path) -> list[dict[str, Any]]:
    """Every user need, with whatever fields its record declares.

    A need may be declared inline in a plan or one-per-file; both are the same
    ``user_needs`` frontmatter key, so both land here.
    """
    seen: dict[str, dict[str, Any]] = {}
    for md in sorted(dhf_dir.rglob("*.md")):
        front = parse_frontmatter(md.read_text(encoding="utf-8", errors="ignore"))
        entries = front.get("user_needs")
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if isinstance(entry, dict):
                need_id = str(entry.get("id", "")).strip()
                fields = {k: v for k, v in entry.items() if k != "id"}
            else:
                need_id, fields = str(entry).strip(), {}
            if not need_id or need_id in seen:
                continue
            seen[need_id] = {
                "id": need_id,
                "source": str(md.relative_to(dhf_dir)),
                **fields,
            }
    return list(seen.values())


def export_dhf(dhf_dir: Path, tests_dir: Path | None = None) -> dict[str, Any]:
    """The whole design history file as plain data."""
    docs = []
    for doc in find_design_docs(dhf_dir):
        front = parse_frontmatter(doc.read_text(encoding="utf-8", errors="ignore"))
        docs.append(
            {
                "path": str(doc.relative_to(dhf_dir)),
                "context": context_of(doc),
                "title": front.get("title"),
                "status": front.get("status"),
                "satisfies": sorted(satisfies_for(doc)),
                "realises": sorted(realises_by_context(dhf_dir).get(doc, set())),
            }
        )

    risks = []
    for cluster_path in find_risk_clusters(dhf_dir):
        for risk in parse_risk_cluster(cluster_path):
            row = risk.model_dump(exclude_none=False)
            row["cluster_file"] = cluster_path.name
            row.setdefault("cluster", None)
            risk_id = row.get("title", "").split(":")[0].strip() or row.get("id", "")
            row["cluster"] = row.get("cluster") or _cluster_of(risk_id)
            risks.append(row)

    if tests_dir is None:
        tests_dir = find_tests_dir(dhf_dir)
    tags = scan_source_tags(tests_dir) if tests_dir else {}

    def _rel(path: str) -> str:
        """Paths relative to the test root.

        An exported data file gets committed and rendered elsewhere; an absolute
        path would bake this machine's layout into it and stop two runs of the
        same tree from producing the same output.
        """
        try:
            return str(Path(path).resolve().relative_to(Path(tests_dir).resolve()))
        except (ValueError, OSError):
            return path

    return {
        "dhf": str(dhf_dir),
        "user_needs": _needs_detail(dhf_dir),
        "user_need_ids": sorted(registry_user_needs(dhf_dir)),
        "design_inputs": design_inputs(dhf_dir),
        "design_documents": docs,
        "risks": risks,
        "tests": {
            # relative to the DHF's repository where possible, for the same
            # reproducibility reason as the per-tag paths below
            "root": _repo_relative(tests_dir, dhf_dir) if tests_dir else None,
            # id -> the test files that tag it, so a view can show what verifies what
            "tags": {tag: sorted(_rel(f) for f in files) for tag, files in sorted(tags.items())},
        },
        "totals": {
            "user_needs": len(registry_user_needs(dhf_dir)),
            "design_inputs": len(design_inputs(dhf_dir)),
            "design_documents": len(docs),
            "risks": len(risks),
            "tagged_ids": len(tags),
        },
    }


def export_command(args) -> int:
    dhf_dir = Path(args.dhf)
    if not dhf_dir.is_dir():
        print(f"Error: DHF directory not found: {dhf_dir}")
        return 1
    data = export_dhf(dhf_dir, Path(args.tests) if args.tests else None)
    if args.format == "json":
        print(json.dumps(data, indent=1, default=str))
    else:
        print(yaml.safe_dump(data, sort_keys=False, allow_unicode=True, default_flow_style=False))
    return 0
