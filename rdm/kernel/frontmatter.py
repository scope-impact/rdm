"""YAML frontmatter of the record's Markdown documents: the one parser every
context reads documents with."""

from __future__ import annotations

import copy
from functools import lru_cache
from pathlib import Path
from typing import Iterator

import yaml


def _block(text: str) -> tuple[str | None, str | None]:
    """A document's frontmatter block, and why it cannot be read: it runs from
    a leading ``---`` line to the next ``---`` line, a byte-order mark ignored.
    ``(None, None)`` when the document has no frontmatter."""
    lines = text.lstrip("﻿").lstrip().splitlines()
    if not lines or lines[0].rstrip() != "---":
        return None, None
    for end, line in enumerate(lines[1:], 1):
        if line.rstrip() == "---":
            return "\n".join(lines[1:end]), None
    return None, "its frontmatter has no closing --- line"


def parse_frontmatter(text: str) -> dict:
    """Parse a YAML frontmatter block delimited by ``---`` lines; ``{}`` when
    there is none or it cannot be read (`frontmatter_problem` says why)."""
    block, _ = _block(text)
    data = _load_yaml(block) if block is not None else None
    return copy.deepcopy(data) if isinstance(data, dict) else {}  # a copy: callers may change what they get


def frontmatter_problem(text: str) -> str | None:
    """Why a document's frontmatter cannot be read -- no closing line, not
    YAML, or not a mapping -- or ``None`` when it can (or there is none)."""
    block, problem = _block(text)
    if block is None:
        return problem
    data = _load_yaml(block)
    if isinstance(data, yaml.YAMLError):
        return f"its frontmatter is not YAML ({str(data).splitlines()[0]})"
    if data is not None and not isinstance(data, dict):
        return "its frontmatter is not a mapping"
    return None


# libyaml when installed (about 10x faster); one parse per distinct block, since
# a projection or a gate run reads the same documents through many helpers.
_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


@lru_cache(maxsize=1024)
def _load_yaml(block: str):
    """The block's YAML, or the error that stopped it."""
    try:
        return yaml.load(block, Loader=_LOADER)
    except yaml.YAMLError as error:
        return error


def _text(path: Path) -> str:
    """A document's text; bytes that are not UTF-8 are dropped, so one stray
    byte never hides a document."""
    return path.read_text(encoding="utf-8", errors="ignore")


def frontmatter_of(path: Path) -> dict:
    """Frontmatter of a document, or ``{}`` if unreadable/absent."""
    try:
        return parse_frontmatter(_text(path))
    except OSError:
        return {}


def documents(dhf_dir: Path) -> Iterator[tuple[Path, dict]]:
    """Every Markdown document under a DHF, in path order, with its frontmatter:
    the one walk of the record every reader shares."""
    for md in sorted(Path(dhf_dir).rglob("*.md")):
        yield md, frontmatter_of(md)


def unreadable_documents(dhf_dir: Path) -> Iterator[tuple[Path, str]]:
    """Every Markdown document under a DHF whose frontmatter cannot be read,
    with why."""
    for md in sorted(Path(dhf_dir).rglob("*.md")):
        try:
            problem = frontmatter_problem(_text(md))
        except OSError as error:
            problem = f"it cannot be read ({error.strerror})"
        if problem:
            yield md, problem
