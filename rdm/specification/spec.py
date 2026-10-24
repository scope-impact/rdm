"""
Read the slices of the record: their plans and their Gherkin specs (DI-90).

A slice is a directory ``plan/NN-slug/`` of the DHF holding ``plan.md`` (its
goal, scope and ``status:``), ``spec.feature`` and ``decisions.md`` (ADR
0002). In a spec, a ``Rule:`` tagged with a design-input id is a design input:
its title is the text, its ``@UN-n`` tags the user needs it traces to and its
``@context-<name>`` tag the bounded context that owns it. Each scenario under
it is an example that accepts it, stamped with an item id
(``@item-NN-SSS``). The official Gherkin parser reads the spec; a Rule or a
spec that cannot be read is listed for the design gate, never dropped.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

PLAN_DIR = "plan"
SPEC = "spec.feature"
PLAN = "plan.md"
PLANNED = "planned"

_DESIGN_INPUT = re.compile(r"DI-\d+")
_USER_NEED = re.compile(r"UN-\d+")
_CONTEXT = re.compile(r"context-(.+)")
_ITEM = re.compile(r"item-\d+-\d+")
_STATUS = re.compile(r"(?m)^status:\s*(\S+)\s*$")


@dataclass(frozen=True)
class Scenario:
    title: str
    line: int
    item: str | None  # its @item-NN-SSS, without the @, if stamped
    tags: tuple[str, ...]


@dataclass(frozen=True)
class Rule:
    title: str
    line: int
    tags: tuple[str, ...]
    scenarios: tuple[Scenario, ...]

    @property
    def design_input(self) -> str | None:
        return next((t for t in self.tags if _DESIGN_INPUT.fullmatch(t)), None)

    @property
    def needs(self) -> list[str]:
        return [t for t in self.tags if _USER_NEED.fullmatch(t)]

    @property
    def context(self) -> str | None:
        return next((m.group(1) for t in self.tags if (m := _CONTEXT.fullmatch(t))), None)


@dataclass(frozen=True)
class Spec:
    path: Path
    rules: tuple[Rule, ...] = ()
    loose: tuple[Scenario, ...] = ()  # scenarios under no Rule
    problem: tuple[int, str] | None = None  # the line and why it cannot be read

    @property
    def scenarios(self) -> list[tuple[Rule | None, Scenario]]:
        return [(None, s) for s in self.loose] + [(r, s) for r in self.rules for s in r.scenarios]


@dataclass(frozen=True)
class Slice:
    path: Path  # the slice's directory
    status: str | None
    spec: Spec | None = field(default=None)

    @property
    def number(self) -> str:
        """The slice number: its directory's name up to its first "-", verbatim."""
        return self.path.name.split("-", 1)[0]

    @property
    def planned(self) -> bool:
        return self.status == PLANNED


def _tags(node: dict) -> tuple[str, ...]:
    return tuple(t["name"].removeprefix("@") for t in node.get("tags", []))


def _scenario(node: dict) -> Scenario:
    tags = _tags(node)
    return Scenario(title=node["name"].strip(), line=node["location"]["line"],
                    item=next((t for t in tags if _ITEM.fullmatch(t)), None), tags=tags)


def read_spec(path: Path) -> Spec:
    """A spec, with its Rules and scenarios in file order; or the line and the
    reason it cannot be parsed."""
    # Imported here: reading a record with no slices needs YAML alone.
    from gherkin.errors import CompositeParserException, ParserException
    from gherkin.parser import Parser
    from gherkin.token_scanner import TokenScanner

    path = Path(path)
    try:
        document = Parser().parse(TokenScanner(path.read_text(encoding="utf-8")))
    except (CompositeParserException, ParserException) as error:
        first = error.errors[0] if isinstance(error, CompositeParserException) else error
        location = getattr(first, "location", None) or {}
        return Spec(path, problem=(int(location.get("line") or 0), str(first).strip()))
    except (OSError, UnicodeDecodeError) as error:
        return Spec(path, problem=(0, str(error)))
    rules, loose = [], []
    for child in (document.get("feature") or {}).get("children", []):
        if "rule" in child:
            rule = child["rule"]
            scenarios = tuple(_scenario(c["scenario"]) for c in rule.get("children", []) if "scenario" in c)
            rules.append(Rule(title=rule["name"].strip(), line=rule["location"]["line"],
                              tags=_tags(rule), scenarios=scenarios))
        elif "scenario" in child:
            loose.append(_scenario(child["scenario"]))
    return Spec(path, rules=tuple(rules), loose=tuple(loose))


def plan_status(slice_dir: Path) -> str | None:
    """The ``status:`` line of a slice's plan, or None when it has none."""
    try:
        match = _STATUS.search((slice_dir / PLAN).read_text(encoding="utf-8"))
    except OSError:
        return None
    return match.group(1) if match else None


def slices(dhf_dir: Path) -> list[Slice]:
    """Every slice of the DHF, in directory order, each with its spec if it has one."""
    root = Path(dhf_dir) / PLAN_DIR
    if not root.is_dir():
        return []
    return [Slice(path=d, status=plan_status(d), spec=read_spec(d / SPEC) if (d / SPEC).is_file() else None)
            for d in sorted(p for p in root.iterdir() if p.is_dir())]


def specs(dhf_dir: Path) -> list[Spec]:
    return [s.spec for s in slices(dhf_dir) if s.spec is not None]


def where(dhf_dir: Path, spec: Spec, line: int | None = None) -> str:
    """A spec's path relative to the DHF, with a line when one is given."""
    try:
        rel = spec.path.relative_to(dhf_dir).as_posix()
    except ValueError:
        rel = spec.path.as_posix()
    return f"{rel}:{line}" if line else rel


def spec_design_inputs(dhf_dir: Path) -> list[tuple[Spec, Rule]]:
    """Each Rule tagged with a design-input id, with the spec it is in."""
    return [(spec, rule) for spec in specs(dhf_dir) if spec.problem is None
            for rule in spec.rules if rule.design_input]


# The rules the design gate checks a spec against (DI-90).
UNTAGGED_RULE = "Untagged Rule"
RULE_WITHOUT_OWNER = "Rule Without Owner"
RULE_WITHOUT_NEED = "Rule Without Need"
UNREADABLE_SPEC = "Unreadable Spec"


def spec_problems(dhf_dir: Path) -> list[tuple[str, str]]:
    """Each Rule or spec the reader cannot take a design input from: the rule
    it breaks and a message naming the spec and the line."""
    dhf_dir = Path(dhf_dir)
    found: list[tuple[str, str]] = []
    for spec in specs(dhf_dir):
        if spec.problem is not None:
            line, why = spec.problem
            found.append((UNREADABLE_SPEC, f"{where(dhf_dir, spec, line)}: the spec cannot be read: {why}"))
            continue
        for rule in spec.rules:
            at = where(dhf_dir, spec, rule.line)
            if not rule.design_input:
                found.append((UNTAGGED_RULE, f"{at}: Rule {rule.title!r} is tagged with no design-input id"))
                continue
            if not rule.context:
                found.append((RULE_WITHOUT_OWNER,
                              f"{at}: {rule.design_input} has no owning context (a @context-<name> tag)"))
            if not rule.needs:
                found.append((RULE_WITHOUT_NEED, f"{at}: {rule.design_input} traces to no user need (a @UN-n tag)"))
    return found
