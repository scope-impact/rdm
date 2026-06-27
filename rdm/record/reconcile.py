"""
Generic reconciliation core: given declared user-need IDs and observations
keyed by ID, bucket the observations per ID, derive a status per bucket, and
report the orphan IDs (referenced but not declared).

Both the Allure verification reconciler (``record.allure``) and the persona
formative reconciler (``record.persona``) are this one shape with a different
aggregate type and status policy. Dependency-free (no pydantic / no story_audit)
so it stays usable from the lightweight record layer.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Callable, Iterable, TypeVar

A = TypeVar("A")


def load_json_records(directory: Path, suffix: str, build: Callable[[dict, str], object | None]) -> list:
    """Load ``*<suffix>`` JSON files from a directory into records.

    Centralizes the glob + safe-decode + dict-check skeleton shared by every
    record ingester (Allure results, persona runs, faithfulness verdicts). For
    each well-formed object, ``build(data, filename)`` returns a record (or
    ``None`` to skip it). A missing directory yields an empty list.
    """
    records: list = []
    if not directory.exists():
        return records
    for path in sorted(directory.glob(f"*{suffix}")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        record = build(data, path.name)
        if record is not None:
            records.append(record)
    return records


def aggregate_by_id(
    declared_ids: set[str],
    observations: Iterable,
    *,
    ids_of: Callable[[object], Iterable[str]],
    new: Callable[[str], A],
    fold: Callable[[A, object], None],
    status: Callable[[A], str],
) -> tuple[dict[str, A], list[str]]:
    """Bucket ``observations`` by declared ID and derive a status per bucket.

    Args:
        declared_ids: the IDs to aggregate (e.g. the user-need registry).
        observations: the items to fold in (test results, persona runs, ...).
        ids_of: the ID(s) an observation references.
        new: build a fresh aggregate for an ID (called as ``new(uid)``).
        fold: mutate an aggregate with one observation it references.
        status: derive the status string assigned to ``agg.status``.

    Returns ``(by_id, orphan_ids)`` where ``orphan_ids`` are referenced IDs that
    were not declared (sorted).
    """
    aggregated = {uid: new(uid) for uid in declared_ids}
    referenced: set[str] = set()
    for obs in observations:
        for uid in ids_of(obs):
            referenced.add(uid)
            agg = aggregated.get(uid)
            if agg is not None:
                fold(agg, obs)
    for agg in aggregated.values():
        agg.status = status(agg)
    return aggregated, sorted(referenced - declared_ids)


def ids_with_status(by_id: dict, status: str) -> list[str]:
    """Sorted IDs whose aggregate has the given status."""
    return sorted(uid for uid, agg in by_id.items() if agg.status == status)


def relevant_orphans(orphans: Iterable[str], declared_ids: set[str]) -> list[str]:
    """Orphan tags worth reporting: those sharing an ID prefix with a declared
    need, to avoid noise from unrelated tags (e.g. FT-001, US-001)."""
    prefixes = {uid.split("-")[0] for uid in declared_ids}
    return [tag for tag in orphans if tag.split("-")[0] in prefixes]


class StatusReportMixin:
    """Provides ``_ids_with`` for reports whose ``by_id`` maps declared IDs to
    aggregates carrying a ``status`` attribute. The IDs may be user needs, design
    inputs, etc. -- the mixin is agnostic."""

    by_id: dict

    def _ids_with(self, status: str) -> list[str]:
        return ids_with_status(self.by_id, status)
