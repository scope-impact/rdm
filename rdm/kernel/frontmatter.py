"""YAML frontmatter of the record's Markdown documents: the one parser every
context reads documents with."""

from __future__ import annotations

import copy
from functools import lru_cache
from pathlib import Path

import yaml


def parse_frontmatter(text: str) -> dict:
    """Parse a YAML frontmatter block delimited by leading ``---`` fences."""
    if not text.lstrip().startswith("---"):
        return {}
    parts = text.split("---", 2)
    if len(parts) < 3:
        return {}
    return copy.deepcopy(_load_yaml(parts[1]))  # a copy: callers may change what they get


# libyaml when installed (about 10x faster); one parse per distinct block, since
# a projection or a gate run reads the same documents through many helpers.
_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


@lru_cache(maxsize=1024)
def _load_yaml(block: str) -> dict:
    try:
        data = yaml.load(block, Loader=_LOADER)
    except yaml.YAMLError:
        return {}
    return data if isinstance(data, dict) else {}


def frontmatter_of(path: Path) -> dict:
    """Frontmatter of a document, or ``{}`` if unreadable/absent. Bytes that
    are not UTF-8 are dropped, so one stray byte never hides a document."""
    try:
        return parse_frontmatter(path.read_text(encoding="utf-8", errors="ignore"))
    except OSError:
        return {}
