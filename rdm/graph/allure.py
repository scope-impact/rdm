"""
Allure results as RDF (DI-53..DI-56).

Each ``*-result.json`` becomes an ``rdm:TestRun`` (a ``prov:Activity``) with
everything Allure recorded about it: uuid, full name, start and end times,
status and its message and trace, parameters, every label (as a name/value
pair, so an unknown label needs no vocabulary change), links, steps and
attachments. Runs of one test across executions share an ``rdm:TestCase``
(Allure's history id). ``output`` labels link a run to the source files it
exercises; ``story``/``feature`` labels to the design inputs it verifies.
Each ``*-container.json`` contributes its before and after fixtures, linked
to the runs they served. Attachment content stays in the files; the graph
holds the reference.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import pyoxigraph as ox

from rdm.graph.project import _DCT, _ID, _PROV, _RDFS, _XSD, _Dataset, _term, rdm
from rdm.record.allure import USER_NEED_LABELS

GRAPH = "executions"


def _time(ms) -> ox.Literal | None:
    try:
        when = datetime.fromtimestamp(int(ms) / 1000, timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return None
    return ox.Literal(when.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                      datatype=_term(_XSD + "dateTime"))


def _times(ds: _Dataset, node: ox.NamedNode, raw: dict) -> None:
    for key, prop in (("start", "startedAtTime"), ("stop", "endedAtTime")):
        when = _time(raw.get(key))
        if when is not None:
            ds.add(node, _term(_PROV + prop), when, GRAPH)


def _evidence(ds: _Dataset, owner: ox.NamedNode, raw: dict, scope: str, key: str = "") -> None:
    """Attachments and (recursively) steps of a run, step or fixture (DI-53)."""
    for item in raw.get("attachments") or []:
        if isinstance(item, dict) and item.get("source"):
            source = str(item["source"])
            att = ds.thing(ds.node("attachment", source), rdm("Attachment"), str(item.get("name") or source), GRAPH)
            ds.add(att, rdm("path"), source, GRAPH)
            if item.get("type"):
                ds.add(att, _term(_DCT + "format"), str(item["type"]), GRAPH)
            ds.add(owner, rdm("attachment"), att, GRAPH)
    for n, step in enumerate(raw.get("steps") or [], 1):
        if not isinstance(step, dict):
            continue
        position = f"{key}.{n}" if key else str(n)
        node = ds.thing(ds.node("step", f"{scope}/{position}"), rdm("Step"), str(step.get("name") or position), GRAPH)
        ds.add(node, rdm("position"), position, GRAPH)
        if step.get("status"):
            ds.add(node, rdm("status"), str(step["status"]), GRAPH)
        _times(ds, node, step)
        ds.add(owner, rdm("step"), node, GRAPH)
        _evidence(ds, node, step, scope, position)


def _result(ds: _Dataset, path: Path, raw: dict) -> tuple[str, ox.NamedNode]:
    stem = path.stem
    run = ds.thing(ds.node("run", stem), rdm("TestRun"), str(raw.get("name") or stem), GRAPH)
    ds.add(run, rdm("status"), str(raw.get("status", "unknown")), GRAPH)
    if raw.get("uuid"):
        ds.add(run, _term(_DCT + "identifier"), str(raw["uuid"]), GRAPH)
    if raw.get("fullName"):
        ds.add(run, rdm("fullName"), str(raw["fullName"]), GRAPH)
    _times(ds, run, raw)
    details = raw.get("statusDetails") if isinstance(raw.get("statusDetails"), dict) else {}
    for key, prop in (("message", "statusMessage"), ("trace", "statusTrace")):
        if details.get(key):
            ds.add(run, rdm(prop), str(details[key]), GRAPH)
    if raw.get("historyId"):  # one test case across executions
        case = ds.thing(ds.node("testcase", str(raw["historyId"])), rdm("TestCase"),
                        str(raw.get("fullName") or raw.get("name") or raw["historyId"]), GRAPH)
        ds.add(run, rdm("runOf"), case, GRAPH)
    for n, param in enumerate(raw.get("parameters") or [], 1):
        if isinstance(param, dict) and param.get("name"):
            node = ds.thing(ds.node("parameter", f"{stem}/{n}"), rdm("Parameter"),
                            f"{param['name']}={param.get('value', '')}", GRAPH)
            ds.add(node, rdm("name"), str(param["name"]), GRAPH)
            ds.add(node, rdm("value"), str(param.get("value", "")), GRAPH)
            ds.add(run, rdm("parameter"), node, GRAPH)
    for n, label in enumerate(raw.get("labels") or [], 1):
        if not isinstance(label, dict) or not label.get("name"):
            continue
        name, value = str(label["name"]), str(label.get("value", "")).strip()
        node = ds.thing(ds.node("label", f"{stem}/{n}"), rdm("ResultLabel"), f"{name}={value}", GRAPH)
        ds.add(node, rdm("name"), name, GRAPH)
        ds.add(node, rdm("value"), value, GRAPH)
        ds.add(run, rdm("hasLabel"), node, GRAPH)
        if name in USER_NEED_LABELS and _ID.match(value):
            ds.add(run, rdm("exercises"), ds.node("input", value), GRAPH)
        elif name == "output" and value:  # DI-56: the code the run exercises
            source = ds.thing(ds.node("source", value), rdm("SourceFile"), value, GRAPH)
            ds.add(source, rdm("path"), value, GRAPH)
            ds.add(run, rdm("exercisesOutput"), source, GRAPH)
    for link in raw.get("links") or []:
        if isinstance(link, dict) and link.get("url"):
            try:
                ds.add(run, _term(_RDFS + "seeAlso"), ox.NamedNode(str(link["url"])), GRAPH)
            except ValueError:
                continue  # not an IRI: nothing to point at
    _evidence(ds, run, raw, stem)
    return str(raw.get("uuid") or ""), run


def _load(path: Path) -> dict | None:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return raw if isinstance(raw, dict) else None


def project_results(ds: _Dataset, results_dir: Path) -> None:
    """Every result and container in an Allure results directory, as quads."""
    results_dir = Path(results_dir)
    runs: dict[str, ox.NamedNode] = {}
    for path in sorted(results_dir.glob("*-result.json")):
        raw = _load(path)
        if raw is not None:
            uuid, run = _result(ds, path, raw)
            if uuid:
                runs[uuid] = run
    for path in sorted(results_dir.glob("*-container.json")):  # DI-55: fixtures
        raw = _load(path)
        if raw is None:
            continue
        served = [runs[c] for c in raw.get("children") or [] if c in runs]
        for phase, key, link in (("before", "befores", "setsUp"), ("after", "afters", "tearsDown")):
            for n, fixture in enumerate(raw.get(key) or [], 1):
                if not isinstance(fixture, dict):
                    continue
                scope = f"{path.stem}/{phase}/{n}"
                node = ds.thing(ds.node("fixture", scope), rdm("Fixture"), str(fixture.get("name") or scope), GRAPH)
                ds.add(node, rdm("phase"), phase, GRAPH)
                if fixture.get("status"):
                    ds.add(node, rdm("status"), str(fixture["status"]), GRAPH)
                _times(ds, node, fixture)
                _evidence(ds, node, fixture, scope)
                for run in served:
                    ds.add(node, rdm(link), run, GRAPH)
