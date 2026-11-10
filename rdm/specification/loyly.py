"""An adapter for one factory's spec layout: Löyly's (``layout: loyly``).

RDM's core reads Gherkin (``rdm.specification.gherkin``) and leaves the rest
of a spec abstract. This adapter knows one factory's layout and its richer
sources of user needs:

- **Layout.** A plan directory whose ``NN-<context>`` entries (links, in
  Löyly) each hold one context's ``spec.feature``; a slice whose ``plan.md``
  says ``status: planned`` or ``status: backlog`` is skipped and recorded,
  and any other slice without one (a link to a folder git never received) is
  a problem, never skipped quietly;
  ``drafts.feature`` is never read. Requirement ids are ``@item-NN-SSS``.
- **User needs.** Each screen of a context's Grain event model
  (``event-model/*.edn``) with the Allium surfaces it links
  (``allium/*.allium``: the actor each faces, the rules whose triggers it
  provides, its guarantees) and the event-storm events
  (``event-storm.edn``) its commands produce; in a context with no screens,
  each surface. A requirement addresses a need when its
  ``@allium-<kind>.<Name>`` tags cover one of the need's rules or guarantees.

Nothing in the core depends on this module; the spec's configuration names
it.
"""

from __future__ import annotations

import dataclasses
import re
from pathlib import Path

from rdm.kernel.edn import Keyword, loads_all
from rdm.specification.gherkin import Reading, read_feature

ID_PATTERN = r"item-\d+-\d+"
SKIPPED_STATUSES = {"planned", "backlog"}

_STATUS = re.compile(r"(?m)^status:\s*(\S+)")
_OBLIGATION = re.compile(r"@allium-([a-z-]+)\.([A-Za-z0-9_]+)")
_ALLIUM_BLOCK = re.compile(r"^(rule|surface|actor)\s+([A-Za-z0-9_]+)\s*\{(.*?)^\}", re.MULTILINE | re.DOTALL)
_WHEN = re.compile(r"^\s*when:\s*([A-Za-z0-9_]+)\s*\(", re.MULTILINE)
_FACING = re.compile(r"^\s*facing\s+\w+\s*:\s*([A-Za-z0-9_]+)", re.MULTILINE)
_PROVIDES = re.compile(r"^\s*provides:\s*\n((?:[ \t]+\S.*\n?)+)", re.MULTILINE)
_TRIGGER = re.compile(r"^\s*([A-Za-z0-9_]+)\s*\(", re.MULTILINE)
_GUARANTEE = re.compile(r"^\s*@guarantee\s+([A-Za-z0-9_]+)", re.MULTILINE)


def _rel(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


# --- Layout -----------------------------------------------------------------

def slice_entries(plan: Path) -> list[Path]:
    return sorted((p for p in plan.iterdir() if p.is_dir() and p.name[:1].isdigit()), key=lambda p: p.name)


def context_name(entry: Path) -> str:
    """The context a plan entry holds: the directory it links to, else its
    name after the slice number."""
    target = entry.resolve()
    if target != entry.absolute() and target.name:
        return target.name
    return entry.name.split("-", 1)[1] if "-" in entry.name else entry.name


def read_layout(plan: Path, root: Path, id_pattern: str | None = None):
    """The Gherkin of every accepted slice: ``(reading, contexts, skipped,
    context directories)``."""
    reading = Reading()
    contexts, skipped, dirs = [], [], {}
    for entry in slice_entries(plan):
        context = context_name(entry)
        plan_md = entry / "plan.md"
        status = None
        if plan_md.is_file():
            match = _STATUS.search(plan_md.read_text(encoding="utf-8"))
            status = match.group(1) if match else None
        if status in SKIPPED_STATUSES:
            skipped.append((entry.name, status))
            continue
        feature = entry / "spec.feature"
        if not feature.is_file():  # a slice the plan accepts has a spec: never drop it quietly
            reading.problems.append(f"{_rel(entry.absolute(), root.absolute())}: no spec.feature "
                                    "(a slice not planned or backlog holds its accepted scenarios)")
            continue
        contexts.append((entry.name, context))
        dirs[context] = entry
        read_feature(feature, _rel(entry.absolute() / "spec.feature", root.absolute()), context,
                     id_pattern or ID_PATTERN, reading)
    return reading, contexts, skipped, dirs


# --- User needs: Allium, the event model, the event storm --------------------

def with_obligations(items):
    """Each item with the Allium obligations its tags name."""
    return [dataclasses.replace(i, obligations=tuple(_OBLIGATION.findall(" ".join(i.tags)))) for i in items]


def _doc_comment(text: str, surface: str) -> str:
    """The ``--`` comment lines right above ``surface <name> {``, joined."""
    lines = text.split("\n")
    start = next((n for n, line in enumerate(lines) if re.match(rf"^surface\s+{surface}\s*\{{", line)), None)
    found = []
    for line in reversed(lines[:start] if start is not None else []):
        if not line.strip().startswith("--"):
            break
        found.insert(0, line.strip()[2:].strip())
    return " ".join(found)


def read_allium(context_dir: Path, context: str, root: Path, rules: dict) -> list[dict]:
    """The surfaces of a context's Allium; its rules go into ``rules``."""
    surfaces = []
    for path in sorted((context_dir / "allium").glob("*.allium")):
        text = path.read_text(encoding="utf-8")
        for kind, name, body in _ALLIUM_BLOCK.findall(text):
            if kind == "rule":
                when = _WHEN.search(body)
                rules[name] = (context, when.group(1) if when else name)
            elif kind == "surface":
                provides = _PROVIDES.search(body)
                surfaces.append({"name": name, "facing": _FACING.findall(body),
                                 "triggers": _TRIGGER.findall(provides.group(1)) if provides else [],
                                 "guarantees": _GUARANTEE.findall(body), "file": _rel(path, root),
                                 "description": _doc_comment(text, name)})
    return surfaces


def _edn(path: Path, problems: list[str], root: Path) -> list:
    try:
        return loads_all(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        problems.append(f"{_rel(path, root)}: {error}")
        return []


def event_model(context_dir: Path, problems: list[str], root: Path) -> tuple[dict, dict]:
    """The screens and commands of a context's event model, merged across fragments."""
    screens, commands = {}, {}
    for path in sorted((context_dir / "event-model").glob("*.edn")):
        for value in _edn(path, problems, root):
            if not isinstance(value, dict):
                continue
            for area in value.values():
                if isinstance(area, dict):
                    screens.update(area.get(Keyword("screens")) or {})
                    commands.update(area.get(Keyword("commands")) or {})
    return screens, commands


def storm(plan: Path, problems: list[str], root: Path) -> list[tuple[str, str, str]]:
    """The board's events an actor causes: (local id, name, actor)."""
    board = next((d / "event-storm.edn" for d in (plan.resolve().parent / "spec", plan.parent / "spec", plan.parent)
                  if (d / "event-storm.edn").is_file()), None)
    if board is None:
        return []
    values = _edn(board, problems, root)
    out = []
    for phase in (values[0].get(Keyword("phases")) if values and isinstance(values[0], dict) else None) or []:
        for event in phase.get(Keyword("events")) or []:
            cause = event.get(Keyword("caused-by")) or {}
            if cause.get(Keyword("kind")) == Keyword("actor"):
                out.append((str(event.get(Keyword("id"))), str(event.get(Keyword("name"))),
                            str(cause.get(Keyword("name")))))
    return out


def _surface_links(block: dict) -> list[str]:
    return [str(link.get(Keyword("name"))) for link in block.get(Keyword("grain/allium")) or []
            if isinstance(link, dict) and link.get(Keyword("kind")) == Keyword("surface")]


def read_needs(spec, dirs: dict[str, Path], plan: Path, root: Path) -> None:
    """Fill ``spec``'s needs, rules, surfaces and the requirements' obligations
    and needs from the Allium, event models and event storm of each context."""
    from rdm.specification.spec import Need

    spec.items = with_obligations(spec.items)
    board = storm(plan, spec.problems, root)
    for context, directory in dirs.items():
        surfaces = read_allium(directory, context, root, spec.rules)
        spec.surfaces += [s["name"] for s in surfaces]
        by_name = {s["name"]: s for s in surfaces}
        screens, commands = event_model(directory, spec.problems, root)
        if screens:
            groups = [(str(key), str(screen.get(Keyword("description")) or ""),
                       [by_name[n] for n in _surface_links(screen) if n in by_name],
                       [str(c) for c in sorted(screen.get(Keyword("commands")) or [])])
                      for key, screen in sorted(screens.items())]
        else:
            groups = [(s["name"], s["description"], [s], []) for s in surfaces]
        for ident, text, linked, command_names in groups:
            triggers = {t for s in linked for t in s["triggers"]}
            produced = set()
            for name in command_names:
                block = commands.get(Keyword(name)) or {}
                produced |= {str(e).split("/", 1)[-1] for e in block.get(Keyword("produces")) or []}
            spec.needs.append(Need(
                id=ident, context=context, text=text, source=linked[0]["file"] if linked else "",
                actors=sorted({a for s in linked for a in s["facing"]}),
                surfaces=[s["name"] for s in linked],
                rules=sorted(n for n, (ctx, trigger) in spec.rules.items() if trigger in triggers or n in triggers),
                guarantees=sorted({g for s in linked for g in s["guarantees"]}),
                commands=command_names,
                storm_events=[f"{name} ({actor})" for local, name, actor in board if local in produced],
            ))
    for item in spec.items:
        covered = {name for kind, name in item.obligations}
        direct = [n.id for n in spec.needs if covered & (set(n.rules) | set(n.guarantees))]
        if direct:
            spec.addresses[item.requirement] = direct
