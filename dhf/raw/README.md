# Raw sources

What rdm-spec's spec is distilled from. Every user need cites at least one
source here (`dhf/spec/needs.edn`, `:sources`), so a rule that rests on nothing
is visible as one.

The sources are three repositories, indexed for machines in `sources.edn`.
Each `.md` file here is a **provenance record**:
the repository, the commit it is pinned to, and what it is evidence for. The
repositories themselves are not copied in: a citation names
`<source>:<path>@<commit>`, and anyone can read the cited file at that commit.

## Rules

- **Never edited.** A record changes only to add a newer pin, and the old pin
  stays listed: a citation made against it must still resolve.
- **Cited, never paraphrased into authority.** A need or a scenario may restate
  what a source says; where they disagree, the source wins and the spec is the
  bug.
- **A source's implementation is fenced.** Its code is never cited
  (`sources.edn`, `:fenced`), and no source checkout reaches a clean room: the
  builder reads the records and the wiki, never a source's code. Cite what a source says, never how it is built.
- **Where sources conflict, say so** in the need or the decision that follows
  one, and say which one is followed and why.

## The sources

| Record | Repository | Pinned at | Evidence for |
|---|---|---|---|
| [innolitics-rdm.md](innolitics-rdm.md) | `innolitics/rdm` | `f21b508` (2022-09-06) | documentation as code: templates and data to documents, `init`, hooks, the developer-first design goals |
| [agent-skills.md](agent-skills.md) | `scope-impact/agent-skills` | `da65901` (2026-10-12) | what a C4 model is, its levels and views, and the quality checklist the lint enforces |
| [loyly.md](loyly.md) | `skallinen/loyly` | `17d68d2` (2026-09-08) | spec first and regenerate: the fence, ratification, the honest instrument, sources and a cited domain wiki |

## Research records

A research record is the spec owner's own investigation, written down so a
later spec change can cite it. It is not a repository and has no pin in
`sources.edn`; the external works it rests on are linked from it.

| Record | Written | Evidence for |
|---|---|---|
| [affordance-contracts.md](affordance-contracts.md) | 2026-10-27 | judging agent-designed UI against an affordance contract: the model, the framework-agnostic layers, the measured conformance runs, the research lineage |

`scope-impact/rdm`, this repository before rdm-spec, is deliberately not a
source: it holds today's implementation of what rdm-spec regenerates, so
citing it would point a coding agent at the answer. It never reaches a clean room
(`dhf/harness/config.edn`, `:clean-room`, is an allow-list), and what it decided reaches the
spec through ADR 0002, not through citations.
