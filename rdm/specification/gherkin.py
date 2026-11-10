"""Gherkin, read: the scenarios of ``.feature`` files as requirements.

A scenario is a requirement when one of its tags is an id: by default any
tag shaped like ``<letters>-<number>`` with optional middle parts
(``@item-02-001``, ``@REQ-12``, ``@alarm-3``), or the pattern the spec's
configuration names. The last id tag before a ``Scenario`` is its id; a
scenario without one is listed as unstamped. A ``Rule:`` sets the rule of
the scenarios after it, reset per file; the ``Feature:`` title and the
description lines under it are kept. Tags are read from lines whose
stripped text starts with ``@``; comments (``#``) are ignored.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

# The default shape of a requirement id: letters, optional parts, a number.
DEFAULT_ID = r"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*-\d+"

_SCENARIO = re.compile(r"^\s*Scenario(?: Outline)?:\s*(.+?)\s*$")
_RULE = re.compile(r"^\s*Rule:\s*(.*?)\s*$")
_FEATURE = re.compile(r"^\s*Feature:\s*(.*?)\s*$")
_BLOCK = re.compile(r"^\s*(Background|Scenario|Scenario Outline|Rule|Examples|Given|When|Then|And|But)\b")


@dataclass(frozen=True)
class Item:
    """One requirement: a scenario with an id tag."""

    id: str                      # the id tag, without "@"
    context: str                 # the bounded context the file belongs to
    title: str
    rule: str | None
    source: str                  # "<path from the repository root>:<line>"
    feature: str = ""            # the Feature's title
    tags: tuple[str, ...] = ()   # every tag on the lines before the scenario, with "@"
    obligations: tuple[tuple[str, str], ...] = ()   # filled by a needs adapter, e.g. Allium's

    @property
    def requirement(self) -> str:
        return self.id


@dataclass
class Feature:
    title: str
    description: str
    context: str
    source: str
    tags: tuple[str, ...] = ()


@dataclass
class Reading:
    items: list[Item] = field(default_factory=list)
    unstamped: list[tuple[str, str]] = field(default_factory=list)   # (source, title)
    features: list[Feature] = field(default_factory=list)


def read_feature(path: Path, source: str, context: str, id_pattern: str = DEFAULT_ID,
                 reading: Reading | None = None) -> Reading:
    """Read one ``.feature`` file into ``reading`` (a new one by default).
    ``source`` is the file's path as it should be cited."""
    reading = reading if reading is not None else Reading()
    id_tag = re.compile(rf"^@({id_pattern})$")
    feature: Feature | None = None
    describing = False
    rule, pending, tags = None, None, []
    for number, line in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        if stripped.startswith("@"):
            describing = False
            for tag in stripped.split():
                tags.append(tag)
                if match := id_tag.match(tag):
                    pending = match.group(1)
        elif match := _FEATURE.match(line):
            feature = Feature(match.group(1), "", context, f"{source}:{number}", tuple(tags))
            reading.features.append(feature)
            describing, tags = True, []
        elif (match := _RULE.match(line)):
            rule, describing = (match.group(1) or None), False
        elif match := _SCENARIO.match(line):
            describing = False
            title = match.group(1)
            if pending:
                reading.items.append(Item(pending, context, title, rule, f"{source}:{number}",
                                          feature.title if feature else "", tuple(tags)))
            else:
                reading.unstamped.append((f"{source}:{number}", title))
            pending, tags = None, []
        elif describing and feature is not None and stripped and not _BLOCK.match(line):
            feature.description = f"{feature.description} {stripped}".strip()
    return reading
