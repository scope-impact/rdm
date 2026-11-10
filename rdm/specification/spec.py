"""The spec the record is held to, read (ADR 0002).

RDM reads a spec; it never writes it. What it needs from one is small and
concrete: **Gherkin**. Every requirement is a scenario tagged with an id
(``rdm.specification.gherkin``), and every test run claims the requirements
it verifies with Allure ``tag`` labels of the same ids. The rest is
abstract, chosen by the DHF's ``config.yml``:

.. code-block:: yaml

    spec:
      features: ["../spec/**/*.feature"]   # globs, from the DHF directory
      exclude: ["**/drafts.feature"]       # optional
      id: "REQ-\\d+"                        # optional: the id tag's pattern, without "@"
      needs: feature                       # feature (default) | allium

- **Where the Gherkin is** (``layout``): globs (the default), or a factory's
  layout through an adapter (``layout: loyly`` with ``plan: <dir>``,
  ``rdm.specification.loyly``). Without ``spec`` in the configuration RDM
  looks, from the repository root, for ``.pi/plan`` (Löyly's layout), then
  ``spec/**/*.feature``, then ``features/**/*.feature``.
- **What a user need is** (``needs``): by default each Gherkin ``Feature``,
  its description the need's text, addressed by the scenarios under it; or
  an adapter's richer reading (``needs: allium``: event-model screens with
  their Allium surfaces and the event storm).

A requirement's **context** is the directory its feature file sits in,
below the spec's root (or the adapter's context).
"""

from __future__ import annotations

import fnmatch
from dataclasses import dataclass, field
from pathlib import Path

from rdm.kernel.util import load_yaml
from rdm.specification.gherkin import DEFAULT_ID, Item, Reading, read_feature

__all__ = ["Item", "Need", "Spec", "find_spec", "read_spec", "load"]


@dataclass
class Need:
    """A user need. Every source gives its id, context and text; an adapter
    may add the actors it faces and what addresses it (rules, guarantees,
    surfaces, commands, storm events)."""

    id: str
    context: str
    text: str
    source: str = ""
    actors: list[str] = field(default_factory=list)
    surfaces: list[str] = field(default_factory=list)
    rules: list[str] = field(default_factory=list)
    guarantees: list[str] = field(default_factory=list)
    commands: list[str] = field(default_factory=list)
    storm_events: list[str] = field(default_factory=list)


@dataclass
class Spec:
    root: Path                                                          # what the sources are cited from
    contexts: list[tuple[str, str]] = field(default_factory=list)       # (where, context)
    items: list[Item] = field(default_factory=list)
    unstamped: list[tuple[str, str]] = field(default_factory=list)      # (source, title)
    skipped: list[tuple[str, str]] = field(default_factory=list)        # (where, why)
    needs: list[Need] = field(default_factory=list)
    addresses: dict[str, list[str]] = field(default_factory=dict)       # requirement -> needs it addresses
    rules: dict[str, tuple[str, str]] = field(default_factory=dict)     # adapter: rule -> (context, trigger)
    surfaces: list[str] = field(default_factory=list)                   # adapter: surface names
    problems: list[str] = field(default_factory=list)                   # files that could not be read

    def item(self, requirement: str) -> Item | None:
        return next((i for i in self.items if i.requirement == requirement), None)

    def duplicates(self) -> dict[str, list[str]]:
        """Each requirement id tagged on more than one scenario, with every source."""
        seen: dict[str, list[str]] = {}
        for item in self.items:
            seen.setdefault(item.requirement, []).append(item.source)
        return {i: s for i, s in seen.items() if len(s) > 1}

    def needs_of(self, item: Item) -> list[str]:
        """The needs a requirement addresses; one the needs source links to
        none traces to the needs of its own context."""
        return self.addresses.get(item.requirement) or [n.id for n in self.needs if n.context == item.context]

    def directly_addressed(self, need: Need) -> list[str]:
        """The requirements the needs source links to the need."""
        return [r for r, needs in self.addresses.items() if need.id in needs]


# --- Configuration ----------------------------------------------------------

def _config(dhf: Path) -> dict | None:
    config = Path(dhf) / "config.yml"
    if not config.is_file():
        return None
    declared = (load_yaml(str(config)) or {}).get("spec")
    if declared is None:
        return None
    if isinstance(declared, str):  # a directory: a plan directory, or a root of feature files
        path = (Path(dhf) / declared).resolve()
        from rdm.specification.loyly import slice_entries

        if path.is_dir() and slice_entries(path):
            return {"layout": "loyly", "plan": str(path), "needs": "allium"}
        return {"features": [str(path / "**" / "*.feature")]}
    if not isinstance(declared, dict):
        raise ValueError("`spec` in config.yml must be a directory or a mapping")
    out = dict(declared)
    if out.get("plan"):
        out["plan"] = str((Path(dhf) / str(out["plan"])).resolve())
    out["features"] = [str((Path(dhf) / g)) for g in out.get("features") or []]
    return out


def _repo_root(path: Path) -> Path:
    path = Path(path).resolve()
    for directory in (path, *path.parents):
        if (directory / ".git").exists():
            return directory
    return path.parent


def find_spec(dhf: Path) -> dict | None:
    """The spec's configuration: the DHF's ``spec``, else the first of
    ``.pi/plan``, ``spec/`` and ``features/`` found from the DHF's parent up
    to the repository root that holds Gherkin."""
    declared = _config(dhf)
    if declared is not None:
        return declared
    here = Path(dhf).resolve().parent
    for directory in (here, *here.parents):
        if (directory / ".pi" / "plan").is_dir():
            return {"layout": "loyly", "plan": str(directory / ".pi" / "plan"), "needs": "allium"}
        for name in ("spec", "features"):
            if (directory / name).is_dir() and any((directory / name).rglob("*.feature")):
                return {"features": [str(directory / name / "**" / "*.feature")]}
        if (directory / ".git").exists():
            break
    return None


# --- Reading ----------------------------------------------------------------

def _glob_root(pattern: str) -> Path:
    parts = Path(pattern).parts
    head = [p for p in parts[: next((i for i, p in enumerate(parts) if any(c in p for c in "*?[")), len(parts))]]
    return Path(*head) if head else Path(".")


def _read_globs(config: dict, root: Path, spec: Spec) -> tuple[Reading, dict[str, Path]]:
    reading, dirs = Reading(), {}
    excludes = config.get("exclude") or []
    files: list[tuple[Path, Path]] = []
    for pattern in config.get("features") or []:
        base = _glob_root(pattern)
        relative = Path(pattern).relative_to(base).as_posix() if base != Path(".") else pattern
        for path in sorted(base.glob(relative)):
            if path.is_file() and not any(fnmatch.fnmatch(path.as_posix(), e) or fnmatch.fnmatch(path.name, e)
                                          for e in excludes):
                files.append((path, base))
    seen = set()
    for path, base in files:
        if path.resolve() in seen:
            continue
        seen.add(path.resolve())
        parent = path.parent.resolve().relative_to(base.resolve()).as_posix() if path.parent != base else ""
        context = parent.split("/", 1)[0] if parent and parent != "." else ""
        if context and context not in dirs:
            dirs[context] = path.parent
            spec.contexts.append((_rel(path.parent, root), context))
        read_feature(path, _rel(path, root), context, config.get("id") or DEFAULT_ID, reading)
    return reading, dirs


def _rel(path: Path, root: Path) -> str:
    try:
        return Path(path).resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return Path(path).as_posix()


def _feature_needs(spec: Spec, reading: Reading) -> None:
    """Each Feature a need: its title the id, its description the text."""
    seen = set()
    for feature in reading.features:
        if feature.title and feature.title not in seen:
            seen.add(feature.title)
            spec.needs.append(Need(feature.title, feature.context, feature.description, feature.source))
    for item in spec.items:
        if item.feature:
            spec.addresses[item.requirement] = [item.feature]


def read_spec(config: dict | str | Path, root: Path | None = None) -> Spec:
    """Read the spec a configuration (``find_spec``) names, or the plan
    directory of a Löyly-layout spec. Sources are cited from ``root``
    (default: the repository holding the spec)."""
    if isinstance(config, (str, Path)):
        config = {"layout": "loyly", "plan": str(config), "needs": "allium"}
    if config.get("layout") == "loyly":
        from rdm.specification import loyly

        plan = Path(config["plan"])
        root = Path(root) if root is not None else _repo_root(plan)
        spec = Spec(root=root)
        reading, spec.contexts, spec.skipped, dirs = loyly.read_layout(plan, root, config.get("id"))
    else:
        features = config.get("features") or []
        root = Path(root) if root is not None else (_repo_root(_glob_root(features[0])) if features else Path("."))
        spec = Spec(root=root)
        reading, dirs = _read_globs(config, root, spec)
        plan = None
    spec.items, spec.unstamped = list(reading.items), list(reading.unstamped)
    needs = config.get("needs") or "feature"
    if needs == "allium":
        from rdm.specification import loyly

        loyly.read_needs(spec, dirs, plan or root, root)
    elif needs == "feature":
        _feature_needs(spec, reading)
    else:
        spec.problems.append(f"unknown needs source {needs!r} (feature or allium)")
    return spec


def load(dhf: Path) -> Spec | None:
    """The spec of a DHF, or None when none is configured or found."""
    config = find_spec(Path(dhf))
    return read_spec(config) if config is not None else None
