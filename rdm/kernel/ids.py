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


_LEADING_LETTERS = re.compile(r"[A-Za-z]+")


def _prefix(tag: str) -> str:
    match = _LEADING_LETTERS.match(tag)
    return match.group().upper() if match else ""


def relevant_orphans(referenced: Iterable[str], declared_ids: set[str]) -> list[str]:
    """The referenced ids no declared id names, worth reporting: those sharing
    an id prefix with a declared one, to avoid noise from unrelated tags (e.g.
    FT-001, US-001). The prefix is the leading letters, whatever their case or
    the separator after them, so a mistyped ``di-1`` or ``DI_1`` is reported,
    not dropped. Sorted; the one rule every gate and the graph report by."""
    prefixes = {_prefix(uid) for uid in declared_ids}
    return sorted(tag for tag in set(referenced) - set(declared_ids) if _prefix(tag) in prefixes)


def sort_key(ident: str) -> tuple:
    """Sort record ids by prefix, then number: DI-2 before DI-10, and
    RISK-TOOL-001 with its kind."""
    prefix, _, number = ident.rpartition("-")
    return (prefix, int(number)) if number.isdigit() else (ident, 0)
