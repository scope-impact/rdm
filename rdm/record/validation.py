"""
Summative validation records (DI-33): per-user-need, human-evidenced.

Verification is machine-executed; validation is a human judgment that the
product meets the user need. This module gives that judgment a place in the
record — ``<dhf>/validation/UN-…-validation.json``:

    {
      "user_need": "UN-001",
      "disposition": "approved",       // only "approved" counts
      "reviewer": "name (role)",
      "summary": "what was reviewed, against what"
    }

The release gate reports each user need lacking an approved record as a
WARNING: absence must be visible, but a machine cannot supply the judgment,
so it does not block.
"""

from __future__ import annotations

from pathlib import Path

from rdm.record.reconcile import load_json_records
from rdm.record.sdd import registry_user_needs

APPROVED = "approved"


def validation_dir_for(dhf_dir: Path) -> Path:
    return Path(dhf_dir) / "validation"


def parse_validation_records(validation_dir: Path) -> dict[str, dict]:
    """Approved-or-not validation records, keyed by user-need id."""
    def _build(data: dict, filename: str) -> dict | None:
        un = str(data.get("user_need", "")).strip()
        if not un:
            return None
        return {
            "user_need": un,
            "disposition": str(data.get("disposition", "")).strip().lower(),
            "reviewer": str(data.get("reviewer", "")).strip(),
            "summary": str(data.get("summary", "")).strip(),
            "source": filename,
        }

    records = load_json_records(Path(validation_dir), "-validation.json", _build)
    return {record["user_need"]: record for record in records}


def unvalidated_user_needs(dhf_dir: Path) -> list[str]:
    """Registered user needs without an approved validation record, sorted."""
    records = parse_validation_records(validation_dir_for(dhf_dir))
    approved = {un for un, record in records.items() if record["disposition"] == APPROVED}
    return sorted(registry_user_needs(Path(dhf_dir)) - approved)
