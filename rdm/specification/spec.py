"""The factory's specification, read: a Löyly-shaped spec (ADR 0002).

RDM reads the spec; it never writes it. The spec is a plan directory whose
``NN-<context>`` entries (links, in Löyly) each hold one bounded context:

- ``spec.feature``: the accepted Gherkin scenarios, each stamped
  ``@item-NN-SSS``. A stamped scenario is a **requirement**; its tags
  ``@allium-<kind>.<Name>`` name the Allium obligations it covers.
- ``allium/*.allium``: rules (``rule X { when: T(...) }``), actors and
  surfaces (``surface S { facing a: Actor  provides: T(...)  @guarantee G }``).
- ``event-model/*.edn``: the Grain event model; its **screens** link the
  surfaces a person faces.
- ``../event-storm.edn`` (beside the contexts): the Big Picture board; an
  event an actor causes links to the screen whose command produces it.

A **user need** is a screen of the event model, with the surfaces it links
(what an actor faces, the rules it provides, what it guarantees) and the
storm events behind it. A context with no screens offers each of its
surfaces as a need. A requirement addresses a need when it covers one of the
need's rules or guarantees.

The Gherkin is read as Löyly's checklist reads it (``gen_checklist``, its
guarantee ``OneReadingOfTheSpec``): plan entries whose name starts with a
digit, sorted; a slice whose ``plan.md`` says ``status: planned`` or
``status: backlog`` is skipped and recorded; ``drafts.feature`` is never
read; a tag line is a stripped line starting with ``@``, and the last
``@item`` tag before a ``Scenario`` is its id; a ``Rule:`` line sets the rule
of the scenarios after it, reset per file; a scenario without an id is
unstamped, and listed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from rdm.kernel.edn import Keyword, loads_all
from rdm.kernel.util import load_yaml

ITEM_PREFIX = "item-"
SKIPPED_STATUSES = {"planned", "backlog"}

_SCENARIO = re.compile(r"^\s*Scenario(?: Outline)?:\s*(.+?)\s*$")
_RULE = re.compile(r"^\s*Rule:\s*(.*?)\s*$")
_FEATURE = re.compile(r"^\s*Feature:\s*(.*?)\s*$")
_ITEM_TAG = re.compile(r"@item-(\d+-\d+)")
_OBLIGATION = re.compile(r"@allium-([a-z-]+)\.([A-Za-z0-9_]+)")
_STATUS = re.compile(r"(?m)^status:\s*(\S+)")

_ALLIUM_BLOCK = re.compile(r"^(rule|surface|actor)\s+([A-Za-z0-9_]+)\s*\{(.*?)^\}", re.MULTILINE | re.DOTALL)
_WHEN = re.compile(r"^\s*when:\s*([A-Za-z0-9_]+)\s*\(", re.MULTILINE)
_FACING = re.compile(r"^\s*facing\s+\w+\s*:\s*([A-Za-z0-9_]+)", re.MULTILINE)
_PROVIDES = re.compile(r"^\s*provides:\s*\n((?:[ \t]+\S.*\n?)+)", re.MULTILINE)
_TRIGGER = re.compile(r"^\s*([A-Za-z0-9_]+)\s*\(", re.MULTILINE)
_GUARANTEE = re.compile(r"^\s*@guarantee\s+([A-Za-z0-9_]+)", re.MULTILINE)


def requirement_id(item_id: str) -> str:
    """RDM's id for a stamped item: ``02-001`` → ``item-02-001``."""
    return item_id if item_id.startswith(ITEM_PREFIX) else ITEM_PREFIX + item_id


@dataclass(frozen=True)
class Item:
    """A stamped scenario: one requirement."""

    id: str                      # Löyly's identity, "02-001"
    context: str                 # the bounded context: the slice's directory
    title: str
    rule: str | None
    source: str                  # "<plan path>/02-measurement/spec.feature:12", from the repository root
    tags: tuple[str, ...] = ()
    obligations: tuple[tuple[str, str], ...] = ()   # (kind, name) of each @allium-<kind>.<Name>

    @property
    def requirement(self) -> str:
        return requirement_id(self.id)

    @property
    def slice(self) -> str:
        return self.id.split("-", 1)[0]


@dataclass
class Surface:
    name: str
    context: str
    facing: list[str]
    triggers: list[str]
    guarantees: list[str]
    file: str
    description: str = ""


@dataclass
class Need:
    """A user need: an event-model screen and the surfaces it links, or a
    surface of a context that has no screens."""

    id: str
    context: str
    text: str
    surfaces: list[str] = field(default_factory=list)
    actors: list[str] = field(default_factory=list)
    rules: list[str] = field(default_factory=list)
    guarantees: list[str] = field(default_factory=list)
    commands: list[str] = field(default_factory=list)
    storm_events: list[str] = field(default_factory=list)
    source: str = ""


@dataclass
class Spec:
    plan: Path
    contexts: list[tuple[str, str]] = field(default_factory=list)       # (slice entry name, context)
    items: list[Item] = field(default_factory=list)
    unstamped: list[tuple[str, str]] = field(default_factory=list)      # (source, title)
    skipped: list[tuple[str, str]] = field(default_factory=list)        # (slice entry name, status)
    features: dict[str, str] = field(default_factory=dict)              # context -> Feature title
    surfaces: list[Surface] = field(default_factory=list)
    rules: dict[str, tuple[str, str]] = field(default_factory=dict)     # rule name -> (context, trigger)
    needs: list[Need] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)                   # files the reader could not read

    def item(self, requirement: str) -> Item | None:
        return next((i for i in self.items if i.requirement == requirement), None)

    def duplicates(self) -> dict[str, list[str]]:
        """Each requirement id stamped on more than one scenario, with every source."""
        seen: dict[str, list[str]] = {}
        for item in self.items:
            seen.setdefault(item.requirement, []).append(item.source)
        return {i: s for i, s in seen.items() if len(s) > 1}

    def needs_of(self, item: Item) -> list[str]:
        """The needs a requirement addresses: those whose rules or guarantees
        it covers; else, when it covers none (a system-facing seam, an
        invariant), the needs of its own context."""
        covered = {name for kind, name in item.obligations}
        direct = [n.id for n in self.needs if covered & (set(n.rules) | set(n.guarantees))]
        return direct or [n.id for n in self.needs if n.context == item.context]

    def directly_addressed(self, need: Need) -> list[str]:
        """The requirements that cover one of the need's rules or guarantees."""
        own = set(need.rules) | set(need.guarantees)
        return [i.requirement for i in self.items if own & {name for kind, name in i.obligations}]


# --- Locating the spec -----------------------------------------------------

def find_plan(dhf: Path) -> Path | None:
    """The plan directory of the spec the record is held to: ``spec`` in the
    DHF's ``config.yml`` (relative to the DHF), else ``.pi/plan`` beside the
    DHF or in a parent directory up to the repository root."""
    dhf = Path(dhf)
    config = dhf / "config.yml"
    if config.is_file():
        declared = (load_yaml(str(config)) or {}).get("spec")
        if declared:
            path = (dhf / str(declared)).resolve()
            return path if path.is_dir() else None
    here = dhf.resolve().parent
    for directory in (here, *here.parents):
        candidate = directory / ".pi" / "plan"
        if candidate.is_dir():
            return candidate
        if (directory / ".git").exists():
            break
    return None


def _slice_entries(plan: Path) -> list[Path]:
    return sorted((p for p in plan.iterdir() if p.is_dir() and p.name[:1].isdigit()), key=lambda p: p.name)


def _context_name(entry: Path) -> str:
    """The context a plan entry holds: the directory it links to, else its
    name after the slice number."""
    target = entry.resolve()
    if target != entry.absolute() and target.name:
        return target.name
    return entry.name.split("-", 1)[1] if "-" in entry.name else entry.name


def _rel(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


# --- Gherkin ---------------------------------------------------------------

def scan_feature(path: Path, source: str, context: str) -> tuple[list[Item], list[tuple[str, str]], str]:
    """One spec file read as the checklist reads it: its stamped items, its
    unstamped scenarios, and its Feature title."""
    items, unstamped, feature = [], [], ""
    rule, pending, tags = None, None, []
    for number, line in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
        stripped = line.strip()
        if stripped.startswith("@"):
            found = _ITEM_TAG.findall(line)
            if found:
                pending = found[-1]
            tags.extend(stripped.split())
        elif (match := _RULE.match(line)) and not stripped.startswith("#"):
            rule = match.group(1) or None
        elif match := _SCENARIO.match(line):
            title = match.group(1)
            if pending:
                obligations = tuple(_OBLIGATION.findall(" ".join(tags)))
                items.append(Item(pending, context, title, rule, f"{source}:{number}",
                                  tuple(t for t in tags if t.startswith("@")), obligations))
            else:
                unstamped.append((f"{source}:{number}", title))
            pending, tags = None, []
        elif not feature and (match := _FEATURE.match(line)):
            feature = match.group(1)
    return items, unstamped, feature


# --- Allium ----------------------------------------------------------------

def _read_allium(context_dir: Path, context: str, root: Path, spec: Spec) -> list[Surface]:
    surfaces = []
    for path in sorted((context_dir / "allium").glob("*.allium")):
        text = path.read_text(encoding="utf-8")
        for kind, name, body in _ALLIUM_BLOCK.findall(text):
            if kind == "rule":
                when = _WHEN.search(body)
                spec.rules[name] = (context, when.group(1) if when else name)
            elif kind == "surface":
                provides = _PROVIDES.search(body)
                triggers = _TRIGGER.findall(provides.group(1)) if provides else []
                surfaces.append(Surface(name, context, _FACING.findall(body), triggers,
                                        _GUARANTEE.findall(body), _rel(path, root), _doc_comment(text, name)))
    return surfaces


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


# --- Event model and event storm -------------------------------------------

def _edn(path: Path, spec: Spec, root: Path) -> list:
    try:
        return loads_all(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        spec.problems.append(f"{_rel(path, root)}: {error}")
        return []


def _blocks(context_dir: Path, spec: Spec, root: Path) -> tuple[dict, dict, dict]:
    """The screens, commands and events of a context's event model, merged
    across its fragments, each keyed by its qualified name."""
    screens, commands, events = {}, {}, {}
    for path in sorted((context_dir / "event-model").glob("*.edn")):
        for value in _edn(path, spec, root):
            if not isinstance(value, dict):
                continue
            for area in value.values():
                if not isinstance(area, dict):
                    continue
                screens.update(area.get(Keyword("screens")) or {})
                commands.update(area.get(Keyword("commands")) or {})
                events.update(area.get(Keyword("events")) or {})
    return screens, commands, events


def _surface_links(block: dict) -> list[str]:
    return [str(link.get(Keyword("name"))) for link in block.get(Keyword("grain/allium")) or []
            if isinstance(link, dict) and link.get(Keyword("kind")) == Keyword("surface")]


def _storm(plan: Path, spec: Spec, root: Path) -> list[tuple[str, str, str]]:
    """The board's events an actor causes: (local id, name, actor)."""
    board = None
    for directory in {plan.resolve().parent / "spec", plan.parent / "spec", plan.parent}:
        if (directory / "event-storm.edn").is_file():
            board = directory / "event-storm.edn"
            break
    if board is None:
        return []
    values = _edn(board, spec, root)
    out = []
    for phase in (values[0].get(Keyword("phases")) if values and isinstance(values[0], dict) else None) or []:
        for event in phase.get(Keyword("events")) or []:
            cause = event.get(Keyword("caused-by")) or {}
            if cause.get(Keyword("kind")) == Keyword("actor"):
                out.append((str(event.get(Keyword("id"))), str(event.get(Keyword("name"))),
                            str(cause.get(Keyword("name")))))
    return out


# --- The whole spec ---------------------------------------------------------

def read_spec(plan: Path, root: Path | None = None) -> Spec:
    """Read the spec under a plan directory. ``root`` is what sources are
    relative to (the repository root; default: the plan's grandparent)."""
    plan = Path(plan)
    root = Path(root) if root is not None else plan.absolute().parent.parent
    spec = Spec(plan=plan)
    storm = _storm(plan, spec, root)
    for entry in _slice_entries(plan):
        context = _context_name(entry)
        plan_md = entry / "plan.md"
        status = None
        if plan_md.is_file():
            match = _STATUS.search(plan_md.read_text(encoding="utf-8"))
            status = match.group(1) if match else None
        if status in SKIPPED_STATUSES:
            spec.skipped.append((entry.name, status))
            continue
        feature = entry / "spec.feature"
        if not feature.is_file():
            continue
        spec.contexts.append((entry.name, context))
        items, unstamped, title = scan_feature(feature, _rel(entry.absolute() / "spec.feature", root.absolute()),
                                               context)
        spec.items += items
        spec.unstamped += unstamped
        spec.features[context] = title

        surfaces = _read_allium(entry, context, root, spec)
        spec.surfaces += surfaces
        by_name = {s.name: s for s in surfaces}
        screens, commands, events = _blocks(entry, spec, root)
        if screens:
            for key, screen in sorted(screens.items()):
                linked = [by_name[n] for n in _surface_links(screen) if n in by_name]
                spec.needs.append(_need(str(key), context, str(screen.get(Keyword("description")) or ""),
                                        linked, [str(c) for c in sorted(screen.get(Keyword("commands")) or [])],
                                        commands, events, storm, spec))
        else:
            for surface in surfaces:
                spec.needs.append(_need(surface.name, context, surface.description, [surface], [], {}, {},
                                        storm, spec))
    return spec


def _need(ident, context, text, surfaces, command_names, commands, events, storm, spec) -> Need:
    triggers = {t for s in surfaces for t in s.triggers}
    rules = sorted(name for name, (ctx, trigger) in spec.rules.items() if trigger in triggers or name in triggers)
    produced = set()
    for name in command_names:
        block = commands.get(Keyword(name)) or {}
        produced |= {str(e).split("/", 1)[-1] for e in block.get(Keyword("produces")) or []}
    return Need(
        id=ident, context=context, text=text,
        surfaces=[s.name for s in surfaces],
        actors=sorted({a for s in surfaces for a in s.facing}),
        rules=rules,
        guarantees=sorted({g for s in surfaces for g in s.guarantees}),
        commands=command_names,
        storm_events=[f"{name} ({actor})" for local, name, actor in storm if local in produced],
        source=surfaces[0].file if surfaces else "",
    )
