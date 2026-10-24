# Raw sources

What rdm-spec's spec is distilled from. Every user need cites at least one
source here (`dhf/spec/needs.edn`, `:sources`), so a rule that rests on nothing
is visible as one.

The sources are two repositories, indexed for machines in `sources.edn`.
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
- **A source's implementation is fenced.** Its code is never cited, and the
  checkout a clean room carries has it removed from tree and history
  (`sources.edn`, `:fenced`). Cite what a source says, never how it is built.
- **Where sources conflict, say so** in the need or the decision that follows
  one, and say which one is followed and why.

## The sources

| Record | Repository | Pinned at | Evidence for |
|---|---|---|---|
| [innolitics-rdm.md](innolitics-rdm.md) | `innolitics/rdm` | `f21b508` (2022-09-06) | documentation as code: templates and data to documents, `init`, hooks, the developer-first design goals |
| [loyly.md](loyly.md) | `skallinen/loyly` | `17d68d2` (2026-09-08) | spec first and regenerate: the fence, the ratifying gate, the honest instrument, sources and a cited domain wiki |

`scope-impact/rdm`, this repository before rdm-spec, is deliberately not a
source: it holds today's implementation of what rdm-spec regenerates, so
citing it would point a coding agent at the answer. Its own history is fenced
(`dhf/harness/config.edn`, `:fenced-paths`), and what it decided reaches the
spec through ADR 0002, not through citations.
