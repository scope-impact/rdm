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
from functools import lru_cache
import subprocess
from pathlib import Path
from urllib.parse import quote

import pyoxigraph as ox

from rdm.record.allure import find_tests_dir, reconcile, scan_source_tests
from rdm.record.sdd import (
    MATRIX_DOC,
    context_of,
    declarations,
    design_input_ids,
    design_inputs,
    find_dhf_doc,
    find_design_docs,
    parse_frontmatter,
    realises_by_context,
    registry_user_needs,
    user_need_texts,
)


NS = "https://github.com/scope-impact/rdm/ns#"
ONTOLOGY_FILE = Path(__file__).with_name("ontology.ttl")
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
    title, revision, repository-relative path and frontmatter, sorted by id.
    The traceability-matrix template is left out: it is an output, rendered
    from the record, not part of it (DI-58)."""
    matrix = find_dhf_doc(Path(dhf), MATRIX_DOC)
    entries = []
    for md in sorted(Path(dhf).rglob("*.md")):
        if md == matrix:
            continue
        try:
            front = parse_frontmatter(md.read_text(encoding="utf-8", errors="ignore"))
        except OSError:
            continue
        doc_id = str(front.get("id", "")).strip()
        if doc_id:
            entries.append({"id": doc_id, "title": str(front.get("title", "")).strip(),
                            "revision": front.get("revision"), "path": _rel(md, root), "file": md,
                            "front": front})
    return sorted(entries, key=lambda e: (e["id"], e["path"]))


def _record(ds: _Dataset, dhf: Path, root: Path) -> None:
    g = "record"
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
        _document_links(ds, doc, entry["front"], g)

    texts = user_need_texts(dhf)
    declared = declarations(dhf)

    def declaring(ident):  # DI-52: the document that declares an id (the first, if several)
        first = declared.get(ident, [])[:1]
        return doc_by_path.get(_rel(dhf / first[0], root)) if first else None

    def count(node, ident):  # DI-46: how many times the record declares this id
        ds.add(node, rdm("declarationCount"),
               ox.Literal(str(len(declared.get(ident, [])) or 1), datatype=_term(_XSD + "integer")), g)

    for un in sorted(registry_user_needs(dhf)):
        need = ds.thing(ds.node("need", un), rdm("UserNeed"), un, g)
        ds.add(need, _term(_DCT + "identifier"), un, g)
        count(need, un)
        if declaring(un) is not None:
            ds.add(need, rdm("declaredIn"), declaring(un), g)
        if un in texts:
            ds.add(need, rdm("text"), texts[un], g)

    for path in find_design_docs(dhf):
        context = context_of(path)
        ctx = ds.thing(ds.node("context", context), rdm("BoundedContext"), context, g)
        doc = doc_by_path.get(_rel(path, root))
        if doc is not None:
            ds.add(doc, rdm("describes"), ctx, g)
    for path, refs in realises_by_context(dhf).items():
        for ref in sorted(refs):
            ds.add(ds.node("context", context_of(path)), rdm("realises"), ds.node("input", ref), g)

    for di in design_inputs(dhf):
        node = ds.thing(ds.node("input", di["id"]), rdm("DesignInput"), di["id"], g)
        ds.add(node, _term(_DCT + "identifier"), di["id"], g)
        count(node, di["id"])
        ds.add(node, rdm("text"), di["text"], g)
        ds.add(node, rdm("ownedBy"), ds.node("context", di["context"]), g)
        for un in di["traces_to"]:
            ds.add(node, rdm("tracesTo"), ds.node("need", un), g)
        if declaring(di["id"]) is not None:
            ds.add(node, rdm("declaredIn"), declaring(di["id"]), g)


def _document_links(ds: _Dataset, doc: ox.NamedNode, front: dict, g: str) -> None:
    """DI-58: the bounded contexts a document's ``contexts`` frontmatter
    declares (each with its part), and the controlled documents its
    ``references`` frontmatter names."""
    for item in front.get("contexts") or []:
        context = str(item.get("id", "") if isinstance(item, dict) else item).strip()
        if not context:
            continue
        ctx = ds.thing(ds.node("context", context), rdm("BoundedContext"), context, g)
        ds.add(ctx, rdm("declaredIn"), doc, g)
        if isinstance(item, dict) and str(item.get("part", "")).strip():
            ds.add(ctx, rdm("part"), str(item["part"]).strip(), g)
    references = front.get("references") or []
    for ref in [references] if isinstance(references, str) else references:
        if str(ref).strip():
            ds.add(doc, _term(_DCT + "references"), ds.node("doc", str(ref).strip()), g)


def _tests(ds: _Dataset, dhf: Path, root: Path) -> dict[str, ox.NamedNode]:
    """Each tagged test, defined in its file and verifying its design inputs
    (DI-61). Returns the Python tests keyed by the full name Allure gives
    their runs (``tests.x.TestClass#test_y``), so a run can find its test."""
    tests_dir = find_tests_dir(dhf)
    by_full_name: dict[str, ox.NamedNode] = {}
    if tests_dir is None:
        return by_full_name
    for file, name, tags in scan_source_tests(tests_dir):
        tags = [tag for tag in tags if _ID.match(tag)]
        if not tags:
            continue
        rel = _rel(Path(file), root)
        test_file = ds.thing(ds.node("testfile", rel), rdm("TestFile"), rel, "tests")
        ds.add(test_file, rdm("path"), rel, "tests")
        ident = f"{rel}::{name}" if name else rel
        test = ds.thing(ds.node("test", ident), rdm("Test"), ident, "tests")
        ds.add(test, rdm("definedIn"), test_file, "tests")
        for tag in tags:
            ds.add(test, rdm("verifies"), ds.node("input", tag), "tests")
        if name and rel.endswith(".py"):
            *owner, function = name.split("::")
            module = rel[:-3].replace("/", ".")
            by_full_name[".".join([module, *owner]) + "#" + function] = test
    return by_full_name


def _executions(ds: _Dataset, results_dir: Path, tests: dict[str, ox.NamedNode]) -> None:
    from rdm.graph.allure import project_results

    project_results(ds, Path(results_dir), tests)


def _git_out(root: Path, *args: str) -> str | None:
    out = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def default_branch(root: Path) -> str | None:
    """The branch changes land on: origin's HEAD, else origin's or the local
    main or master (origin first: a local branch may be stale)."""
    remote = _git_out(root, "rev-parse", "--abbrev-ref", "origin/HEAD")
    if remote and remote != "origin/HEAD":
        return remote
    for ref in ("refs/remotes/origin/main", "refs/remotes/origin/master", "refs/heads/main", "refs/heads/master"):
        if _git_out(root, "rev-parse", "--verify", "--quiet", ref):
            return ref.split("/", 2)[2]
    return None


@lru_cache(maxsize=4096)
def _commit_facts(root: Path, sha: str) -> tuple[str, str, str, str]:
    """A commit's full sha, author, time and subject. A commit never changes, so
    one git call per commit serves every projection that names it."""
    return tuple(_git_out(root, "log", "-1", "--format=%H%x1f%an%x1f%aI%x1f%s", sha).split("\x1f", 3))


def _commit(ds: _Dataset, root: Path, sha: str, g: str) -> tuple[ox.NamedNode, ox.NamedNode]:
    """A commit node with its time and author (prov:Agent)."""
    sha, author, when, subject = _commit_facts(root, sha)
    commit = ds.thing(ds.node("commit", sha), _term(_PROV + "Activity"), f"{sha[:7]} {subject}", g)
    ds.add(commit, rdm("sha"), sha, g)
    ds.add(commit, _term(_PROV + "endedAtTime"), ox.Literal(when, datatype=_term(_XSD + "dateTime")), g)
    agent = ds.thing(ds.node("agent", _slug(author)), _term(_PROV + "Agent"), author, g)
    ds.add(commit, _term(_PROV + "wasAssociatedWith"), agent, g)
    return commit, agent


def _landing(root: Path, sha: str, branch: str, first_parent: set[str]) -> str | None:
    """The first-parent commit of ``branch`` that brought ``sha`` in (DI-51):
    ``sha`` itself when it is on that line (a direct or squash commit), else
    the oldest first-parent commit descending from it (the merge)."""
    if sha in first_parent:
        return sha
    path = _git_out(root, "rev-list", "--first-parent", "--ancestry-path", f"{sha}..{branch}")
    return path.splitlines()[-1] if path else None


def _git(ds: _Dataset, dhf: Path, root: Path) -> None:
    g = "git"
    head = _git_out(root, "rev-parse", "HEAD")
    if head:  # DI-60: the commit the record was built at
        record = ds.thing(_term(ds.base + "record"), rdm("Record"), ds.base.split(":")[2], g)
        ds.add(record, rdm("atCommit"), _commit(ds, root, head, g)[0], g)
    branch = default_branch(root)
    first_parent = set((_git_out(root, "rev-list", "--first-parent", branch) or "").split()) if branch else set()
    for entry in controlled_documents(dhf, root):  # DI-35, DI-51: every controlled document
        doc_id = entry["id"]
        sha = _git_out(root, "log", "-1", "--format=%H", "--", entry["path"])
        if not sha:
            continue  # never committed: no fact to state
        doc = ds.node("doc", doc_id)
        commit, _ = _commit(ds, root, sha, g)
        ds.add(doc, _term(_PROV + "wasGeneratedBy"), commit, g)
        landed = _landing(root, sha, branch, first_parent) if branch else None
        if landed:
            landing, who = _commit(ds, root, landed, g)
            ds.add(doc, rdm("landedIn"), landing, g)
            ds.add(doc, rdm("landedBy"), who, g)


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
    register = risks(dhf, policy)
    declared = Counter(r.id for r in register if r.id)
    doc_ids = {entry["path"]: entry["id"] for entry in controlled_documents(dhf, root)}
    g = "risks"
    policy_doc = doc_ids.get(_rel(dhf / policy.source, root)) if policy else None
    unnamed = Counter()
    for r in register:
        if r.id:
            node = ds.thing(ds.node("risk", r.id), rdm("Risk"), r.id, g)
            ds.add(node, _term(_DCT + "identifier"), r.id, g)
            ds.add(node, rdm("declarationCount"),
                   ox.Literal(str(declared[r.id]), datatype=_term(_XSD + "integer")), g)
        else:  # still in the graph, so the shapes block it as the release gate does
            unnamed[r.document] += 1
            label = f"risk with no id #{unnamed[r.document]} in {r.document}"
            node = ds.thing(ds.node("risk", f"_no-id/{r.document}/{unnamed[r.document]}"), rdm("Risk"), label, g)
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
        if policy_doc:  # DI-52: the policy the risk was evaluated against
            ds.add(node, rdm("evaluatedAgainst"), ds.node("doc", policy_doc), g)


def _ontology(ds: _Dataset) -> None:
    for triple in ox.parse(path=str(ONTOLOGY_FILE), format=ox.RdfFormat.TURTLE):
        ds.quads.append(ox.Quad(triple.subject, triple.predicate, triple.object, ds.graph("ontology")))


def project(
    dhf_dir: Path,
    allure_results_dir: Path | None = None,
    project_name: str | None = None,
    checklists: list[str] | None = None,
    infer: bool = False,
) -> list[ox.Quad]:
    """The record as quads, de-duplicated, in a stable order. ``checklists``
    (built-in names or files) adds the checklists and references graphs;
    ``infer`` adds what the vocabulary's rules derive, in the inferred graph
    (DI-62)."""
    dhf = Path(dhf_dir).resolve()
    repo = _repo_root(dhf.parent)
    root = repo or dhf.parent
    results = Path(allure_results_dir) if allure_results_dir is not None and Path(allure_results_dir).exists() else None
    ds = _Dataset(project_name or (repo or dhf.parent).name)
    _record(ds, dhf, root)
    tests = _tests(ds, dhf, root)
    verified: set[str] = set()
    if results is not None:
        _executions(ds, results, tests)
        verified = set(reconcile(design_input_ids(dhf), results).verified)
    if repo is not None:
        _git(ds, dhf, root)
    _risks(ds, dhf, root, verified)
    if checklists:
        from rdm.graph.checklists import checklist_quads, reference_quads

        quads, clauses = checklist_quads(list(checklists), ds.graph("checklists"))
        ds.quads.extend(quads)
        ds.quads.extend(reference_quads(controlled_documents(dhf, root), lambda doc_id: ds.node("doc", doc_id),
                                        clauses, ds.graph("references")))
    _ontology(ds)
    if infer:
        from rdm.graph.rules import infer as derive

        ds.quads.extend(derive(ds.quads, ds.graph("inferred")))
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
