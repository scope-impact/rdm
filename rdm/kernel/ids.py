"""
Record ids, one grammar: an upper-case prefix of two or more characters, any
further upper-case segments, and a number — UN-012, DI-3, RISK-TOOL-001.
Text that does not match (a "{di_id}" in a template string, a word) is no id.
"""

from __future__ import annotations

import re
from typing import Iterable

ID = re.compile(r"[A-Z][A-Z0-9]+(?:-[A-Z][A-Z0-9]*)*-\d+")


def is_id(text: str) -> bool:
    """Whether ``text`` is a record id."""
    return ID.fullmatch(text) is not None


def relevant_orphans(referenced: Iterable[str], declared_ids: set[str]) -> list[str]:
    """The referenced ids no declared id names, worth reporting: those sharing
    an id prefix with a declared one, to avoid noise from unrelated tags (e.g.
    FT-001, US-001). Sorted; the one rule every gate and the graph report by."""
    prefixes = {uid.split("-")[0] for uid in declared_ids}
    return sorted(tag for tag in set(referenced) - set(declared_ids) if tag.split("-")[0] in prefixes)


def sort_key(ident: str) -> tuple:
    """Sort record ids by prefix, then number: DI-2 before DI-10, and
    RISK-TOOL-001 with its kind."""
    prefix, _, number = ident.rpartition("-")
    return (prefix, int(number)) if number.isdigit() else (ident, 0)
