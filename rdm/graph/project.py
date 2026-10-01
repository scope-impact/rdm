"""
Project the design record into an RDF dataset (DI-35) and load, query and
serve it (DI-36).

The Markdown record stays the only authored source: this module reads what
the gates already read and states it as quads, one named graph per source, so
a query can always tell where a fact came from:

- ``record``     — user needs, bounded contexts, design inputs, controlled documents
- ``tests``      — verification tags found in test sources
- ``executions`` — Allure results, when given
- ``git``        — each design document's latest commit
- ``checklists`` — requested regulatory checklists, as data (DI-37)
- ``references`` — documents' ``[[…]]`` tags linked to the clauses they name
- ``ontology``   — RDM's vocabulary (``ontology.ttl``), so browsers can label things

Open world: the graph asserts only what the record says. A missing
``rdm:verifies`` edge means no tag was found — never "unverified"; pass/fail
judgments stay in the gates.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path
from urllib.parse import quote

import pyoxigraph as ox

from rdm.record.allure import find_tests_dir, parse_results, reconcile, scan_source_tags
from rdm.record.sdd import (
    context_of,
    design_inputs,
    find_design_docs,
    parse_frontmatter,
    realises_by_context,
    registry_user_needs,
    satisfies_for,
    user_need_texts,
)

NS = "https://github.com/scope-impact/rdm/ns#"
ONTOLOGY_FILE = Path(__file__).with_name("ontology.ttl")
GRAPHS = ("record", "tests", "executions", "git", "risks", "checklists", "references", "ontology")

_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
_RDFS = "http://www.w3.org/2000/01/rdf-schema#"
_XSD = "http://www.w3.org/2001/XMLSchema#"
_DCT = "http://purl.org/dc/terms/"
_PROV = "http://www.w3.org/ns/prov#"

# An id worth a node: DI-3, UN-012, RISK-14 ... (a tag like "{di_id}" in a
# template string is not).
_ID = re.compile(r"^[A-Z][A-Z0-9]*-\d+$")


def _term(iri: str) -> ox.NamedNode:
    return ox.NamedNode(iri)


TYPE = _term(_RDF + "type")
LABEL = _term(_RDFS + "label")


def rdm(local: str) -> ox.NamedNode:
    """A term of RDM's vocabulary."""
    return _term(NS + local)


def _slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-") or "unnamed"


def _repo_root(path: Path) -> Path | None:
    try:
        out = subprocess.run(["git", "-C", str(path), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True)
    except OSError:
        return None
    return Path(out.stdout.strip()) if out.returncode == 0 and out.stdout.strip() else None


class _Dataset:
    """Collects quads under one project's IRI scheme."""

    def __init__(self, project: str):
        self.base = f"urn:dhf:{_slug(project)}:"
        self.quads: list[ox.Quad] = []

    def node(self, kind: str, local: str) -> ox.NamedNode:
        return _term(self.base + kind + "/" + quote(local, safe="/-._~"))

    def graph(self, name: str) -> ox.NamedNode:
        return self.node("graph", name)

    def add(self, s, p, o, graph: str) -> None:
        if isinstance(o, str):
            o = ox.Literal(o)
        self.quads.append(ox.Quad(s, p, o, self.graph(graph)))

    def thing(self, node: ox.NamedNode, cls: ox.NamedNode, label: str, graph: str) -> ox.NamedNode:
        self.add(node, TYPE, cls, graph)
        self.add(node, LABEL, label, graph)
        return node


def _rel(path: Path, root: Path) -> str:
    try:
        return Path(path).resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return Path(path).as_posix()


def controlled_documents(dhf: Path, root: Path) -> list[dict]:
    """Every Markdown file under the DHF with a frontmatter ``id``: its id,
    title, revision and repository-relative path, sorted by id."""
    entries = []
    for md in sorted(Path(dhf).rglob("*.md")):
        try:
            front = parse_frontmatter(md.read_text(encoding="utf-8", errors="ignore"))
        except OSError:
            continue
        doc_id = str(front.get("id", "")).strip()
        if doc_id:
            entries.append({"id": doc_id, "title": str(front.get("title", "")).strip(),
                            "revision": front.get("revision"), "path": _rel(md, root), "file": md})
    return sorted(entries, key=lambda e: (e["id"], e["path"]))


def _record(ds: _Dataset, dhf: Path, root: Path) -> None:
    g = "record"
    texts = user_need_texts(dhf)
    for un in sorted(registry_user_needs(dhf)):
        need = ds.thing(ds.node("need", un), rdm("UserNeed"), un, g)
        ds.add(need, _term(_DCT + "identifier"), un, g)
        if un in texts:
            ds.add(need, rdm("text"), texts[un], g)

    # Controlled documents (frontmatter id), keyed by that id.
    doc_by_path = {}
    for entry in controlled_documents(dhf, root):
        doc = ds.thing(ds.node("doc", entry["id"]), rdm("Document"), entry["id"], g)
        ds.add(doc, _term(_DCT + "identifier"), entry["id"], g)
        if entry.get("title"):
            ds.add(doc, _term(_DCT + "title"), entry["title"], g)
        if entry.get("revision") is not None:
            ds.add(doc, rdm("revision"), str(entry["revision"]), g)
        ds.add(doc, rdm("path"), entry["path"], g)
        doc_by_path[entry["path"]] = doc

    declared_in: dict[str, ox.NamedNode] = {}
    for path in find_design_docs(dhf):
        context = context_of(path)
        ctx = ds.thing(ds.node("context", context), rdm("BoundedContext"), context, g)
        for un in sorted(satisfies_for(path)):
            ds.add(ctx, rdm("satisfies"), ds.node("need", un), g)
        doc = doc_by_path.get(_rel(path, root))
        if doc is not None:
            ds.add(doc, rdm("describes"), ctx, g)
            front = parse_frontmatter(path.read_text(encoding="utf-8"))
            for item in front.get("design_inputs") or []:
                if isinstance(item, dict) and str(item.get("id", "")).strip():
                    declared_in.setdefault(str(item["id"]).strip(), doc)
    for path, refs in realises_by_context(dhf).items():
        for ref in sorted(refs):
            ds.add(ds.node("context", context_of(path)), rdm("realises"), ds.node("input", ref), g)

    for di in design_inputs(dhf):
        node = ds.thing(ds.node("input", di["id"]), rdm("DesignInput"), di["id"], g)
        ds.add(node, _term(_DCT + "identifier"), di["id"], g)
        ds.add(node, rdm("text"), di["text"], g)
        ds.add(node, rdm("ownedBy"), ds.node("context", di["context"]), g)
        for un in di["traces_to"]:
            ds.add(node, rdm("tracesTo"), ds.node("need", un), g)
        if di["id"] in declared_in:
            ds.add(node, rdm("declaredIn"), declared_in[di["id"]], g)


def _tests(ds: _Dataset, dhf: Path, root: Path) -> None:
    tests_dir = find_tests_dir(dhf)
    if tests_dir is None:
        return
    for tag, files in sorted(scan_source_tags(tests_dir).items()):
        if not _ID.match(tag):
            continue
        for file in sorted(set(files)):
            rel = _rel(Path(file), root)
            node = ds.thing(ds.node("test", rel), rdm("TestFile"), rel, "tests")
            ds.add(node, rdm("path"), rel, "tests")
            ds.add(node, rdm("verifies"), ds.node("input", tag), "tests")


def _executions(ds: _Dataset, results_dir: Path) -> None:
    for result in parse_results(Path(results_dir)):
        run = ds.thing(ds.node("run", Path(result.source).stem), rdm("TestRun"),
                       result.name or result.source, "executions")
        ds.add(run, rdm("status"), result.status, "executions")
        for di in result.user_need_ids:
            if _ID.match(di):
                ds.add(run, rdm("exercises"), ds.node("input", di), "executions")


def _git(ds: _Dataset, dhf: Path, root: Path) -> None:
    g = "git"
    by_path = {e["path"]: e["id"] for e in controlled_documents(dhf, root)}
    for path in find_design_docs(dhf):
        doc_id = by_path.get(_rel(path, root))
        if doc_id is None:
            continue
        out = subprocess.run(
            ["git", "-C", str(root), "log", "-1", "--format=%H%x1f%an%x1f%aI%x1f%s", "--", _rel(path, root)],
            capture_output=True, text=True,
        )
        if out.returncode != 0 or not out.stdout.strip():
            continue  # never committed: no fact to state
        sha, author, when, subject = out.stdout.strip().split("\x1f", 3)
        commit = ds.thing(ds.node("commit", sha), _term(_PROV + "Activity"), f"{sha[:7]} {subject}", g)
        ds.add(commit, rdm("sha"), sha, g)
        ds.add(commit, _term(_PROV + "endedAtTime"), ox.Literal(when, datatype=_term(_XSD + "dateTime")), g)
        agent = ds.thing(ds.node("agent", _slug(author)), _term(_PROV + "Agent"), author, g)
        ds.add(commit, _term(_PROV + "wasAssociatedWith"), agent, g)
        ds.add(ds.node("doc", doc_id), _term(_PROV + "wasGeneratedBy"), commit, g)


def _risks(ds: _Dataset, dhf: Path, root: Path, verified: set[str]) -> None:
    """The risk register (DI-45): each risk's branch, chain, scores, evaluated
    levels, residual decision, status, controls and acceptance. Without a
    usable policy the levels are left out, so the shapes report every risk as
    unevaluated, as the release gate blocks."""
    from collections import Counter

    from rdm.record.risk import read_policy, residual_decision, risks

    try:
        policy = read_policy(dhf)
    except ValueError:
        policy = None
    register = [r for r in risks(dhf, policy) if r.id]
    declared = Counter(r.id for r in register)
    doc_ids = {entry["path"]: entry["id"] for entry in controlled_documents(dhf, root)}
    g = "risks"
    for r in register:
        node = ds.thing(ds.node("risk", r.id), rdm("Risk"), r.id, g)
        ds.add(node, _term(_DCT + "identifier"), r.id, g)
        ds.add(node, rdm("declarationCount"), ox.Literal(str(declared[r.id]), datatype=_term(_XSD + "integer")), g)
        ds.add(node, rdm("residualDecision"), residual_decision(r, policy, verified), g)
        for prop, value in (("category", r.category), ("stride", r.stride), ("hazard", r.hazard),
                            ("situation", r.situation), ("harm", r.harm), ("severity", r.severity),
                            ("probability", r.probability), ("level", r.level),
                            ("recordedLevel", r.recorded_level), ("residualSeverity", r.residual_severity),
                            ("residualProbability", r.residual_probability), ("residualLevel", r.residual_level),
                            ("acceptedBy", r.accepted_by), ("acceptanceRationale", r.acceptance_rationale),
                            ("riskStatus", r.status)):
            if value:
                ds.add(node, rdm(prop), value, g)
        for control in r.controls:
            ds.add(node, rdm("controlledBy"), ds.node("input", control), g)
        for link in r.linked:
            ds.add(node, rdm("linkedTo"), ds.node("risk", link), g)
        doc_id = doc_ids.get(_rel(dhf.parent / r.document, root))
        if doc_id:
            ds.add(node, rdm("declaredIn"), ds.node("doc", doc_id), g)


def _ontology(ds: _Dataset) -> None:
    for triple in ox.parse(path=str(ONTOLOGY_FILE), format=ox.RdfFormat.TURTLE):
        ds.quads.append(ox.Quad(triple.subject, triple.predicate, triple.object, ds.graph("ontology")))


def default_project(dhf: Path) -> str:
    """The DHF's repository name (or its parent directory's)."""
    root = _repo_root(Path(dhf).resolve().parent)
    return (root or Path(dhf).resolve().parent).name


def project(
    dhf_dir: Path,
    allure_results_dir: Path | None = None,
    project_name: str | None = None,
    checklists: list[str] | None = None,
) -> list[ox.Quad]:
    """The record as quads, de-duplicated, in a stable order. ``checklists``
    (built-in names or files) adds the checklists and references graphs."""
    dhf = Path(dhf_dir).resolve()
    root = _repo_root(dhf.parent) or dhf.parent
    ds = _Dataset(project_name or default_project(dhf))
    _record(ds, dhf, root)
    _tests(ds, dhf, root)
    if allure_results_dir is not None and Path(allure_results_dir).exists():
        _executions(ds, Path(allure_results_dir))
    if _repo_root(dhf.parent) is not None:
        _git(ds, dhf, root)
    verified: set[str] = set()
    if allure_results_dir is not None and Path(allure_results_dir).exists():
        verified = set(reconcile({di["id"] for di in design_inputs(dhf)}, Path(allure_results_dir)).verified)
    _risks(ds, dhf, root, verified)
    if checklists:
        from rdm.graph.checklists import checklist_quads, reference_quads

        quads, keys = checklist_quads(list(checklists), ds.graph("checklists"))
        ds.quads.extend(quads)
        ds.quads.extend(reference_quads(controlled_documents(dhf, root), lambda doc_id: ds.node("doc", doc_id),
                                        keys, ds.graph("references")))
    _ontology(ds)
    unique = {str(q): q for q in ds.quads}
    return [unique[k] for k in sorted(unique)]


def nquads(quads: list[ox.Quad]) -> str:
    """Sorted N-Quads: one statement per line, byte-identical for an unchanged record."""
    text = ox.serialize(quads, format=ox.RdfFormat.N_QUADS).decode("utf-8")
    return "".join(line + "\n" for line in sorted(text.splitlines()) if line.strip())


def build_store(location: Path, quads: list[ox.Quad]) -> int:
    """Replace the store's contents with ``quads`` (cleared first, never merged)."""
    Path(location).mkdir(parents=True, exist_ok=True)
    store = ox.Store(str(location))
    store.clear()
    store.extend(quads)
    store.flush()
    count = len(store)
    del store  # release the on-disk lock so `oxigraph serve` can open it
    return count
