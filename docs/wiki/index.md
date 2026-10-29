# The harness's domain wiki

The domain this harness serves is not a product's; it is the practice of
regenerating a product from its spec. This wiki is that domain, distilled
from the sources under `raw/`. **Sources are authoritative; the wiki is
derived.** Where a page and a source disagree, the source wins and the page
is the bug (`raw/loyly/README.md`, "How to read the repo").

Every factual claim carries a footnote naming the source file and the
section, pinned by commit. A claim with no footnote is not finished, it is
filed. Where sources conflict, the page says so and records which one the
harness follows and why.

## The sources

| Source | What it is | Weight |
|---|---|---|
| `raw/loyly/` at `17d68d2` | Löyly: the method's own statement (README, way of working, invariants, agent harness) and its harness's README and spec | the method, as its author states it |
| `raw/rdm/` at `a4641ae` | rdm-spec: its way of working after 27 cycles, five decisions, one retrospective, one event storm, its events and needs | what happened when the method was applied to a harness, and what it cost |

## The pages

| Page | What it settles |
|---|---|
| [the-method](pages/the-method.md) | the five mechanisms; the spec as the asset and code as a build product; harness versus instrument |
| [event-storm](pages/event-storm.md) | what a storm produces, why it is kept as data, why the harness forces it |
| [scope-and-container](pages/scope-and-container.md) | the unit of regeneration; how a scope is computed; the whole-container cycle |
| [clean-room](pages/clean-room.md) | the fence, its proof, and what leaks past it |
| [gate-and-instrument](pages/gate-and-instrument.md) | "done" ratified; the derived checklist; honest denominators; the goldens |

## Vocabulary

[glossary.md](glossary.md): every term, where it appears, what holds it, and
the words not to use for it. The names there appear at every layer, re-cased
per layer; a term in the glossary, in `harness/spec.feature` and in a
product's storm must agree.
