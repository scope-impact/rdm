"""
Device-master-record index data, generated from the record (DI-29).

The DMR (§820.181 analog) is the current approved specification set. Its index
must be derived from the controlled documents themselves — the same
generated-not-transcribed rule the traceability matrix follows — so this
module scans a documents directory and writes one entry per controlled
document (id, title, path, revision) from its frontmatter.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

from rdm.record.sdd import parse_frontmatter


def dmr_entries(documents_dir: Path, relative_to: Path | None = None) -> list[dict]:
    """One entry per controlled document (has frontmatter ``id``), sorted by id.

    Documents without an ``id`` are skipped with a warning — an un-identified
    document is not controlled, so it cannot be indexed.
    """
    base = relative_to or documents_dir.parent
    entries: list[dict] = []
    for md in sorted(documents_dir.glob("*.md")):
        frontmatter = parse_frontmatter(md.read_text(encoding="utf-8", errors="ignore"))
        doc_id = str(frontmatter.get("id", "")).strip()
        if not doc_id:
            print(f"Warning: {md.name} has no frontmatter id -- not a controlled document, skipped",
                  file=sys.stderr)
            continue
        entries.append({
            "id": doc_id,
            "title": str(frontmatter.get("title", "")).strip(),
            "path": md.relative_to(base).as_posix() if md.is_relative_to(base) else str(md),
            "revision": frontmatter.get("revision"),
        })
    return sorted(entries, key=lambda e: e["id"])


def dmr_command(documents_dir: Path, output: Path) -> int:
    """Run `rdm story dmr <documents_dir> -o <out.yml>`."""
    docs = Path(documents_dir)
    if not docs.is_dir():
        print(f"Error: documents directory not found: {docs}")
        return 2
    entries = dmr_entries(docs)
    if not entries:
        print(f"Error: no controlled documents (frontmatter id) found in {docs}")
        return 1
    out = Path(output)
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = {"entries": entries}
    header = ("# Device-master-record index data -- GENERATED from the controlled\n"
              "# documents' frontmatter by `rdm story dmr`. Do not edit by hand.\n")
    out.write_text(header + yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
    print(f"Wrote {out}: {len(entries)} controlled document(s) indexed")
    return 0
