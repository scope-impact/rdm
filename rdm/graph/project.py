"""
Project the design record into an RDF dataset (item-06-001) and load, query and
serve it (item-06-002).

The Markdown record stays the only authored source: this module reads what
the gates already read and states it as quads, one named graph per source, so
a query can always tell where a fact came from:

- ``record``     — the spec (user needs, requirements, Allium rules, bounded
                   contexts) and the controlled documents
- ``executions`` — Allure results, when given
- ``git``        — each design document's latest commit
- ``checklists`` — requested regulatory checklists, as data (item-06-003)
- ``references`` — documents' ``[[…]]`` tags linked to the clauses they name
- ``ontology``   — RDM's vocabulary (``ontology.ttl``), so browsers can label things

Open world: the graph asserts only what the record says. A missing
``rdm:exercises`` edge means no run claimed the requirement — never
"unverified"; pass/fail judgments stay in the gates.
"""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path
from urllib.parse import quote

import pyoxigraph as ox

from rdm.graph.ns import DCTERMS, PROV, RDF, RDFS, RDM, XSD
from rdm.evidence.allure import reconcile
from rdm.kernel.frontmatter import frontmatter_of
from rdm.kernel.git import git, repo_root
from rdm.specification.sdd import MATRIX_DOC, find_dhf_doc, load_spec


NS = RDM
ONTOLOGY_FILE = Path(__file__).with_name("ontology.ttl")
_RDF, _RDFS, _XSD, _DCT, _PROV = RDF, RDFS, XSD, DCTERMS, PROV



def _term(iri: str) -> ox.NamedNode:
    return ox.NamedNode(iri)


TYPE = _term(_RDF + "type")
LABEL = _term(_RDFS + "label")


def rdm(local: str) -> ox.NamedNode:
    """A term of RDM's vocabulary."""
    return _term(NS + local)


def _slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-") or "unnamed"


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
    from the record, not part of it (item-06-015)."""
    matrix = find_dhf_doc(Path(dhf), MATRIX_DOC)
    entries = []
    for md in sorted(Path(dhf).rglob("*.md")):
        if md == matrix:
            continue
        front = frontmatter_of(md)
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

    spec = load_spec(dhf)
    if spec is None:
        return
    for _, context in spec.contexts:
        ds.thing(ds.node("context", context), rdm("BoundedContext"), context, g)

    def source(node, where):  # the spec file and line a fact is read from
        if where:
            ds.add(node, rdm("source"), where, g)

    for need in spec.needs:  # a screen of the event model and the surfaces it links
        node = ds.thing(ds.node("need", need.id), rdm("UserNeed"), need.id, g)
        ds.add(node, _term(_DCT + "identifier"), need.id, g)
        if need.text:
            ds.add(node, rdm("text"), need.text, g)
        ds.add(node, rdm("ownedBy"), ds.node("context", need.context), g)
        source(node, need.source)
        for actor in need.actors:
            ds.add(node, rdm("facing"), actor, g)
        for surface in need.surfaces:
            ds.add(node, rdm("surface"), surface, g)
        for rule in need.rules:
            ds.add(node, rdm("providesRule"), ds.node("rule", rule), g)
        for guarantee in need.guarantees:
            ds.add(node, rdm("guarantee"), guarantee, g)
        for command in need.commands:
            ds.add(node, rdm("command"), command, g)
        for event in need.storm_events:
            ds.add(node, rdm("stormEvent"), event, g)
    for rule, (context, trigger) in sorted(spec.rules.items()):
        node = ds.thing(ds.node("rule", rule), rdm("AlliumRule"), rule, g)
        ds.add(node, rdm("ownedBy"), ds.node("context", context), g)
        ds.add(node, rdm("trigger"), trigger, g)

    counts: dict[str, int] = {}
    for item in spec.items:
        counts[item.requirement] = counts.get(item.requirement, 0) + 1
    for item in spec.items:
        node = ds.thing(ds.node("requirement", item.requirement), rdm("Requirement"), item.requirement, g)
        ds.add(node, _term(_DCT + "identifier"), item.requirement, g)
        ds.add(node, rdm("declarationCount"),
               ox.Literal(str(counts[item.requirement]), datatype=_term(_XSD + "integer")), g)
        ds.add(node, rdm("text"), item.title, g)
        if item.rule:
            ds.add(node, rdm("inRule"), item.rule, g)
        ds.add(node, rdm("ownedBy"), ds.node("context", item.context), g)
        source(node, item.source)
        for need in spec.needs_of(item):
            ds.add(node, rdm("tracesTo"), ds.node("need", need), g)
        for need in spec.addresses.get(item.requirement, []):  # linked directly by the spec's needs source
            ds.add(node, rdm("addresses"), ds.node("need", need), g)
        for kind, name in item.obligations:
            ds.add(node, rdm("covers"), f"{kind}.{name}", g)
            if kind.startswith("rule-") and name in spec.rules:
                ds.add(node, rdm("coversRule"), ds.node("rule", name), g)


def _document_links(ds: _Dataset, doc: ox.NamedNode, front: dict, g: str) -> None:
    """item-06-015: the bounded contexts a document's ``contexts`` frontmatter
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


def _executions(ds: _Dataset, results_dir: Path) -> None:
    from rdm.graph.allure import project_results

    project_results(ds, Path(results_dir))


def _record_findings(ds: _Dataset, dhf: Path, results: Path | None) -> None:
    """What the release gate blocks about the whole record (a design control
    not met, no requirement, a result file that cannot be read), on the
    record's node as the gate words it, for the shapes to block (item-06-004)."""
    from rdm.release.gate import record_findings

    findings = record_findings(dhf, results)
    if findings:
        record = ds.thing(_term(ds.base + "record"), rdm("Record"), ds.base.split(":")[2], "record")
        for message in findings:
            ds.add(record, rdm("finding"), message, "record")


def default_branch(root: Path) -> str | None:
    """The branch changes land on: origin's HEAD, else origin's or the local
    main or master (origin first: a local branch may be stale), else git's
    ``init.defaultBranch``, else the only local branch."""
    remote = git(root, "rev-parse", "--abbrev-ref", "origin/HEAD")
    if remote and remote != "origin/HEAD":
        return remote
    for ref in ("refs/remotes/origin/main", "refs/remotes/origin/master", "refs/heads/main", "refs/heads/master"):
        if git(root, "rev-parse", "--verify", "--quiet", ref):
            return ref.split("/", 2)[2]
    configured = git(root, "config", "init.defaultBranch")
    if configured and git(root, "rev-parse", "--verify", "--quiet", f"refs/heads/{configured}"):
        return configured
    local = (git(root, "for-each-ref", "--format=%(refname:short)", "refs/heads") or "").split()
    return local[0] if len(local) == 1 else None


@lru_cache(maxsize=4096)
def _commit_facts(root: Path, sha: str) -> tuple[str, str, str, str]:
    """A commit's full sha, author, time and subject. A commit never changes, so
    one git call per commit serves every projection that names it."""
    return tuple(git(root, "log", "-1", "--format=%H%x1f%an%x1f%aI%x1f%s", sha).split("\x1f", 3))


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
    """The first-parent commit of ``branch`` that brought ``sha`` in (item-06-011):
    ``sha`` itself when it is on that line (a direct or squash commit), else
    the oldest first-parent commit descending from it (the merge). Descent is
    walked through every parent: walked through first parents only, a merge
    descends from the change only when the change is its branch's last commit."""
    if sha in first_parent:
        return sha
    descendants = (git(root, "rev-list", "--ancestry-path", f"{sha}..{branch}") or "").split()
    landed = [c for c in descendants if c in first_parent]  # newest first
    return landed[-1] if landed else None


def _latest_commits(root: Path, paths: list[str]) -> dict[str, str]:
    """Each path's latest commit — what ``git log -1 -- <path>`` gives — from one
    ``git log`` over all of them: newest first, the first commit naming a path
    is its latest."""
    latest: dict[str, str] = {}
    wanted, sha = set(paths), None
    for line in (git(root, "log", "--format=%x00%H", "--name-only", "--", *paths) or "").splitlines():
        if line.startswith("\x00"):
            sha = line[1:]
        elif line in wanted and sha:
            latest.setdefault(line, sha)
    return latest


def _git(ds: _Dataset, dhf: Path, root: Path) -> None:
    g = "git"
    head = git(root, "rev-parse", "HEAD")
    if head:  # item-06-016: the commit the record was built at
        record = ds.thing(_term(ds.base + "record"), rdm("Record"), ds.base.split(":")[2], g)
        ds.add(record, rdm("atCommit"), _commit(ds, root, head, g)[0], g)
        # The version the evidence must be for: the commit of the repository the
        # spec lives in (the product), the record's own when they are one.
        spec = load_spec(dhf)
        product_root = repo_root(spec.root) if spec is not None else None
        if product_root is not None and product_root.resolve() != Path(root).resolve():
            product = git(product_root, "rev-parse", "HEAD")
            if product:
                ds.add(record, rdm("productAt"), _commit(ds, product_root, product, g)[0], g)
        else:
            ds.add(record, rdm("productAt"), _commit(ds, root, head, g)[0], g)
    branch = default_branch(root)
    first_parent = set((git(root, "rev-list", "--first-parent", branch) or "").split()) if branch else set()
    documents = controlled_documents(dhf, root)  # item-06-001, item-06-011: every controlled document
    latest = _latest_commits(root, [entry["path"] for entry in documents])
    landings: dict[str, str | None] = {}  # documents often share a commit
    for entry in documents:
        sha = latest.get(entry["path"])
        if not sha:
            continue  # never committed: no fact to state
        doc = ds.node("doc", entry["id"])
        commit, _ = _commit(ds, root, sha, g)
        ds.add(doc, _term(_PROV + "wasGeneratedBy"), commit, g)
        if branch and sha not in landings:
            landings[sha] = _landing(root, sha, branch, first_parent)
        landed = landings.get(sha)
        if landed:
            landing, who = _commit(ds, root, landed, g)
            ds.add(doc, rdm("landedIn"), landing, g)
            ds.add(doc, rdm("landedBy"), who, g)


def _risks(ds: _Dataset, dhf: Path, root: Path, verified: set[str]) -> None:
    """The risk register (item-06-008): each risk's branch, chain, scores, evaluated
    levels, residual decision, status, controls and acceptance — and the
    release gate's own findings on it (``rdm:finding`` blocks,
    ``rdm:riskWarning`` warns), which the shapes report, so the two cannot
    disagree. A finding about the register as a whole goes on every risk, or
    on a stand-in node for the register when there is none."""
    from collections import Counter

    from rdm.risk.register import assess, policy_or_none, residual_decision

    policy = policy_or_none(dhf)
    from rdm.specification.sdd import requirement_ids

    register, found = assess(dhf, requirement_ids(dhf), verified)
    declared = Counter(r.id for r in register if r.id)
    doc_ids = {entry["path"]: entry["id"] for entry in controlled_documents(dhf, root)}
    g = "risks"
    policy_doc = doc_ids.get(_rel(dhf / policy.source, root)) if policy else None
    unnamed = Counter()
    for index, r in enumerate(register):
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
            ds.add(node, rdm("controlledBy"), ds.node("requirement", control), g)
        for link in r.linked:
            ds.add(node, rdm("linkedTo"), ds.node("risk", link), g)
        doc_id = doc_ids.get(_rel(dhf.parent / r.document, root))
        if doc_id:
            ds.add(node, rdm("declaredIn"), ds.node("doc", doc_id), g)
        if policy_doc:  # item-06-012: the policy the risk was evaluated against
            ds.add(node, rdm("evaluatedAgainst"), ds.node("doc", policy_doc), g)
        for finding in found:
            if finding.risk == index or (finding.risk is None and finding.blocking):
                ds.add(node, rdm("finding" if finding.blocking else "riskWarning"), finding.message, g)
    whole = [finding.message for finding in found if finding.risk is None and finding.blocking]
    if whole and not register:  # still in the graph, so the shapes block it as the release gate does
        node = ds.thing(ds.node("risk", "_register"), rdm("Risk"), "the risk register", g)
        for message in whole:
            ds.add(node, rdm("finding"), message, g)


def _ontology(ds: _Dataset) -> None:
    for triple in ox.parse(path=str(ONTOLOGY_FILE), format=ox.RdfFormat.TURTLE):
        ds.quads.append(ox.Quad(triple.subject, triple.predicate, triple.object, ds.graph("ontology")))


def project(
    dhf_dir: Path,
    allure_results_dir: Path | None = None,
    project_name: str | None = None,
    checklists: list[str] | None = None,
) -> list[ox.Quad]:
    """The record as quads, de-duplicated, in a stable order. ``checklists``
    (built-in names or files) adds the checklists and references graphs."""
    dhf = Path(dhf_dir).resolve()
    repo = repo_root(dhf.parent)
    root = repo or dhf.parent
    results = Path(allure_results_dir) if allure_results_dir is not None and Path(allure_results_dir).exists() else None
    ds = _Dataset(project_name or (repo or dhf.parent).name)
    _record(ds, dhf, root)
    _record_findings(ds, dhf, results)
    from rdm.specification.sdd import requirement_ids

    ids = requirement_ids(dhf)
    verified: set[str] = set()
    if results is not None:
        _executions(ds, results)
        verified = set(reconcile(ids, results).verified)
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
    unique = {str(q): q for q in ds.quads}
    return [unique[k] for k in sorted(unique)]


def nquads(quads: list[ox.Quad]) -> str:
    """Sorted N-Quads: one statement per line, byte-identical for an unchanged record."""
    text = ox.serialize(quads, format=ox.RdfFormat.N_QUADS).decode("utf-8")
    # Split at line feeds only: a literal may hold U+2028 and other line separators.
    return "".join(line + "\n" for line in sorted(text.split("\n")) if line.strip())


def build_store(location: Path, quads: list[ox.Quad]) -> int:
    """Replace the store's contents with ``quads`` (cleared first, never merged)."""
    Path(location).mkdir(parents=True, exist_ok=True)
    store = ox.Store(str(location))
    store.clear()
    store.extend(quads)
    store.flush()
    count = len(store)
    del store  # release the on-disk lock so `rdm graph serve` can open it
    return count
