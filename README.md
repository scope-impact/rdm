# harness

A pluggable, opinionated harness, after Löyly, that forces an event storm and
regenerates a product from its spec one container or component at a time.

## The one thing

Given a spec whose model was found by an event storm, regenerate a
container's components from a cut, in a clean room, gate the result, and
measure it.

That is Löyly's loop (cut, fence, regenerate, gate, measure;
`raw/loyly/harness/README.md`) with two things added that Löyly does not have:

- **The storm is the front of the chain, and it is forced.** No storm, no cut.
  The model is derived from the storm's events; a component is where the
  storm put it; a scope is read from the events a component publishes and
  consumes. Nothing in the architecture is drawn by hand.
- **Regeneration is scoped to a container or a component,** not always the
  whole app. A scope is computed from the cut, never typed. A whole-container
  cycle runs every Nth cycle, so a scoped cycle cannot hide code nobody rebuilt.

## What it is not

It is not the product under test. The harness is written by hand, in one
language, held still, and judged by its own spec and oracle. It is never
regenerated (`docs/wow/invariants.md`, INV-3). The first product it
regenerates is Löyly's sauna booking (`raw/loyly/`); the harness is written
against a toy spec first, so it is never written against one app.

It is pluggable in one way: a project declares extra judges as commands in
its configuration, answering `check` or `verify`. There is no event bus, no
site, no wiki tooling, no regulated profile in the core. Each of those is a
plugin when a second project asks for it.

It is opinionated in one way: an opinion is a refusal, never a flag. A project
that needs an opinion relaxed adds and owns a plugin that says so; there is
no configuration key that turns one off.

## The repository

Read in this order; each layer is derived from the one above it.

| Order | Path | What it is |
|---|---|---|
| 1 | `README.md` | this: the intent. Every rule in the harness cites it or `docs/wow/invariants.md` |
| 2 | `raw/` | the only material that entered the room, pinned by commit; `raw/README.md` is the allow-list. Never edited, only cited |
| 3 | `docs/wiki/` | the domain: the practice of regenerating from a spec, distilled from the sources; every claim footnotes one; `glossary.md` is the vocabulary. Start at `index.md` |
| 4 | `docs/wow/` | the way of working (`way-of-working.md`), the invariants with their enforcement points (`invariants.md`), the agent harness (`harness.md`), what the instrument measures (`posterior.md`) |
| 5 | `docs/decisions/` | what was decided and why, starting with the clean room itself |
| 6 | `harness/` | the harness's own spec (`spec.feature`) and oracle, once it exists |
| 7 | `src/` | the harness, in Rust, one binary |

## Provenance

This is a clean-room rewrite of rdm-spec (`scope-impact/rdm`, branch
`claude/elegant-feynman-wx2ayn` at `a4641ae`), on an orphan branch that shares
no history with it. What came across is listed in `raw/README.md`; nothing
else was read. Why: `docs/decisions/0001-clean-room-rewrite.md`.
