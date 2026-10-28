# Decisions: slice 03-ground-the-spec

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `sources.feature`, Rule: A source is indexed with its repository, its pins and its record

The chain of citations has to bottom out in something no generation step
can quietly rewrite (loyly:docs/wow/invariants.md@17d68d2, INV-1).

### `sources.feature`, Scenario: A source that drops a pin it had fails the check (spec-0121)

A pin is dropped when the index as it stands lacks a pin some earlier
commit of it had. Cycle 21 read the rule as "no commit may ever have
lacked one", and so failed this repository, whose index once deleted rdm
(967c1de) and then restored it as retired, its pin kept (spec owner,
spec-26).

### `sources.feature`, Scenario: A source is retired, never deleted, and nothing may cite it (spec-0236)

A source removed on purpose once read as dropped pins (cycle 04).

### `sources.feature`, Rule: A citation resolves to a source, a pin and a path

Checkouts are machine-local, so they are never committed: they are given on
the command line, a directory holding one checkout per source, named by
the source's id.

### `sources.feature`, Scenario: A citation's finding names the line the citation is on (spec-0402)

spec-32, from cycle 24's grader: with several needs citing one string,
every finding named the line where the string first appeared.

### `sources.feature`, Rule: A source's implementation is fenced

Decided by the event storm's hotspot H14 (dhf/storm/2026-10-24-rdm.md): a
source holding an implementation of what is regenerated hands the builder
the answer. What a source says may be cited; how it is built may not.

### `sources.feature`, Rule: A need's citations resolve

A need's citations are resolved here, with the wiki's, so one component
reports them (cycle 01, C20). The spec check keeps only "a need cites no
source".

### `wiki.feature`, Feature: Wiki

The wiki sits between the raw sources and the spec: the domain, distilled,
every claim footnoted to a source (loyly:README.md@17d68d2, "How to
read the repo"; docs/wiki/index.md@17d68d2).

### `wiki.feature`, Rule: Every claim in the wiki cites a source

The wiki is dhf/wiki/index.md and the pages under dhf/wiki/pages/. The
index carries the rules and the list of pages; the rules below govern pages.

### `wiki.feature`, Rule: The wiki never names an implementation

A wiki that names a stack has become a description of one implementation,
and a regeneration from it is no longer a measurement (loyly:docs/wow/invariants.md@17d68d2, INV-2).

