"""Gherkin, read: the scenarios of ``.feature`` files as requirements.

Parsed with Cucumber's own parser (``gherkin-official``), so RDM reads a
feature file exactly as Gherkin tools do. A scenario (or scenario outline)
is a requirement when one of its tags is an id: by default any tag shaped
like ``<letters>-<number>`` with optional middle parts (``@item-02-001``,
``@REQ-12``, ``@alarm-3``), or the pattern the spec's configuration names.
The last id tag of a scenario is its id; a scenario without one is listed
as unstamped. Each requirement keeps its Feature, its Rule (if any), its
title, its tags and its source line; each Feature keeps its description. A
file that does not parse is a problem, never skipped quietly.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from gherkin.errors import CompositeParserException, ParserException
from gherkin.parser import Parser
from gherkin.token_scanner import TokenScanner

# The default shape of a requirement id: letters, optional parts, a number.
DEFAULT_ID = r"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*-\d+"


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
    problems: list[str] = field(default_factory=list)                 # files that do not parse


def _description(text: str | None) -> str:
    return " ".join(line.strip() for line in (text or "").splitlines() if line.strip())


def read_feature(path: Path, source: str, context: str, id_pattern: str = DEFAULT_ID,
                 reading: Reading | None = None) -> Reading:
    """Read one ``.feature`` file into ``reading`` (a new one by default).
    ``source`` is the file's path as it should be cited."""
    reading = reading if reading is not None else Reading()
    id_tag = re.compile(rf"^@({id_pattern})$")
    try:
        document = Parser().parse(TokenScanner(path.read_text(encoding="utf-8")))
    except (CompositeParserException, ParserException, OSError, UnicodeDecodeError) as error:
        reading.problems.append(f"{source}: {error}")
        return reading
    node = document.get("feature")
    if not node:
        return reading
    feature = Feature(node["name"], _description(node.get("description")), context,
                      f"{source}:{node['location']['line']}", tuple(t["name"] for t in node.get("tags") or []))
    reading.features.append(feature)

    def scenarios(children, rule):
        for child in children:
            if "rule" in child:
                yield from scenarios(child["rule"].get("children") or [], child["rule"]["name"] or None)
            elif "scenario" in child:
                yield child["scenario"], rule

    for scenario, rule in scenarios(node.get("children") or [], None):
        tags = tuple(t["name"] for t in scenario.get("tags") or [])
        ids = [m.group(1) for t in tags if (m := id_tag.match(t))]
        where = f"{source}:{scenario['location']['line']}"
        if ids:
            reading.items.append(Item(ids[-1], context, scenario["name"], rule, where, feature.title, tags))
        else:
            reading.unstamped.append((where, scenario["name"]))
    return reading
